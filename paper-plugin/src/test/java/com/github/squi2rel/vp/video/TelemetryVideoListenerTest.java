package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryVideoListenerTest {
    @Test
    void telemetryCallbacksNeverControlPlayback() {
        FakeListener playback = new FakeListener();
        FakeListener telemetry = new FakeListener();
        TelemetryVideoListener listener = new TelemetryVideoListener(playback, telemetry);
        AtomicInteger playing = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        AtomicInteger errored = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        listener.playing(ignored -> playing.incrementAndGet());
        listener.stopped(stopped::incrementAndGet);
        listener.errored(errored::incrementAndGet);
        listener.timeout(timedOut::incrementAndGet);

        listener.listen();
        telemetry.firePlaying();
        telemetry.fireStopped();
        telemetry.fireErrored();
        telemetry.fireTimeout();

        assertEquals(0, playing.get());
        assertEquals(0, stopped.get());
        assertEquals(0, errored.get());
        assertEquals(0, timedOut.get());

        playback.firePlaying();
        playback.fireStopped();
        playback.fireErrored();
        playback.fireTimeout();

        assertEquals(1, playing.get());
        assertEquals(1, stopped.get());
        assertEquals(1, errored.get());
        assertEquals(1, timedOut.get());
    }

    @Test
    void dynamicallyAttachedTelemetryStartsAtPlaybackProgress() {
        FakeListener playback = new FakeListener();
        playback.progress = 42_000L;
        FakeListener telemetry = new FakeListener();
        TelemetryVideoListener listener = new TelemetryVideoListener(playback, null);

        listener.listen();
        assertTrue(listener.attachTelemetry(telemetry));

        assertEquals(1, telemetry.listenCount);
        assertEquals(42_000L, telemetry.progress);
        assertSame(telemetry.audio, listener.audioLevel());
        assertSame(telemetry.color, listener.videoColor());
        assertTrue(listener.detachTelemetry());
        assertTrue(telemetry.cancelled);
        assertEquals(AudioLevelSnapshot.Status.UNSUPPORTED, listener.audioLevel().status());
        assertEquals(VideoColorSnapshot.Status.UNSUPPORTED, listener.videoColor().status());
    }

    @Test
    void cancellationReleasesPlaybackAndTelemetry() {
        FakeListener playback = new FakeListener();
        FakeListener telemetry = new FakeListener();
        TelemetryVideoListener listener = new TelemetryVideoListener(playback, telemetry);

        listener.listen();
        listener.cancel();

        assertTrue(playback.cancelled);
        assertTrue(telemetry.cancelled);
        assertFalse(listener.isPlaying());
    }

    @Test
    void telemetryErrorImmediatelyBecomesUnsupported() {
        FakeListener playback = new FakeListener();
        FakeListener telemetry = new FakeListener();
        TelemetryVideoListener listener = new TelemetryVideoListener(playback, telemetry);

        listener.listen();
        telemetry.fireErrored();

        assertTrue(telemetry.cancelled);
        assertEquals(AudioLevelSnapshot.Status.UNSUPPORTED, listener.audioLevel().status());
        assertEquals(VideoColorSnapshot.Status.UNSUPPORTED, listener.videoColor().status());
    }

    @Test
    void telemetryTimeoutImmediatelyBecomesUnsupported() {
        FakeListener playback = new FakeListener();
        FakeListener telemetry = new FakeListener();
        TelemetryVideoListener listener = new TelemetryVideoListener(playback, telemetry);

        listener.listen();
        telemetry.fireTimeout();

        assertTrue(telemetry.cancelled);
        assertEquals(AudioLevelSnapshot.Status.UNSUPPORTED, listener.audioLevel().status());
        assertEquals(VideoColorSnapshot.Status.UNSUPPORTED, listener.videoColor().status());
    }

    private static final class FakeListener implements IVideoListener {
        private final AudioLevelSnapshot audio = AudioLevelSnapshot.available(-20f, -8f, 10L);
        private final VideoColorSnapshot color = VideoColorSnapshot.available(0x123456, 0.4f, 10L);
        private Consumer<Boolean> playing = ignored -> {
        };
        private Runnable stopped = () -> {
        };
        private Runnable errored = () -> {
        };
        private Runnable timeout = () -> {
        };
        private long progress;
        private int listenCount;
        private boolean cancelled;

        @Override
        public long getProgress() {
            return progress;
        }

        @Override
        public void setProgress(long progress) {
            this.progress = progress;
        }

        @Override
        public boolean isPlaying() {
            return listenCount > 0 && !cancelled;
        }

        @Override
        public void playing(Consumer<Boolean> playing) {
            this.playing = playing;
        }

        @Override
        public void stopped(Runnable stopped) {
            this.stopped = stopped;
        }

        @Override
        public void errored(Runnable errored) {
            this.errored = errored;
        }

        @Override
        public void timeout(Runnable timeout) {
            this.timeout = timeout;
        }

        @Override
        public AudioLevelSnapshot audioLevel() {
            return audio;
        }

        @Override
        public VideoColorSnapshot videoColor() {
            return color;
        }

        @Override
        public void listen() {
            listenCount++;
            cancelled = false;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void firePlaying() {
            playing.accept(true);
        }

        private void fireStopped() {
            stopped.run();
        }

        private void fireErrored() {
            errored.run();
        }

        private void fireTimeout() {
            timeout.run();
        }
    }
}
