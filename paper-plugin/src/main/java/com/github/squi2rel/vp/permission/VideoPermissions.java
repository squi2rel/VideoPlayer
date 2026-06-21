package com.github.squi2rel.vp.permission;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

public final class VideoPermissions {
    public static final String ADMIN = "videoplayer.admin";
    private static final GlobalPermissionChecker ALLOW_GLOBAL = (player, action, context) -> true;
    private static final AreaPermissionChecker ALLOW_AREA = (player, action, context) -> true;

    private static volatile GlobalPermissionChecker globalChecker = ALLOW_GLOBAL;
    private static volatile AreaPermissionChecker areaChecker = ALLOW_AREA;

    private VideoPermissions() {
    }

    public static void setGlobalChecker(GlobalPermissionChecker checker) {
        globalChecker = Objects.requireNonNullElse(checker, ALLOW_GLOBAL);
    }

    public static void setAreaChecker(AreaPermissionChecker checker) {
        areaChecker = Objects.requireNonNullElse(checker, ALLOW_AREA);
    }

    public static void reset() {
        globalChecker = ALLOW_GLOBAL;
        areaChecker = ALLOW_AREA;
    }

    public static boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        if (player instanceof BukkitPermissionPlayer bukkit && !bukkit.hasAction(action)) return false;
        if (!globalChecker.allowed(player, action, safeContext)) return false;
        if (!safeContext.hasArea()) return true;
        return player.opOrGameMaster() || areaChecker.allowed(player, action, safeContext);
    }

    public static long mask(VideoPermissionPlayer player, VideoPermissionContext context) {
        long mask = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            if (allowed(player, action, context)) {
                mask |= action.bit();
            }
        }
        return mask;
    }

    public static VideoPermissionPlayer player(Player player) {
        return new BukkitPermissionPlayer(player);
    }

    private record BukkitPermissionPlayer(Player player) implements VideoPermissionPlayer {
        @Override
        public java.util.UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public boolean opOrGameMaster() {
            return player.isOp() || player.hasPermission(ADMIN);
        }

        boolean hasAction(VideoPermissionAction action) {
            return player.hasPermission("videoplayer.action." + action.name().toLowerCase(Locale.ROOT));
        }
    }
}
