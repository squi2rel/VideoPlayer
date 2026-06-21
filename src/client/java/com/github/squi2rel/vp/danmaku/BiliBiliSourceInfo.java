package com.github.squi2rel.vp.danmaku;

public record BiliBiliSourceInfo(Type type, String bvid, long aid, long cid, int page, long roomId) {
    public enum Type {
        VOD,
        LIVE
    }

    public static BiliBiliSourceInfo vod(String bvid, long aid, long cid, int page) {
        return new BiliBiliSourceInfo(Type.VOD, bvid, aid, cid, Math.max(1, page), 0);
    }

    public static BiliBiliSourceInfo live(long roomId) {
        return new BiliBiliSourceInfo(Type.LIVE, "", 0, 0, 0, roomId);
    }

    public boolean vod() {
        return type == Type.VOD;
    }

    public boolean live() {
        return type == Type.LIVE;
    }

    public String stableKey() {
        return live() ? "live:" + roomId : "vod:" + bvid + ":" + aid + ":" + cid + ":" + page;
    }
}
