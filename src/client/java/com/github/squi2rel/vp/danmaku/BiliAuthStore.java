package com.github.squi2rel.vp.danmaku;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class BiliAuthStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("videoplayer").resolve("auth.json");

    private static AuthData cache;
    private static boolean loaded;

    private BiliAuthStore() {
    }

    public static synchronized AuthData snapshot() {
        ensureLoaded();
        return copy(cache);
    }

    public static synchronized String cookie() {
        ensureLoaded();
        return sanitize(cache.bilibiliCookie);
    }

    public static synchronized void replaceCookieAndResetAuth(String cookie) {
        AuthData next = snapshotMutable();
        next.bilibiliCookie = sanitize(cookie);
        next.bilibiliRefreshToken = "";
        next.bilibiliLoginTimestamp = 0L;
        next.bilibiliRefreshTimestamp = 0L;
        next.bilibiliLastRefreshCheckTimestamp = 0L;
        next.bilibiliMid = 0L;
        next.bilibiliUserName = "";
        persist(next);
    }

    public static synchronized void updateCookie(String cookie) {
        AuthData next = snapshotMutable();
        next.bilibiliCookie = sanitize(cookie);
        persist(next);
    }

    public static synchronized void saveLogin(String cookie, String refreshToken, long loginTimestamp, long mid, String userName) {
        AuthData next = snapshotMutable();
        long timestamp = Math.max(0L, loginTimestamp);
        next.bilibiliCookie = sanitize(cookie);
        next.bilibiliRefreshToken = sanitize(refreshToken);
        next.bilibiliLoginTimestamp = timestamp;
        next.bilibiliRefreshTimestamp = 0L;
        next.bilibiliLastRefreshCheckTimestamp = timestamp;
        next.bilibiliMid = Math.max(0L, mid);
        next.bilibiliUserName = sanitize(userName);
        persist(next);
    }

    public static synchronized void saveRefresh(String cookie, String refreshToken, long refreshTimestamp, long mid, String userName) {
        AuthData next = snapshotMutable();
        long timestamp = Math.max(0L, refreshTimestamp);
        next.bilibiliCookie = sanitize(cookie);
        next.bilibiliRefreshToken = sanitize(refreshToken);
        if (next.bilibiliLoginTimestamp <= 0L) next.bilibiliLoginTimestamp = timestamp;
        next.bilibiliRefreshTimestamp = timestamp;
        next.bilibiliLastRefreshCheckTimestamp = timestamp;
        next.bilibiliMid = Math.max(0L, mid);
        next.bilibiliUserName = sanitize(userName);
        persist(next);
    }

    public static synchronized void updateRefreshCheck(long timestamp) {
        AuthData next = snapshotMutable();
        next.bilibiliLastRefreshCheckTimestamp = Math.max(0L, timestamp);
        persist(next);
    }

    public static synchronized void clear() {
        persist(new AuthData());
    }

    static synchronized Map<String, String> cookieMap() {
        return cookieMap(cookie());
    }

    static Map<String, String> cookieMap(String cookie) {
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

    static String formatCookie(Map<String, String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            if (builder.length() > 0) builder.append("; ");
            builder.append(key.trim());
            builder.append('=');
            builder.append(sanitize(entry.getValue()));
        }
        return builder.toString();
    }

    private static AuthData snapshotMutable() {
        ensureLoaded();
        return copy(cache);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        cache = read();
        loaded = true;
    }

    private static AuthData read() {
        if (!Files.exists(PATH)) return new AuthData();
        try {
            String raw = Files.readString(PATH);
            AuthData data = GSON.fromJson(raw, AuthData.class);
            return normalize(data == null ? new AuthData() : data);
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Failed to read Bilibili auth store", e);
            return new AuthData();
        }
    }

    private static void persist(AuthData data) {
        AuthData next = normalize(copy(data));
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(next));
            cache = next;
            loaded = true;
        } catch (IOException e) {
            LOGGER.warn("Failed to save Bilibili auth store", e);
            throw new RuntimeException("Failed to save Bilibili auth store", e);
        }
    }

    private static AuthData normalize(AuthData data) {
        if (data.bilibiliCookie == null) data.bilibiliCookie = "";
        if (data.bilibiliRefreshToken == null) data.bilibiliRefreshToken = "";
        if (data.bilibiliUserName == null) data.bilibiliUserName = "";
        if (data.bilibiliLoginTimestamp < 0L) data.bilibiliLoginTimestamp = 0L;
        if (data.bilibiliRefreshTimestamp < 0L) data.bilibiliRefreshTimestamp = 0L;
        if (data.bilibiliLastRefreshCheckTimestamp < 0L) data.bilibiliLastRefreshCheckTimestamp = 0L;
        if (data.bilibiliMid < 0L) data.bilibiliMid = 0L;
        return data;
    }

    private static AuthData copy(AuthData data) {
        AuthData copy = new AuthData();
        if (data == null) return copy;
        copy.bilibiliCookie = data.bilibiliCookie;
        copy.bilibiliRefreshToken = data.bilibiliRefreshToken;
        copy.bilibiliLoginTimestamp = data.bilibiliLoginTimestamp;
        copy.bilibiliRefreshTimestamp = data.bilibiliRefreshTimestamp;
        copy.bilibiliLastRefreshCheckTimestamp = data.bilibiliLastRefreshCheckTimestamp;
        copy.bilibiliMid = data.bilibiliMid;
        copy.bilibiliUserName = data.bilibiliUserName;
        return copy;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim().replace("\r", "").replace("\n", "");
    }

    public static final class AuthData {
        public String bilibiliCookie = "";
        public String bilibiliRefreshToken = "";
        public long bilibiliLoginTimestamp = 0L;
        public long bilibiliRefreshTimestamp = 0L;
        public long bilibiliLastRefreshCheckTimestamp = 0L;
        public long bilibiliMid = 0L;
        public String bilibiliUserName = "";
    }
}
