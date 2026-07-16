package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.IProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BiliBiliLiveProvider extends BiliBiliProvider {
    public static final String FETCH_URL = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=%s";
    public static final String PLAY_URL = "https://api.live.bilibili.com/room/v1/Room/playUrl?cid=%s&platform=web&qn=10000";
    public static final Pattern REGEX = Pattern.compile("(?<=https://live\\.bilibili\\.com/)\\d+");

    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        Matcher matcher = REGEX.matcher(str);
        if (!matcher.find()) return null;
        String cid = matcher.group();
        return CompletableFuture.supplyAsync(() -> {
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
                HttpResponse<InputStream> response = client.send(makeRequest(String.format(FETCH_URL, cid)), HttpResponse.BodyHandlers.ofInputStream());
                JsonObject root = JsonParser.parseString(responseBody(response)).getAsJsonObject().getAsJsonObject("data");
                if (root.get("live_status").getAsLong() != 1) {
                    source.reply(VpTranslation.of("message.videoplayer.bilibili_live_offline", "The live room is not streaming"));
                    return null;
                }
                return new VideoMeta(root.get("title").getAsString(), root.get("room_id").getAsString());
            } catch (Exception e) {
                source.reply(e.toString());
                return null;
            }
        }).thenApply(meta -> {
            if (meta == null) return null;
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
                HttpResponse<InputStream> response = client.send(makeRequest(String.format(PLAY_URL, meta.cid())), HttpResponse.BodyHandlers.ofInputStream());
                JsonObject root = JsonParser.parseString(responseBody(response)).getAsJsonObject().getAsJsonObject("data");
                String url = root.getAsJsonArray("durl").get(0).getAsJsonObject().get("url").getAsString();
                return new VideoInfo(source.name(), meta.title(), url, str, System.currentTimeMillis() + 10000, false, MPV_PARAMS);
            } catch (Exception e) {
                source.reply(e.toString());
                return null;
            }
        });
    }
}
