package com.github.squi2rel.vp;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NativeDownloadConfig {
    public static final String BACKEND_VLC = "vlc";
    public static final String BACKEND_MPV = "mpv";

    public static final String WINDOWS_X86 = "windows_x86";
    public static final String WINDOWS_X64 = "windows_x64";
    public static final String WINDOWS_ARM64 = "windows_arm64";
    public static final String MACOS_X64 = "macos_x64";
    public static final String MACOS_ARM64 = "macos_arm64";
    public static final String LINUX_X86 = "linux_x86";
    public static final String LINUX_X64 = "linux_x64";
    public static final String LINUX_ARM32 = "linux_arm32";
    public static final String LINUX_ARM64 = "linux_arm64";
    public static final String ANDROID_ARM64 = "android_arm64-v8a";
    public static final String ANDROID_ARMV7 = "android_armeabi-v7a";
    public static final String ANDROID_X86 = "android_x86";
    public static final String ANDROID_X86_64 = "android_x86_64";

    private static final String[] BACKENDS = {BACKEND_VLC, BACKEND_MPV};
    private static final String DEFAULTS_RESOURCE = "/assets/videoplayer/native-downloads.json";
    private static final String CONFIG_FILE_NAME = "native-downloads.json";
    private static final Gson GSON = new Gson();
    private static final String[] PLATFORMS = {
            WINDOWS_X86,
            WINDOWS_X64,
            WINDOWS_ARM64,
            MACOS_X64,
            MACOS_ARM64,
            LINUX_X86,
            LINUX_X64,
            LINUX_ARM32,
            LINUX_ARM64,
            ANDROID_ARM64,
            ANDROID_ARMV7,
            ANDROID_X86,
            ANDROID_X86_64
    };
    private static volatile Map<String, Map<String, List<DownloadSource>>> defaults;

    public Map<String, Map<String, List<DownloadSource>>> urls = new LinkedHashMap<>();

    public NativeDownloadConfig() {
        ensureDefaults();
    }

    public static NativeDownloadConfig load() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.warn("Failed to create native download config directory {}", path.getParent(), e);
        }
        if (Files.isRegularFile(path)) {
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
                NativeDownloadConfig config = GSON.fromJson(reader, NativeDownloadConfig.class);
                if (config == null) config = new NativeDownloadConfig();
                config.ensureDefaults();
                return config;
            } catch (RuntimeException | IOException e) {
                VideoPlayerMain.LOGGER.warn("Failed to load native download config {}", path, e);
            }
        }
        return new NativeDownloadConfig();
    }

    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(this));
    }

    public static Path configPath() {
        return configDir().resolve("videoplayer").resolve(CONFIG_FILE_NAME);
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

    public boolean ensureDefaults() {
        boolean changed = false;
        if (urls == null) {
            urls = new LinkedHashMap<>();
            changed = true;
        }
        Map<String, Map<String, List<DownloadSource>>> defaultUrls = defaultUrls();
        for (String backend : BACKENDS) {
            Map<String, List<DownloadSource>> backendUrls = urls.get(backend);
            if (backendUrls == null) {
                backendUrls = new LinkedHashMap<>();
                urls.put(backend, backendUrls);
                changed = true;
            }
            for (String platform : PLATFORMS) {
                List<DownloadSource> sources = backendUrls.get(platform);
                if (sources == null) {
                    sources = new ArrayList<>();
                    backendUrls.put(platform, sources);
                    changed = true;
                }
                if (sources.isEmpty() && addDefaultSources(defaultUrls, backend, platform, sources)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    public List<DownloadSource> sources(String backend, String platform) {
        ensureDefaults();
        Map<String, List<DownloadSource>> backendUrls = urls.get(normalizeBackend(backend));
        if (backendUrls == null) return List.of();
        List<DownloadSource> sources = backendUrls.get(platform);
        if (sources == null) return List.of();
        return sources.stream()
                .filter(source -> source != null && source.url != null && !source.url.isBlank())
                .toList();
    }

    public List<String> platformsForCurrentOs() {
        return platformsForOs(osKey());
    }

    public static List<String> platformsForOs(String os) {
        String prefix = (os == null ? "" : os) + "_";
        return Arrays.stream(PLATFORMS)
                .filter(platform -> platform.startsWith(prefix))
                .toList();
    }

    public static boolean isKnownPlatform(String platform) {
        if (platform == null) return false;
        for (String known : PLATFORMS) {
            if (known.equals(platform)) return true;
        }
        return false;
    }

    public static String normalizePlatformForCurrentOs(String platform) {
        String currentOs = osKey();
        if (isKnownPlatform(platform) && currentOs.equals(osFromPlatform(platform))) {
            return platform;
        }
        return platformKey();
    }

    public static String normalizeKnownPlatform(String platform) {
        return isKnownPlatform(platform) ? platform : platformKey();
    }

    public static String osFromPlatform(String platform) {
        if (platform == null) return "";
        int separator = platform.indexOf('_');
        return separator < 0 ? platform : platform.substring(0, separator);
    }

    public static String archFromPlatform(String platform) {
        if (platform == null) return "";
        int separator = platform.indexOf('_');
        return separator < 0 || separator + 1 >= platform.length() ? "" : platform.substring(separator + 1);
    }

    public static String platformKey() {
        String os = osKey();
        return os + "_" + archKey(os);
    }

    public static String normalizeBackend(String backend) {
        if (backend == null) return BACKEND_VLC;
        String normalized = backend.trim().toLowerCase(Locale.ROOT);
        return BACKEND_MPV.equals(normalized) ? BACKEND_MPV : BACKEND_VLC;
    }

    public static String osKey() {
        if (Files.exists(Path.of("/system/build.prop"))) return "android";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("linux")) return "linux";
        return os.replaceAll("[^a-z0-9]+", "_");
    }

    public static String archKey() {
        return archKey(osKey());
    }

    private static String archKey(String os) {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if ("android".equals(os)) {
            if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64-v8a";
            if (arch.equals("arm") || arch.equals("armv7l") || arch.equals("armeabi-v7a")) return "armeabi-v7a";
            if (arch.equals("amd64") || arch.equals("x86_64")) return "x86_64";
            if (arch.equals("i386") || arch.equals("i686") || arch.equals("x86")) return "x86";
        }
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        if (arch.equals("arm") || arch.equals("armv7l")) return "arm32";
        if (arch.equals("i386") || arch.equals("i686") || arch.equals("x86")) return "x86";
        return arch.replaceAll("[^a-z0-9]+", "_");
    }

    private static boolean addDefaultSources(Map<String, Map<String, List<DownloadSource>>> defaultUrls, String backend, String platform,
                                             List<DownloadSource> target) {
        Map<String, List<DownloadSource>> backendUrls = defaultUrls.get(normalizeBackend(backend));
        if (backendUrls == null) return false;
        List<DownloadSource> sources = backendUrls.get(platform);
        if (sources == null || sources.isEmpty()) return false;
        for (DownloadSource source : sources) {
            if (source == null || source.url == null || source.url.isBlank()) continue;
            target.add(new DownloadSource(source.url, source.sha256));
        }
        return !target.isEmpty();
    }

    private static Map<String, Map<String, List<DownloadSource>>> defaultUrls() {
        Map<String, Map<String, List<DownloadSource>>> loaded = defaults;
        if (loaded != null) return loaded;
        synchronized (NativeDownloadConfig.class) {
            if (defaults == null) {
                defaults = Collections.unmodifiableMap(loadDefaultUrls());
            }
            return defaults;
        }
    }

    private static Map<String, Map<String, List<DownloadSource>>> loadDefaultUrls() {
        try (InputStream stream = NativeDownloadConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (stream == null) return Map.of();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                DefaultDownloadFile file = GSON.fromJson(reader, DefaultDownloadFile.class);
                if (file == null || file.urls == null) return Map.of();
                return file.urls;
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            return Map.of();
        }
    }

    private static class DefaultDownloadFile {
        Map<String, Map<String, List<DownloadSource>>> urls = new LinkedHashMap<>();
    }

    public static class DownloadSource {
        public String url = "";
        public String sha256 = "";

        public DownloadSource() {
        }

        public DownloadSource(String url, String sha256) {
            this.url = url == null ? "" : url;
            this.sha256 = sha256 == null ? "" : sha256;
        }
    }
}
