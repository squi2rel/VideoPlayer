package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.provider.IProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.video.VideoParams;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class BiliBiliVideoProvider extends BiliBiliProvider {
    public static final String FETCH_URL = "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    public static final String PLAY_URL = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=%s&fnver=0&fnval=%s&fourk=%s&platform=%s&high_quality=1";
    public static final Pattern REGEX = Pattern.compile("(?<=^|/)(BV[0-9A-Za-z]{10})/?(?:\\?[^#]*?p=(\\d+))?");
    // 1024 is not documented in the collected API notes, but the web endpoint needs it to expose qn=127 (8K).
    private static final int DASH_FNVAL = 16 | 128 | 1024;
    private static final int CODECID_HEVC = 12;
    private static final Cache<String, VideoCache> CACHE = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1024).build();
    private static final Cache<String, List<Integer>> AVAILABLE_QUALITIES = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).maximumSize(1024).build();

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String bvid = matcher.group(1);
        int page = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
        String rawPath = canonicalRawPath(bvid, page);
        int qualityLimit = BiliQuality.providerLimit(source.bilibiliQualityLimit());
        String cacheKey = rawPath + "|qn=" + qualityLimit + "|auth=" + hasClientCookie();
        VideoCache cache = CACHE.getIfPresent(cacheKey);
        if (cache != null && System.currentTimeMillis() < cache.expireTime) {
            return CompletableFuture.completedFuture(new VideoInfo(source.name(), cache.title, cache.url, rawPath, cache.expireTime, true, cache.params, cache.durationMs));
        }
        return CompletableFuture.supplyAsync(() -> {
            String title = rawPath;
            long durationMs = 0L;
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
                HttpResponse<InputStream> response = client.send(makeRequest(String.format(FETCH_URL, bvid)), HttpResponse.BodyHandlers.ofInputStream());
                JsonObject data = requireData(responseBody(response), "view");
                String cid;
                if (page <= 1) {
                    cid = data.get("cid").getAsString();
                } else {
                    JsonArray pages = data.getAsJsonArray("pages");
                    if (pages == null || page > pages.size()) {
                        throw new IllegalArgumentException("Bilibili page does not exist");
                    }
                    cid = pages.get(page - 1).getAsJsonObject().get("cid").getAsString();
                }
                title = data.get("title").getAsString();
                durationMs = Math.max(0L, intValue(data, "duration", 0)) * 1000L;
                VideoMeta meta = new VideoMeta(title, cid);
                ResolvedVideo resolved = resolvePlayUrl(client, bvid, rawPath, meta, qualityLimit);
                long expire = System.currentTimeMillis() + 1000 * 60 * 60 * 2;
                CACHE.put(cacheKey, new VideoCache(meta.title(), resolved.url, expire, resolved.params, durationMs));
                return new VideoInfo(source.name(), meta.title(), resolved.url, rawPath, expire, true, resolved.params, durationMs);
            } catch (Exception e) {
                LOGGER.warn("Failed to resolve Bilibili source {}; falling back to client-side resolution", VideoProviders.redactedSource(rawPath), e);
                return clientResolvableFallback(source, title, rawPath, durationMs);
            }
        });
    }

    private static VideoInfo clientResolvableFallback(IProviderSource source, String title, String rawPath, long durationMs) {
        String safeTitle = title == null || title.isBlank() ? rawPath : title;
        return new VideoInfo(source.name(), safeTitle, "", rawPath, -1, true, NO_PARAMS, durationMs);
    }

    public static List<Integer> availableQualities(String rawPath) {
        String key = canonicalRawPath(rawPath);
        if (key == null) return List.of();
        List<Integer> qualities = AVAILABLE_QUALITIES.getIfPresent(key);
        return qualities == null ? List.of() : qualities;
    }

    public static boolean isBiliVideoRawPath(String rawPath) {
        return canonicalRawPath(rawPath) != null;
    }

    private static ResolvedVideo resolvePlayUrl(HttpClient client, String bvid, String rawPath, VideoMeta meta, int qualityLimit) throws Exception {
        try {
            return resolveDash(client, bvid, rawPath, meta, qualityLimit);
        } catch (Exception dashError) {
            try {
                return resolveProgressive(client, bvid, rawPath, meta, qualityLimit);
            } catch (Exception progressiveError) {
                progressiveError.addSuppressed(dashError);
                throw progressiveError;
            }
        }
    }

    private static ResolvedVideo resolveProgressive(HttpClient client, String bvid, String rawPath, VideoMeta meta, int qualityLimit) throws Exception {
        String url = String.format(PLAY_URL, bvid, meta.cid(), qualityLimit, 0, 0, "html5");
        JsonObject data = playData(client, url);
        updateAvailableQualities(rawPath, data);
        JsonArray durl = data.getAsJsonArray("durl");
        if (durl == null || durl.isEmpty()) {
            throw new IllegalStateException("Bilibili progressive stream is unavailable");
        }
        JsonObject entry = durl.get(0).getAsJsonObject();
        String videoUrl = string(entry, "url");
        if (videoUrl.isBlank()) {
            throw new IllegalStateException("Bilibili progressive stream URL is missing");
        }
        return new ResolvedVideo(videoUrl, MPV_PARAMS);
    }

    private static ResolvedVideo resolveDash(HttpClient client, String bvid, String rawPath, VideoMeta meta, int qualityLimit) throws Exception {
        int requestQuality = BiliQuality.providerLimit(BiliQuality.UNLIMITED);
        String url = String.format(PLAY_URL, bvid, meta.cid(), requestQuality, DASH_FNVAL, 1, "pc");
        JsonObject data = playData(client, url);
        updateAvailableQualities(rawPath, data);
        JsonObject dash = object(data, "dash");
        JsonArray videos = dash == null ? null : dash.getAsJsonArray("video");
        if (videos == null || videos.isEmpty()) {
            throw new IllegalStateException("Bilibili DASH stream is unavailable");
        }

        ArrayList<DashMedia> videoOptions = new ArrayList<>();
        Set<Integer> qualities = new HashSet<>();
        for (JsonElement element : videos) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            int id = intValue(item, "id", -1);
            String videoUrl = mediaUrl(item);
            if (id < 0 || videoUrl.isBlank()) continue;
            qualities.add(id);
            videoOptions.add(new DashMedia(videoUrl, id, intValue(item, "codecid", 0), intValue(item, "bandwidth", 0)));
        }
        if (videoOptions.isEmpty()) {
            throw new IllegalStateException("Bilibili DASH video URL is missing");
        }
        int selectedQuality = BiliQuality.bestAtOrBelow(qualities, qualityLimit);
        DashMedia video = videoOptions.stream()
                .filter(option -> option.quality == selectedQuality)
                .max(BiliBiliVideoProvider::compareVideoOption)
                .orElseGet(() -> videoOptions.stream()
                        .min((a, b) -> Integer.compare(BiliQuality.rank(a.quality), BiliQuality.rank(b.quality)))
                        .orElse(videoOptions.get(0)));

        String audioUrl = "";
        JsonArray audios = dash.getAsJsonArray("audio");
        if (audios != null && !audios.isEmpty()) {
            DashMedia audio = null;
            for (JsonElement element : audios) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String candidate = mediaUrl(item);
                if (candidate.isBlank()) continue;
                DashMedia media = new DashMedia(candidate, 0, 0, intValue(item, "bandwidth", 0));
                if (audio == null || media.bandwidth > audio.bandwidth) audio = media;
            }
            if (audio != null) audioUrl = audio.url;
        }
        return new ResolvedVideo(video.url, dashParams(audioUrl, bvid));
    }

    private static JsonObject playData(HttpClient client, String url) throws Exception {
        HttpResponse<InputStream> response = client.send(makeRequest(url), HttpResponse.BodyHandlers.ofInputStream());
        return requireData(responseBody(response), "playurl");
    }

    private static JsonObject requireData(String body, String apiName) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : 0;
        if (code != 0) {
            String message = root.has("message") && !root.get("message").isJsonNull() ? root.get("message").getAsString() : "unknown";
            throw new IllegalStateException("Bilibili " + apiName + " API returned code " + code + ": " + message);
        }
        JsonObject data = object(root, "data");
        if (data == null) throw new IllegalStateException("Bilibili " + apiName + " API returned no data");
        return data;
    }

    private static void updateAvailableQualities(String rawPath, JsonObject data) {
        Set<Integer> qualities = new HashSet<>();
        JsonArray accept = data.getAsJsonArray("accept_quality");
        if (accept != null) {
            for (JsonElement element : accept) {
                if (element.isJsonPrimitive()) qualities.add(element.getAsInt());
            }
        }
        JsonArray supportFormats = data.getAsJsonArray("support_formats");
        if (supportFormats != null) {
            for (JsonElement element : supportFormats) {
                if (!element.isJsonObject()) continue;
                JsonObject format = element.getAsJsonObject();
                if (format.has("quality")) qualities.add(format.get("quality").getAsInt());
            }
        }
        if (!qualities.isEmpty()) {
            List<Integer> existing = AVAILABLE_QUALITIES.getIfPresent(rawPath);
            if (existing != null) qualities.addAll(existing);
            List<Integer> supported = BiliQuality.filterSupported(qualities);
            if (!supported.isEmpty()) AVAILABLE_QUALITIES.put(rawPath, supported);
        }
    }

    private static String[] dashParams(String audioUrl, String bvid) {
        String referer = "https://www.bilibili.com/video/" + bvid;
        if (audioUrl == null || audioUrl.isBlank()) {
            return new String[]{"user-agent=" + UA, "referrer=" + referer, VideoParams.PARAM_VIDEO_ONLY + "=true"};
        }
        return new String[]{
                "user-agent=" + UA,
                "referrer=" + referer,
                "audio-file=" + audioUrl,
                ":input-slave=" + audioUrl
        };
    }

    private static String canonicalRawPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        Matcher matcher = REGEX.matcher(rawPath);
        if (!matcher.find()) return null;
        int page = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
        return canonicalRawPath(matcher.group(1), page);
    }

    private static String canonicalRawPath(String bvid, int page) {
        return bvid + (page > 1 ? "?p=" + page : "");
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    private static String mediaUrl(JsonObject object) {
        String value = string(object, "baseUrl");
        if (value.isBlank()) value = string(object, "base_url");
        if (!value.isBlank()) return value;
        JsonArray backups = object.has("backupUrl") ? object.getAsJsonArray("backupUrl") : object.getAsJsonArray("backup_url");
        if (backups == null || backups.isEmpty()) return "";
        JsonElement first = backups.get(0);
        return first == null || first.isJsonNull() ? "" : first.getAsString();
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int intValue(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static int compareVideoOption(DashMedia a, DashMedia b) {
        int codec = Integer.compare(videoCodecRank(a), videoCodecRank(b));
        if (codec != 0) return codec;
        return Integer.compare(a.bandwidth, b.bandwidth);
    }

    private static int videoCodecRank(DashMedia media) {
        // Many hardware H.264 decoders do not handle Bilibili's 4K frame sizes; prefer HEVC for 4K+.
        return media.quality >= 120 && media.codecid == CODECID_HEVC ? 1 : 0;
    }

    private record VideoCache(String title, String url, long expireTime, String[] params, long durationMs) {}

    private record ResolvedVideo(String url, String[] params) {}

    private record DashMedia(String url, int quality, int codecid, int bandwidth) {}
}
