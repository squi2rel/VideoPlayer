package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.MediaAddressPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class BiliCcSubtitleFetcher {
    private static final String WBI_PLAYER_URL = "https://api.bilibili.com/x/player/wbi/v2";
    private static final String PLAYER_URL = "https://api.bilibili.com/x/player/v2";

    private BiliCcSubtitleFetcher() {
    }

    static CompletableFuture<SubtitleCatalog> fetchCatalog(BiliBiliSourceInfo info) {
        return CompletableFuture.supplyAsync(() -> fetchCatalogBlocking(info));
    }

    static CompletableFuture<SubtitleTrack> fetchTrack(SubtitleOption option) {
        return CompletableFuture.supplyAsync(() -> fetchTrackBlocking(option));
    }

    private static SubtitleCatalog fetchCatalogBlocking(BiliBiliSourceInfo info) {
        if (info == null || !info.vod()) return SubtitleCatalog.empty();
        String referer = "https://www.bilibili.com/video/" + info.bvid();
        try {
            JsonObject data = playerData(info, referer);
            return new SubtitleCatalog(selectSubtitles(data, referer));
        } catch (Exception ignored) {
            return SubtitleCatalog.empty();
        }
    }

    private static SubtitleTrack fetchTrackBlocking(SubtitleOption option) {
        if (option == null || option.url().isBlank()) return SubtitleTrack.empty();
        try {
            URI uri = normalizeSubtitleUri(option.url());
            if (!MediaAddressPolicy.isAllowed(uri.toString())) return SubtitleTrack.empty();
            String body = BiliHttp.getString(uri, option.referer());
            return new SubtitleTrack(option.key(), option.label(), parseCues(body));
        } catch (Exception ignored) {
            return SubtitleTrack.empty();
        }
    }

    private static JsonObject playerData(BiliBiliSourceInfo info, String referer) throws Exception {
        Map<String, String> params = playerParams(info);
        try {
            return requireData(BiliHttp.getString(BiliWbiSigner.signedUri(WBI_PLAYER_URL, params, referer), referer));
        } catch (Exception ignored) {
            return requireData(BiliHttp.getString(URI.create(PLAYER_URL + "?" + query(params)), referer));
        }
    }

    private static Map<String, String> playerParams(BiliBiliSourceInfo info) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("bvid", info.bvid());
        params.put("aid", Long.toString(info.aid()));
        params.put("cid", Long.toString(info.cid()));
        return params;
    }

    private static JsonObject requireData(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int code = root.has("code") && !root.get("code").isJsonNull() ? root.get("code").getAsInt() : 0;
        if (code != 0) throw new IllegalStateException("Bilibili player API returned code " + code);
        JsonElement data = root.get("data");
        if (data == null || data.isJsonNull() || !data.isJsonObject()) {
            throw new IllegalStateException("Bilibili player API returned no data");
        }
        return data.getAsJsonObject();
    }

    private static List<SubtitleOption> selectSubtitles(JsonObject data, String referer) {
        JsonObject subtitle = object(data, "subtitle");
        JsonArray subtitles = subtitle == null ? null : subtitle.getAsJsonArray("subtitles");
        if (subtitles == null || subtitles.isEmpty()) return List.of();

        ArrayList<SubtitleOption> options = new ArrayList<>();
        int index = 0;
        for (JsonElement element : subtitles) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String url = string(item, "subtitle_url");
            if (url.isBlank()) continue;
            String language = string(item, "lan");
            String label = string(item, "lan_doc");
            if (label.isBlank()) label = language;
            String key = subtitleKey(item, language, index);
            options.add(new SubtitleOption(key, label, language, url, referer, score(language, label, index++)));
        }
        options.sort(Comparator.comparingInt(SubtitleOption::score).reversed());
        return List.copyOf(options);
    }

    private static String subtitleKey(JsonObject item, String language, int index) {
        String id = string(item, "id_str");
        if (id.isBlank()) id = string(item, "id");
        if (!id.isBlank()) return id;
        String lan = language == null || language.isBlank() ? "unknown" : language;
        return lan + ":" + index;
    }

    private static int score(String language, String label, int index) {
        String lan = language == null ? "" : language.toLowerCase();
        String doc = label == null ? "" : label;
        int score = Math.max(0, 100 - index);
        if (lan.equals("zh-hans") || lan.equals("zh-cn")) score += 1000;
        else if (lan.startsWith("zh")) score += 900;
        else if (doc.contains("中文") || doc.contains("汉语") || doc.contains("漢語")) score += 800;
        else if (lan.equals("en-us") || lan.startsWith("en")) score += 200;
        return score;
    }

    private static URI normalizeSubtitleUri(String url) {
        String value = url == null ? "" : url.trim();
        if (value.startsWith("//")) return URI.create("https:" + value);
        if (value.startsWith("/")) return URI.create("https://www.bilibili.com" + value);
        return URI.create(value);
    }

    private static List<SubtitleCue> parseCues(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray entries = root.getAsJsonArray("body");
        if (entries == null || entries.isEmpty()) return List.of();

        ArrayList<SubtitleCue> cues = new ArrayList<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            long from = Math.round(doubleValue(item, "from", -1.0) * 1000.0);
            long to = Math.round(doubleValue(item, "to", -1.0) * 1000.0);
            String content = string(item, "content").replace("\r", "").trim();
            if (from < 0 || to <= from || content.isBlank()) continue;
            cues.add(new SubtitleCue(from, to, content));
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::fromMs));
        return List.copyOf(cues);
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static double doubleValue(JsonObject object, String name, double fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
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

    record SubtitleCue(long fromMs, long toMs, String content) {
        boolean active(long progressMs) {
            return progressMs >= fromMs && progressMs < toMs;
        }
    }

    record SubtitleCatalog(List<SubtitleOption> options) {
        static SubtitleCatalog empty() {
            return new SubtitleCatalog(List.of());
        }
    }

    record SubtitleTrack(String key, String label, List<SubtitleCue> cues) {
        static SubtitleTrack empty() {
            return new SubtitleTrack("", "", List.of());
        }
    }

    record SubtitleOption(String key, String label, String language, String url, String referer, int score) {
    }
}
