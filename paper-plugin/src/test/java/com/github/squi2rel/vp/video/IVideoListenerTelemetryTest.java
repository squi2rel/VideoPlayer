package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IVideoListenerTelemetryTest {
    @Test
    void defaultTelemetryIsUnsupported() {
        IVideoListener listener = new IVideoListener() {
            public long getProgress() { return 0; }
            public boolean isPlaying() { return false; }
            public void playing(Consumer<Boolean> playing) { }
            public void stopped(Runnable stopped) { }
            public void errored(Runnable errored) { }
            public void timeout(Runnable timeout) { }
            public void listen() { }
            public void cancel() { }
        };

        assertEquals(AudioLevelSnapshot.Status.UNSUPPORTED, listener.audioLevel().status());
        assertEquals(VideoColorSnapshot.Status.UNSUPPORTED, listener.videoColor().status());
    }
}
