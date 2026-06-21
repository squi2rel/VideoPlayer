package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import org.joml.Vector3f;

public record VideoPermissionContext(
        String dimension,
        String areaName,
        String screenName,
        Vector3f areaMin,
        Vector3f areaMax,
        VideoArea area,
        VideoScreen screen
) {
    public static VideoPermissionContext global(String dimension) {
        return new VideoPermissionContext(dimension, null, null, null, null, null, null);
    }

    public static VideoPermissionContext area(VideoArea area) {
        if (area == null) return global(null);
        return new VideoPermissionContext(
                area.dim,
                area.name,
                null,
                new Vector3f(area.min),
                new Vector3f(area.max),
                area,
                null
        );
    }

    public static VideoPermissionContext screen(VideoScreen screen) {
        if (screen == null) return global(null);
        VideoArea area = screen.area;
        return new VideoPermissionContext(
                area == null ? null : area.dim,
                area == null ? null : area.name,
                screen.name,
                area == null ? null : new Vector3f(area.min),
                area == null ? null : new Vector3f(area.max),
                area,
                screen
        );
    }

    public boolean hasArea() {
        return areaName != null && !areaName.isBlank();
    }
}
