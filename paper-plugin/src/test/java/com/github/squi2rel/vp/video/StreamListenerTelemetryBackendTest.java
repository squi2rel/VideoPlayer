package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.NativePackageManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamListenerTelemetryBackendTest {
    @Test
    void telemetryPrefersMpvEvenWhenVlcIsConfigured() {
        assertEquals(NativePackageManager.BACKEND_MPV,
                StreamListener.selectBackend(true, NativePackageManager.BACKEND_VLC, true, true));
    }

    @Test
    void normalPlaybackKeepsConfiguredVlcPreference() {
        assertEquals(NativePackageManager.BACKEND_VLC,
                StreamListener.selectBackend(false, NativePackageManager.BACKEND_VLC, true, true));
    }

    @Test
    void telemetryFallsBackToVlcWhenMpvIsUnavailable() {
        assertEquals(NativePackageManager.BACKEND_VLC,
                StreamListener.selectBackend(true, NativePackageManager.BACKEND_VLC, false, true));
    }
}
