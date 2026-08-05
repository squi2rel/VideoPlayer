package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MpvPendingSeekTest {
    @Test
    void preservesLatestProgressUntilConsumedOrSuccessfullySent() {
        MpvPendingSeek pending = new MpvPendingSeek();

        pending.request(12_000L);
        pending.request(42_000L);
        assertEquals(42_000L, pending.peek());
        assertEquals(42_000L, pending.consume());
        assertEquals(-1L, pending.consume());

        pending.request(-5L);
        assertEquals(0L, pending.peek());
        pending.clearIf(0L);
        assertEquals(-1L, pending.peek());
    }
}
