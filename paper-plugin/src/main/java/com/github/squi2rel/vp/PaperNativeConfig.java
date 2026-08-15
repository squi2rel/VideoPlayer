package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.video.StreamListener;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class PaperNativeConfig {
    private static final String DEFAULT_BACKEND = NativeDownloadConfig.BACKEND_MPV;

    private final String backend;
    private final String platform;
    private final String downloadProxy;
    private final String mpvYtdlPath;
    private final String youtubeCookiesFile;
    private final String youtubeCookiesFromBrowser;

    private PaperNativeConfig(String backend, String platform, String downloadProxy, String mpvYtdlPath,
                              String youtubeCookiesFile, String youtubeCookiesFromBrowser) {
        this.backend = backend;
        this.platform = platform;
        this.downloadProxy = downloadProxy == null ? "" : downloadProxy.trim();
        this.mpvYtdlPath = mpvYtdlPath == null ? "" : mpvYtdlPath.trim();
        this.youtubeCookiesFile = setting(youtubeCookiesFile);
        this.youtubeCookiesFromBrowser = setting(youtubeCookiesFromBrowser);
    }

    static PaperNativeConfig load(VideoPlayerPaperPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String backend = backend(config.getString("native.backend", ""));
        String platform = platform(config.getString("native.os", ""), config.getString("native.arch", ""));
        if (!NativeDownloadConfig.isSupportedBackendPlatform(backend, platform)) {
            String configuredBackend = backend;
            String configuredPlatform = platform;
            platform = NativeDownloadConfig.platformKey();
            backend = "android".equals(NativeDownloadConfig.osFromPlatform(platform))
                    ? NativeDownloadConfig.BACKEND_VLC
                    : DEFAULT_BACKEND;
            VideoPlayerMain.LOGGER.warn("Unsupported native backend/platform '{} / {}'; using {} / {}",
                    configuredBackend, configuredPlatform, backend, platform);
        }
        String proxy = config.getString("native.download-proxy", "");
        String ytdlPath = config.getString("native.mpv-ytdl-path", "");
        String cookiesFile = firstNonBlank(
                config.getString("youtube.cookies-file", ""),
                config.getString("native.youtube-cookies-file", ""),
                config.getString("native.youtube.cookies-file", "")
        );
        String cookiesFromBrowser = firstNonBlank(
                config.getString("youtube.cookies-from-browser", ""),
                config.getString("native.youtube-cookies-from-browser", ""),
                config.getString("native.youtube.cookies-from-browser", "")
        );
        return new PaperNativeConfig(backend, platform, proxy, ytdlPath, cookiesFile, cookiesFromBrowser);
    }

    void apply() {
        NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, platform);
        NativePackageManager.selectPlatform(NativePackageManager.BACKEND_VLC, platform);
        StreamListener.configurePreferredBackend(backend);
        StreamListener.configureProxy(downloadProxy);
        String effectiveYtdlPath = YtDlpManager.effectiveExecutable(mpvYtdlPath);
        StreamListener.configureYtdlPath(effectiveYtdlPath);
        YouTubeProvider.configureProxy(downloadProxy);
        YouTubeProvider.configureYtdlPath(effectiveYtdlPath);
        YouTubeProvider.configureCookies(youtubeCookiesFile, youtubeCookiesFromBrowser);
        YouTubeProvider.configureMissingYtdlHandler(this::ensureYtdlForResolution);
        validateYtdlPath();
        StreamListener.resetLoadState();
        VideoPlayerMain.LOGGER.info("VideoPlayer native backend={} platform={} dataDir={}",
                backend, platform, System.getProperty("videoplayer.configDir", ""));
    }

    void downloadYtDlpIfMissing(BooleanSupplier active) {
        if (!active.getAsBoolean()) return;
        YtDlpManager.Detection detection = YtDlpManager.detect(mpvYtdlPath, active);
        if (detection.available()) {
            if (!active.getAsBoolean()) return;
            applyDetectedYtDlp(detection);
            VideoPlayerMain.LOGGER.info("Using yt-dlp {} from {}", detection.version(), detection.source().name().toLowerCase(Locale.ROOT));
            return;
        }
        if (!mpvYtdlPath.isBlank() && !YtDlpManager.isManagedExecutable(mpvYtdlPath)) {
            VideoPlayerMain.LOGGER.error("Configured yt-dlp executable is unavailable: {}", mpvYtdlPath, detection.error());
            return;
        }

        NativeDownloadConfig downloads = NativeDownloadConfig.load();
        String detectedPlatform = NativeDownloadConfig.platformKey();
        if (!YtDlpManager.isSupported(downloads, detectedPlatform)) {
            VideoPlayerMain.LOGGER.warn("yt-dlp automatic installation is not supported on {}; configure native.mpv-ytdl-path manually", detectedPlatform);
            return;
        }

        VideoPlayerMain.LOGGER.info("Downloading yt-dlp for {}", detectedPlatform);
        NativePackageManager.DownloadResult result = YtDlpManager.downloadAndInstall(
                downloads, detectedPlatform, downloadProxy, null, active
        );
        if (!active.getAsBoolean()) return;
        if (!result.success()) {
            VideoPlayerMain.LOGGER.warn("Failed to download yt-dlp for {}: {}", detectedPlatform, message(result.message()), result.error());
            return;
        }
        YtDlpManager.Detection installed = YtDlpManager.detect("", active);
        if (!installed.available()) {
            VideoPlayerMain.LOGGER.error("yt-dlp installation completed but the executable is unavailable", installed.error());
            return;
        }
        applyDetectedYtDlp(installed);
        VideoPlayerMain.LOGGER.info("Installed yt-dlp {} from {}", installed.version(), result.sourceName());
    }

    private void applyDetectedYtDlp(YtDlpManager.Detection detection) {
        String executable = detection.source() == YtDlpManager.Source.PATH ? "" : detection.executable();
        StreamListener.configureYtdlPath(executable);
        YouTubeProvider.configureYtdlPath(executable);
    }

    private String ensureYtdlForResolution() {
        try {
            YtDlpManager.EnsureResult result = YtDlpManager.ensureAvailable(
                    mpvYtdlPath, NativeDownloadConfig.load(), platform, downloadProxy, null
            );
            if (result == null || !result.available() || result.detection() == null) return "";
            String executable = result.detection().executable();
            if (executable == null || executable.isBlank()) return "";
            StreamListener.configureYtdlPath(executable);
            YouTubeProvider.configureYtdlPath(executable);
            return executable;
        } catch (RuntimeException error) {
            VideoPlayerMain.LOGGER.warn("Unable to make yt-dlp available for YouTube resolution", error);
            return "";
        }
    }

    private void validateYtdlPath() {
        if (mpvYtdlPath.isBlank()) {
            VideoPlayerMain.LOGGER.info("No yt-dlp path configured; searching server PATH");
            return;
        }
        try {
            Path path = Path.of(mpvYtdlPath);
            if (Files.isRegularFile(path)) {
                VideoPlayerMain.LOGGER.info("Using configured yt-dlp executable {}", path.toAbsolutePath());
            } else {
                VideoPlayerMain.LOGGER.error("Configured yt-dlp executable does not exist or is not a file: {}", path.toAbsolutePath());
            }
        } catch (InvalidPathException e) {
            VideoPlayerMain.LOGGER.error("Configured yt-dlp path is invalid: {}", mpvYtdlPath, e);
        }
    }

    void downloadIfMissing(BooleanSupplier active) {
        if (!active.getAsBoolean()) return;
        if (NativePackageManager.isInstalled(backend, platform)) {
            return;
        }

        NativeDownloadConfig downloads = NativeDownloadConfig.load();
        if (!active.getAsBoolean()) return;
        List<NativeDownloadConfig.DownloadSource> sources = downloads.sources(backend, platform);
        if (sources.isEmpty()) {
            VideoPlayerMain.LOGGER.warn("No native download sources configured for {} {}; trying system libraries", backend, platform);
            return;
        }

        VideoPlayerMain.LOGGER.info("Downloading VideoPlayer native package {} {}", backend, platform);
        NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstall(
                backend, platform, sources, downloadProxy, null, active
        );
        if (!active.getAsBoolean()) return;
        if (result.success()) {
            VideoPlayerMain.LOGGER.info("Installed VideoPlayer native package {} {} from {}", backend, platform, result.sourceName());
            return;
        }

        VideoPlayerMain.LOGGER.warn("Failed to download VideoPlayer native package {} {}; trying system libraries: {}",
                backend, platform, message(result.message()), result.error());
    }

    void downloadVlcFallbackIfMissing(BooleanSupplier active) {
        if (!active.getAsBoolean() || !NativeDownloadConfig.BACKEND_MPV.equals(backend)) return;
        if (!NativeDownloadConfig.isSupportedBackendPlatform(NativeDownloadConfig.BACKEND_VLC, platform)) return;
        if (NativePackageManager.isInstalled(NativeDownloadConfig.BACKEND_VLC, platform)) return;

        NativeDownloadConfig downloads = NativeDownloadConfig.load();
        List<NativeDownloadConfig.DownloadSource> sources = downloads.sources(NativeDownloadConfig.BACKEND_VLC, platform);
        if (sources.isEmpty()) {
            VideoPlayerMain.LOGGER.warn("No VLC fallback runtime download sources are configured for {}", platform);
            return;
        }

        VideoPlayerMain.LOGGER.info("Downloading VLC fallback runtime for {} after MPV could not load", platform);
        NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstall(
                NativeDownloadConfig.BACKEND_VLC, platform, sources, downloadProxy, null, active
        );
        if (!active.getAsBoolean()) return;
        if (result.success()) {
            VideoPlayerMain.LOGGER.info("Installed VLC fallback runtime for {} from {}", platform, result.sourceName());
            return;
        }
        VideoPlayerMain.LOGGER.warn("Failed to download VLC fallback runtime for {}: {}",
                platform, message(result.message()), result.error());
    }

    private static String backend(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return DEFAULT_BACKEND;
        }
        if (NativeDownloadConfig.BACKEND_MPV.equals(value) || NativeDownloadConfig.BACKEND_VLC.equals(value)) {
            return value;
        }
        VideoPlayerMain.LOGGER.warn("Invalid native.backend '{}'; using {}", raw, DEFAULT_BACKEND);
        return DEFAULT_BACKEND;
    }

    private static String platform(String rawOs, String rawArch) {
        String os = sanitize(rawOs);
        String arch = sanitize(rawArch);
        if (os.isBlank() && arch.isBlank()) {
            return NativeDownloadConfig.platformKey();
        }

        String current = NativeDownloadConfig.platformKey();
        if (os.isBlank()) {
            os = NativeDownloadConfig.osFromPlatform(current);
        }
        if (arch.isBlank()) {
            arch = NativeDownloadConfig.archFromPlatform(current);
        }

        String platform = os + "_" + arch;
        if (NativeDownloadConfig.isKnownPlatform(platform)) {
            return platform;
        }

        VideoPlayerMain.LOGGER.warn("Invalid native platform override '{} / {}'; using detected {}", rawOs, rawArch, current);
        return current;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String normalized = setting(value);
            if (!normalized.isBlank()) return normalized;
        }
        return "";
    }

    private static String setting(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace("\r", "").replace("\n", "").replace("\u0000", "");
        return normalized.length() > 4096 ? normalized.substring(0, 4096) : normalized;
    }

    private static String message(com.github.squi2rel.vp.i18n.VpTranslation translation) {
        if (translation == null || translation.isEmpty()) return "";
        if (translation.args().isEmpty()) return translation.fallback();
        try {
            return String.format(translation.fallback(), translation.args().toArray());
        } catch (RuntimeException ignored) {
            return translation.fallback();
        }
    }
}
