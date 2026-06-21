package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.video.VideoParams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class CloudMusicProvider implements IVideoProvider {
    public static final Pattern REGEX = Pattern.compile("^https?://music\\.163\\.com/(?:#/)?song.*?[?&]id=(\\d+)");
    private static final String DETAIL_URL = "https://music.163.com/api/song/detail?ids=%%5B%s%%5D";
    private static final String PLAY_URL = "https://music.163.com/api/song/enhance/player/url?id=%s&ids=%%5B%s%%5D&br=320000";
    private static final String UA = "Mozilla/5.0";

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String id = matcher.group(1);
        LOGGER.info("CloudMusic Song id: {}", id);
        return CompletableFuture.supplyAsync(() -> fetchMeta(id, source))
                .thenApply(meta -> {
                    if (meta == null) return null;
                    return fetchPlayableUrl(str, id, meta, source);
                });
    }

    private SongMeta fetchMeta(String id, IProviderSource source) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request(String.format(DETAIL_URL, id)), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.get("code").getAsLong() != 200) {
                source.reply(VpTranslation.of("message.videoplayer.cloud_music_status_error", "Cloud Music returned status code %s", root.get("code")));
                return null;
            }
            JsonObject song = root.getAsJsonArray("songs").get(0).getAsJsonObject();
            return new SongMeta(string(song, "name", "CloudMusic Song " + id), longValue(song, "duration", 0L));
        } catch (Exception e) {
            source.reply(e.toString());
            return null;
        }
    }

    private VideoInfo fetchPlayableUrl(String rawPath, String id, SongMeta meta, IProviderSource source) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request(String.format(PLAY_URL, id, id)), HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.get("code").getAsLong() != 200) {
                source.reply(VpTranslation.of("message.videoplayer.cloud_music_status_error", "Cloud Music returned status code %s", root.get("code")));
                return null;
            }
            JsonObject data = root.getAsJsonArray("data").get(0).getAsJsonObject();
            if (data.get("url") == null || data.get("url").isJsonNull()) {
                source.reply(VpTranslation.of("message.videoplayer.cloud_music_song_unplayable", "This song is VIP-only or cannot be played"));
                return null;
            }
            long expireSeconds = longValue(data, "expi", 1200L);
            long durationMs = meta.durationMs() > 0 ? meta.durationMs() : longValue(data, "time", 0L);
            long expire = System.currentTimeMillis() + Math.max(1L, expireSeconds) * 1000L;
            return new VideoInfo(source.name(), meta.name(), data.get("url").getAsString(), rawPath, expire, true, VideoParams.audioOnlyParams(), durationMs);
        } catch (Exception e) {
            source.reply(e.toString());
            return null;
        }
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

    private record SongMeta(String name, long durationMs) {
    }
}
