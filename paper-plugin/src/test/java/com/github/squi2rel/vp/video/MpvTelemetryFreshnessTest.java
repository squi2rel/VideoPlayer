package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MpvTelemetryFreshnessTest {
    @Test
    void identicalAudioMetadataRefreshesTimestamp() {
        String metadata = "lavfi.astats.Overall.RMS_level=-20\nlavfi.astats.Overall.Peak_level=-8";
        AudioLevelSnapshot first = MpvStreamListener.updateAudioSnapshot(AudioLevelSnapshot.waiting(), metadata, 100L);
        AudioLevelSnapshot second = MpvStreamListener.updateAudioSnapshot(first, metadata, 200L);

        assertEquals(AudioLevelSnapshot.Status.AVAILABLE, second.status());
        assertEquals(-8f, second.peakDb());
        assertEquals(200L, second.sampledAtMs());
    }

    @Test
    void identicalColorMetadataRefreshesTimestamp() {
        String metadata = "lavfi.signalstats.YAVG=63\nlavfi.signalstats.UAVG=102\nlavfi.signalstats.VAVG=240";
        VideoColorSnapshot first = MpvStreamListener.updateColorSnapshot(
                VideoColorSnapshot.waiting(), metadata, "bt.709", "limited", 100L
        );
        VideoColorSnapshot second = MpvStreamListener.updateColorSnapshot(
                first, metadata, "bt.709", "limited", 200L
        );

        assertEquals(VideoColorSnapshot.Status.AVAILABLE, second.status());
        assertEquals(first.rgb(), second.rgb());
        assertEquals(200L, second.sampledAtMs());
    }
}
