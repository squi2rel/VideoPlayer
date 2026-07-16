package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoListenersTest {
    @Test
    void onlyUnknownDurationServerStreamsRequireNativeListener() {
        VideoInfo knownDuration = info("https://example.com/video.mp4", "", 1_000);
        VideoInfo clientResolved = info("", "https://example.com/watch", 0);
        VideoInfo unknownDurationStream = info("rtsp://example.com/live", "rtsp://example.com/live", 0);

        assertFalse(VideoListeners.requiresNativeStreamListener(knownDuration));
        assertFalse(VideoListeners.requiresNativeStreamListener(clientResolved));
        assertTrue(VideoListeners.requiresNativeStreamListener(unknownDurationStream));
    }

    private static VideoInfo info(String path, String rawPath, long duration) {
        return new VideoInfo("player", "video", path, rawPath, -1, false, new String[0], duration);
    }
}
