package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlaybackResolutionPolicyTest {
    @Test
    void skipsLocalResolutionForPlayableServerResolvedYouTubeLiveStream() {
        VideoInfo info = youtube("https://video.example/live", false, System.currentTimeMillis() + 60_000L);

        assertFalse(LocalPlaybackResolutionPolicy.shouldResolve(info));
    }

    @Test
    void resolvesYouTubeLiveStreamLocallyWhenServerPathIsMissing() {
        VideoInfo info = youtube("", false, -1L);

        assertTrue(LocalPlaybackResolutionPolicy.shouldResolve(info));
    }

    @Test
    void resolvesYouTubeLiveStreamLocallyWhenServerPathExpired() {
        VideoInfo info = youtube("https://video.example/live", false, System.currentTimeMillis() - 1L);

        assertTrue(LocalPlaybackResolutionPolicy.shouldResolve(info));
    }

    @Test
    void continuesResolvingYouTubeVideoOnTheClient() {
        VideoInfo info = youtube("https://video.example/vod", true, System.currentTimeMillis() + 60_000L);

        assertTrue(LocalPlaybackResolutionPolicy.shouldResolve(info));
    }

    @Test
    void continuesResolvingNonYouTubeLiveSourcesOnTheClient() {
        VideoInfo info = new VideoInfo(
                "player",
                "live",
                "https://video.example/live",
                "https://example.com/live",
                -1L,
                false,
                new String[0],
                0L
        );

        assertTrue(LocalPlaybackResolutionPolicy.shouldResolve(info));
    }

    @Test
    void skipsLocalResolutionWithoutAResolvableRawPath() {
        VideoInfo info = new VideoInfo(
                "player",
                "direct",
                "https://video.example/direct",
                "",
                -1L,
                true,
                new String[0],
                1_000L
        );

        assertFalse(LocalPlaybackResolutionPolicy.shouldResolve(info));
        assertFalse(LocalPlaybackResolutionPolicy.shouldResolve(null));
    }

    private static VideoInfo youtube(String path, boolean seekable, long expire) {
        return new VideoInfo(
                "player",
                "youtube",
                path,
                "https://www.youtube.com/watch?v=hotfix-test",
                expire,
                seekable,
                new String[0],
                seekable ? 1_000L : 0L
        );
    }
}
