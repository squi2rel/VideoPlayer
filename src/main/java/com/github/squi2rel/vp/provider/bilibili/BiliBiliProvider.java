package com.github.squi2rel.vp.provider.bilibili;

import com.github.squi2rel.vp.provider.IVideoProvider;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public abstract class BiliBiliProvider implements IVideoProvider {
    private static final int MAX_API_RESPONSE_BYTES = 4 * 1024 * 1024;
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36 Edg/136.0.0.0";
    public static final String[] MPV_PARAMS = {"user-agent=" + UA, "referrer=https://www.bilibili.com"};

    public static volatile String biliTicket;
    public static volatile long expireTime;
    private static final AtomicBoolean ticketRefreshInFlight = new AtomicBoolean();
    private static Supplier<String> cookieSupplier = () -> "";

    public static void setCookieSupplier(Supplier<String> supplier) {
        cookieSupplier = supplier == null ? () -> "" : supplier;
    }

    protected static HttpRequest makeRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com");
        String cookie = clientCookie();
        if (biliTicket != null && System.currentTimeMillis() < expireTime) {
            builder.header("Cookie", withTicket(cookie, biliTicket));
        } else if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        } else if (System.currentTimeMillis() > expireTime && ticketRefreshInFlight.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    biliTicket = JsonParser.parseString(BiliTicket.getBiliTicket("")).getAsJsonObject().getAsJsonObject("data").get("ticket").getAsString();
                    LOGGER.info("Refreshed Bilibili ticket");
                    expireTime = System.currentTimeMillis() + 259260 * 1000;
                } catch (Exception ignored) {
                    expireTime = System.currentTimeMillis() + 1000 * 60 * 10;
                } finally {
                    ticketRefreshInFlight.set(false);
                }
            });
        }
        return builder.build();
    }

    protected static boolean hasClientCookie() {
        return !clientCookie().isBlank();
    }

    private static String clientCookie() {
        try {
            String cookie = cookieSupplier.get();
            return cookie == null ? "" : cookie.trim().replace("\r", "").replace("\n", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String withTicket(String cookie, String ticket) {
        if (ticket == null || ticket.isBlank()) return cookie == null ? "" : cookie;
        if (cookie == null || cookie.isBlank()) return "bili_ticket=" + ticket;
        if (cookie.contains("bili_ticket=")) return cookie;
        return cookie + "; bili_ticket=" + ticket;
    }

    protected static String responseBody(HttpResponse<InputStream> response) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (total > MAX_API_RESPONSE_BYTES - read) {
                    throw new IllegalStateException("Bilibili API response exceeds " + MAX_API_RESPONSE_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    protected record VideoMeta(String title, String cid) {}
}
