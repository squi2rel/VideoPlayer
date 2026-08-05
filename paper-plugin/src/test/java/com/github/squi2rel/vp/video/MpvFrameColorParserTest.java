package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpvFrameColorParserTest {
    @Test
    void convertsLimitedBt709BlackAndWhite() {
        VideoColorSnapshot black = MpvFrameColorParser.parse(metadata(16, 128, 128), "bt.709", "limited", 10L);
        VideoColorSnapshot white = MpvFrameColorParser.parse(metadata(235, 128, 128), "bt.709", "limited", 20L);

        assertEquals(0x000000, black.rgb());
        assertEquals(0f, black.luminance(), 0.001f);
        assertEquals(0xFFFFFF, white.rgb());
        assertEquals(1f, white.luminance(), 0.001f);
    }

    @Test
    void convertsLimitedBt709Red() {
        VideoColorSnapshot snapshot = MpvFrameColorParser.parse(metadata(63, 102, 240), "bt.709", "limited", 30L);

        assertEquals(VideoColorSnapshot.Status.AVAILABLE, snapshot.status());
        assertTrue(channel(snapshot.rgb(), 16) >= 245);
        assertTrue(channel(snapshot.rgb(), 8) <= 12);
        assertTrue(channel(snapshot.rgb(), 0) <= 12);
    }

    @Test
    void handlesFullRangeAndMapMetadata() {
        VideoColorSnapshot snapshot = MpvFrameColorParser.parse(
                "{\"lavfi.signalstats.YAVG\":\"128\",\"lavfi.signalstats.UAVG\":\"128\",\"lavfi.signalstats.VAVG\":\"128\"}",
                "bt.601",
                "full",
                40L
        );

        assertEquals(VideoColorSnapshot.Status.AVAILABLE, snapshot.status());
        assertEquals(128, channel(snapshot.rgb(), 16), 1);
        assertEquals(128, channel(snapshot.rgb(), 8), 1);
        assertEquals(128, channel(snapshot.rgb(), 0), 1);
        assertEquals(40L, snapshot.sampledAtMs());
    }

    @Test
    void rejectsMissingAndMalformedMetadata() {
        assertEquals(VideoColorSnapshot.Status.WAITING,
                MpvFrameColorParser.parse(null, "bt.709", "limited", 1L).status());
        assertEquals(VideoColorSnapshot.Status.WAITING,
                MpvFrameColorParser.parse("lavfi.signalstats.YAVG=bad", "bt.709", "limited", 1L).status());
        assertEquals(VideoColorSnapshot.Status.WAITING,
                MpvFrameColorParser.parse("lavfi.signalstats.YAVG=16", "bt.709", "limited", 1L).status());
    }

    private static String metadata(double y, double u, double v) {
        return "lavfi.signalstats.YAVG=" + y
                + "\nlavfi.signalstats.UAVG=" + u
                + "\nlavfi.signalstats.VAVG=" + v;
    }

    private static int channel(int rgb, int shift) {
        return rgb >> shift & 0xFF;
    }
}
