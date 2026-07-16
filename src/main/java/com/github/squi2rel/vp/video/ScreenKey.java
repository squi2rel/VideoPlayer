package com.github.squi2rel.vp.video;

public record ScreenKey(String dimension, String areaName, String screenName) {
    public static ScreenKey of(VideoScreen screen) {
        VideoArea area = screen == null ? null : screen.area;
        return new ScreenKey(
                area == null ? "" : safe(area.dim),
                area == null ? "" : safe(area.name),
                screen == null ? "" : safe(screen.name)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
