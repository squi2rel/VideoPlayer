package com.github.squi2rel.vp.danmaku;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class BiliVodDanmakuFetcher {
    private static final String WBI_SEG_URL = "https://api.bilibili.com/x/v2/dm/wbi/web/seg.so";
    private static final String SEG_URL = "https://api.bilibili.com/x/v2/dm/web/seg.so";
    private static final String XML_URL = "https://api.bilibili.com/x/v1/dm/list.so";

    private BiliVodDanmakuFetcher() {
    }

    static CompletableFuture<List<DanmakuEntry>> fetchSegment(BiliBiliSourceInfo info, int segmentIndex) {
        int segment = Math.max(1, segmentIndex);
        return CompletableFuture.supplyAsync(() -> fetchBlocking(info, segment));
    }

    private static List<DanmakuEntry> fetchBlocking(BiliBiliSourceInfo info, int segment) {
        String referer = "https://www.bilibili.com/video/" + info.bvid();
        Map<String, String> params = segmentParams(info, segment);
        try {
            URI uri = BiliWbiSigner.signedUri(WBI_SEG_URL, params, referer);
            List<DanmakuEntry> entries = BiliDmSegParser.parseProtobuf(BiliHttp.getBytes(uri, referer));
            if (!entries.isEmpty()) return entries;
        } catch (Exception ignored) {
        }

        try {
            URI uri = URI.create(SEG_URL + "?" + query(params));
            List<DanmakuEntry> entries = BiliDmSegParser.parseProtobuf(BiliHttp.getBytes(uri, referer));
            if (!entries.isEmpty()) return entries;
        } catch (Exception ignored) {
        }

        try {
            URI uri = URI.create(XML_URL + "?oid=" + info.cid());
            return BiliDmSegParser.parseXml(BiliHttp.getBytes(uri, referer));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Map<String, String> segmentParams(BiliBiliSourceInfo info, int segment) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("type", "1");
        params.put("oid", Long.toString(info.cid()));
        params.put("pid", Long.toString(info.aid()));
        params.put("segment_index", Integer.toString(segment));
        return params;
    }

    private static String query(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
