package com.github.squi2rel.vp.video;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public final class ScreenVolumeCache {
    private static final int MAX_ENTRIES = 4096;
    private static final Cache<ScreenVolumeKey, Integer> CACHE = CacheBuilder.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .build();

    private ScreenVolumeCache() {
    }

    public static Integer get(ClientVideoScreen screen) {
        if (screen == null) return null;
        return CACHE.getIfPresent(key(screen));
    }

    public static void put(ClientVideoScreen screen, int volume) {
        if (screen == null) return;
        CACHE.put(key(screen), Math.clamp(volume, 0, 100));
    }

    public static void invalidate(ClientVideoScreen screen) {
        if (screen == null) return;
        CACHE.invalidate(key(screen));
    }

    public static void invalidateArea(ClientVideoArea area) {
        if (area == null) return;
        for (VideoScreen screen : area.screens) {
            if (screen instanceof ClientVideoScreen clientScreen) {
                invalidate(clientScreen);
            }
        }
    }

    public static void clear() {
        CACHE.invalidateAll();
    }

    private static ScreenVolumeKey key(ClientVideoScreen screen) {
        VideoArea area = screen == null ? null : screen.area;
        return new ScreenVolumeKey(
                safe(area == null ? null : area.dim),
                safe(area == null ? null : area.name),
                safe(screen == null ? null : screen.name)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScreenVolumeKey(String dimension, String areaName, String screenName) {
    }
}
