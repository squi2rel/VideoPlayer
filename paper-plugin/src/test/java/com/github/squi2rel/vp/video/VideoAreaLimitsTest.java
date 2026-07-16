package com.github.squi2rel.vp.video;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoAreaLimitsTest {
    @Test
    void acceptsMaximumAxisLength() {
        assertTrue(VideoArea.validSize(new Vector3f(), new Vector3f(VideoArea.MAX_AXIS_LENGTH, 1, 1)));
    }

    @Test
    void rejectsOversizedOrNonFiniteBounds() {
        assertFalse(VideoArea.validSize(new Vector3f(), new Vector3f(VideoArea.MAX_AXIS_LENGTH + 1, 1, 1)));
        assertFalse(VideoArea.validSize(new Vector3f(), new Vector3f(Float.NaN, 1, 1)));
        assertFalse(VideoArea.validSize(null, new Vector3f()));
    }
}
