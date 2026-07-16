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
    private static volatile Map<String, ToolDownload> defaultTools;

    public Map<String, Map<String, List<DownloadSource>>> urls = new LinkedHashMap<>();
    public Map<String, ToolDownload> tools = new LinkedHashMap<>();

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
        for (Map.Entry<String, Map<String, List<DownloadSource>>> entry : urls.entrySet()) {
            Map<String, List<DownloadSource>> platforms = entry.getValue();
            if (platforms != null && platforms.keySet().removeIf(platform ->
                    platform != null && platform.startsWith("android_")
                            && !isSupportedBackendPlatform(entry.getKey(), platform))) {
                changed = true;
            }
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
                if (!isSupportedBackendPlatform(backend, platform)) continue;
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
        if (tools == null) {
            tools = new LinkedHashMap<>();
            changed = true;
        }
        for (Map.Entry<String, ToolDownload> entry : defaultTools().entrySet()) {
            ToolDownload configured = tools.get(entry.getKey());
            if (configured == null) {
                tools.put(entry.getKey(), entry.getValue().copy());
                changed = true;
                continue;
            }
            if (configured.version == null || configured.version.isBlank()) {
                configured.version = entry.getValue().version;
                changed = true;
            }
            if (configured.platforms == null) {
                configured.platforms = new LinkedHashMap<>();
                changed = true;
            }
            for (Map.Entry<String, List<DownloadSource>> platform : entry.getValue().platforms.entrySet()) {
                List<DownloadSource> sources = configured.platforms.get(platform.getKey());
                if (sources == null || sources.isEmpty()) {
                    configured.platforms.put(platform.getKey(), copySources(platform.getValue()));
                    changed = true;
                }
            }
        }
        return changed;
    }

    public List<DownloadSource> sources(String backend, String platform) {
        ensureDefaults();
        String normalizedBackend = normalizeBackend(backend);
        if (!isSupportedBackendPlatform(normalizedBackend, platform)) return List.of();
        Map<String, List<DownloadSource>> backendUrls = urls.get(normalizedBackend);
        if (backendUrls == null) return List.of();
        List<DownloadSource> sources = backendUrls.get(platform);
        if (sources == null) return List.of();
        return sources.stream()
                .filter(source -> source != null && source.url != null && !source.url.isBlank())
                .toList();
    }

    public ToolDownload tool(String name) {
        ensureDefaults();
        ToolDownload tool = tools.get(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
        return tool == null ? new ToolDownload() : tool;
    }

    public List<String> platformsForCurrentOs() {
        return platformsForOs(osKey());
    }

    public static List<String> platformsForOs(String os) {
        if ("android".equals(os)) return List.of(ANDROID_ARM64);
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

    public static boolean isSupportedBackendPlatform(String backend, String platform) {
        if (!isKnownPlatform(platform)) return false;
        if (!"android".equals(osFromPlatform(platform))) return true;
        return BACKEND_VLC.equals(normalizeBackend(backend)) && ANDROID_ARM64.equals(platform);
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
            target.add(new DownloadSource(source.name, source.url, source.sha256));
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

    private static Map<String, ToolDownload> defaultTools() {
        Map<String, ToolDownload> loaded = defaultTools;
        if (loaded != null) return loaded;
        synchronized (NativeDownloadConfig.class) {
            if (defaultTools == null) {
                defaultTools = Collections.unmodifiableMap(loadDefaultFile().tools);
            }
            return defaultTools;
        }
    }

    private static DefaultDownloadFile loadDefaultFile() {
        try (InputStream stream = NativeDownloadConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (stream == null) return new DefaultDownloadFile();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                DefaultDownloadFile file = GSON.fromJson(reader, DefaultDownloadFile.class);
                return file == null ? new DefaultDownloadFile() : file;
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            return new DefaultDownloadFile();
        }
    }

    private static List<DownloadSource> copySources(List<DownloadSource> sources) {
        if (sources == null || sources.isEmpty()) return new ArrayList<>();
        ArrayList<DownloadSource> copy = new ArrayList<>();
        for (DownloadSource source : sources) {
            if (source != null) copy.add(new DownloadSource(source.name, source.url, source.sha256));
        }
        return copy;
    }

    private static class DefaultDownloadFile {
        Map<String, Map<String, List<DownloadSource>>> urls = new LinkedHashMap<>();
        Map<String, ToolDownload> tools = new LinkedHashMap<>();
    }

    public static class ToolDownload {
        public String version = "";
        public Map<String, List<DownloadSource>> platforms = new LinkedHashMap<>();

        public List<DownloadSource> sources(String platform) {
            if (platforms == null || platform == null) return List.of();
            List<DownloadSource> sources = platforms.get(platform);
            if (sources == null) return List.of();
            return sources.stream()
                    .filter(source -> source != null && source.url != null && !source.url.isBlank())
                    .toList();
        }

        private ToolDownload copy() {
            ToolDownload copy = new ToolDownload();
            copy.version = version == null ? "" : version;
            if (platforms != null) {
                for (Map.Entry<String, List<DownloadSource>> entry : platforms.entrySet()) {
                    copy.platforms.put(entry.getKey(), copySources(entry.getValue()));
                }
            }
            return copy;
        }
    }

    public static class DownloadSource {
        public String name = "";
        public String url = "";
        public String sha256 = "";

        public DownloadSource() {
        }

        public DownloadSource(String url, String sha256) {
            this("", url, sha256);
        }

        public DownloadSource(String name, String url, String sha256) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.sha256 = sha256 == null ? "" : sha256;
        }
    }
}
