package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class CloudMusicMVProvider implements IVideoProvider {
    public static final Pattern REGEX = Pattern.compile("^https?://music\\.163\\.com/(?:#/)?mv.*?[?&]id=(\\d+)");
    private static final String DETAIL_URL = "https://music.163.com/api/mv/detail?id=%s&type=mp4";
    private static final String UA = "Mozilla/5.0";

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String id = matcher.group(1);
        LOGGER.info("CloudMusic MV id: {}", id);
        return CompletableFuture.supplyAsync(() -> fetchPlayableMv(str, id, source));
    }

    private VideoInfo fetchPlayableMv(String rawPath, String id, IProviderSource source) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = client.send(request(String.format(DETAIL_URL, id)), HttpResponse.BodyHandlers.ofInputStream());
            JsonObject root = JsonParser.parseString(HttpResponseBody.read(response)).getAsJsonObject();
            if (root.get("code").getAsLong() != 200) {
                source.reply(VpTranslation.of("message.videoplayer.cloud_music_status_error", "Cloud Music returned status code %s", root.get("code")));
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            String url = bestUrl(data == null ? null : data.getAsJsonObject("brs"));
            if (url.isBlank()) {
                source.reply(VpTranslation.of("message.videoplayer.cloud_music_mv_unplayable", "This MV cannot be played"));
                return null;
            }
            String name = string(data, "name", "CloudMusic MV " + id);
            long durationMs = longValue(data, "duration", 0L);
            long expire = System.currentTimeMillis() + Duration.ofHours(2).toMillis();
            return new VideoInfo(source.name(), name, url, rawPath, expire, true, NO_PARAMS, durationMs);
        } catch (Exception e) {
            source.reply(e.toString());
            return null;
        }
    }

    private static String bestUrl(JsonObject brs) {
        if (brs == null || brs.isEmpty()) return "";
        int bestQuality = -1;
        String bestUrl = "";
        for (Map.Entry<String, JsonElement> entry : brs.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isJsonNull()) continue;
            int quality;
            try {
                quality = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            String url = entry.getValue().getAsString();
            if (url == null || url.isBlank()) continue;
            if (quality > bestQuality) {
                bestQuality = quality;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    private static String string(JsonObject object, String name, String fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .build();
    }
}
