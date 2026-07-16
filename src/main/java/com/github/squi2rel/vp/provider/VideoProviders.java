package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliLiveProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public class VideoProviders {
    private static final int MAX_IN_FLIGHT_RESOLUTIONS = 64;
    private static final Semaphore RESOLUTION_LIMIT = new Semaphore(MAX_IN_FLIGHT_RESOLUTIONS);
    public static CopyOnWriteArrayList<IVideoProvider> providers = new CopyOnWriteArrayList<>();

    public static void register() {
        providers.clear();
        providers.add(new EntityViewProvider());
        providers.add(new BiliBiliVideoProvider());
        providers.add(new BiliBiliLiveProvider());
        providers.add(new CloudMusicProvider());
        providers.add(new CloudMusicMVProvider());
        providers.add(new YouTubeProvider());
        providers.add(new NetworkProvider());
    }

    public static @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        if (!RESOLUTION_LIMIT.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Video resolution capacity is full"));
        }
        boolean released = false;
        try {
            for (IVideoProvider provider : providers) {
                CompletableFuture<VideoInfo> info = provider.from(str, source);
                if (info != null) {
                    info.whenComplete((resolved, error) -> {
                        RESOLUTION_LIMIT.release();
                        if (error != null) {
                            VideoPlayerMain.LOGGER.warn("Provider {} failed for {}", provider.getClass().getSimpleName(), redactedSource(str), error);
                        } else if (resolved == null) {
                            VideoPlayerMain.LOGGER.warn("Provider {} produced no playable result for {}", provider.getClass().getSimpleName(), redactedSource(str));
                        } else {
                            VideoPlayerMain.LOGGER.info("Provider {} resolved {} as '{}'", provider.getClass().getSimpleName(), redactedSource(str), resolved.name());
                        }
                    });
                    released = true;
                    VideoPlayerMain.LOGGER.info("Player {} requested {} using {}", source.name(), redactedSource(str), provider.getClass().getSimpleName());
                    return info;
                }
            }
            VideoPlayerMain.LOGGER.info("No suitable provider for {}", redactedSource(str));
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.error(e.toString());
            source.reply(e.toString());
        } finally {
            if (!released) RESOLUTION_LIMIT.release();
        }
        return null;
    }

    public static String redactedSource(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "<empty>";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "unknown" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return scheme + ":<redacted>";
            int port = uri.getPort();
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String authority = scheme + "://" + normalizedHost + (port < 0 ? "" : ":" + port);
            if (isYouTubeHost(normalizedHost)) {
                String path = uri.getPath();
                if (path == null || path.isBlank()) path = "/";
                if (path.equals("/watch") && hasQueryKey(uri.getRawQuery(), "v")) {
                    return authority + path + "?v=<redacted>";
                }
                return authority + path;
            }
            return authority;
        } catch (RuntimeException ignored) {
            return "<redacted>";
        }
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
    }

    private static boolean hasQueryKey(String query, String key) {
        if (query == null || query.isBlank()) return false;
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String name = separator < 0 ? part : part.substring(0, separator);
            if (name.equals(key)) return true;
        }
        return false;
    }
}
