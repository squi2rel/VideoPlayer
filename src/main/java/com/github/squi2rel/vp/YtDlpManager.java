package com.github.squi2rel.vp;

import com.github.squi2rel.vp.i18n.VpTranslation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

public final class YtDlpManager {
    public static final String TOOL_NAME = "yt-dlp";
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_VERSION_OUTPUT_BYTES = 64 * 1024;
    private static final ReentrantLock DOWNLOAD_LOCK = new ReentrantLock();
    private static final BooleanSupplier ALWAYS_ACTIVE = () -> true;
    private static volatile Path rejectedManagedExecutable;

    private YtDlpManager() {
    }

    public static String effectiveExecutable(String configured) {
        String explicit = configured == null ? "" : configured.trim();
        if (!explicit.isBlank() && !isManagedExecutable(explicit)) return explicit;
        Path managed = managedExecutable().toAbsolutePath();
        boolean executable = NativeDownloadConfig.osKey().equals("windows") || Files.isExecutable(managed);
        if (Files.isRegularFile(managed) && executable && !managed.equals(rejectedManagedExecutable)) {
            return managed.toString();
        }
        if (!explicit.isBlank()) {
            try {
                Path legacy = Path.of(explicit).toAbsolutePath().normalize();
                boolean legacyExecutable = NativeDownloadConfig.osKey().equals("windows") || Files.isExecutable(legacy);
                if (Files.isRegularFile(legacy) && legacyExecutable) return legacy.toString();
            } catch (InvalidPathException ignored) {
            }
        }
        return "";
    }

    public static Detection detect(String configured) {
        return detect(configured, ALWAYS_ACTIVE);
    }

    public static Detection detect(String configured, BooleanSupplier active) {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        checkActive(guard);
        String explicit = configured == null ? "" : configured.trim();
        if (!explicit.isBlank() && !isManagedExecutable(explicit)) {
            return detectCommand(explicit, Source.CONFIGURED, guard);
        }

        Path managed = managedExecutable().toAbsolutePath();
        if (Files.isRegularFile(managed)) {
            Detection detection = detectCommand(managed.toString(), Source.MANAGED, guard);
            if (detection.available()) {
                rejectedManagedExecutable = null;
                return detection;
            }
            rejectedManagedExecutable = managed;
        }
        for (String candidate : pathCandidates()) {
            checkActive(guard);
            Detection detection = detectCommand(candidate, Source.PATH, guard);
            if (detection.available()) return detection;
        }
        return new Detection(false, "", "", Source.MISSING, null);
    }

    public static Path managedExecutable() {
        NativeDownloadConfig config = NativeDownloadConfig.load();
        return managedExecutable(config, NativeDownloadConfig.platformKey());
    }

    public static Path managedExecutable(NativeDownloadConfig config, String platform) {
        NativeDownloadConfig.ToolDownload tool = config.tool(TOOL_NAME);
        String version = sanitizeSegment(tool.version.isBlank() ? "unknown" : tool.version);
        return configDir().resolve("videoplayer-native").resolve("tools").resolve(TOOL_NAME)
                .resolve(version).resolve(sanitizeSegment(platform)).resolve(executableName(platform));
    }

    public static boolean isManagedExecutable(String configured) {
        if (configured == null || configured.isBlank()) return false;
        try {
            Path executable = Path.of(configured).toAbsolutePath().normalize();
            return executable.startsWith(managedRoot());
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    public static boolean isCurrentManagedExecutable(String configured) {
        if (!isManagedExecutable(configured)) return false;
        try {
            return Path.of(configured).toAbsolutePath().normalize()
                    .equals(managedExecutable().toAbsolutePath().normalize());
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    public static boolean isSupported(NativeDownloadConfig config, String platform) {
        return platform != null
                && !platform.startsWith("android_")
                && !config.tool(TOOL_NAME).sources(platform).isEmpty();
    }

    public static List<String> supportedPlatformsForCurrentOs(NativeDownloadConfig config) {
        ArrayList<String> result = new ArrayList<>();
        for (String platform : config.platformsForCurrentOs()) {
            if (isSupported(config, platform)) result.add(platform);
        }
        return List.copyOf(result);
    }

    public static NativePackageManager.DownloadResult downloadAndInstall(NativeDownloadConfig config, String platform,
                                                                         String proxy, NativePackageManager.ProgressListener listener) {
        return downloadAndInstall(config, platform, proxy, listener, ALWAYS_ACTIVE);
    }

    public static NativePackageManager.DownloadResult downloadAndInstall(NativeDownloadConfig config, String platform,
                                                                         String proxy, NativePackageManager.ProgressListener listener,
                                                                         BooleanSupplier active) {
        if (!isSupported(config, platform)) {
            return NativePackageManager.DownloadResult.fail(VpTranslation.of(
                    "error.videoplayer.ytdlp.unsupported_platform",
                    "yt-dlp automatic installation is not supported on %s", platform), null);
        }
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        NativeDownloadConfig.ToolDownload tool = config.tool(TOOL_NAME);
        Path destination = managedExecutable(config, platform);
        boolean locked = false;
        try {
            checkActive(guard);
            while (!(locked = DOWNLOAD_LOCK.tryLock(100, TimeUnit.MILLISECONDS))) {
                checkActive(guard);
            }
            NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstallFile(
                    TOOL_NAME, platform, tool.sources(platform), proxy, destination, !platform.startsWith("windows_"),
                    file -> verifyVersion(file, tool.version, guard), listener, guard);
            if (result.success()) rejectedManagedExecutable = null;
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return cancelled(interrupted);
        } catch (CancellationException cancelled) {
            return cancelled(cancelled);
        } finally {
            if (locked) DOWNLOAD_LOCK.unlock();
        }
    }

    static Detection detectCommand(String command, Source source) {
        return detectCommand(command, source, ALWAYS_ACTIVE);
    }

    static Detection detectCommand(String command, Source source, BooleanSupplier active) {
        try {
            String version = commandVersion(command, VERSION_TIMEOUT, active);
            return new Detection(true, command, version, source, null);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception error) {
            return new Detection(false, command, "", source, error);
        }
    }

    static String commandVersion(String command, Duration timeout) throws Exception {
        return commandVersion(command, timeout, ALWAYS_ACTIVE);
    }

    static String commandVersion(String command, Duration timeout, BooleanSupplier active) throws Exception {
        BooleanSupplier guard = active == null ? ALWAYS_ACTIVE : active;
        checkActive(guard);
        Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
        boolean success = false;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                checkActive(guard);
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) throw new IOException("yt-dlp --version timed out");
                long waitMillis = Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            }
            checkActive(guard);
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(readLimited(input), StandardCharsets.UTF_8).trim();
            }
            if (process.exitValue() != 0) throw new IOException("yt-dlp --version exited with " + process.exitValue());
            if (output.isBlank()) throw new IOException("yt-dlp --version returned no version");
            success = true;
            return output.lines().findFirst().orElse(output).trim();
        } finally {
            if (!success && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > MAX_VERSION_OUTPUT_BYTES - read) {
                throw new IOException("yt-dlp version output exceeds " + MAX_VERSION_OUTPUT_BYTES + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static void verifyVersion(Path executable, String expectedVersion, BooleanSupplier active) throws Exception {
        String actual = commandVersion(executable.toAbsolutePath().toString(), VERSION_TIMEOUT, active);
        if (expectedVersion != null && !expectedVersion.isBlank() && !expectedVersion.equals(actual)) {
            throw new IOException("yt-dlp version mismatch: expected " + expectedVersion + ", got " + actual);
        }
    }

    private static void checkActive(BooleanSupplier active) {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) {
            throw new CancellationException("yt-dlp installation cancelled");
        }
    }

    private static NativePackageManager.DownloadResult cancelled(Throwable error) {
        return NativePackageManager.DownloadResult.fail(VpTranslation.of(
                "error.videoplayer.native.download_cancelled", "Native package operation cancelled"
        ), error);
    }

    private static List<String> pathCandidates() {
        return NativeDownloadConfig.osKey().equals("windows")
                ? List.of("yt-dlp.exe", "yt-dlp")
                : List.of("yt-dlp", "yt-dlp.exe");
    }

    private static String executableName(String platform) {
        return platform != null && platform.startsWith("windows_") ? "yt-dlp.exe" : "yt-dlp";
    }

    private static String sanitizeSegment(String value) {
        String sanitized = (value == null ? "" : value.trim()).replaceAll("[^A-Za-z0-9._-]+", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static Path configDir() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object path = loader.getMethod("getConfigDir").invoke(instance);
            if (path instanceof Path configPath) return configPath;
        } catch (Throwable ignored) {
        }
        String configured = System.getProperty("videoplayer.configDir", "").trim();
        if (!configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.dir", ".")).resolve("config");
    }

    private static Path managedRoot() {
        return configDir().resolve("videoplayer-native").resolve("tools").resolve(TOOL_NAME)
                .toAbsolutePath().normalize();
    }

    public enum Source {
        CONFIGURED,
        MANAGED,
        PATH,
        MISSING
    }

    public record Detection(boolean available, String executable, String version, Source source, Throwable error) {
    }
}
