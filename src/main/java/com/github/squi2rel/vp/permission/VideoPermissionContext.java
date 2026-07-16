package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import org.joml.Vector3f;

public record VideoPermissionContext(
        String dimension,
        String areaName,
        String screenName,
        Position areaMin,
        Position areaMax,
        Position anchor
) {
    public static VideoPermissionContext global(String dimension) {
        return new VideoPermissionContext(dimension, null, null, null, null, null);
    }

    public static VideoPermissionContext area(VideoArea area) {
        if (area == null) return global(null);
        return new VideoPermissionContext(
                area.dim,
                area.name,
                null,
                Position.from(area.min),
                Position.from(area.max),
                null
        );
    }

    public static VideoPermissionContext screen(VideoScreen screen) {
        if (screen == null) return global(null);
        VideoArea area = screen.area;
        Vector3f anchor = null;
        if (screen.surface == ScreenSurface.SPHERE_360 && screen.spherePreset && screen.sphereCenter != null) {
            anchor = screen.sphereCenter;
        } else if (screen.vertices != null && !screen.vertices.isEmpty()) {
            anchor = screen.vertices.getFirst();
        }
        return new VideoPermissionContext(
                area == null ? null : area.dim,
                area == null ? null : area.name,
                screen.name,
                area == null ? null : Position.from(area.min),
                area == null ? null : Position.from(area.max),
                Position.from(anchor)
        );
    }

    public boolean hasArea() {
        return areaName != null && !areaName.isBlank();
    }

    public record Position(float x, float y, float z) {
        public static Position from(Vector3f value) {
            return value == null ? null : new Position(value.x, value.y, value.z);
        }
    }
}
