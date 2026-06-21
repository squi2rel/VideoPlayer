package com.github.squi2rel.vp.danmaku;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BiliWbiSigner {
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32,
            15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19,
            29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63,
            57, 62, 11, 36, 20, 34, 44, 52
    };
    private static final ZoneId BILI_ZONE = ZoneId.of("Asia/Shanghai");
    private static volatile WbiKey cachedKey;

    private BiliWbiSigner() {
    }

    static void invalidate() {
        cachedKey = null;
    }

    static URI signedUri(String baseUrl, Map<String, String> params, String referer) throws Exception {
        LinkedHashMap<String, String> signed = sign(params, referer);
        return URI.create(baseUrl + "?" + query(signed));
    }

    private static LinkedHashMap<String, String> sign(Map<String, String> params, String referer) throws Exception {
        WbiKey key = key(referer);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (params != null) values.putAll(params);
        values.put("wts", Long.toString(System.currentTimeMillis() / 1000));

        List<Map.Entry<String, String>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder payload = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted) {
            if (payload.length() > 0) payload.append('&');
            payload.append(encode(entry.getKey())).append('=').append(encode(filter(entry.getValue())));
        }
        values.put("w_rid", md5(payload + key.mixinKey()));
        return values;
    }

    private static WbiKey key(String referer) throws Exception {
        WbiKey existing = cachedKey;
        LocalDate today = LocalDate.now(BILI_ZONE);
        if (existing != null && today.equals(existing.date())) return existing;

        BiliCookie.ensureBuvids();
        String body = BiliHttp.getString(NAV_URL, referer);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
        JsonObject image = data.getAsJsonObject("wbi_img");
        String imgKey = keyFromUrl(image.get("img_url").getAsString());
        String subKey = keyFromUrl(image.get("sub_url").getAsString());
        WbiKey key = new WbiKey(today, mixinKey(imgKey + subKey));
        cachedKey = key;
        return key;
    }

    private static String keyFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (slash < 0) slash = -1;
        if (dot <= slash) dot = url.length();
        return url.substring(slash + 1, dot);
    }

    private static String mixinKey(String raw) {
        StringBuilder builder = new StringBuilder(32);
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index >= raw.length()) continue;
            builder.append(raw.charAt(index));
            if (builder.length() == 32) break;
        }
        return builder.toString();
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

    private static String filter(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            builder.append(c);
        }
        return builder.toString();
    }

    private static String md5(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) builder.append('0');
            builder.append(hex);
        }
        return builder.toString();
    }

    private record WbiKey(LocalDate date, String mixinKey) {
    }
}
