package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.video.StreamListener;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

final class PaperNativeConfig {
    private static final String DEFAULT_BACKEND = NativeDownloadConfig.BACKEND_MPV;

    private final String backend;
    private final String platform;
    private final String downloadProxy;
    private final String mpvYtdlPath;

    private PaperNativeConfig(String backend, String platform, String downloadProxy, String mpvYtdlPath) {
        this.backend = backend;
        this.platform = platform;
        this.downloadProxy = downloadProxy == null ? "" : downloadProxy.trim();
        this.mpvYtdlPath = mpvYtdlPath == null ? "" : mpvYtdlPath.trim();
    }

    static PaperNativeConfig load(VideoPlayerPaperPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String backend = backend(config.getString("native.backend", ""));
        String platform = platform(config.getString("native.os", ""), config.getString("native.arch", ""));
        String proxy = config.getString("native.download-proxy", "");
        String ytdlPath = config.getString("native.mpv-ytdl-path", "");
        return new PaperNativeConfig(backend, platform, proxy, ytdlPath);
    }

    void apply() {
        NativePackageManager.selectPlatform(backend, platform);
        StreamListener.configurePreferredBackend(backend);
        StreamListener.configureProxy(downloadProxy);
        StreamListener.configureYtdlPath(mpvYtdlPath);
        YouTubeProvider.configureProxy(downloadProxy);
        YouTubeProvider.configureYtdlPath(mpvYtdlPath);
        StreamListener.resetLoadState();
        VideoPlayerMain.LOGGER.info("VideoPlayer native backend={} platform={} dataDir={}",
                backend, platform, System.getProperty("videoplayer.configDir", ""));
    }

    void downloadIfMissing() {
        if (NativePackageManager.isInstalled(backend, platform)) {
            return;
        }

        NativeDownloadConfig downloads = NativeDownloadConfig.load();
        List<NativeDownloadConfig.DownloadSource> sources = downloads.sources(backend, platform);
        if (sources.isEmpty()) {
            VideoPlayerMain.LOGGER.warn("No native download sources configured for {} {}; trying system libraries", backend, platform);
            return;
        }

        VideoPlayerMain.LOGGER.info("Downloading VideoPlayer native package {} {}", backend, platform);
        NativePackageManager.DownloadResult result = NativePackageManager.downloadAndInstall(backend, platform, sources, downloadProxy, null);
        if (result.success()) {
            VideoPlayerMain.LOGGER.info("Installed VideoPlayer native package {} {}", backend, platform);
            return;
        }

        VideoPlayerMain.LOGGER.warn("Failed to download VideoPlayer native package {} {}; trying system libraries: {}",
                backend, platform, message(result.message()), result.error());
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
