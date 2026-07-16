package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.MediaAddressPolicy;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VideoParams {
    public static final String PARAM_YOUTUBE = "videoplayer-youtube";
    public static final String PARAM_SUBTITLE = "videoplayer-subtitle";
    public static final String PARAM_AUDIO_ONLY = "videoplayer-audio-only";
    public static final String PARAM_VIDEO_ONLY = "videoplayer-video-only";

    private VideoParams() {
    }

    public static String mpvLoadOptions(String[] params) {
        return mpvLoadOptions(params, "");
    }

    public static String mpvLoadOptions(String[] params, String configuredProxy) {
        return mpvLoadOptions(params, configuredProxy, "");
    }

    public static String mpvLoadOptions(String[] params, String configuredProxy, String configuredYtdlPath) {
        return mpvLoadOptions(params, configuredProxy, configuredYtdlPath, "");
    }

    public static String mpvLoadOptions(String[] params, String configuredProxy, String configuredYtdlPath, String lavfiComplex) {
        return mpvLoadOptions(params, configuredProxy, configuredYtdlPath, lavfiComplex, false);
    }

    public static String mpvLoadOptionsForPath(String rawPath, String[] params, String configuredProxy, String configuredYtdlPath) {
        return mpvLoadOptions(params, configuredProxy, configuredYtdlPath, "", isRtspTcp(rawPath));
    }

    public static String mpvLoadOptionsForPath(String rawPath, String[] params, String configuredProxy, String configuredYtdlPath,
                                                String lavfiComplex) {
        return mpvLoadOptions(params, configuredProxy, configuredYtdlPath, lavfiComplex, isRtspTcp(rawPath));
    }

    private static String mpvLoadOptions(String[] params, String configuredProxy, String configuredYtdlPath, String lavfiComplex,
                                         boolean rtspTcp) {
        String[] safeParams = params == null ? new String[0] : params;

        ArrayList<String> options = new ArrayList<>(safeParams.length + 1);
        Set<String> optionKeys = new HashSet<>();
        boolean youtube = false;
        for (String raw : safeParams) {
            ParsedParam param = parse(raw);
            if (param == null || param.key.isEmpty()) continue;
            if (isInternal(param.key)) {
                if (PARAM_YOUTUBE.equals(param.key)) youtube = truthy(param.value);
                continue;
            }
            String mpvKey = mpvKey(param.key);
            if (mpvKey == null) {
                if (param.vlcStyle || param.value == null) continue;
                mpvKey = param.key;
            }
            if (rtspTcp && "rtsp-transport".equals(mpvKey)) {
                if (optionKeys.add(mpvKey)) options.add("rtsp-transport=" + mpvSubValue("tcp"));
                continue;
            }
            optionKeys.add(mpvKey);
            options.add(mpvKey + "=" + mpvSubValue(param.value));
        }
        if (rtspTcp && optionKeys.add("rtsp-transport")) {
            options.add("rtsp-transport=" + mpvSubValue("tcp"));
        }
        String proxy = normalizeHttpProxy(configuredProxy);
        String ytdlProxy = youtube ? proxyWithoutCredentials(proxy) : "";
        if (!proxy.isBlank()) {
            if (!optionKeys.contains("http-proxy")) {
                options.add("http-proxy=" + mpvSubValue(proxy));
            }
            if (!ytdlProxy.isBlank() && !optionKeys.contains("ytdl-raw-options")) {
                options.add("ytdl-raw-options=" + mpvSubValue("proxy=[" + ytdlProxy + "]"));
            }
        }
        String ytdlPath = youtube ? normalizeMpvOptionValue(configuredYtdlPath) : "";
        if (!ytdlPath.isBlank() && !optionKeys.contains("script-opt") && !optionKeys.contains("script-opts-append")) {
            options.add("script-opts-append=" + mpvSubValue("ytdl_hook-ytdl_path=" + ytdlPath));
        }
        String graph = normalizeMpvOptionValue(lavfiComplex);
        if (!graph.isBlank() && !optionKeys.contains("lavfi-complex")) {
            options.add("lavfi-complex=" + mpvSubValue(graph));
        }
        return String.join(",", options);
    }

    public static String[] youtubeParams() {
        return new String[]{
                PARAM_YOUTUBE + "=true",
                "ytdl=yes"
        };
    }

    public static String[] audioOnlyParams() {
        return new String[]{PARAM_AUDIO_ONLY + "=true"};
    }

    public static String[] videoOnlyParams() {
        return new String[]{PARAM_VIDEO_ONLY + "=true"};
    }

    public static boolean isYouTube(String[] params) {
        if (params == null || params.length == 0) return false;
        for (String raw : params) {
            ParsedParam param = parse(raw);
            if (param != null && PARAM_YOUTUBE.equals(param.key) && truthy(param.value)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAudioOnly(String[] params) {
        if (params == null || params.length == 0) return false;
        for (String raw : params) {
            ParsedParam param = parse(raw);
            if (param != null && PARAM_AUDIO_ONLY.equals(param.key) && truthy(param.value)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVideoOnly(String[] params) {
        if (params == null || params.length == 0) return false;
        for (String raw : params) {
            ParsedParam param = parse(raw);
            if (param != null && PARAM_VIDEO_ONLY.equals(param.key) && truthy(param.value)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasDisallowedMediaUrls(String[] params) {
        if (params == null || params.length == 0) return false;
        for (String raw : params) {
            ParsedParam param = parse(raw);
            if (param == null) continue;
            if (!"audio-file".equals(param.key) && !"input-slave".equals(param.key)) continue;
            if (param.value == null || !MediaAddressPolicy.isAllowed(param.value)) return true;
        }
        return false;
    }

    public static boolean looksAudioOnlyPath(String raw) {
        String path = uriPath(raw);
        if (path.isBlank()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".flac")
                || lower.endsWith(".wav")
                || lower.endsWith(".aac")
                || lower.endsWith(".m4a")
                || lower.endsWith(".ogg")
                || lower.endsWith(".opus")
                || lower.endsWith(".wma")
                || lower.endsWith(".ape");
    }

    public static String subtitleParam(String key, String label, String language, String url, String referer, boolean automatic) {
        return PARAM_SUBTITLE + "="
                + encodeField(key) + "\t"
                + encodeField(label) + "\t"
                + encodeField(language) + "\t"
                + encodeField(url) + "\t"
                + encodeField(referer) + "\t"
                + (automatic ? "1" : "0");
    }

    public static List<SubtitleParam> subtitleParams(String[] params) {
        if (params == null || params.length == 0) return List.of();
        ArrayList<SubtitleParam> result = new ArrayList<>();
        for (String raw : params) {
            ParsedParam param = parse(raw);
            if (param == null || !PARAM_SUBTITLE.equals(param.key) || param.value == null || param.value.isBlank()) continue;
            String[] parts = param.value.split("\t", -1);
            if (parts.length < 6) continue;
            String key = decodeField(parts[0]);
            String label = decodeField(parts[1]);
            String language = decodeField(parts[2]);
            String url = decodeField(parts[3]);
            String referer = decodeField(parts[4]);
            boolean automatic = "1".equals(parts[5]) || "true".equalsIgnoreCase(parts[5]);
            if (key.isBlank() || url.isBlank()) continue;
            result.add(new SubtitleParam(key, label, language, url, referer, automatic));
        }
        return List.copyOf(result);
    }

    public static String normalizeHttpProxy(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        String normalized = value.contains("://") ? value : "http://" + value;
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
            if (uri.getHost() == null || uri.getHost().isBlank()) return "";
            int port = uri.getPort();
            if (port > 65535) return "";
            return normalized;
        } catch (Exception e) {
            return "";
        }
    }

    static String proxyWithoutCredentials(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) return "";
            return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String normalizeMpvOptionValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        return value.replace("\r", "").replace("\n", "");
    }

    public static String[] vlcOptions(String[] params) {
        return vlcOptions(null, params);
    }

    public static String[] vlcOptions(String rawPath, String[] params) {
        return vlcOptions(rawPath, params, "");
    }

    public static String[] vlcOptions(String rawPath, String[] params, String configuredProxy) {
        String[] safeParams = params == null ? new String[0] : params;
        ArrayList<String> options = new ArrayList<>(safeParams.length + 1);
        boolean hasRtspTcp = false;
        boolean youtube = false;
        for (String raw : safeParams) {
            ParsedParam param = parse(raw);
            if (param == null || param.key.isEmpty()) continue;
            if (isInternal(param.key)) {
                if (PARAM_YOUTUBE.equals(param.key)) youtube = truthy(param.value);
                continue;
            }
            if ("rtsp-tcp".equals(param.key)) hasRtspTcp = true;
            if (param.vlcStyle) {
                options.add(raw.trim());
                continue;
            }
            switch (param.key) {
                case "user-agent", "http-user-agent" -> options.add(":http-user-agent=" + param.value);
                case "referrer", "referer", "http-referrer", "http-referer" -> options.add(":http-referrer=" + param.value);
                default -> {
                }
            }
        }
        if (isRtspTcp(rawPath) && !hasRtspTcp) options.add(":rtsp-tcp");
        String proxy = isHttpPath(rawPath) ? normalizeHttpProxy(configuredProxy) : "";
        if (!proxy.isBlank()) options.add(":http-proxy=" + proxy);
        return options.toArray(String[]::new);
    }

    public static boolean isRtspTcp(String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim();
        return value.regionMatches(true, 0, "rtsps://", 0, "rtsps://".length())
                || value.regionMatches(true, 0, "rtspt://", 0, "rtspt://".length());
    }

    public static String normalizeStreamPath(String rawPath) {
        String trimmed = rawPath == null ? "" : rawPath.trim();
        if (!trimmed.regionMatches(true, 0, "rtspt://", 0, "rtspt://".length())) return trimmed;
        return "rtsp://" + trimmed.substring("rtspt://".length());
    }

    private static boolean isHttpPath(String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim();
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
                || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    private static String mpvKey(String key) {
        return switch (key) {
            case "user-agent", "http-user-agent" -> "user-agent";
            case "referrer", "referer", "http-referrer", "http-referer" -> "referrer";
            default -> null;
        };
    }

    private static boolean isInternal(String key) {
        return key != null && key.startsWith("videoplayer-");
    }

    private static String uriPath(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        try {
            String path = URI.create(value).getPath();
            if (path != null && !path.isBlank()) return path;
        } catch (RuntimeException ignored) {
        }
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        return value;
    }

    private static ParsedParam parse(String raw) {
        if (raw == null) return null;
        String option = raw.trim();
        if (option.isEmpty()) return null;
        boolean vlcStyle = option.startsWith(":");
        while (option.startsWith(":")) option = option.substring(1);
        if (option.startsWith("--")) option = option.substring(2);

        int equals = option.indexOf('=');
        if (equals <= 0) {
            return new ParsedParam(normalizeKey(option), null, vlcStyle);
        }
        return new ParsedParam(
                normalizeKey(option.substring(0, equals)),
                option.substring(equals + 1),
                vlcStyle
        );
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static String mpvSubValue(String value) {
        String safeValue = value == null ? "" : value;
        return "%" + safeValue.getBytes(StandardCharsets.UTF_8).length + "%" + safeValue;
    }

    private static boolean truthy(String value) {
        return value == null || value.isBlank()
                || value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes");
    }

    private static String encodeField(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decodeField(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private record ParsedParam(String key, String value, boolean vlcStyle) {
    }

    public record SubtitleParam(String key, String label, String language, String url, String referer, boolean automatic) {
    }
}
