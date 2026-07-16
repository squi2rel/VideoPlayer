package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataHolderTrackingTest {
    @Test
    void trackingIsThrottledAndStationaryPlayersDoNotScan() {
        DataHolder.PlayerPosition current = new DataHolder.PlayerPosition("world", 1.25, 64, -3.5);

        assertTrue(DataHolder.PLAYER_TRACKING_PERIOD_TICKS > 1);
        assertTrue(DataHolder.shouldScan(null, current));
        assertFalse(DataHolder.shouldScan(new DataHolder.PlayerPosition("world", 1.25, 64, -3.5), current));
        assertTrue(DataHolder.shouldScan(new DataHolder.PlayerPosition("world", 1.5, 64, -3.5), current));
        assertTrue(DataHolder.shouldScan(new DataHolder.PlayerPosition("other", 1.25, 64, -3.5), current));
    }
}
