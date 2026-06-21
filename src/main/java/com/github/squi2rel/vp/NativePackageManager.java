package com.github.squi2rel.vp;

import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.TranslatableIOException;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NativePackageManager {
    public static final String BACKEND_VLC = NativeDownloadConfig.BACKEND_VLC;
    public static final String BACKEND_MPV = NativeDownloadConfig.BACKEND_MPV;

    private static final Path ROOT = configDir().resolve("videoplayer-native");
    private static final Path PACKAGE_ROOT = ROOT.resolve("packages");
    private static final Path DOWNLOAD_ROOT = ROOT.resolve("downloads");
    private static final Path EXTRACT_ROOT = ROOT.resolve("extracting");
    private static final String FINGERPRINT_FILE = ".videoplayer-fingerprint";
    private static final ConcurrentMap<String, String> SELECTED_PLATFORMS = new ConcurrentHashMap<>();

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
        return Files.isDirectory(installedRoot(backend));
    }

    public static boolean isInstalled(String backend, String platform) {
        return Files.isDirectory(installedRoot(backend, platform));
    }

    public static DownloadResult downloadAndInstall(String backend, List<NativeDownloadConfig.DownloadSource> sources, ProgressListener listener) {
        return downloadAndInstall(backend, selectedPlatform(backend), sources, listener);
    }

    public static DownloadResult downloadAndInstall(String backend, String platform, List<NativeDownloadConfig.DownloadSource> sources, ProgressListener listener) {
        return downloadAndInstall(backend, platform, sources, "", listener);
    }

    public static DownloadResult downloadAndInstall(String backend, String platform, List<NativeDownloadConfig.DownloadSource> sources,
                                                    String proxy, ProgressListener listener) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String normalizedPlatform = NativeDownloadConfig.normalizeKnownPlatform(platform);
        List<NativeDownloadConfig.DownloadSource> usableSources = sources == null
                ? List.of()
                : sources.stream()
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
            try {
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        VpTranslation.of("message.videoplayer.native.connecting", "Connecting")));
                Path zip = download(http, normalizedBackend, normalizedPlatform, source, i, usableSources.size(), listener);
                verify(source, zip);
                installZip(normalizedBackend, normalizedPlatform, zip);
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete")));
                return DownloadResult.ok(VpTranslation.of("message.videoplayer.native.install_complete", "Installation complete"));
            } catch (Throwable t) {
                lastError = t;
                VideoPlayerMain.LOGGER.warn("Native package download source {}/{} failed for {}", i + 1, usableSources.size(), normalizedBackend, t);
                notify(listener, new DownloadProgress(i + 1, usableSources.size(), 0, -1,
                        VpTranslation.of("message.videoplayer.native.source_failed_try_next", "Source failed, trying fallback")));
            }
        }
        return DownloadResult.fail(VpTranslations.from(lastError,
                "error.videoplayer.native.download_failed",
                "Download failed: %s",
                lastError == null || lastError.getMessage() == null ? "" : lastError.getMessage()), lastError);
    }

    public static Optional<PreparedNativePackage> prepareForLoad(String backend) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        String platform = selectedPlatform(normalizedBackend);
        Path installed = installedRoot(normalizedBackend, platform);
        if (!Files.isDirectory(installed)) {
            return Optional.empty();
        }
        try {
            Path jnaRoot = jnaTmpDir();
            Files.createDirectories(jnaRoot);

            String fingerprint = fingerprint(installed);
            Path marker = jnaRoot.resolve(fingerprintFile(normalizedBackend, platform));
            if (!Files.exists(marker)
                    || !Files.readString(marker).equals(fingerprint)
                    || findDirectNativeLibrary(normalizedBackend, jnaRoot).isEmpty()) {
                copyDirectory(installed, jnaRoot);
                Files.writeString(marker, fingerprint);
            }

            Optional<Path> library = findDirectNativeLibrary(normalizedBackend, jnaRoot)
                    .or(() -> findNativeLibrary(normalizedBackend, jnaRoot));
            Optional<Path> pluginPath = BACKEND_VLC.equals(normalizedBackend) ? findDirectDirectory(jnaRoot, "plugins")
                    .or(() -> findDirectory(jnaRoot, "plugins")) : Optional.empty();
            return Optional.of(new PreparedNativePackage(jnaRoot, library.orElse(null), pluginPath.orElse(null)));
        } catch (Throwable t) {
            VideoPlayerMain.LOGGER.warn("Failed to prepare native package {} for loading", normalizedBackend, t);
            return Optional.empty();
        }
    }

    private static String fingerprintFile(String backend, String platform) {
        return FINGERPRINT_FILE + "-" + backend + "_" + platform;
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
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isBackendLibrary(normalizedBackend, path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findDirectNativeLibrary(String backend, Path root) {
        String normalizedBackend = NativeDownloadConfig.normalizeBackend(backend);
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isBackendLibrary(normalizedBackend, path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path download(HttpClient http, String backend, String platform, NativeDownloadConfig.DownloadSource source, int sourceIndex, int sourceCount,
                                 ProgressListener listener) throws Exception {
        Files.createDirectories(DOWNLOAD_ROOT);
        Path target = DOWNLOAD_ROOT.resolve(backend + "_" + platform + ".zip.tmp");
        Files.deleteIfExists(target);

        HttpRequest request = HttpRequest.newBuilder(URI.create(source.url.trim()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "VideoPlayer/" + VideoPlayerMain.version)
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }

        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        long read = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = response.body(); var out = Files.newOutputStream(target)) {
            int len;
            while ((len = in.read(buffer)) >= 0) {
                if (len == 0) continue;
                out.write(buffer, 0, len);
                read += len;
                notify(listener, new DownloadProgress(sourceIndex + 1, sourceCount, read, total,
                        VpTranslation.of("message.videoplayer.native.downloading", "Downloading")));
            }
        }
        return target;
    }

    private static HttpClient httpClient(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxyConfig parsed = ProxyConfig.parse(proxy);
        if (parsed.enabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(parsed.host(), parsed.port())));
            if (!parsed.username().isEmpty()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(parsed.username(), parsed.password().toCharArray());
                    }
                });
            }
        }
        return builder.build();
    }

    private static void verify(NativeDownloadConfig.DownloadSource source, Path zip) throws Exception {
        String expected = source.sha256 == null ? "" : source.sha256.trim();
        if (expected.isBlank()) return;
        if (!isLowercaseSha256(expected)) {
            throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.sha_invalid",
                    "Configured SHA-256 must be 64 lowercase hex characters"));
        }
        String actual = sha256(zip);
        if (!expected.equals(actual)) {
            Files.deleteIfExists(zip);
            throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.sha_mismatch_detail",
                    "SHA-256 mismatch: expected %s, got %s", expected, actual));
        }
    }

    private static boolean isLowercaseSha256(String value) {
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static void installZip(String backend, String platform, Path zip) throws Exception {
        Path temp = EXTRACT_ROOT.resolve(backend + "_" + platform + "_" + System.nanoTime());
        Path extractedRoot = temp.resolve(platform);
        deleteRecursively(temp);
        Files.createDirectories(extractedRoot);
        try {
            extractZip(zip, extractedRoot);
            Optional<Path> library = findNativeLibrary(backend, extractedRoot);
            if (library.isEmpty()) {
                throw new TranslatableIOException(VpTranslation.of("error.videoplayer.native.library_missing", "Could not find %s native library in zip", backend));
            }
            Path installed = installedRoot(backend, platform);
            Files.createDirectories(installed.getParent());
            deleteRecursively(installed);
            Files.move(extractedRoot, installed, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteRecursively(temp);
            Files.deleteIfExists(zip);
        }
    }

    private static void extractZip(Path zip, Path targetRoot) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
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
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static boolean isBackendLibrary(String backend, String name) {
        if (BACKEND_MPV.equals(backend)) {
            if (NativeDownloadConfig.osKey().equals("windows")) {
                return name.equals("libmpv-2.dll") || name.equals("mpv-2.dll") || name.equals("libmpv.dll") || name.equals("mpv.dll");
            }
            if (NativeDownloadConfig.osKey().equals("macos")) {
                return name.equals("libmpv.dylib") || name.startsWith("libmpv.") && name.endsWith(".dylib");
            }
            return name.equals("libmpv.so") || name.startsWith("libmpv.so.");
        }
        if (NativeDownloadConfig.osKey().equals("windows")) {
            return name.equals("libvlc.dll");
        }
        if (NativeDownloadConfig.osKey().equals("macos")) {
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fingerprint(Path root) throws IOException {
        long count = 0;
        long size = 0;
        long modified = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path)) continue;
                count++;
                size += Files.size(path);
                modified = Math.max(modified, Files.getLastModifiedTime(path).toMillis());
            }
        }
        return count + ":" + size + ":" + modified;
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

    private record ProxyConfig(boolean enabled, String host, int port, String username, String password) {
        private static ProxyConfig parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isBlank()) {
                return new ProxyConfig(false, "", 0, "", "");
            }
            String normalized = value.contains("://") ? value : "http://" + value;
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new TranslatableIllegalArgumentException(VpTranslation.of("error.videoplayer.native.proxy_http_only", "Only HTTP proxies are supported"));
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new TranslatableIllegalArgumentException(VpTranslation.of("error.videoplayer.native.proxy_host_missing", "Proxy host is missing"));
            }
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
            if (port > 65535) {
                throw new TranslatableIllegalArgumentException(VpTranslation.of("error.videoplayer.native.proxy_port_invalid", "Invalid proxy port"));
            }
            String username = "";
            String password = "";
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                int separator = userInfo.indexOf(':');
                if (separator < 0) {
                    username = userInfo;
                } else {
                    username = userInfo.substring(0, separator);
                    password = userInfo.substring(separator + 1);
                }
            }
            return new ProxyConfig(true, host, port, username, password);
        }
    }

    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    public record DownloadProgress(int sourceIndex, int sourceCount, long bytesRead, long totalBytes, VpTranslation message) {
    }

    public record DownloadResult(boolean success, VpTranslation message, Throwable error) {
        public static DownloadResult ok(VpTranslation message) {
            return new DownloadResult(true, message, null);
        }

        public static DownloadResult fail(VpTranslation message, Throwable error) {
            VpTranslation safe = message == null || message.isEmpty()
                    ? VpTranslation.of("error.videoplayer.native.download_failed", "Download failed")
                    : message;
            return new DownloadResult(false, safe, error);
        }
    }

    public record PreparedNativePackage(Path root, Path library, Path pluginPath) {
    }
}
