package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackTelemetryRegistryTest {
    @Test
    void referenceCountsAndClosesIdempotently() {
        ScreenKey key = new ScreenKey("minecraft:overworld", "area", "screen");
        PlaybackTelemetryRegistry.Registration first = PlaybackTelemetryRegistry.acquire(key);
        PlaybackTelemetryRegistry.Registration second = PlaybackTelemetryRegistry.acquire(key);

        assertEquals(1, PlaybackTelemetryRegistry.apiVersion());
        assertTrue(PlaybackTelemetryRegistry.requested(key));
        first.close();
        first.close();
        assertTrue(PlaybackTelemetryRegistry.requested(key));
        second.close();
        assertFalse(PlaybackTelemetryRegistry.requested(key));
    }

    @Test
    void rejectsIncompleteScreenKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackTelemetryRegistry.acquire(new ScreenKey("", "area", "screen")));
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackTelemetryRegistry.acquire(new ScreenKey(null, "area", "screen")));
    }
}
