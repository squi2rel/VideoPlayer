package com.github.squi2rel.vp.provider.youtube;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubeQualityTest {
    @Test
    void exposesSupportedHeightOptions() {
        assertArrayEquals(new int[]{360, 480, 720, 1080, 1440, 2160, 4320}, YouTubeQuality.options());
        assertTrue(YouTubeQuality.isOption(1080));
        assertFalse(YouTubeQuality.isOption(2161));
    }

    @Test
    void normalizesUnknownValuesToAuto() {
        assertEquals(YouTubeQuality.AUTO, YouTubeQuality.normalizeClient(0));
        assertEquals(YouTubeQuality.AUTO, YouTubeQuality.normalizeScreenLimit(999));
        assertEquals(1440, YouTubeQuality.normalizeClient(1440));
    }

    @Test
    void combinesLocalPreferenceWithScreenMaximum() {
        assertEquals(720, YouTubeQuality.effective(720, 1080));
        assertEquals(720, YouTubeQuality.effective(1440, 720));
        assertEquals(1080, YouTubeQuality.effective(YouTubeQuality.AUTO, 1080));
        assertEquals(1440, YouTubeQuality.effective(1440, YouTubeQuality.AUTO));
        assertEquals(YouTubeQuality.AUTO, YouTubeQuality.effective(YouTubeQuality.AUTO, YouTubeQuality.AUTO));
    }

    @Test
    void selectsBestAvailableHeightWithoutExceedingLimit() {
        List<Integer> qualities = List.of(360, 720, 1080, 2160);

        assertEquals(2160, YouTubeQuality.bestAtOrBelow(qualities, YouTubeQuality.AUTO));
        assertEquals(1080, YouTubeQuality.bestAtOrBelow(qualities, 1440));
        assertEquals(360, YouTubeQuality.bestAtOrBelow(qualities, 480));
        assertEquals(360, YouTubeQuality.bestAtOrBelow(List.of(720, 1080), 360));
    }

    @Test
    void filtersAndSortsSupportedHeights() {
        assertEquals(List.of(4320, 1440, 720, 360), YouTubeQuality.filterSupported(List.of(240, 720, 4320, 1440, 360, 1440)));
    }
}
