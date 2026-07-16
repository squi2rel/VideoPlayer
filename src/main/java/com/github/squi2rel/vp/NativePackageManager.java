package com.github.squi2rel.vp;

import com.github.squi2rel.vp.i18n.TranslatableIOException;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.provider.MediaAddressPolicy;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NativePackageManager {
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_DOWNLOAD_REDIRECTS = 5;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 1L * 1024L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 4096;
    public static final String BACKEND_VLC = NativeDownloadConfig.BACKEND_VLC;
    public static final String BACKEND_MPV = NativeDownloadConfig.BACKEND_MPV;
    public static final String BUNDLED_ANDROID_VLC_RESOURCE = "/assets/videoplayer/native/vlc/android_arm64-v8a.zip";
    public static final String BUNDLED_ANDROID_VLC_SHA256 = "dbae70c264a9d86cd8d7fbd7ca35388cbe973de03636c08f0ac8d7cdb493f9ec";

    private static final Path ROOT = configDir().resolve("videoplayer-native");
    private static final Path PACKAGE_ROOT = ROOT.resolve("packages");
    private static final Path DOWNLOAD_ROOT = ROOT.resolve("downloads");
    private static final Path LOCK_ROOT = ROOT.resolve("locks");
    private static final Gson GSON = new Gson();
    private static final int PACKAGE_MANIFEST_VERSION = 1;
    private static final String PACKAGE_MANIFEST_FILE = ".videoplayer-package.json";
    private static final String PREPARED_DIRECTORY = "videoplayer-native";
    private static final String PROCESS_DIRECTORY = processDirectory();
    private static final List<DownloadMirror> DOWNLOAD_MIRRORS = List.of(
            new DownloadMirror("ghfast.top", "https://ghfast.top/"),
            new DownloadMirror("gh-proxy.com", "https://gh-proxy.com/"),
            new DownloadMirror("ghproxy.net", "https://ghproxy.net/")
    );
    private static final ConcurrentMap<String, String> SELECTED_PLATFORMS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ReentrantLock> INSTALL_LOCKS = new ConcurrentHashMap<>();
    private static final BooleanSupplier ALWAYS_ACTIVE = () -> true;
    private static final long DOWNLOAD_READ_IDLE_TIMEOUT_MILLIS = 30_000L;
    private static final ExecutorService DOWNLOAD_READ_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "VideoPlayer-native-download-read");
        thread.setDaemon(true);
        return thread;
    });

    private NativePackageManager() {
    }

    private static Path configDir() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object path = loader.getMethod("getConfigDir").invoke(instance);
            if (path instanceof Path configPath) {
                return configPath;
            }
        } catch (Throwable ignored) {
        }
        String configured = System.getProperty("videoplayer.configDir", "").trim();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.dir", ".")).resolve("config");
    }

    private static String processDirectory() {
        try {
            ProcessHandle process = ProcessHandle.current();
            long started = process.info().startInstant().map(value -> value.toEpochMilli()).orElse(0L);
            return "process-" + process.pid() + "-" + started;
        } catch (Throwable ignored) {
            return "process-" + UUID.randomUUID();
        }
    }

    public static String platformKey() {
        return NativeDownloadConfig.platformKey();
    }

    public static void selectPlatform(String backend, String platform) {
        SELECTED_PLATFORMS.put(
                NativeDownloadConfig.normalizeBackend(backend),
                NativeDownloadConfig.normalizeKnownPlatform(platform)
        );
    }

    public static String selectedPlatform(String backend) {
        return SELECTED_PLATFORMS.getOrDefault(NativeDownloadConfig.normalizeBackend(backend), platformKey());
    }

    public static Path installedRoot(String backend) {
        return installedRoot(backend, selectedPlatform(backend));
    }

    public static Path installedRoot(String backend, String platform) {
        return PACKAGE_ROOT
                .resolve(NativeDownloadConfig.normalizeBackend(backend))
                .resolve(NativeDownloadConfig.normalizeKnownPlatform(platform));
    }

    public static boolean isInstalled(String backend) {
        return isInstalled(backend, selectedPlatform(backend));
    }

    public static boolean isInstalled(String backend, String platform) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
        return isValidInstallation(installedRoot(normalizedBackend, normalizedPlatform), normalizedBackend, normalizedPlatform);
    }

    public static synchronized boolean ensureBundledAndroidVlc() {
        if (!"android".equals(NativeDownloadConfig.osKey())) return true;
        String platform = platformKey();
        if (!NativeDownloadConfig.ANDROID_ARM64.equals(platform)) return false;
        selectPlatform(BACKEND_VLC, platform);
        if (bundledAndroidVlcReady()) return true;
        DownloadResult result = installBundled(
                BACKEND_VLC,
                platform,
                BUNDLED_ANDROID_VLC_RESOURCE,
                BUNDLED_ANDROID_VLC_SHA256
        );
        if (!result.success()) {
            VideoPlayerMain.LOGGER.warn("Failed to install bundled Android VLC runtime", result.error());
            return false;
        }
        return bundledAndroidVlcReady();
    }

    private static boolean bundledAndroidVlcReady() {
        Path root = installedRoot(BACKEND_VLC, NativeDownloadConfig.ANDROID_ARM64);
        return isValidInstallation(root, BACKEND_VLC, NativeDownloadConfig.ANDROID_ARM64)
                && Files.isRegularFile(root.resolve("libvlc.so"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("libvlcjni.so"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("libvlc_jvm_bridge.so"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("libc++_shared.so"), LinkOption.NOFOLLOW_LINKS);
    }

    public static DownloadResult downloadAndInstall(String backend, List<NativeDownloadConfig.DownloadSource> sources, ProgressListener listener) {
        return downloadAndInstall(backend, selectedPlatform(backend), sources, listener);
    }

    public static DownloadResult downloadAndInstall(String backend, String platform, List<NativeDownloadConfig.DownloadSource> sources, ProgressListener listener) {
        return downloadAndInstall(backend, platform, sources, "", listener);
    }

    public static DownloadResult downloadAndInstall(String backend, String platform, List<NativeDownloadConfig.DownloadSource> sources,
                                                    String proxy, ProgressListener listener) {
        return downloadAndInstall(backend, platform, sources, proxy, listener, ALWAYS_ACTIVE);
    }

    public static DownloadResult downloadAndInstall(String backend, String platform, List<NativeDownloadConfig.DownloadSource> sources,
                                                    String proxy, ProgressListener listener, BooleanSupplier active) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        String lockKey = normalizedBackend + ":" + normalizedPlatform;
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        boolean locked = false;
        try {
            checkActive(guard);
            while (!(locked = installLock.tryLock(100, TimeUnit.MILLISECONDS))) {
                checkActive(guard);
            }
            try (InstallFileLock ignored = acquireInstallFileLock(normalizedBackend, normalizedPlatform, guard)) {
                if (isInstalled(normalizedBackend, normalizedPlatform)) {
                    return DownloadResult.ok(VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete"));
                }
                return downloadAndInstallLocked(normalizedBackend, normalizedPlatform, sources, proxy, listener, guard);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return cancelled(interrupted);
        } catch (CancellationException cancelled) {
            return cancelled(cancelled);
        } catch (IOException error) {
            return DownloadResult.fail(VpTranslations.from(error,
                    "error.videoplayer.native.install_lock_failed",
                    "Unable to lock native package installation: %s",
                    error.getMessage() == null ? "" : error.getMessage()), error);
        } finally {
            if (locked) installLock.unlock();
        }
    }

    private static DownloadResult downloadAndInstallLocked(String normalizedBackend, String normalizedPlatform,
                                                           List<NativeDownloadConfig.DownloadSource> sources,
                                                           String proxy, ProgressListener listener, BooleanSupplier active) {
        checkActive(active);
        List<NativeDownloadConfig.DownloadSource> selectedSources = selectDownloadSources(sources, proxy);
        List<NativeDownloadConfig.DownloadSource> usableSources = selectedSources.stream()
                .filter(source -> source != null && source.url != null && !source.url.isBlank())
                .toList();
        if (usableSources.isEmpty()) {
            return DownloadResult.fail(VpTranslation.of("error.videoplayer.native.no_sources", "No download sources configured"), null);
        }

        HttpClient http;
        try {
            http = httpClient(proxy);
        } catch (RuntimeException e) {
            return DownloadResult.fail(VpTranslation.of("error.videoplayer.native.invalid_proxy", "Invalid proxy configuration: %s", e.getMessage()), e);
        }

        Throwable lastError = null;
        for (int i = 0; i < usableSources.size(); i++) {
            NativeDownloadConfig.DownloadSource source = usableSources.get(i);
            String sourceName = sourceName(source);
            checkActive(active);
            try {
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        sourceName, VpTranslation.of("message.videoplayer.native.connecting", "Connecting")));
                Path zip = download(http, temporaryDownloadName(normalizedBackend, normalizedPlatform, ".zip.tmp"), source,
                        i, usableSources.size(), listener, active);
                checkActive(active);
                verify(source, zip, active);
                checkActive(active);
                installZip(normalizedBackend, normalizedPlatform, zip, active);
                VpTranslation complete = VpTranslation.of("message.videoplayer.native.install_complete_source",
                        "Installation complete (source: %s)", sourceName);
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        sourceName, complete));
                VideoPlayerMain.LOGGER.info("Installed native package {} {} from {}", normalizedBackend, normalizedPlatform, sourceName);
                return DownloadResult.ok(complete, sourceName);
            } catch (CancellationException cancelled) {
                return cancelled(cancelled);
            } catch (Throwable t) {
                lastError = t;
                VideoPlayerMain.LOGGER.warn("Native package download source {} ({}/{}) failed for {}", sourceName, i + 1, usableSources.size(), normalizedBackend, t);
                if (i + 1 < usableSources.size()) {
                    notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                            sourceName, VpTranslation.of("message.videoplayer.native.source_failed_try_next", "Source failed, trying fallback")));
                }
            }
        }
        return DownloadResult.fail(VpTranslations.from(lastError,
                "error.videoplayer.native.download_failed",
                "Download failed: %s",
                lastError == null || lastError.getMessage() == null ? "" : lastError.getMessage()), lastError);
    }

    static DownloadResult downloadAndInstallFile(String name, String platform, List<NativeDownloadConfig.DownloadSource> sources,
                                                 String proxy, Path destination, boolean executable, FileValidator validator,
                                                 ProgressListener listener) {
        return downloadAndInstallFile(name, platform, sources, proxy, destination, executable, validator, listener, ALWAYS_ACTIVE);
    }

    static DownloadResult downloadAndInstallFile(String name, String platform, List<NativeDownloadConfig.DownloadSource> sources,
                                                 String proxy, Path destination, boolean executable, FileValidator validator,
                                                 ProgressListener listener, BooleanSupplier active) {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        Path normalizedDestination = normalizeDestination(destination);
        String lockKey = "file:" + normalizedDestinationKey(normalizedDestination);
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        boolean locked = false;
        try {
            checkActive(guard);
            while (!(locked = installLock.tryLock(100, TimeUnit.MILLISECONDS))) {
                checkActive(guard);
            }
            try (InstallFileLock ignored = acquireDestinationFileLock(normalizedDestination, guard)) {
                if (validExistingFile(normalizedDestination, validator)) {
                    return DownloadResult.ok(VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete"));
                }
                return downloadAndInstallFileLocked(name, platform, sources, proxy, normalizedDestination,
                        executable, validator, listener, guard);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return cancelled(interrupted);
        } catch (CancellationException cancelled) {
            return cancelled(cancelled);
        } catch (IOException error) {
            return DownloadResult.fail(VpTranslations.from(error,
                    "error.videoplayer.native.install_lock_failed",
                    "Unable to lock managed file installation: %s",
                    error.getMessage() == null ? "" : error.getMessage()), error);
        } finally {
            if (locked) installLock.unlock();
        }
    }

    private static DownloadResult downloadAndInstallFileLocked(String name, String platform,
                                                               List<NativeDownloadConfig.DownloadSource> sources,
                                                               String proxy, Path destination, boolean executable,
                                                               FileValidator validator, ProgressListener listener,
                                                               BooleanSupplier guard) {
        List<NativeDownloadConfig.DownloadSource> usableSources = selectDownloadSources(sources, proxy).stream()
                .filter(source -> source != null && source.url != null && !source.url.isBlank())
                .toList();
        if (usableSources.isEmpty()) {
            return DownloadResult.fail(VpTranslation.of("error.videoplayer.native.no_sources", "No download sources configured"), null);
        }

        HttpClient http;
        try {
            http = httpClient(proxy);
        } catch (RuntimeException e) {
            return DownloadResult.fail(VpTranslation.of("error.videoplayer.native.invalid_proxy", "Invalid proxy configuration: %s", e.getMessage()), e);
        }

        Throwable lastError = null;
        for (int i = 0; i < usableSources.size(); i++) {
            NativeDownloadConfig.DownloadSource source = usableSources.get(i);
            String selectedSource = sourceName(source);
            try {
                checkActive(guard);
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        selectedSource, VpTranslation.of("message.videoplayer.native.connecting", "Connecting")));
                Path downloaded = download(http, temporaryDownloadName(name, platform, ".tmp"), source,
                        i, usableSources.size(), listener, guard);
                checkActive(guard);
                verify(source, downloaded, guard);
                checkActive(guard);
                installFile(downloaded, destination, executable, validator, guard);
                VpTranslation complete = VpTranslation.of("message.videoplayer.native.install_complete_source",
                        "Installation complete (source: %s)", selectedSource);
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1, selectedSource, complete));
                return DownloadResult.ok(complete, selectedSource);
            } catch (CancellationException cancelled) {
                return cancelled(cancelled);
            } catch (Throwable error) {
                lastError = error;
                VideoPlayerMain.LOGGER.warn("Tool download source {} ({}/{}) failed for {}", selectedSource,
                        i + 1, usableSources.size(), name, error);
                if (i + 1 < usableSources.size()) {
                    notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1, selectedSource,
                            VpTranslation.of("message.videoplayer.native.source_failed_try_next", "Source failed, trying fallback")));
                }
            }
        }
        return DownloadResult.fail(VpTranslations.from(lastError,
                "error.videoplayer.native.download_failed", "Download failed: %s",
                lastError == null || lastError.getMessage() == null ? "" : lastError.getMessage()), lastError);
    }

    static List<NativeDownloadConfig.DownloadSource> selectDownloadSources(List<NativeDownloadConfig.DownloadSource> sources, String proxy) {
        if (sources == null || sources.isEmpty()) return List.of();
        boolean useGithubDirectly = proxy != null && !proxy.isBlank();
        LinkedHashMap<String, NativeDownloadConfig.DownloadSource> selected = new LinkedHashMap<>();
        for (NativeDownloadConfig.DownloadSource source : sources) {
            if (source == null || source.url == null || source.url.isBlank()) continue;
            String url = source.url.trim();
            if (isGithubUrl(url)) {
                if (!useGithubDirectly) {
                    for (DownloadMirror mirror : DOWNLOAD_MIRRORS) {
                        addSource(selected, new NativeDownloadConfig.DownloadSource(mirror.name(), mirror.prefix() + url, source.sha256));
                    }
                }
                addSource(selected, new NativeDownloadConfig.DownloadSource("GitHub", url, source.sha256));
            } else {
                addSource(selected, new NativeDownloadConfig.DownloadSource(sourceName(source), url, source.sha256));
            }
        }
        return List.copyOf(selected.values());
    }

    private static void addSource(LinkedHashMap<String, NativeDownloadConfig.DownloadSource> sources,
                                  NativeDownloadConfig.DownloadSource source) {
        sources.putIfAbsent(source.url, source);
    }

    private static boolean isGithubUrl(String url) {
        try {
            return "github.com".equalsIgnoreCase(URI.create(url).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String sourceName(NativeDownloadConfig.DownloadSource source) {
        if (source.name != null && !source.name.isBlank()) return source.name.trim();
        try {
            String host = URI.create(source.url.trim()).getHost();
            return host == null || host.isBlank() ? "Unknown" : host;
        } catch (IllegalArgumentException ignored) {
            return "Unknown";
        }
    }

    public static DownloadResult installBundled(String backend, String platform, String resource, String expectedSha256) {
        return installBundled(backend, platform, resource, expectedSha256, ALWAYS_ACTIVE);
    }

    public static DownloadResult installBundled(String backend, String platform, String resource, String expectedSha256,
                                                BooleanSupplier active) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        String lockKey = normalizedBackend + ":" + normalizedPlatform;
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        boolean locked = false;
        Path zip = DOWNLOAD_ROOT.resolve(temporaryDownloadName(normalizedBackend, normalizedPlatform, "_bundled.zip"));
        try {
            checkActive(guard);
            while (!(locked = installLock.tryLock(100, TimeUnit.MILLISECONDS))) {
                checkActive(guard);
            }
            try (InstallFileLock ignored = acquireInstallFileLock(normalizedBackend, normalizedPlatform, guard)) {
                checkActive(guard);
                if (isInstalled(normalizedBackend, normalizedPlatform)) {
                    return DownloadResult.ok(VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete"));
                }
                InputStream resourceInput = NativePackageManager.class.getResourceAsStream(resource);
                if (resourceInput == null) {
                    return DownloadResult.fail(VpTranslation.of("error.videoplayer.native.bundled_missing", "Bundled native package is missing"), null);
                }
                try (InputStream input = resourceInput) {
                    Files.createDirectories(zip.getParent());
                    copy(input, zip, guard);
                }
                checkActive(guard);
                verify(new NativeDownloadConfig.DownloadSource(resource, expectedSha256), zip, guard);
                checkActive(guard);
                installZip(normalizedBackend, normalizedPlatform, zip, guard);
                return DownloadResult.ok(VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            deleteTemporary(zip, interrupted);
            return cancelled(interrupted);
        } catch (CancellationException cancelled) {
            deleteTemporary(zip, cancelled);
            return cancelled(cancelled);
        } catch (Throwable error) {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException ignored) {
            }
            return DownloadResult.fail(VpTranslations.from(error,
                    "error.videoplayer.native.bundled_install_failed",
                    "Bundled native package installation failed: %s",
                    error.getMessage() == null ? "" : error.getMessage()), error);
        } finally {
            if (locked) installLock.unlock();
        }
    }

    public static Optional<PreparedNativePackage> prepareForLoad(String backend) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String platform = selectedPlatform(normalizedBackend);
        Path installed = installedRoot(normalizedBackend, platform);
        String lockKey = "prepare:" + normalizedBackend + ":" + platform;
        ReentrantLock prepareLock = INSTALL_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        prepareLock.lock();
        try (InstallFileLock ignored = acquireInstallFileLock(normalizedBackend, platform, ALWAYS_ACTIVE)) {
            cleanupPreparedRoots(jnaTmpDir());
            if (!isValidInstallation(installed, normalizedBackend, platform)) {
                return Optional.empty();
            }
            String packageKey = sha256(installed.resolve(PACKAGE_MANIFEST_FILE));
            Path prepared = preparedRoot(jnaTmpDir(), normalizedBackend, platform, installedPackageVersion(installed),
                    PROCESS_DIRECTORY, packageKey);
            if (!isValidInstallation(prepared, normalizedBackend, platform)) {
                Path staging = prepared.resolveSibling(prepared.getFileName() + ".preparing-" + UUID.randomUUID());
                Files.createDirectories(prepared.getParent());
                deleteRecursively(staging);
                try {
                    copyDirectory(installed, staging);
                    if (!isValidInstallation(staging, normalizedBackend, platform)) {
                        throw new IOException("Prepared native package failed integrity verification");
                    }
                    deleteRecursively(prepared);
                    moveReplacing(staging, prepared);
                } finally {
                    deleteRecursively(staging);
                }
            }

            Optional<Path> library = findDirectNativeLibrary(normalizedBackend, platform, prepared)
                    .or(() -> findNativeLibrary(normalizedBackend, platform, prepared));
            Optional<Path> pluginPath = BACKEND_VLC.equals(normalizedBackend) ? findDirectDirectory(prepared, "plugins")
                    .or(() -> findDirectory(prepared, "plugins")) : Optional.empty();
            return Optional.of(new PreparedNativePackage(prepared, library.orElse(null), pluginPath.orElse(null)));
        } catch (Throwable t) {
            VideoPlayerMain.LOGGER.warn("Failed to prepare native package {} for loading", normalizedBackend, t);
            return Optional.empty();
        } finally {
            prepareLock.unlock();
        }
    }

    static Path preparedRoot(Path temporaryRoot, String backend, String platform, String version,
                             String processDirectory, String packageKey) {
        return temporaryRoot
                .resolve(PREPARED_DIRECTORY)
                .resolve(safeName(processDirectory))
                .resolve(safeName(version))
                .resolve(NativeDownloadConfig.normalizeBackend(backend))
                .resolve(safeName(platform))
                .resolve(safeName(packageKey));
    }

    private static void cleanupPreparedRoots(Path temporaryRoot) {
        Path root = temporaryRoot.resolve(PREPARED_DIRECTORY);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path processRoot : stream.toList()) {
                if (!Files.isDirectory(processRoot, LinkOption.NOFOLLOW_LINKS)
                        || processRoot.getFileName() == null
                        || processRoot.getFileName().toString().equals(PROCESS_DIRECTORY)) {
                    continue;
                }
                if (staleProcessDirectory(processRoot.getFileName().toString())) {
                    try {
                        deleteRecursively(processRoot);
                    } catch (IOException cleanupError) {
                        VideoPlayerMain.LOGGER.debug("Unable to clean stale native preparation directory {}", processRoot, cleanupError);
                    }
                }
            }
        } catch (IOException error) {
            VideoPlayerMain.LOGGER.debug("Unable to enumerate native preparation directories under {}", root, error);
        }
    }

    private static boolean staleProcessDirectory(String name) {
        if (!name.startsWith("process-")) return false;
        int separator = name.indexOf('-', "process-".length());
        if (separator < 0) return false;
        long pid;
        long started;
        try {
            pid = Long.parseLong(name.substring("process-".length(), separator));
            started = Long.parseLong(name.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) return true;
        if (started <= 0L) return false;
        return process.get().info().startInstant()
                .map(value -> value.toEpochMilli() != started)
                .orElse(false);
    }

    private static InstallFileLock acquireInstallFileLock(String backend, String platform, BooleanSupplier active) throws IOException {
        String lockName = safeName(NativeDownloadConfig.normalizeBackend(backend))
                + "_" + safeName(NativeDownloadConfig.normalizeKnownPlatform(platform)) + ".lock";
        return acquireFileLock(LOCK_ROOT.resolve(lockName), active);
    }

    private static InstallFileLock acquireDestinationFileLock(Path destination, BooleanSupplier active) throws IOException {
        return acquireFileLock(destinationLockPath(destination), active);
    }

    static Path destinationLockPath(Path destination) {
        Path normalized = normalizeDestination(destination);
        String digest = sha256Text(normalizedDestinationKey(normalized));
        return LOCK_ROOT.resolve("managed-file-" + digest + ".lock");
    }

    private static Path normalizeDestination(Path destination) {
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null && normalized.getFileName() != null) {
            try {
                if (Files.exists(parent)) {
                    normalized = parent.toRealPath().resolve(normalized.getFileName()).normalize();
                }
            } catch (IOException ignored) {
            }
        }
        return normalized;
    }

    private static String normalizedDestinationKey(Path destination) {
        String key = destination.toString();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? key.toLowerCase(Locale.ROOT) : key;
    }

    private static String sha256Text(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private static boolean validExistingFile(Path destination, FileValidator validator) {
        if (validator == null || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            validator.validate(destination);
            return true;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception ignored) {
            return false;
        }
    }

    static InstallFileLock acquireFileLock(Path lockPath, BooleanSupplier active) throws IOException {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        Path normalized = lockPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) Files.createDirectories(parent);
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            while (true) {
                checkActive(guard);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) return new InstallFileLock(channel, lock);
                } catch (OverlappingFileLockException ignored) {
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    CancellationException cancelled = new CancellationException("Native package operation cancelled");
                    cancelled.initCause(interrupted);
                    throw cancelled;
                }
            }
        } catch (IOException | RuntimeException | Error error) {
            try {
                channel.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private static Path jnaTmpDir() {
        String configured = System.getProperty("jna.tmpdir", "").trim();
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        String javaTmp = System.getProperty("java.io.tmpdir", "").trim();
        if (!javaTmp.isBlank()) {
            return Path.of(javaTmp);
        }
        return Path.of(".");
    }

    public static Optional<Path> findNativeLibrary(String backend, Path root) {
        return findNativeLibrary(backend, selectedPlatform(backend), root);
    }

    private static Optional<Path> findNativeLibrary(String backend, String platform, Path root) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isBackendLibrary(normalizedBackend, platform, path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findDirectNativeLibrary(String backend, String platform, Path root) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isBackendLibrary(normalizedBackend, platform, path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path download(HttpClient http, String targetName, NativeDownloadConfig.DownloadSource source, int sourceIndex, int sourceCount,
                                  ProgressListener listener) throws Exception {
        return download(http, targetName, source, sourceIndex, sourceCount, listener, ALWAYS_ACTIVE);
    }

    private static Path download(HttpClient http, String targetName, NativeDownloadConfig.DownloadSource source, int sourceIndex, int sourceCount,
                                 ProgressListener listener, BooleanSupplier active) throws Exception {
        checkActive(active);
        Files.createDirectories(DOWNLOAD_ROOT);
        Path target = DOWNLOAD_ROOT.resolve(targetName);
        Files.deleteIfExists(target);
        try {
            URI uri = URI.create(source.url.trim());
            if (!MediaAddressPolicy.isAllowed(uri.toString())) {
                throw new IOException("Download source address is not allowed");
            }
            for (int redirect = 0; ; redirect++) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "VideoPlayer/" + VideoPlayerMain.version)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream ignored = response.body()) {
                        String location = response.headers().firstValue("Location").orElse("");
                        if (redirect >= MAX_DOWNLOAD_REDIRECTS || location.isBlank()) {
                            throw new IOException("Too many redirects while downloading native package");
                        }
                        URI next = uri.resolve(location);
                        if (!MediaAddressPolicy.isAllowed(next.toString())) {
                            throw new IOException("Download redirect target is not allowed");
                        }
                        uri = next;
                    }
                    continue;
                }
                try (InputStream in = response.body()) {
                    checkActive(active);
                    if (status < 200 || status >= 300) {
                        throw new IOException("HTTP " + status);
                    }

                    long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Download exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
                    }
                    long read = 0;
                    byte[] buffer = new byte[64 * 1024];
                    try (var out = Files.newOutputStream(target)) {
                        int len;
                        while ((len = readWithIdleTimeout(in, buffer, active)) >= 0) {
                            checkActive(active);
                            if (len == 0) continue;
                            if (read > MAX_DOWNLOAD_BYTES - len) {
                                throw new IOException("Download exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
                            }
                            out.write(buffer, 0, len);
                            read += len;
                            notify(listener, new DownloadProgress(sourceIndex + 1, sourceCount, read, total,
                                    sourceName(source), VpTranslation.of("message.videoplayer.native.downloading", "Downloading")));
                        }
                    }
                }
                return target;
            }
        } catch (Exception error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static HttpClient httpClient(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER);
        return HttpProxyConfig.parse(proxy).configure(builder).build();
    }

    private static int readWithIdleTimeout(InputStream input, byte[] buffer, BooleanSupplier active) throws IOException {
        Future<Integer> readTask = DOWNLOAD_READ_EXECUTOR.submit(() -> input.read(buffer));
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DOWNLOAD_READ_IDLE_TIMEOUT_MILLIS);
            while (true) {
                checkActive(active);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new IOException("Download stalled while reading response body");
                }
                try {
                    return readTask.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    CancellationException cancelled = new CancellationException("Native package operation interrupted");
                    cancelled.initCause(interrupted);
                    throw cancelled;
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Unable to read download response", cause);
                }
            }
        } finally {
            if (!readTask.isDone()) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
                readTask.cancel(true);
            }
        }
    }

    private static void verify(NativeDownloadConfig.DownloadSource source, Path zip) throws Exception {
        verify(source, zip, ALWAYS_ACTIVE);
    }

    private static void verify(NativeDownloadConfig.DownloadSource source, Path zip, BooleanSupplier active) throws Exception {
        checkActive(active);
        String expected = source.sha256 == null ? "" : source.sha256.trim();
        if (expected.isBlank()) {
            throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.sha_missing", "Configured download source is missing a SHA-256 checksum"));
        }
        if (!isLowercaseSha256(expected)) {
            throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.sha_invalid",
                    "Configured SHA-256 must be 64 lowercase hex characters"));
        }
        String actual = sha256(zip, active);
        if (!expected.equals(actual)) {
            Files.deleteIfExists(zip);
            throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.sha_mismatch_detail",
                    "SHA-256 mismatch: expected %s, got %s", expected, actual));
        }
    }

    private static boolean isLowercaseSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static void installZip(String backend, String platform, Path zip) throws Exception {
        installZip(backend, platform, zip, ALWAYS_ACTIVE);
    }

    private static void installZip(String backend, String platform, Path zip, BooleanSupplier active) throws Exception {
        checkActive(active);
        Path installed = installedRoot(backend, platform);
        Path parent = installed.getParent();
        Path staging = installed.resolveSibling(installed.getFileName() + ".installing-" + UUID.randomUUID());
        Files.createDirectories(parent);
        deleteRecursively(staging);
        Files.createDirectories(staging);
        try {
            extractZip(zip, staging, active);
            checkActive(active);
            Optional<Path> library = findNativeLibrary(backend, platform, staging);
            if (library.isEmpty()) {
                throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.library_missing", "Could not find %s native library in zip", backend));
            }
            checkActive(active);
            writePackageManifest(staging, backend, platform, active);
            checkActive(active);
            if (!isValidInstallation(staging, backend, platform)) {
                throw new IOException("Staged native package failed integrity verification");
            }
            replaceDirectory(staging, installed, active, NativePackageManager::moveReplacing);
        } finally {
            try {
                deleteRecursively(staging);
            } catch (IOException cleanupError) {
                VideoPlayerMain.LOGGER.warn("Unable to clean native package staging directory {}", staging, cleanupError);
            }
            try {
                Files.deleteIfExists(zip);
            } catch (IOException cleanupError) {
                VideoPlayerMain.LOGGER.warn("Unable to clean native package download {}", zip, cleanupError);
            }
        }
    }

    static void replaceDirectory(Path staging, Path installed, BooleanSupplier active, PathMover mover) throws Exception {
        replacePath(staging, installed, active, mover, NativePackageManager::deleteRecursively);
    }

    private static void extractZip(Path zip, Path targetRoot) throws IOException {
        extractZip(zip, targetRoot, ALWAYS_ACTIVE);
    }

    private static void extractZip(Path zip, Path targetRoot, BooleanSupplier active) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            int entryCount = 0;
            long expandedBytes = 0;
            while ((entry = in.getNextEntry()) != null) {
                checkActive(active);
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.isBlank()) continue;
                Path target = targetRoot.resolve(name).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.zip_path_illegal", "Illegal zip path: %s", entry.getName()));
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    long entryBytes = copyLimited(in, target, active, MAX_ARCHIVE_ENTRY_BYTES);
                    if (expandedBytes > MAX_ARCHIVE_EXPANDED_BYTES - entryBytes) {
                        throw new IOException("Archive expands beyond " + MAX_ARCHIVE_EXPANDED_BYTES + " bytes");
                    }
                    expandedBytes += entryBytes;
                }
            }
        }
    }

    private static boolean isBackendLibrary(String backend, String platform, String name) {
        String os = NativeDownloadConfig.osFromPlatform(platform);
        if (os.isBlank()) os = NativeDownloadConfig.osKey();
        if (BACKEND_MPV.equals(backend)) {
            if (os.equals("windows")) {
                return name.equals("libmpv-2.dll") || name.equals("mpv-2.dll") || name.equals("libmpv.dll") || name.equals("mpv.dll");
            }
            if (os.equals("macos")) {
                return name.equals("libmpv.dylib") || name.startsWith("libmpv.") && name.endsWith(".dylib");
            }
            return name.equals("libmpv.so") || name.startsWith("libmpv.so.");
        }
        if (os.equals("windows")) {
            return name.equals("libvlc.dll");
        }
        if (os.equals("macos")) {
            return name.equals("libvlc.dylib") || name.startsWith("libvlc.") && name.endsWith(".dylib");
        }
        return name.equals("libvlc.so") || name.startsWith("libvlc.so.");
    }

    private static Optional<Path> findDirectory(Path root, String name) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findDirectDirectory(Path root, String name) {
        Path direct = root.resolve(name);
        return Files.isDirectory(direct) ? Optional.of(direct) : Optional.empty();
    }

    private static String sha256(Path file) throws Exception {
        return sha256(file, ALWAYS_ACTIVE);
    }

    private static String sha256(Path file, BooleanSupplier active) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                checkActive(active);
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void writePackageManifest(Path root, String backend, String platform) throws Exception {
        writePackageManifest(root, backend, platform, ALWAYS_ACTIVE);
    }

    private static void writePackageManifest(Path root, String backend, String platform, BooleanSupplier active) throws Exception {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path manifestPath = normalizedRoot.resolve(PACKAGE_MANIFEST_FILE);
        Files.deleteIfExists(manifestPath);
        ArrayList<PackageFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            for (Path path : stream.sorted().toList()) {
                checkActive(guard);
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic links are not supported in managed native packages");
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = normalizedRoot.relativize(path).toString().replace('\\', '/');
                files.add(new PackageFile(relative, Files.size(path), sha256(path, guard)));
            }
        }
        if (files.isEmpty()) {
            throw new IOException("Managed native package is empty");
        }
        String installerVersion = managedPackageVersion();
        PackageManifest manifest = new PackageManifest(PACKAGE_MANIFEST_VERSION, installerVersion,
                normalizedBackend, normalizedPlatform, files);
        Path temporary = manifestPath.resolveSibling(manifestPath.getFileName() + ".installing-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, GSON.toJson(manifest), StandardCharsets.UTF_8);
            checkActive(guard);
            moveReplacing(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean isValidInstallation(Path root, String backend, String platform) {
        try {
            String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
            String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path manifestPath = normalizedRoot.resolve(PACKAGE_MANIFEST_FILE);
            if (!Files.isDirectory(normalizedRoot) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            PackageManifest manifest = GSON.fromJson(Files.readString(manifestPath, StandardCharsets.UTF_8), PackageManifest.class);
            if (manifest == null
                    || manifest.version != PACKAGE_MANIFEST_VERSION
                    || manifest.installerVersion == null
                    || manifest.installerVersion.isBlank()
                    || !managedPackageVersion().equals(manifest.installerVersion)
                    || !normalizedBackend.equals(manifest.backend)
                    || !normalizedPlatform.equals(manifest.platform)
                    || manifest.files == null
                    || manifest.files.isEmpty()) {
                return false;
            }

            Set<String> expected = new HashSet<>();
            for (PackageFile entry : manifest.files) {
                if (entry == null
                        || entry.path == null
                        || entry.path.isBlank()
                        || entry.path.indexOf('\\') >= 0
                        || entry.size < 0
                        || !isLowercaseSha256(entry.sha256)
                        || !expected.add(entry.path)) {
                    return false;
                }
                Path relative = Path.of(entry.path);
                Path target = normalizedRoot.resolve(relative).normalize();
                if (relative.isAbsolute()
                        || !target.startsWith(normalizedRoot)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target) != entry.size
                        || !entry.sha256.equals(sha256(target))) {
                    return false;
                }
            }

            Set<String> actual = new HashSet<>();
            try (Stream<Path> stream = Files.walk(normalizedRoot)) {
                for (Path path : stream.toList()) {
                    if (Files.isSymbolicLink(path)) return false;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || path.equals(manifestPath)) continue;
                    actual.add(normalizedRoot.relativize(path).toString().replace('\\', '/'));
                }
            }
            if (!actual.equals(expected)) return false;
            Optional<Path> library = findNativeLibrary(normalizedBackend, normalizedPlatform, normalizedRoot);
            if (library.isEmpty() || Files.size(library.get()) <= 0) return false;
            if (BACKEND_VLC.equals(normalizedBackend) && NativeDownloadConfig.ANDROID_ARM64.equals(normalizedPlatform)) {
                return requiredFileReady(normalizedRoot.resolve("libvlc.so"))
                        && requiredFileReady(normalizedRoot.resolve("libvlcjni.so"))
                        && requiredFileReady(normalizedRoot.resolve("libvlc_jvm_bridge.so"))
                        && requiredFileReady(normalizedRoot.resolve("libc++_shared.so"));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean requiredFileReady(Path path) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 0;
    }

    private static String managedPackageVersion() {
        String configured = System.getProperty("videoplayer.version", "").trim();
        if (!configured.isBlank()) return configured;
        Package packageInfo = NativePackageManager.class.getPackage();
        String implementationVersion = packageInfo == null ? null : packageInfo.getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) return implementationVersion;
        String applicationVersion = System.getProperty("videoplayer.version", "").trim();
        return applicationVersion.isBlank() || "unknown".equalsIgnoreCase(applicationVersion)
                ? "manifest-" + PACKAGE_MANIFEST_VERSION
                : applicationVersion;
    }

    private static String installedPackageVersion(Path root) throws IOException {
        PackageManifest manifest = GSON.fromJson(
                Files.readString(root.resolve(PACKAGE_MANIFEST_FILE), StandardCharsets.UTF_8),
                PackageManifest.class
        );
        return manifest == null || manifest.installerVersion == null || manifest.installerVersion.isBlank()
                ? "manifest-" + PACKAGE_MANIFEST_VERSION
                : manifest.installerVersion;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static void notify(ProgressListener listener, DownloadProgress progress) {
        if (listener != null) listener.onProgress(progress);
    }

    private static void copy(InputStream input, Path target, BooleanSupplier active) throws IOException {
        copyLimited(input, target, active, MAX_DOWNLOAD_BYTES);
    }

    private static long copyLimited(InputStream input, Path target, BooleanSupplier active, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkActive(active);
                if (read == 0) continue;
                if (total > maxBytes - read) {
                    throw new IOException("Copied data exceeds " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static void checkActive(BooleanSupplier active) {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) {
            throw new CancellationException("Native package operation cancelled");
        }
    }

    private static DownloadResult cancelled(Throwable error) {
        return DownloadResult.fail(VpTranslation.of(
                "error.videoplayer.native.download_cancelled", "Native package operation cancelled"
        ), error);
    }

    private static void deleteTemporary(Path path, Throwable error) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupError) {
            error.addSuppressed(cleanupError);
        }
    }

    private static void installFile(Path downloaded, Path destination, boolean executable, FileValidator validator) throws Exception {
        installFile(downloaded, destination, executable, validator, ALWAYS_ACTIVE);
    }

    static void installFile(Path downloaded, Path destination, boolean executable, FileValidator validator,
                            BooleanSupplier active) throws Exception {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        checkActive(guard);
        Files.createDirectories(destination.getParent());
        String filename = destination.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String stagingName = extension > 0
                ? filename.substring(0, extension) + "-installing-" + System.nanoTime() + filename.substring(extension)
                : filename + "-installing-" + System.nanoTime();
        Path staging = destination.resolveSibling(stagingName);
        Files.deleteIfExists(staging);
        try {
            try (InputStream input = Files.newInputStream(downloaded)) {
                copy(input, staging, guard);
            }
            checkActive(guard);
            if (executable && !staging.toFile().setExecutable(true, true) && !Files.isExecutable(staging)) {
                throw new IOException("Unable to set executable permission on " + staging);
            }
            checkActive(guard);
            if (validator != null) validator.validate(staging);
            checkActive(guard);
            replaceFile(staging, destination, guard, NativePackageManager::moveReplacing);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanupError) {
                VideoPlayerMain.LOGGER.warn("Unable to clean native file staging path {}", staging, cleanupError);
            }
            try {
                Files.deleteIfExists(downloaded);
            } catch (IOException cleanupError) {
                VideoPlayerMain.LOGGER.warn("Unable to clean native file download {}", downloaded, cleanupError);
            }
        }
    }

    static void replaceFile(Path staging, Path installed, BooleanSupplier active, PathMover mover) throws Exception {
        replacePath(staging, installed, active, mover, Files::deleteIfExists);
    }

    private static void replacePath(Path staging, Path installed, BooleanSupplier active,
                                    PathMover mover, PathDeleter deleter) throws Exception {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        Path previous = installed.resolveSibling(installed.getFileName() + ".previous");
        checkActive(guard);
        if (Files.exists(previous)) {
            if (Files.exists(installed)) {
                deleter.delete(previous);
            } else {
                mover.move(previous, installed);
            }
        }
        checkActive(guard);
        boolean backedUp = false;
        boolean installedNew = false;
        try {
            if (Files.exists(installed)) {
                mover.move(installed, previous);
                backedUp = true;
                checkActive(guard);
            }
            mover.move(staging, installed);
            installedNew = true;
            checkActive(guard);
            if (backedUp) deleter.delete(previous);
        } catch (Throwable error) {
            Throwable rollbackFailure = null;
            try {
                if (installedNew && Files.exists(installed)) deleter.delete(installed);
            } catch (Throwable rollbackError) {
                rollbackFailure = rollbackError;
            }
            try {
                if (backedUp && Files.exists(previous)) mover.move(previous, installed);
            } catch (Throwable rollbackError) {
                if (rollbackFailure == null) {
                    rollbackFailure = rollbackError;
                } else {
                    rollbackFailure.addSuppressed(rollbackError);
                }
            }
            if (rollbackFailure != null) error.addSuppressed(rollbackFailure);
            throw error;
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeName(String value) {
        return (value == null ? "download" : value).replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    static String temporaryDownloadName(String name, String platform, String suffix) {
        return safeName(name) + "_" + safeName(platform) + "_" + UUID.randomUUID() + safeNameSuffix(suffix);
    }

    private static String safeNameSuffix(String suffix) {
        String value = suffix == null ? ".tmp" : suffix;
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return sanitized.isBlank() ? ".tmp" : sanitized;
    }

    private record DownloadMirror(String name, String prefix) {
    }

    static final class InstallFileLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        InstallFileLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                if (lock.isValid()) lock.release();
            } catch (IOException error) {
                failure = error;
            }
            try {
                channel.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class PackageManifest {
        int version;
        String installerVersion;
        String backend;
        String platform;
        List<PackageFile> files;

        PackageManifest(int version, String installerVersion, String backend, String platform, List<PackageFile> files) {
            this.version = version;
            this.installerVersion = installerVersion;
            this.backend = backend;
            this.platform = platform;
            this.files = List.copyOf(files);
        }
    }

    private static final class PackageFile {
        String path;
        long size;
        String sha256;

        PackageFile(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    interface FileValidator {
        void validate(Path file) throws Exception;
    }

    @FunctionalInterface
    interface PathMover {
        void move(Path source, Path target) throws Exception;
    }

    @FunctionalInterface
    private interface PathDeleter {
        void delete(Path path) throws Exception;
    }

    public record DownloadProgress(int sourceIndex, int sourceCount, long bytesRead, long totalBytes, String sourceName, VpTranslation message) {
    }

    public record DownloadResult(boolean success, VpTranslation message, Throwable error, String sourceName) {
        public static DownloadResult ok(VpTranslation message) {
            return ok(message, "");
        }

        public static DownloadResult ok(VpTranslation message, String sourceName) {
            return new DownloadResult(true, message, null, sourceName == null ? "" : sourceName);
        }

        public static DownloadResult fail(VpTranslation message, Throwable error) {
            VpTranslation safe = message == null || message.isEmpty()
                    ? VpTranslation.of("error.videoplayer.native.download_failed", "Download failed")
                    : message;
            return new DownloadResult(false, safe, error, "");
        }
    }

    public record PreparedNativePackage(Path root, Path library, Path pluginPath) {
    }
}
