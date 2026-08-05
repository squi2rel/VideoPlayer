package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoColorSnapshotTest {
    @Test
    void clampsAvailableValues() {
        VideoColorSnapshot snapshot = VideoColorSnapshot.available(0x1FFFFFF, 1.5f, -2L);

        assertEquals(VideoColorSnapshot.Status.AVAILABLE, snapshot.status());
        assertEquals(0xFFFFFF, snapshot.rgb());
        assertEquals(1f, snapshot.luminance());
        assertEquals(0L, snapshot.sampledAtMs());
    }

    @Test
    void exposesUnavailableStates() {
        assertEquals(VideoColorSnapshot.Status.WAITING, VideoColorSnapshot.waiting().status());
        assertEquals(VideoColorSnapshot.Status.NO_VIDEO, VideoColorSnapshot.noVideo().status());
        assertEquals(VideoColorSnapshot.Status.UNSUPPORTED, VideoColorSnapshot.unsupported().status());
    }
}
