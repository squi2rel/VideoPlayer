package com.github.squi2rel.vp.video;

import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoAreaBoundsTest {
    @Test
    void usesExclusiveMaximumForPlayerMembership() {
        VideoArea area = new VideoArea(new Vector3f(1, 2, 3), new Vector3f(5, 6, 7), "area", "world");

        assertTrue(area.inBounds(new Vec3d(1, 2, 3)));
        assertTrue(area.inBounds(new Vec3d(4.999, 5.999, 6.999)));
        assertFalse(area.inBounds(new Vec3d(5, 5, 6)));
        assertFalse(area.inBounds(new Vec3d(4, 6, 6)));
        assertFalse(area.inBounds(new Vec3d(4, 5, 7)));
    }
}
