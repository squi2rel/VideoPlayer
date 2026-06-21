package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;

public class VideoListeners {
    public static IVideoListener from(VideoInfo info) {
        if (hasKnownDuration(info)) {
            return new DurationStreamListener(info.durationMs());
        }
        if (isClientResolved(info) || isBilibiliClientPlayback(info)) {
            return null;
        }
        if (StreamListener.accept(info)) {
            return new StreamListener(info);
        }
        return null;
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
                && info.rawPath() != null
                && BiliBiliVideoProvider.isBiliVideoRawPath(info.rawPath());
    }
}
