package com.github.squi2rel.vp.permission;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

import java.util.Locale;
import java.util.Set;

final class ResidenceFlagRefreshFilter {
    private static final Set<String> VIDEO_FLAGS = Set.copyOf(VideoPermissionNodes.residenceFlags());
    private final ResidenceReflection.Handle getFlag;

    ResidenceFlagRefreshFilter(Class<? extends Event> eventType) {
        ResidenceReflection.Handle accessor;
        try {
            accessor = ResidenceReflection.virtualMethod(eventType, "getFlag", String.class);
        } catch (RuntimeException error) {
            accessor = null;
        }
        getFlag = accessor;
    }

    boolean shouldRefresh(Event event) {
        if (event instanceof Cancellable cancellable && cancellable.isCancelled()) return false;
        if (getFlag == null) return true;
        try {
            return affectsPermissions((String) ResidenceReflection.invoke(getFlag, event));
        } catch (RuntimeException error) {
            return true;
        }
    }

    static boolean affectsPermissions(String flag) {
        if (flag == null || flag.isBlank()) return true;
        String normalized = flag.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("admin") || VIDEO_FLAGS.contains(normalized);
    }
}
