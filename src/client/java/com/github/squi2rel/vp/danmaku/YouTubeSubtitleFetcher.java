package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.VideoParams;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YouTubeSubtitleFetcher {
    private static final Pattern TIMING = Pattern.compile("^(\\S+)\\s+-->\\s+(\\S+).*$");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String UA = "Mozilla/5.0";

    private YouTubeSubtitleFetcher() {
    }

    static boolean canResolve(VideoInfo info) {
        return info != null && !VideoParams.subtitleParams(info.params()).isEmpty();
    }

    static boolean canFetch(BiliCcSubtitleFetcher.SubtitleOption option) {
        return option != null && option.key() != null && option.key().startsWith("yt:");
    }

    static CompletableFuture<BiliCcSubtitleFetcher.SubtitleCatalog> fetchCatalog(VideoInfo info) {
        return CompletableFuture.supplyAsync(() -> fetchCatalogBlocking(info));
    }

    static CompletableFuture<BiliCcSubtitleFetcher.SubtitleTrack> fetchTrack(BiliCcSubtitleFetcher.SubtitleOption option) {
        return CompletableFuture.supplyAsync(() -> fetchTrackBlocking(option));
    }

    private static BiliCcSubtitleFetcher.SubtitleCatalog fetchCatalogBlocking(VideoInfo info) {
        List<VideoParams.SubtitleParam> params = VideoParams.subtitleParams(info == null ? null : info.params());
        if (params.isEmpty()) return BiliCcSubtitleFetcher.SubtitleCatalog.empty();
        ArrayList<BiliCcSubtitleFetcher.SubtitleOption> options = new ArrayList<>();
        int index = 0;
        for (VideoParams.SubtitleParam param : params) {
            String label = param.label();
            if (label == null || label.isBlank()) label = param.language();
            if (label == null || label.isBlank()) label = "CC";
            options.add(new BiliCcSubtitleFetcher.SubtitleOption(
                    param.key(),
                    label,
                    param.language(),
                    param.url(),
                    param.referer(),
                    score(param.language(), label, param.automatic(), index++)
            ));
        }
        options.sort(Comparator.comparingInt(BiliCcSubtitleFetcher.SubtitleOption::score).reversed());
        return new BiliCcSubtitleFetcher.SubtitleCatalog(List.copyOf(options));
    }

    private static BiliCcSubtitleFetcher.SubtitleTrack fetchTrackBlocking(BiliCcSubtitleFetcher.SubtitleOption option) {
        if (option == null || option.url() == null || option.url().isBlank()) {
            return BiliCcSubtitleFetcher.SubtitleTrack.empty();
        }
        try {
            HttpResponse<String> response = httpClient().send(request(option), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return BiliCcSubtitleFetcher.SubtitleTrack.empty();
            }
            return new BiliCcSubtitleFetcher.SubtitleTrack(option.key(), option.label(), parseVtt(response.body()));
        } catch (Exception ignored) {
            return BiliCcSubtitleFetcher.SubtitleTrack.empty();
        }
    }

    private static HttpRequest request(BiliCcSubtitleFetcher.SubtitleOption option) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(option.url()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/vtt,text/plain,*/*");
        if (option.referer() != null && !option.referer().isBlank()) {
            builder.header("Referer", option.referer());
        }
        return builder.GET().build();
    }

    private static HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = VideoParams.normalizeHttpProxy(VideoPlayerClient.config == null ? "" : VideoPlayerClient.config.nativeDownloadProxy);
        if (proxy.isBlank()) return builder.build();
        URI uri = URI.create(proxy);
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            String username = separator < 0 ? userInfo : userInfo.substring(0, separator);
            String password = separator < 0 ? "" : userInfo.substring(separator + 1);
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password.toCharArray());
                }
            });
        }
        return builder.build();
    }

    private static List<BiliCcSubtitleFetcher.SubtitleCue> parseVtt(String body) {
        if (body == null || body.isBlank()) return List.of();
        String[] lines = body.replace("\r", "").split("\n");
        ArrayList<BiliCcSubtitleFetcher.SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher matcher = TIMING.matcher(line);
            if (!matcher.matches()) continue;
            long from = parseTimestamp(matcher.group(1));
            long to = parseTimestamp(matcher.group(2));
            if (from < 0 || to <= from) continue;
            StringBuilder text = new StringBuilder();
            while (++i < lines.length) {
                String cueLine = lines[i].trim();
                if (cueLine.isBlank()) break;
                if (!text.isEmpty()) text.append('\n');
                text.append(cleanText(cueLine));
            }
            String content = text.toString().trim();
            if (!content.isBlank()) {
                cues.add(new BiliCcSubtitleFetcher.SubtitleCue(from, to, content));
            }
        }
        cues.sort(Comparator.comparingLong(BiliCcSubtitleFetcher.SubtitleCue::fromMs));
        return List.copyOf(cues);
    }

    private static long parseTimestamp(String value) {
        if (value == null || value.isBlank()) return -1L;
        String[] parts = value.replace(',', '.').split(":");
        try {
            double seconds;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600.0
                        + Integer.parseInt(parts[1]) * 60.0
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60.0
                        + Double.parseDouble(parts[1]);
            } else {
                return -1L;
            }
            return seconds >= 0 ? Math.round(seconds * 1000.0) : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String cleanText(String text) {
        String stripped = TAG.matcher(text == null ? "" : text).replaceAll("");
        return stripped
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    private static int score(String language, String label, boolean automatic, int index) {
        String lan = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String text = label == null ? "" : label;
        int score = Math.max(0, 100 - index);
        if (!automatic) score += 10_000;
        if (lan.equals("zh-hans") || lan.equals("zh-cn")) score += 1000;
        else if (lan.startsWith("zh")) score += 900;
        else if (text.contains("中文") || text.contains("汉语") || text.contains("漢語")) score += 800;
        else if (lan.equals("en-us") || lan.startsWith("en")) score += 200;
        return score;
    }
}
