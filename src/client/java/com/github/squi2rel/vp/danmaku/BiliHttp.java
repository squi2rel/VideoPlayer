package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;

final class BiliHttp {
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BINARY_BYTES = 8 * 1024 * 1024;
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BiliHttp() {
    }

    static String getString(String url, String referer) throws Exception {
        return getString(URI.create(url), referer);
    }

    static String getString(URI uri, String referer) throws Exception {
        HttpResponse<InputStream> response = CLIENT.send(request(uri, referer).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            return new String(readLimited(input, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        }
    }

    static byte[] getBytes(URI uri, String referer) throws Exception {
        HttpResponse<InputStream> response = CLIENT.send(request(uri, referer).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            return readLimited(input, MAX_BINARY_BYTES);
        }
    }

    static HttpResponse.BodyHandler<String> limitedStringBodyHandler() {
        return ignored -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofInputStream(),
                input -> {
                    try (InputStream stream = input) {
                        return new String(readLimited(stream, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                    } catch (Exception error) {
                        throw new CompletionException(error);
                    }
                }
        );
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBytes - read) {
                throw new IllegalStateException("Bilibili response exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    static HttpRequest.Builder request(URI uri, String referer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BiliBiliProvider.UA)
                .header("Referer", referer == null || referer.isBlank() ? "https://www.bilibili.com" : referer);
        String cookie = BiliCookie.header();
        if (!cookie.isBlank()) builder.header("Cookie", cookie);
        return builder;
    }
}
