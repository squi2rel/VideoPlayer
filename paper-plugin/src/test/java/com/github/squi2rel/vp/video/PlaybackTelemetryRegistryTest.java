package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    void bindingReceivesOnlyReferenceCountTransitions() {
        ScreenKey key = new ScreenKey("minecraft:overworld", "area", "transitions");
        ArrayList<Boolean> states = new ArrayList<>();
        PlaybackTelemetryRegistry.Binding binding = PlaybackTelemetryRegistry.bind(key, states::add);

        PlaybackTelemetryRegistry.Registration first = PlaybackTelemetryRegistry.acquire(key);
        PlaybackTelemetryRegistry.Registration second = PlaybackTelemetryRegistry.acquire(key);
        first.close();
        second.close();
        binding.close();

        assertEquals(List.of(false, true, false), states);
    }

    @Test
    void bindingCreatedAfterAcquireReceivesCurrentStateAndStopsAfterClose() {
        ScreenKey key = new ScreenKey("minecraft:overworld", "area", "late-binding");
        ArrayList<Boolean> states = new ArrayList<>();
        PlaybackTelemetryRegistry.Registration registration = PlaybackTelemetryRegistry.acquire(key);
        PlaybackTelemetryRegistry.Binding binding = PlaybackTelemetryRegistry.bind(key, states::add);

        binding.close();
        registration.close();

        assertEquals(List.of(true), states);
    }

    @Test
    void requestedScreenKeySpaceIsBoundedAndReleasedKeysCanBeReused() {
        ArrayList<PlaybackTelemetryRegistry.Registration> registrations = new ArrayList<>();
        try {
            for (int i = 0; i < PlaybackTelemetryRegistry.MAX_REQUESTED_SCREENS; i++) {
                registrations.add(PlaybackTelemetryRegistry.acquire(
                        new ScreenKey("minecraft:overworld", "bounded", "screen-" + i)
                ));
            }
            assertThrows(IllegalStateException.class, () -> PlaybackTelemetryRegistry.acquire(
                    new ScreenKey("minecraft:overworld", "bounded", "overflow")
            ));
        } finally {
            registrations.forEach(PlaybackTelemetryRegistry.Registration::close);
        }

        PlaybackTelemetryRegistry.Registration reused = PlaybackTelemetryRegistry.acquire(
                new ScreenKey("minecraft:overworld", "bounded", "reused")
        );
        reused.close();
    }
}
