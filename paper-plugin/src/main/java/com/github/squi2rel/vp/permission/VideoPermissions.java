package com.github.squi2rel.vp.permission;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

public final class VideoPermissions {
    public static final String ADMIN = "videoplayer.admin";
    private static final GlobalPermissionChecker ALLOW_GLOBAL = (player, action, context) -> true;
    private static final AreaPermissionResolver NO_AREA = (player, action, context) -> AreaPermissionDecision.NOT_APPLICABLE;

    private static volatile GlobalPermissionChecker globalChecker = ALLOW_GLOBAL;
    private static volatile AreaPermissionResolver areaResolver = NO_AREA;

    private VideoPermissions() {
    }

    public static void setGlobalChecker(GlobalPermissionChecker checker) {
        globalChecker = Objects.requireNonNullElse(checker, ALLOW_GLOBAL);
    }

    public static void setAreaChecker(AreaPermissionChecker checker) {
        if (checker == null) {
            areaResolver = NO_AREA;
            return;
        }
        areaResolver = (player, action, context) -> {
            if (context == null || !context.hasArea()) return AreaPermissionDecision.NOT_APPLICABLE;
            return checker.allowed(player, action, context) ? AreaPermissionDecision.ALLOW : AreaPermissionDecision.DENY;
        };
    }

    public static void setAreaResolver(AreaPermissionResolver resolver) {
        areaResolver = Objects.requireNonNullElse(resolver, NO_AREA);
    }

    public static void reset() {
        globalChecker = ALLOW_GLOBAL;
        areaResolver = NO_AREA;
    }

    public static boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
        return allowed(player, action, context, areaResolver);
    }

    private static boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context,
                                   AreaPermissionResolver resolver) {
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        if (player instanceof BukkitPermissionPlayer bukkit && !bukkit.online()) return false;
        if (player.opOrGameMaster()) return true;
        if (action == VideoPermissionAction.SEEK
                && player instanceof BukkitPermissionPlayer bukkit
                && !bukkit.hasAction(action)) return false;
        AreaPermissionDecision areaDecision = resolver.resolve(player, action, safeContext);
        if (areaDecision == AreaPermissionDecision.ALLOW) return true;
        if (areaDecision == null || areaDecision == AreaPermissionDecision.DENY) return false;
        if (player instanceof BukkitPermissionPlayer bukkit && !bukkit.hasAction(action)) return false;
        return globalChecker.allowed(player, action, safeContext);
    }

    public static long mask(VideoPermissionPlayer player, VideoPermissionContext context) {
        if (player instanceof BukkitPermissionPlayer bukkit && !bukkit.online()) return 0L;
        if (player.opOrGameMaster()) {
            long mask = 0L;
            for (VideoPermissionAction action : VideoPermissionAction.values()) mask |= action.bit();
            return mask;
        }
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        AreaPermissionResolver prepared = areaResolver.prepare(player, safeContext);
        long mask = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            if (allowed(player, action, safeContext, prepared)) {
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

        boolean online() {
            return player.isOnline();
        }
    }
}
