package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class BiliHttp {
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BiliHttp() {
    }

    static String getString(String url, String referer) throws Exception {
        return getString(URI.create(url), referer);
    }

    static String getString(URI uri, String referer) throws Exception {
        HttpResponse<String> response = CLIENT.send(request(uri, referer).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    static byte[] getBytes(URI uri, String referer) throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(request(uri, referer).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Bilibili API returned HTTP " + response.statusCode());
        }
        return response.body();
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
