package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class BiliQrLoginClient {
    private static final String GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=%s";
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final Set<String> LOGIN_COOKIE_NAMES = Set.of("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid");
    private static final Set<String> QUERY_COOKIE_NAMES = lowerSet(LOGIN_COOKIE_NAMES);

    private BiliQrLoginClient() {
    }

    public static QrCode generate() throws Exception {
        HttpRequest request = baseRequest(URI.create(GENERATE_URL))
                .GET()
                .build();
        HttpResponse<String> response = BiliHttp.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureHttpOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        ensureApiOk(root, "Failed to generate Bilibili QR code");
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IllegalStateException("Bilibili QR response missing data");
        String url = string(data, "url");
        String key = string(data, "qrcode_key");
        if (url.isBlank() || key.isBlank()) throw new IllegalStateException("Bilibili QR response missing fields");
        return new QrCode(url, key);
    }

    public static CompletableFuture<QrCode> generateAsync() {
        HttpRequest request = baseRequest(URI.create(GENERATE_URL))
                .GET()
                .build();
        return BiliHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureHttpOk(response);
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    ensureApiOk(root, "Failed to generate Bilibili QR code");
                    JsonObject data = root.getAsJsonObject("data");
                    if (data == null) throw new IllegalStateException("Bilibili QR response missing data");
                    String url = string(data, "url");
                    String key = string(data, "qrcode_key");
                    if (url.isBlank() || key.isBlank()) throw new IllegalStateException("Bilibili QR response missing fields");
                    return new QrCode(url, key);
                });
    }

    public static PollResult poll(String qrcodeKey) throws Exception {
        String key = encode(qrcodeKey);
        HttpRequest request = baseRequest(URI.create(String.format(POLL_URL, key)))
                .GET()
                .build();
        HttpResponse<String> response = BiliHttp.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        ensureHttpOk(response);

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        ensureApiOk(root, "Failed to poll Bilibili QR code");
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IllegalStateException("Bilibili QR poll response missing data");

        int code = intValue(data, "code", -1);
        String message = string(data, "message");
        return switch (code) {
            case 86101 -> PollResult.state(State.WAITING, translation("message.videoplayer.bili_login_waiting", "Waiting for scan"));
            case 86090 -> PollResult.state(State.SCANNED, translation("message.videoplayer.bili_login_scanned", "Scanned, confirm on your phone"));
            case 86038 -> PollResult.state(State.EXPIRED, translation("message.videoplayer.bili_login_expired", "QR code expired"));
            case 0 -> loginSuccess(data, response.headers());
            default -> PollResult.state(State.ERROR, translation("message.videoplayer.bili_login_unknown_state", "Bilibili returned login state %s", code == -1 ? message : code));
        };
    }

    public static CompletableFuture<PollResult> pollAsync(String qrcodeKey) {
        String key = encode(qrcodeKey);
        HttpRequest request = baseRequest(URI.create(String.format(POLL_URL, key)))
                .GET()
                .build();
        return BiliHttp.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ensureHttpOk(response);
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    ensureApiOk(root, "Failed to poll Bilibili QR code");
                    JsonObject data = root.getAsJsonObject("data");
                    if (data == null) throw new IllegalStateException("Bilibili QR poll response missing data");

                    int code = intValue(data, "code", -1);
                    String message = string(data, "message");
                    return switch (code) {
                        case 86101 -> PollResult.state(State.WAITING, translation("message.videoplayer.bili_login_waiting", "Waiting for scan"));
                        case 86090 -> PollResult.state(State.SCANNED, translation("message.videoplayer.bili_login_scanned", "Scanned, confirm on your phone"));
                        case 86038 -> PollResult.state(State.EXPIRED, translation("message.videoplayer.bili_login_expired", "QR code expired"));
                        case 0 -> loginSuccessUnchecked(data, response.headers());
                        default -> PollResult.state(State.ERROR, translation("message.videoplayer.bili_login_unknown_state", "Bilibili returned login state %s", code == -1 ? message : code));
                    };
                });
    }

    public static VerifiedUser verify(String cookie) throws Exception {
        HttpRequest.Builder builder = baseRequest(URI.create(NAV_URL))
                .GET();
        String sanitized = cookie == null ? "" : cookie.trim();
        if (!sanitized.isBlank()) builder.header("Cookie", sanitized);
        HttpResponse<String> response = BiliHttp.CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        ensureHttpOk(response);
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || !booleanValue(data, "isLogin", false)) {
            throw new IllegalStateException("Bilibili nav did not confirm login");
        }
        return new VerifiedUser(longValue(data, "mid", 0L), string(data, "uname"));
    }

    private static PollResult loginSuccess(JsonObject data, HttpHeaders headers) throws Exception {
        String refreshToken = string(data, "refresh_token");
        long timestamp = System.currentTimeMillis();
        Map<String, String> cookies = new LinkedHashMap<>();
        extractSetCookie(headers, cookies);
        if (cookies.isEmpty()) {
            extractUrlQueryCookies(string(data, "url"), cookies);
        }
        String cookie = BiliAuthStore.formatCookie(cookies);
        if (cookie.isBlank()) throw new IllegalStateException("Bilibili login succeeded without cookies");

        VerifiedUser user = verify(cookie);
        BiliAuthStore.saveLogin(cookie, refreshToken, timestamp, user.mid(), user.userName());
        BiliWbiSigner.invalidate();
        return PollResult.success(user);
    }

    private static PollResult loginSuccessUnchecked(JsonObject data, HttpHeaders headers) {
        try {
            return loginSuccess(data, headers);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private static HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BiliBiliProvider.UA)
                .header("Referer", "https://www.bilibili.com")
                .header("Origin", "https://www.bilibili.com");
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
            if (LOGIN_COOKIE_NAMES.contains(name)) cookies.put(name, value);
        }
    }

    private static void extractUrlQueryCookies(String url, Map<String, String> cookies) {
        if (url == null || url.isBlank()) return;
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) return;
        String query = url.substring(queryStart + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) query = query.substring(0, fragment);
        for (String part : query.split("&")) {
            if (part.isBlank()) continue;
            int index = part.indexOf('=');
            String rawName = index < 0 ? part : part.substring(0, index);
            String rawValue = index < 0 ? "" : part.substring(index + 1);
            String name = decode(rawName);
            if (!QUERY_COOKIE_NAMES.contains(name.toLowerCase(Locale.ROOT))) continue;
            String canonical = canonicalCookieName(name);
            if (!canonical.isBlank()) cookies.put(canonical, decode(rawValue));
        }
    }

    private static String canonicalCookieName(String name) {
        for (String candidate : LOGIN_COOKIE_NAMES) {
            if (candidate.equalsIgnoreCase(name)) return candidate;
        }
        return "";
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

    private static VpTranslation translation(String key, String fallback, Object... args) {
        return VpTranslation.of(key, fallback, args);
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Set<String> lowerSet(Set<String> values) {
        HashSet<String> result = new HashSet<>();
        for (String value : values) {
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    public record QrCode(String url, String qrcodeKey) {
    }

    public record VerifiedUser(long mid, String userName) {
    }

    public enum State {
        WAITING,
        SCANNED,
        EXPIRED,
        SUCCESS,
        ERROR
    }

    public record PollResult(State state, VpTranslation message, VerifiedUser user) {
        public static PollResult state(State state, VpTranslation message) {
            return new PollResult(state, message, null);
        }

        public static PollResult success(VerifiedUser user) {
            return new PollResult(State.SUCCESS,
                    VpTranslation.of("message.videoplayer.bili_login_success", "Logged in as %s", user == null ? "" : user.userName()),
                    user);
        }
    }
}
