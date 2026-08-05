package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpvTelemetryPermitPoolTest {
    @Test
    void boundsConcurrentTelemetryDecodersAndReusesReleasedSlots() {
        MpvTelemetryPermitPool pool = new MpvTelemetryPermitPool(2);

        assertTrue(pool.acquire());
        assertTrue(pool.acquire());
        assertFalse(pool.acquire());
        assertEquals(0, pool.available());

        pool.release();
        assertTrue(pool.acquire());
        assertEquals(0, pool.available());
    }

    @Test
    void rejectsInvalidCapacity() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MpvTelemetryPermitPool(0));
    }
}
