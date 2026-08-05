package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoListenersTelemetryTest {
    @Test
    void registeredKnownDurationScreenRequiresNativeTelemetry() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(10, 10, 10), "area", "minecraft:overworld");
        VideoScreen screen = new VideoScreen(area, "screen", new Vector3f(), new Vector3f(0, 1, 0),
                new Vector3f(1, 1, 0), new Vector3f(1, 0, 0), "");
        VideoInfo knownDuration = new VideoInfo("player", "video", "https://example.com/video.mp4", "", -1,
                true, new String[0], 60_000L);

        assertFalse(VideoListeners.requiresNativeTelemetry(screen, knownDuration));
        try (PlaybackTelemetryRegistry.Registration ignored = PlaybackTelemetryRegistry.acquire(ScreenKey.of(screen))) {
            assertTrue(VideoListeners.requiresNativeTelemetry(screen, knownDuration));
        }
        assertFalse(VideoListeners.requiresNativeTelemetry(screen, knownDuration));
    }

    @Test
    void pathlessMediaNeverRequiresNativeTelemetry() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(10, 10, 10), "area", "minecraft:overworld");
        VideoScreen screen = new VideoScreen(area, "screen", new Vector3f(), new Vector3f(0, 1, 0),
                new Vector3f(1, 1, 0), new Vector3f(1, 0, 0), "");
        VideoInfo pathless = new VideoInfo("player", "video", "", "https://example.com/watch", -1,
                true, new String[0], 0L);

        try (PlaybackTelemetryRegistry.Registration ignored = PlaybackTelemetryRegistry.acquire(ScreenKey.of(screen))) {
            assertFalse(VideoListeners.requiresNativeTelemetry(screen, pathless));
        }
    }
}
