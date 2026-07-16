package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoPermissionContextTest {
    @Test
    void screenContextDoesNotRetainMutableAreaOrScreenState() {
        VideoArea area = new VideoArea(new Vector3f(1, 2, 3), new Vector3f(8, 9, 10), "area", "world");
        VideoScreen screen = new VideoScreen(area, "screen", List.of(
                new Vector3f(4, 5, 6),
                new Vector3f(5, 5, 6),
                new Vector3f(5, 6, 6),
                new Vector3f(4, 6, 6)
        ), "source");

        VideoPermissionContext context = VideoPermissionContext.screen(screen);

        area.min.set(20, 21, 22);
        area.max.set(30, 31, 32);
        area.name = "changed-area";
        screen.name = "changed-screen";
        screen.vertices.getFirst().set(40, 41, 42);

        assertEquals("world", context.dimension());
        assertEquals("area", context.areaName());
        assertEquals("screen", context.screenName());
        assertEquals(new VideoPermissionContext.Position(1, 2, 3), context.areaMin());
        assertEquals(new VideoPermissionContext.Position(8, 9, 10), context.areaMax());
        assertEquals(new VideoPermissionContext.Position(4, 5, 6), context.anchor());
    }

    @Test
    void sphereContextUsesCopiedSphereCenterAsAnchor() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(10), "area", "world");
        VideoScreen screen = new VideoScreen(area, "sphere", List.of(), "source");
        screen.surface = ScreenSurface.SPHERE_360;
        screen.spherePreset = true;
        screen.sphereCenter.set(3, 4, 5);

        VideoPermissionContext context = VideoPermissionContext.screen(screen);
        screen.sphereCenter.set(7, 8, 9);

        assertEquals(new VideoPermissionContext.Position(3, 4, 5), context.anchor());
    }
}
