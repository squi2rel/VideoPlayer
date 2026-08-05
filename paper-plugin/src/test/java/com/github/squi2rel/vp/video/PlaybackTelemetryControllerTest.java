package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackTelemetryControllerTest {
    @Test
    void telemetryRequestAttachesProbeWithoutReplacingPlaybackAuthority() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(1), "area", "world");
        area.initServer();
        area.addPlayer(UUID.randomUUID());
        VideoScreen screen = new VideoScreen(area, "telemetry", new Vector3f(), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0), "");
        PlaybackQueue queue = new PlaybackQueue(screen);
        FakeListener playback = new FakeListener();
        FakeListener probe = new FakeListener();
        PlaybackController controller = new PlaybackController(
                screen,
                queue,
                new ScreenBroadcaster(screen),
                (info, settings) -> info,
                (url, settings) -> null,
                info -> new TelemetryVideoListener(playback, null),
                Runnable::run,
                Runnable::run,
                (command, delay) -> command.run()
        );
        VideoInfo item = new VideoInfo("player", "telemetry", "https://example.com/telemetry.mp4", "",
                -1, true, new String[0], 1_000L);
        queue.add(item);
        controller.playNext();

        controller.applyTelemetryRequest(true, ignored -> probe);
        probe.fail();

        assertSame(item, controller.currentInfo());
        assertTrue(playback.isPlaying());
        assertFalse(probe.isPlaying());

        controller.applyTelemetryRequest(false, ignored -> {
            throw new AssertionError("detach must not create a probe");
        });
        assertFalse(probe.isPlaying());
    }

    private static final class FakeListener implements IVideoListener {
        private boolean playing;
        private Consumer<Boolean> playingCallback = ignored -> {
        };
        private Runnable stoppedCallback = () -> {
        };
        private Runnable errorCallback = () -> {
        };

        @Override
        public long getProgress() {
            return 0L;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void playing(Consumer<Boolean> playing) {
            playingCallback = playing;
        }

        @Override
        public void stopped(Runnable stopped) {
            stoppedCallback = stopped;
        }

        @Override
        public void errored(Runnable errored) {
            errorCallback = errored;
        }

        @Override
        public void timeout(Runnable timeout) {
        }

        @Override
        public void listen() {
            playing = true;
            playingCallback.accept(true);
        }

        @Override
        public void cancel() {
            playing = false;
        }

        private void fail() {
            playing = false;
            errorCallback.run();
            stoppedCallback.run();
        }
    }
}
