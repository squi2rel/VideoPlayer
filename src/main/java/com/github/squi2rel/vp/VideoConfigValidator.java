package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.video.VideoSourceGraph;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;

public final class VideoConfigValidator {
    private VideoConfigValidator() {
    }

    public static void validate(ServerConfig config) {
        if (config == null) throw new IllegalArgumentException("VideoPlayer config is missing");
        if (config.dataVersion > ServerConfig.CURRENT_DATA_VERSION) {
            throw new IllegalArgumentException("VideoPlayer config data version " + config.dataVersion + " is newer than supported version " + ServerConfig.CURRENT_DATA_VERSION);
        }
        if (config.remoteControlName == null) config.remoteControlName = "minecraft:iron_ingot";
        if (config.remoteControlName.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("VideoPlayer remote control item name is invalid");
        }
        if (!Float.isFinite(config.remoteControlId)
                || !Float.isFinite(config.remoteControlRange) || config.remoteControlRange < 0f || config.remoteControlRange > 1024f
                || !Float.isFinite(config.noControlRange) || config.noControlRange < 0f || config.noControlRange > 1024f) {
            throw new IllegalArgumentException("VideoPlayer remote control configuration is invalid");
        }
        if (config.areas == null) config.areas = new ArrayList<>();
        if (config.areas.size() > VideoArea.MAX_AREAS_PER_WORLD) {
            throw new IllegalArgumentException("VideoPlayer world contains more than " + VideoArea.MAX_AREAS_PER_WORLD + " areas");
        }
        HashSet<String> names = new HashSet<>();
        for (VideoArea area : config.areas) {
            validateArea(area);
            if (!names.add(area.name)) throw new IllegalArgumentException("VideoPlayer area name is duplicated: " + area.name);
        }
    }

    public static void validateArea(VideoArea area) {
        if (area == null || area.min == null || area.max == null || !VideoScreen.validName(area.name)) {
            throw new IllegalArgumentException("VideoPlayer area is missing valid required fields");
        }
        if (!VideoArea.validSize(area.min, area.max)) {
            throw new IllegalArgumentException("VideoPlayer area exceeds the maximum axis length of " + VideoArea.MAX_AXIS_LENGTH);
        }
        if (area.screens == null) area.screens = new ArrayList<>();
        if (area.screens.size() > VideoArea.MAX_SCREENS) {
            throw new IllegalArgumentException("VideoPlayer area contains more than " + VideoArea.MAX_SCREENS + " screens");
        }
        HashSet<String> screenNames = new HashSet<>();
        for (VideoScreen screen : area.screens) {
            if (screen == null || !VideoScreen.validName(screen.name)) {
                throw new IllegalArgumentException("VideoPlayer screen is missing a valid name");
            }
            if (!screenNames.add(screen.name)) {
                throw new IllegalArgumentException("VideoPlayer screen name is duplicated: " + screen.name);
            }
            validateScreenState(area, screen);
            String source = screen.source == null ? "" : screen.source;
            if (!source.isEmpty() && !VideoScreen.validName(source)) {
                throw new IllegalArgumentException("VideoPlayer source screen name exceeds protocol limits");
            }
            if (!VideoScreen.validIdlePlayConfig(screen.idlePlayUrls)) {
                throw new IllegalArgumentException("VideoPlayer IdlePlay configuration exceeds protocol limits");
            }
            if (screen.metadata == null) screen.metadata = new ScreenMetadata();
            screen.metadata.ensureValid();
            if (screen.metadata.size() > ScreenMetadata.MAX_ENTRIES) {
                throw new IllegalArgumentException("VideoPlayer screen contains more than " + ScreenMetadata.MAX_ENTRIES + " metadata entries");
            }
        }
        for (VideoScreen screen : area.screens) {
            String source = screen.source == null ? "" : screen.source;
            if (!source.isEmpty() && (source.equals(screen.name) || !screenNames.contains(source))) {
                throw new IllegalArgumentException("VideoPlayer source screen does not reference another screen in the same area");
            }
        }
        if (!VideoSourceGraph.isAcyclic(area.screens)) {
            throw new IllegalArgumentException("VideoPlayer source screen graph contains a cycle");
        }
    }

    private static void validateScreenState(VideoArea area, VideoScreen screen) {
        if (screen.vertices == null || screen.vertices.size() > ScreenGeometry.MAX_VERTICES) {
            throw new IllegalArgumentException("VideoPlayer screen contains too many vertices");
        }
        ScreenSurface surface = screen.surface == null ? ScreenSurface.FLAT : screen.surface;
        if (surface != ScreenSurface.SPHERE_360 && screen.vertices.size() < ScreenGeometry.MIN_VERTICES) {
            throw new IllegalArgumentException("VideoPlayer flat screen must contain at least three vertices");
        }
        for (Vector3f vertex : screen.vertices) {
            if (vertex == null || !finite(vertex)) {
                throw new IllegalArgumentException("VideoPlayer screen contains an invalid vertex");
            }
            if (vertex.x < area.min.x || vertex.x > area.max.x
                    || vertex.y < area.min.y || vertex.y > area.max.y
                    || vertex.z < area.min.z || vertex.z > area.max.z) {
                throw new IllegalArgumentException("VideoPlayer screen vertex is outside its area");
            }
        }
        if (surface != ScreenSurface.SPHERE_360) {
            try {
                ScreenGeometry.create(screen.vertices);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("VideoPlayer screen geometry is invalid", error);
            }
        }
        if (!finite(screen.u1, screen.v1, screen.u2, screen.v2)) {
            throw new IllegalArgumentException("VideoPlayer screen UV values must be finite");
        }
        if (!finite(screen.scaleX, screen.scaleY)
                || screen.scaleX < 0.0625f || screen.scaleX > 16f
                || screen.scaleY < 0.0625f || screen.scaleY > 16f) {
            throw new IllegalArgumentException("VideoPlayer screen scale is invalid");
        }
        if (!Float.isFinite(screen.skipPercent) || screen.skipPercent < 0f || screen.skipPercent > 1f) {
            throw new IllegalArgumentException("VideoPlayer screen skip percent is invalid");
        }
        if (screen.sphereCenter == null || !finite(screen.sphereCenter)) {
            throw new IllegalArgumentException("VideoPlayer sphere center is invalid");
        }
        if (!Float.isFinite(screen.sphereRadius) || screen.sphereRadius <= 0f || screen.sphereRadius > 1024f) {
            throw new IllegalArgumentException("VideoPlayer sphere radius is invalid");
        }
        if (screen.sphereLat < VideoScreen.MIN_SPHERE_SEGMENTS || screen.sphereLat > VideoScreen.MAX_SPHERE_SEGMENTS
                || screen.sphereLon < VideoScreen.MIN_SPHERE_SEGMENTS || screen.sphereLon > VideoScreen.MAX_SPHERE_SEGMENTS) {
            throw new IllegalArgumentException("VideoPlayer sphere segments are invalid");
        }
    }

    private static boolean finite(Vector3f vector) {
        return vector != null && finite(vector.x, vector.y, vector.z);
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }
}
