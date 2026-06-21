package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class BiliCookie {
    private static final String SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi";

    private BiliCookie() {
    }

    public static void set(String cookie) {
        BiliAuthStore.replaceCookieAndResetAuth(cookie);
        BiliWbiSigner.invalidate();
    }

    public static void clear() {
        BiliAuthStore.clear();
        BiliWbiSigner.invalidate();
    }

    public static VpTranslation status() {
        BiliAuthStore.AuthData auth = BiliAuthStore.snapshot();
        String cookie = sanitize(auth.bilibiliCookie);
        if (cookie.isBlank()) {
            return VpTranslation.of("message.videoplayer.bilibili_cookie_unset", "Bilibili auth: not set");
        }
        String user = auth.bilibiliUserName == null || auth.bilibiliUserName.isBlank() ? "-" : auth.bilibiliUserName.trim();
        return VpTranslation.of("message.videoplayer.bilibili_cookie_set",
                "Bilibili auth: SESSDATA=%s bili_jct=%s buvid3=%s buvid4=%s verified user=%s",
                present(has(cookie, "SESSDATA")),
                present(has(cookie, "bili_jct")),
                present(has(cookie, "buvid3")),
                present(has(cookie, "buvid4")),
                user);
    }

    public static String header() {
        return BiliAuthStore.cookie();
    }

    public static String ensureBuvids() {
        String cookie = header();
        if (has(cookie, "buvid3") && has(cookie, "buvid4")) {
            return cookie;
        }
        try {
            String body = BiliHttp.getString(SPI_URL, "https://www.bilibili.com");
            JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
            Map<String, String> values = parse(cookie);
            if (data != null) {
                if (data.has("b_3")) values.put("buvid3", data.get("b_3").getAsString());
                if (data.has("b_4")) values.put("buvid4", data.get("b_4").getAsString());
            }
            String merged = format(values);
            if (!merged.equals(cookie)) {
                BiliAuthStore.updateCookie(merged);
            }
            return merged;
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh Bilibili buvid cookie", e);
            return cookie;
        }
    }

    static boolean has(String name) {
        return has(header(), name);
    }

    static String value(String name) {
        return parse(header()).getOrDefault(name, "");
    }

    private static boolean has(String cookie, String name) {
        return parse(cookie).containsKey(name);
    }

    private static String sanitize(String cookie) {
        return cookie == null ? "" : cookie.trim().replace("\r", "").replace("\n", "");
    }

    private static String present(boolean value) {
        return value ? "yes" : "no";
    }

    private static Map<String, String> parse(String cookie) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (cookie == null || cookie.isBlank()) return result;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int index = trimmed.indexOf('=');
            if (index <= 0) continue;
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (key.isEmpty()) continue;
            result.put(key, value);
        }
        return result;
    }

    private static String format(Map<String, String> values) {
        return BiliAuthStore.formatCookie(values);
    }
}
