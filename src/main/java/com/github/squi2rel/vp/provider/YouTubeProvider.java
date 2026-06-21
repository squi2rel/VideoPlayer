package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.video.VideoParams;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class YouTubeProvider implements IVideoProvider {
    private static final String[] YT_DLP_CANDIDATES = {"yt-dlp", "yt-dlp.exe"};
    private static final long YT_DLP_TIMEOUT_SECONDS = 30;
    private static final int MAX_SUBTITLE_OPTIONS = 24;
    private static final int MAX_SUBTITLE_PARAM_BYTES = 7800;
    private static volatile String configuredProxy = "";
    private static volatile String configuredYtdlPath = "";

    public static void configureProxy(String proxy) {
        configuredProxy = proxy == null ? "" : proxy.trim();
    }

    public static void configureYtdlPath(String ytdlPath) {
        configuredYtdlPath = ytdlPath == null ? "" : ytdlPath.trim();
    }

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        String url = normalizedUrl(str);
        if (url == null) return null;
        return CompletableFuture.supplyAsync(() -> resolve(url, source));
    }

    private VideoInfo resolve(String url, IProviderSource source) {
        YtDlpResult result;
        try {
            result = runYtDlp(url);
        } catch (YtDlpMissingException e) {
            source.reply(VpTranslation.of("message.videoplayer.youtube_ytdlp_missing", "yt-dlp is not installed or not on PATH"));
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to run yt-dlp for YouTube metadata", e);
            source.reply(VpTranslation.of("message.videoplayer.youtube_metadata_failed", "Failed to resolve YouTube metadata"));
            return null;
        }

        if (result.exitCode != 0) {
            LOGGER.warn("yt-dlp failed while resolving YouTube metadata; exit={}", result.exitCode);
            source.reply(VpTranslation.of("message.videoplayer.youtube_metadata_failed", "Failed to resolve YouTube metadata"));
            return null;
        }

        JsonObject metadata;
        try {
            metadata = JsonParser.parseString(result.stdout).getAsJsonObject();
        } catch (RuntimeException e) {
            LOGGER.warn("yt-dlp returned invalid YouTube metadata JSON", e);
            source.reply(VpTranslation.of("message.videoplayer.youtube_metadata_failed", "Failed to resolve YouTube metadata"));
            return null;
        }

        long durationMs = durationMs(metadata);
        if (durationMs <= 0) {
            source.reply(VpTranslation.of("message.videoplayer.youtube_duration_missing", "Failed to get YouTube duration"));
            return null;
        }

        String id = string(metadata, "id", idFromUrl(url));
        String title = string(metadata, "title", id == null ? "YouTube Video" : "YouTube " + id);
        return new VideoInfo(source.name(), title, url, url, -1, true, params(metadata, url), durationMs);
    }

    private static String[] params(JsonObject metadata, String referer) {
        ArrayList<String> params = new ArrayList<>(List.of(VideoParams.youtubeParams()));
        for (SubtitleCandidate candidate : subtitleCandidates(metadata, referer)) {
            String param = VideoParams.subtitleParam(candidate.key(), candidate.label(), candidate.language(), candidate.url(), referer, candidate.automatic());
            if (param.getBytes(StandardCharsets.UTF_8).length <= MAX_SUBTITLE_PARAM_BYTES) {
                params.add(param);
            }
        }
        return params.toArray(String[]::new);
    }

    private static List<SubtitleCandidate> subtitleCandidates(JsonObject metadata, String referer) {
        if (metadata == null) return List.of();
        ArrayList<SubtitleCandidate> result = new ArrayList<>();
        addSubtitleCandidates(result, object(metadata, "subtitles"), referer, false);
        addSubtitleCandidates(result, object(metadata, "automatic_captions"), referer, true);
        result.sort(Comparator.comparingInt(SubtitleCandidate::score).reversed());
        if (result.size() > MAX_SUBTITLE_OPTIONS) {
            return List.copyOf(result.subList(0, MAX_SUBTITLE_OPTIONS));
        }
        return List.copyOf(result);
    }

    private static void addSubtitleCandidates(List<SubtitleCandidate> result, JsonObject subtitles, String referer, boolean automatic) {
        if (subtitles == null || subtitles.isEmpty()) return;
        int index = result.size();
        for (String language : subtitles.keySet()) {
            JsonElement element = subtitles.get(language);
            if (element == null || !element.isJsonArray()) continue;
            JsonObject format = bestSubtitleFormat(element.getAsJsonArray());
            if (format == null) continue;
            String url = string(format, "url", "");
            if (url.isBlank()) continue;
            String name = string(format, "name", "");
            String label = subtitleLabel(language, name, automatic);
            String key = "yt:" + (automatic ? "auto:" : "sub:") + (language == null || language.isBlank() ? "unknown" : language) + ":" + index;
            result.add(new SubtitleCandidate(key, label, language, url, automatic, subtitleScore(language, label, automatic, index++)));
        }
    }

    private static JsonObject bestSubtitleFormat(JsonArray formats) {
        if (formats == null || formats.isEmpty()) return null;
        JsonObject fallback = null;
        for (JsonElement element : formats) {
            if (!element.isJsonObject()) continue;
            JsonObject format = element.getAsJsonObject();
            String url = string(format, "url", "");
            if (url.isBlank()) continue;
            if (fallback == null) fallback = format;
            String ext = string(format, "ext", "").toLowerCase(Locale.ROOT);
            if (ext.equals("vtt") || url.contains("fmt=vtt")) return format;
        }
        return null;
    }

    private static String subtitleLabel(String language, String name, boolean automatic) {
        String label = name == null || name.isBlank() ? language : name;
        if (label == null || label.isBlank()) label = "CC";
        return automatic ? label + " (auto)" : label;
    }

    private static int subtitleScore(String language, String label, boolean automatic, int index) {
        String lan = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String text = label == null ? "" : label;
        int score = Math.max(0, 100 - index);
        if (!automatic) score += 10_000;
        if (lan.equals("zh-hans") || lan.equals("zh-cn")) score += 1000;
        else if (lan.startsWith("zh")) score += 900;
        else if (text.contains("中文") || text.contains("汉语") || text.contains("漢語")) score += 800;
        else if (lan.equals("en-us") || lan.startsWith("en")) score += 200;
        return score;
    }

    private static YtDlpResult runYtDlp(String url) throws Exception {
        YtDlpStartException missing = null;
        String configured = VideoParams.normalizeMpvOptionValue(configuredYtdlPath);
        if (!configured.isBlank()) {
            try {
                return executeYtDlp(configured, url);
            } catch (YtDlpStartException e) {
                missing = e;
            }
        }
        for (String executable : YT_DLP_CANDIDATES) {
            try {
                return executeYtDlp(executable, url);
            } catch (YtDlpStartException e) {
                missing = e;
            }
        }
        throw new YtDlpMissingException(missing);
    }

    private static YtDlpResult executeYtDlp(String executable, String url) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--dump-single-json");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--skip-download");
        String proxy = VideoParams.normalizeHttpProxy(configuredProxy);
        if (!proxy.isBlank()) {
            command.add("--proxy");
            command.add(proxy);
        }
        command.add(url);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new YtDlpStartException(e);
        }
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        boolean completed = process.waitFor(YT_DLP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException("yt-dlp timed out");
        }
        return new YtDlpResult(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
    }

    private static CompletableFuture<String> readAsync(InputStream input) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (InputStream in = input) {
                result.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        }, "VideoPlayer-yt-dlp-reader");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static String normalizedUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isBlank()) return null;
        String withScheme = hasScheme(value) ? value : "https://" + value;
        URI uri;
        try {
            uri = URI.create(withScheme);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = uri.getHost();
        if (!isYouTubeHost(host)) return null;
        if (!hasYouTubeVideoId(uri)) return null;
        return withScheme;
    }

    private static boolean hasScheme(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0) return false;
        for (int i = 0; i < separator; i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    private static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("youtu.be")
                || normalized.equals("youtube.com")
                || normalized.endsWith(".youtube.com");
    }

    private static boolean hasYouTubeVideoId(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.equals("youtu.be")) {
            return firstPathSegment(path) != null;
        }
        if (path.equals("/watch")) {
            return queryValue(uri.getRawQuery(), "v") != null;
        }
        return path.startsWith("/shorts/")
                || path.startsWith("/live/")
                || path.startsWith("/embed/")
                || path.startsWith("/v/");
    }

    private static String idFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("youtu.be")) return firstPathSegment(path);
            if (path.equals("/watch")) return queryValue(uri.getRawQuery(), "v");
            for (String prefix : List.of("/shorts/", "/live/", "/embed/", "/v/")) {
                if (path.startsWith(prefix)) return firstPathSegment(path.substring(prefix.length() - 1));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static String firstPathSegment(String path) {
        if (path == null) return null;
        String trimmed = path;
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.isBlank()) return null;
        int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            if (name.equals(key)) {
                String value = equals < 0 ? "" : part.substring(equals + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static long durationMs(JsonObject metadata) {
        if (metadata == null) return 0L;
        JsonElement duration = metadata.get("duration");
        if (duration == null || duration.isJsonNull() || !duration.isJsonPrimitive()) return 0L;
        try {
            double seconds = duration.getAsDouble();
            return seconds > 0 ? Math.round(seconds * Duration.ofSeconds(1).toMillis()) : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) return fallback;
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        String value = element.getAsString();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    private record YtDlpResult(int exitCode, String stdout, String stderr) {
    }

    private record SubtitleCandidate(String key, String label, String language, String url, boolean automatic, int score) {
    }

    private static final class YtDlpMissingException extends Exception {
        YtDlpMissingException(Throwable cause) {
            super(cause);
        }
    }

    private static final class YtDlpStartException extends IOException {
        YtDlpStartException(Throwable cause) {
            super(cause);
        }
    }
}
