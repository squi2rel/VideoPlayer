package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class BiliAuthRefresher {
    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final String COOKIE_INFO_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/info";
    private static final String CORRESPOND_URL = "https://www.bilibili.com/correspond/1/%s";
    private static final String REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";
    private static final String CONFIRM_URL = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh";
    private static final String PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
            Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
            nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
            JNrRuoEUXpabUzGB8QIDAQAB
            -----END PUBLIC KEY-----
            """;
    private static final Pattern REFRESH_CSRF_PATTERN = Pattern.compile("id=[\"']1-name[\"'][^>]*>([^<]+)<");
    private static final Set<String> REFRESH_COOKIE_NAMES = Set.of("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BiliAuthRefresher() {
    }

    public static void checkOnStartup() {
        checkAsync();
    }

    public static void tick() {
        checkAsync();
    }

    private static void checkAsync() {
        BiliAuthStore.AuthData auth = BiliAuthStore.snapshot();
        if (!hasRefreshCredentials(auth)) return;
        long now = System.currentTimeMillis();
        if (now - auth.bilibiliLastRefreshCheckTimestamp < CHECK_INTERVAL_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        BiliAuthStore.updateRefreshCheck(now);
        CompletableFuture.runAsync(() -> {
            try {
                checkAndRefresh(auth);
            } catch (Exception e) {
                LOGGER.warn("Failed to check Bilibili auth refresh state", e);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static boolean hasRefreshCredentials(BiliAuthStore.AuthData auth) {
        return auth != null
                && auth.bilibiliCookie != null
                && !auth.bilibiliCookie.isBlank()
                && auth.bilibiliRefreshToken != null
                && !auth.bilibiliRefreshToken.isBlank()
                && !csrf(auth.bilibiliCookie).isBlank();
    }

    private static void checkAndRefresh(BiliAuthStore.AuthData auth) throws Exception {
        RefreshInfo info = refreshInfo(auth.bilibiliCookie);
        if (!info.refresh()) return;
        refresh(auth, info.timestamp());
    }

    private static RefreshInfo refreshInfo(String cookie) throws Exception {
        String csrf = csrf(cookie);
        URI uri = URI.create(COOKIE_INFO_URL + "?csrf=" + encode(csrf));
        HttpResponse<String> response = BiliHttp.CLIENT.send(baseRequest(uri, cookie).GET().build(), BiliHttp.limitedStringBodyHandler());
        ensureHttpOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        ensureApiOk(root, "Bilibili cookie info failed");
        JsonObject data = object(root, "data");
        if (data == null) throw new IllegalStateException("Bilibili cookie info returned no data");
        return new RefreshInfo(booleanValue(data, "refresh", false), longValue(data, "timestamp", System.currentTimeMillis()));
    }

    private static void refresh(BiliAuthStore.AuthData auth, long timestamp) throws Exception {
        String oldCookie = auth.bilibiliCookie;
        String oldRefreshToken = auth.bilibiliRefreshToken;
        String refreshCsrf = refreshCsrf(oldCookie, timestamp);
        RefreshResult result = requestRefresh(oldCookie, oldRefreshToken, refreshCsrf);

        BiliAuthStore.AuthData current = BiliAuthStore.snapshot();
        if (!oldRefreshToken.equals(current.bilibiliRefreshToken)) {
            LOGGER.info("Skipped Bilibili auth refresh because auth changed during refresh");
            return;
        }
        String baseCookie = current.bilibiliCookie == null || current.bilibiliCookie.isBlank() ? oldCookie : current.bilibiliCookie;
        Map<String, String> merged = BiliAuthStore.cookieMap(baseCookie);
        merged.putAll(result.cookies());
        String newCookie = BiliAuthStore.formatCookie(merged);
        BiliQrLoginClient.VerifiedUser user = BiliQrLoginClient.verify(newCookie);

        long now = System.currentTimeMillis();
        BiliAuthStore.saveRefresh(newCookie, result.refreshToken(), now, user.mid(), user.userName());
        BiliWbiSigner.invalidate();
        try {
            confirmRefresh(newCookie, oldRefreshToken);
        } catch (Exception e) {
            LOGGER.warn("Failed to confirm Bilibili auth refresh", e);
        }
        LOGGER.info("Refreshed Bilibili auth for {}", user.userName());
    }

    private static String refreshCsrf(String cookie, long timestamp) throws Exception {
        String path = correspondPath(timestamp);
        URI uri = URI.create(String.format(CORRESPOND_URL, path));
        HttpResponse<String> response = BiliHttp.CLIENT.send(baseRequest(uri, cookie).GET().build(), BiliHttp.limitedStringBodyHandler());
        ensureHttpOk(response);
        Matcher matcher = REFRESH_CSRF_PATTERN.matcher(response.body());
        if (!matcher.find()) throw new IllegalStateException("Bilibili refresh csrf was not found");
        return matcher.group(1).trim();
    }

    private static RefreshResult requestRefresh(String cookie, String refreshToken, String refreshCsrf) throws Exception {
        String body = form(
                "csrf", csrf(cookie),
                "refresh_csrf", refreshCsrf,
                "source", "main_web",
                "refresh_token", refreshToken
        );
        HttpRequest request = baseRequest(URI.create(REFRESH_URL), cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = BiliHttp.CLIENT.send(request, BiliHttp.limitedStringBodyHandler());
        ensureHttpOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        ensureApiOk(root, "Bilibili cookie refresh failed");
        JsonObject data = object(root, "data");
        String nextRefreshToken = string(data, "refresh_token");
        if (nextRefreshToken.isBlank()) throw new IllegalStateException("Bilibili cookie refresh returned no refresh token");

        Map<String, String> cookies = new LinkedHashMap<>();
        extractSetCookie(response.headers(), cookies);
        if (cookies.isEmpty()) throw new IllegalStateException("Bilibili cookie refresh returned no cookies");
        return new RefreshResult(cookies, nextRefreshToken);
    }

    private static void confirmRefresh(String cookie, String oldRefreshToken) throws Exception {
        String body = form(
                "csrf", csrf(cookie),
                "refresh_token", oldRefreshToken
        );
        HttpRequest request = baseRequest(URI.create(CONFIRM_URL), cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = BiliHttp.CLIENT.send(request, BiliHttp.limitedStringBodyHandler());
        ensureHttpOk(response);
        ensureApiOk(JsonParser.parseString(response.body()).getAsJsonObject(), "Bilibili cookie refresh confirm failed");
    }

    private static HttpRequest.Builder baseRequest(URI uri, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BiliBiliProvider.UA)
                .header("Referer", "https://www.bilibili.com")
                .header("Origin", "https://www.bilibili.com");
        String safeCookie = cookie == null ? "" : cookie.trim().replace("\r", "").replace("\n", "");
        if (!safeCookie.isBlank()) builder.header("Cookie", safeCookie);
        return builder;
    }

    private static String correspondPath(long timestamp) throws Exception {
        String pem = PUBLIC_KEY
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim();
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return hex(cipher.doFinal(("refresh_" + timestamp).getBytes(StandardCharsets.UTF_8)));
    }

    private static void extractSetCookie(HttpHeaders headers, Map<String, String> cookies) {
        List<String> values = headers.allValues("set-cookie");
        if (values.isEmpty()) values = headers.allValues("Set-Cookie");
        for (String header : values) {
            if (header == null || header.isBlank()) continue;
            String first = header.split(";", 2)[0].trim();
            int index = first.indexOf('=');
            if (index <= 0) continue;
            String name = first.substring(0, index).trim();
            String value = first.substring(index + 1).trim();
            if (REFRESH_COOKIE_NAMES.contains(name)) cookies.put(name, value);
        }
    }

    private static String csrf(String cookie) {
        return BiliAuthStore.cookieMap(cookie).getOrDefault("bili_jct", "");
    }

    private static void ensureHttpOk(HttpResponse<?> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
    }

    private static void ensureApiOk(JsonObject root, String fallback) {
        int code = intValue(root, "code", 0);
        if (code != 0) {
            String message = string(root, "message");
            throw new IllegalStateException(message.isBlank() ? fallback : fallback + ": " + message);
        }
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return "";
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return "";
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int intValue(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name)) return fallback;
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        if (object == null || !object.has(name)) return fallback;
        try {
            return object.get(name).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject object, String name, boolean fallback) {
        if (object == null || !object.has(name)) return fallback;
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String form(String... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (builder.length() > 0) builder.append('&');
            builder.append(encode(values[i])).append('=').append(encode(values[i + 1]));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String value = Integer.toHexString(b & 0xFF);
            if (value.length() == 1) builder.append('0');
            builder.append(value);
        }
        return builder.toString();
    }

    private record RefreshInfo(boolean refresh, long timestamp) {
    }

    private record RefreshResult(Map<String, String> cookies, String refreshToken) {
    }
}
