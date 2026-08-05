package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void unavailableTelemetryKeepsKnownDurationPlaybackListener() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(10, 10, 10), "area", "minecraft:overworld");
        VideoScreen screen = new VideoScreen(area, "screen", new Vector3f(), new Vector3f(0, 1, 0),
                new Vector3f(1, 1, 0), new Vector3f(1, 0, 0), "");
        VideoInfo knownDuration = new VideoInfo("player", "video", "https://example.com/video.mp4", "", -1,
                true, new String[0], 60_000L);
        AtomicBoolean factoryCalled = new AtomicBoolean();
        AtomicBoolean playing = new AtomicBoolean();

        try (PlaybackTelemetryRegistry.Registration ignored = PlaybackTelemetryRegistry.acquire(ScreenKey.of(screen))) {
            IVideoListener listener = VideoListeners.from(screen, knownDuration, info -> {
                factoryCalled.set(true);
                return null;
            });
            assertNotNull(listener);
            listener.playing(playing::set);
            listener.listen();
            listener.cancel();
        }

        assertTrue(factoryCalled.get());
        assertTrue(playing.get());
    }

    @Test
    void eligiblePlaybackIsPreparedForLateTelemetryWithoutStartingProbe() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(10, 10, 10), "area", "minecraft:overworld");
        VideoScreen screen = new VideoScreen(area, "late", new Vector3f(), new Vector3f(0, 1, 0),
                new Vector3f(1, 1, 0), new Vector3f(1, 0, 0), "");
        VideoInfo knownDuration = new VideoInfo("player", "video", "https://example.com/video.mp4", "", -1,
                true, new String[0], 60_000L);
        AtomicBoolean factoryCalled = new AtomicBoolean();

        IVideoListener listener = VideoListeners.from(screen, knownDuration, info -> {
            factoryCalled.set(true);
            return null;
        });

        assertInstanceOf(TelemetryVideoListener.class, listener);
        assertFalse(factoryCalled.get());
        listener.cancel();
    }
}
