package com.github.squi2rel.vp.video;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.github.squi2rel.vp.VideoPlayerClient;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class ScreenVolumeCache {
    private static final int MAX_ENTRIES = 4096;
    private static final Cache<ScreenVolumeStore.Key, Integer> CACHE = CacheBuilder.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .build();
    private static ScreenVolumeStore store;

    private ScreenVolumeCache() {
    }

    public static void initialize(Path path) {
        CACHE.invalidateAll();
        store = new ScreenVolumeStore(path);
        try {
            store.load();
        } catch (IOException e) {
            LOGGER.warn("Failed to read screen volume configuration {}; keeping the original file", path, e);
        }
    }

    public static Integer get(ClientVideoScreen screen) {
        if (screen == null) return null;
        ScreenVolumeStore.Key key = key(screen);
        if (key == null) return null;
        Integer cached = CACHE.getIfPresent(key);
        if (cached != null || store == null) return cached;
        Integer saved = store.get(key);
        if (saved != null) CACHE.put(key, saved);
        return saved;
    }

    public static void put(ClientVideoScreen screen, int volume) {
        if (screen == null) return;
        ScreenVolumeStore.Key key = key(screen);
        if (key == null) return;
        CACHE.put(key, Math.clamp(volume, 0, 100));
        if (store != null) store.put(key, volume);
    }

    public static void invalidate(ClientVideoScreen screen) {
        if (screen == null) return;
        ScreenVolumeStore.Key key = key(screen);
        if (key == null) return;
        CACHE.invalidate(key);
        if (store != null) store.remove(key);
        flush();
    }

    public static void invalidate(String dimension, String areaName, String screenName) {
        if (dimension == null) return;
        String server = serverKey();
        if (server == null) return;
        CACHE.invalidate(new ScreenVolumeStore.Key(server, dimension, areaName, screenName));
        if (store != null) store.removeScreen(server, dimension, areaName, screenName);
        flush();
    }

    public static void invalidateArea(ClientVideoArea area) {
        if (area == null) return;
        invalidateArea(area.dim, area.name);
    }

    public static void invalidateArea(String dimension, String areaName) {
        if (dimension == null) return;
        String server = serverKey();
        if (server == null) return;
        CACHE.asMap().keySet().removeIf(key -> key.server().equals(server)
                && key.dimension().equals(dimension) && key.areaName().equals(areaName));
        if (store != null) store.removeArea(server, dimension, areaName);
        flush();
    }

    public static void flush() {
        if (store == null) return;
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.warn("Failed to save screen volume configuration; keeping the in-memory settings", e);
        }
    }

    public static void clear() {
        CACHE.invalidateAll();
    }

    private static ScreenVolumeStore.Key key(ClientVideoScreen screen) {
        String server = serverKey();
        if (server == null) return null;
        VideoArea area = screen == null ? null : screen.area;
        return new ScreenVolumeStore.Key(
                server,
                safe(area == null ? null : area.dim),
                safe(area == null ? null : area.name),
                safe(screen == null ? null : screen.name)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String serverKey() {
        var client = VideoPlayerClient.client;
        var localServer = client.getSingleplayerServer();
        if (localServer != null) {
            return "local:" + localServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return "remote:" + server.ip.strip().toLowerCase(Locale.ROOT);
    }
}
