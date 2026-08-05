package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import com.github.squi2rel.vp.provider.YouTubeProvider;

public class VideoListeners {
    public static IVideoListener from(VideoScreen screen, VideoInfo info) {
        if (requiresNativeTelemetry(screen, info)) {
            return new StreamListener(info, true);
        }
        return from(info);
    }

    public static IVideoListener from(VideoInfo info) {
        if (PlayerListener.accept(info)) {
            return new PlayerListener();
        }
        if (hasKnownDuration(info)) {
            return new DurationStreamListener(info.durationMs());
        }
        if (isYouTubeUnknownDuration(info)) {
            return new ClientResolutionStreamListener();
        }
        if (isClientResolved(info) || isBilibiliClientPlayback(info)) {
            return null;
        }
        if (requiresNativeStreamListener(info)) {
            return new StreamListener(info);
        }
        return null;
    }

    static boolean requiresNativeTelemetry(VideoScreen screen, VideoInfo info) {
        return screen != null
                && info != null
                && StreamListener.accept(info)
                && !PlayerListener.accept(info)
                && PlaybackTelemetryRegistry.requested(ScreenKey.of(screen));
    }

    public static boolean requiresNativeStreamListener(VideoInfo info) {
        return info != null
                && !PlayerListener.accept(info)
                && !hasKnownDuration(info)
                && !isClientResolved(info)
                && !isBilibiliClientPlayback(info)
                && !isYouTubeUnknownDuration(info)
                && StreamListener.accept(info);
    }

    public static boolean awaitsClientPlaybackResolution(VideoInfo info) {
        return info != null
                && info.durationMs() <= 0
                && info.seekable()
                && isClientResolved(info)
                && YouTubeProvider.isYouTubeRawPath(info.rawPath());
    }

    private static boolean hasKnownDuration(VideoInfo info) {
        return info != null && info.durationMs() > 0;
    }

    private static boolean isClientResolved(VideoInfo info) {
        return info != null
                && (info.path() == null || info.path().isEmpty())
                && info.rawPath() != null
                && !info.rawPath().isEmpty();
    }

    private static boolean isBilibiliClientPlayback(VideoInfo info) {
        return info != null
                && isClientResolved(info)
                && info.rawPath() != null
                && BiliBiliVideoProvider.isBiliVideoRawPath(info.rawPath());
    }

    private static boolean isYouTubeUnknownDuration(VideoInfo info) {
        return info != null
                && info.durationMs() <= 0
                && YouTubeProvider.isYouTubeRawPath(info.rawPath())
                && (!info.seekable() || isClientResolved(info));
    }
}
