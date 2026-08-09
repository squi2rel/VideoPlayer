package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.LocalPlaybackInfo;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.YouTubeProvider;

final class LocalPlaybackResolutionPolicy {
    private LocalPlaybackResolutionPolicy() {
    }

    static boolean shouldResolve(VideoInfo info) {
        if (info == null || info.rawPath() == null || info.rawPath().isBlank()) return false;
        return info.seekable()
                || !YouTubeProvider.isYouTubeRawPath(info.rawPath())
                || !LocalPlaybackInfo.playable(info);
    }
}
