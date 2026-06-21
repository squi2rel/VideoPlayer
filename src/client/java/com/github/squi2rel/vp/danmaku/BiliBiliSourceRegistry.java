package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliLiveProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BiliBiliSourceRegistry {
    private static final Pattern DIRECT_ROOM = Pattern.compile("^(?:https?://live\\.bilibili\\.com/)?(\\d+)(?:[/?#].*)?$");
    private static final Map<String, CompletableFuture<BiliBiliSourceInfo>> CACHE = new ConcurrentHashMap<>();

    private BiliBiliSourceRegistry() {
    }

    static boolean canResolve(VideoInfo info) {
        String raw = rawPath(info);
        if (raw.isBlank()) return false;
        return BiliBiliVideoProvider.REGEX.matcher(raw).find()
                || BiliBiliLiveProvider.REGEX.matcher(raw).find()
                || DIRECT_ROOM.matcher(raw).matches();
    }

    static CompletableFuture<BiliBiliSourceInfo> resolve(VideoInfo info) {
        String raw = rawPath(info);
        if (raw.isBlank()) return CompletableFuture.completedFuture(null);
        return CACHE.computeIfAbsent(raw, key -> CompletableFuture.supplyAsync(() -> resolveBlocking(raw)));
    }

    private static BiliBiliSourceInfo resolveBlocking(String raw) {
        Matcher video = BiliBiliVideoProvider.REGEX.matcher(raw);
        if (video.find()) return resolveVod(video);

        Matcher live = BiliBiliLiveProvider.REGEX.matcher(raw);
        if (live.find()) return resolveLive(live.group());

        Matcher directRoom = DIRECT_ROOM.matcher(raw);
        if (directRoom.matches()) return resolveLive(directRoom.group(1));
        return null;
    }

    private static BiliBiliSourceInfo resolveVod(Matcher matcher) {
        String bvid = matcher.group(1);
        int page = matcher.group(2) == null ? 1 : Math.max(1, Integer.parseInt(matcher.group(2)));
        try {
            String body = BiliHttp.getString(String.format(BiliBiliVideoProvider.FETCH_URL, bvid), "https://www.bilibili.com/video/" + bvid);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
            long aid = data.get("aid").getAsLong();
            long cid = data.get("cid").getAsLong();
            JsonArray pages = data.getAsJsonArray("pages");
            if (pages != null && page <= pages.size()) {
                cid = pages.get(page - 1).getAsJsonObject().get("cid").getAsLong();
            }
            return BiliBiliSourceInfo.vod(bvid, aid, cid, page);
        } catch (Exception e) {
            return null;
        }
    }

    private static BiliBiliSourceInfo resolveLive(String room) {
        try {
            String body = BiliHttp.getString(String.format(BiliBiliLiveProvider.FETCH_URL, room), "https://live.bilibili.com/" + room);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
            return BiliBiliSourceInfo.live(data.get("room_id").getAsLong());
        } catch (Exception e) {
            try {
                return BiliBiliSourceInfo.live(Long.parseLong(room));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static String rawPath(VideoInfo info) {
        if (info == null || info.rawPath() == null) return "";
        return info.rawPath().trim();
    }
}
