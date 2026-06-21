package com.github.squi2rel.vp;

import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.video.VideoScreen;

import java.util.HashMap;
import java.util.Map;

public final class ClientPermissionCache {
    private static boolean globalKnown;
    private static long globalMask;
    private static final Map<String, Long> areaMasks = new HashMap<>();
    private static final Map<String, Map<String, Long>> screenMasks = new HashMap<>();

    private ClientPermissionCache() {
    }

    public static void clear() {
        globalKnown = false;
        globalMask = 0L;
        areaMasks.clear();
        screenMasks.clear();
    }

    public static void update(String areaName, String screenName, long allowedMask) {
        String area = normalize(areaName);
        String screen = normalize(screenName);
        if (area.isEmpty()) {
            globalKnown = true;
            globalMask = allowedMask;
            return;
        }
        if (screen.isEmpty()) {
            areaMasks.put(area, allowedMask);
            return;
        }
        screenMasks.computeIfAbsent(area, ignored -> new HashMap<>()).put(screen, allowedMask);
    }

    public static void removeArea(String areaName) {
        String area = normalize(areaName);
        areaMasks.remove(area);
        screenMasks.remove(area);
    }

    public static void removeScreen(String areaName, String screenName) {
        Map<String, Long> screens = screenMasks.get(normalize(areaName));
        if (screens != null) screens.remove(normalize(screenName));
    }

    public static boolean isDenied(VideoPermissionAction action, VideoScreen screen) {
        if (screen == null || screen.area == null) return false;
        return isDenied(action, screen.area.name, screen.name);
    }

    public static boolean isDenied(VideoPermissionAction action, String areaName, String screenName) {
        Long mask = knownMask(areaName, screenName);
        return mask != null && (mask & action.bit()) == 0L;
    }

    public static boolean allowedOrUnknown(VideoPermissionAction action, VideoScreen screen) {
        return !isDenied(action, screen);
    }

    public static boolean allowedOrUnknown(VideoPermissionAction action, String areaName, String screenName) {
        return !isDenied(action, areaName, screenName);
    }

    public static void setAllowed(VideoPermissionAction action, String areaName, String screenName, boolean allowed) {
        String area = normalize(areaName);
        String screen = normalize(screenName);
        Long current = knownMask(area, screen);
        long mask = current == null ? allKnownMask() : current;
        if (allowed) {
            mask |= action.bit();
        } else {
            mask &= ~action.bit();
        }
        update(area, screen, mask);
    }

    private static Long knownMask(String areaName, String screenName) {
        String area = normalize(areaName);
        String screen = normalize(screenName);
        if (!area.isEmpty() && !screen.isEmpty()) {
            Map<String, Long> screens = screenMasks.get(area);
            if (screens != null && screens.containsKey(screen)) return screens.get(screen);
        }
        if (!area.isEmpty() && areaMasks.containsKey(area)) {
            return areaMasks.get(area);
        }
        return globalKnown ? globalMask : null;
    }

    private static long allKnownMask() {
        long mask = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            mask |= action.bit();
        }
        return mask;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
