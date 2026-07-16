package com.github.squi2rel.vp.permission;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPermissionsResidenceControlTest {
    @AfterEach
    void resetPermissionCheckers() {
        VideoPermissions.reset();
    }

    @Test
    void residenceControlledActionIgnoresBukkitNodeAndGlobalChecker() {
        Player player = player(false, false, Set.of());
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);
        VideoPermissions.setAreaChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);

        for (VideoPermissionAction action : List.of(
                VideoPermissionAction.FORCE_SKIP,
                VideoPermissionAction.SET_SKIP_PERCENT,
                VideoPermissionAction.CREATE_AREA,
                VideoPermissionAction.REMOVE_AREA,
                VideoPermissionAction.CREATE_SCREEN,
                VideoPermissionAction.REMOVE_SCREEN,
                VideoPermissionAction.UPDATE_SCREEN,
                VideoPermissionAction.SET_UV,
                VideoPermissionAction.SET_SCALE,
                VideoPermissionAction.SET_METADATA,
                VideoPermissionAction.SET_IDLE_PLAY
        )) {
            assertTrue(VideoPermissions.allowed(
                    VideoPermissions.player(player),
                    action,
                    areaContext()
            ), action.name());
        }
    }

    @Test
    void residenceControlledActionStillRequiresResidenceAreaPermission() {
        Player player = player(false, false, Set.of());
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);
        VideoPermissions.setAreaChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.SET_METADATA,
                areaContext()
        ));
    }

    @Test
    void ordinaryActionKeepsGlobalPermissionGate() {
        Player player = player(false, false, Set.of("videoplayer.action.play"));
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);
        VideoPermissions.setAreaChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.PLAY,
                areaContext()
        ));
    }

    @Test
    void controlledActionIsAllowedInWildernessWithoutResidenceCheckerApproval() {
        Player player = player(false, false, Set.of());
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);
        VideoPermissions.setAreaChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);

        assertTrue(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.REMOVE_SCREEN,
                VideoPermissionContext.global("world")
        ));
    }

    private static VideoPermissionContext areaContext() {
        return new VideoPermissionContext("world", "area", "screen", null, null, null);
    }

    private static Player player(boolean op, boolean admin, Set<String> permissions) {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> true;
                    case "isOp" -> op;
                    case "hasPermission" -> admin && VideoPermissions.ADMIN.equals(args[0])
                            || permissions.contains(args[0]);
                    case "getUniqueId" -> uuid;
                    case "getName" -> "player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
