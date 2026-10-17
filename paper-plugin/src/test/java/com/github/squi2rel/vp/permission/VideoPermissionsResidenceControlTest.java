package com.github.squi2rel.vp.permission;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoPermissionsResidenceControlTest {
    @Test
    void preparedMaskPreservesEverySingleActionDecision() {
        Player player = player(false, false, Set.of("videoplayer.action.play", "videoplayer.action.remove_screen"));
        VideoPermissions.setAreaResolver((ignored, action, context) -> switch (action) {
            case SEEK, UPDATE_SCREEN -> AreaPermissionDecision.ALLOW;
            case PLAY -> AreaPermissionDecision.DENY;
            default -> AreaPermissionDecision.NOT_APPLICABLE;
        });
        long expected = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            if (VideoPermissions.allowed(VideoPermissions.player(player), action, screenContext())) expected |= action.bit();
        }
        assertEquals(expected, VideoPermissions.mask(VideoPermissions.player(player), screenContext()));
    }

    @Test
    void maskDoesNotPrepareResolverForAdministratorsOrOfflinePlayers() {
        VideoPermissions.setAreaResolver(new AreaPermissionResolver() {
            @Override
            public AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
                throw new AssertionError("resolver must not run");
            }

            @Override
            public AreaPermissionResolver prepare(VideoPermissionPlayer player, VideoPermissionContext context) {
                throw new AssertionError("prepare must not run");
            }
        });
        assertEquals(0L, VideoPermissions.mask(VideoPermissions.player(player(true, true, Set.of())), screenContext()));
        long allActions = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) allActions |= action.bit();
        assertEquals(allActions, VideoPermissions.mask(VideoPermissions.player(player(true, false, Set.of())), screenContext()));
    }

    @AfterEach
    void resetPermissionCheckers() {
        VideoPermissions.reset();
    }

    @Test
    void residenceAllowOverridesBukkitNodeAndGlobalChecker() {
        Player player = player(false, false, Set.of());
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> AreaPermissionDecision.ALLOW);

        assertTrue(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.UPDATE_SCREEN,
                screenContext()
        ));
    }

    @Test
    void seekRequiresBukkitNodeBeforeResidenceAllow() {
        Player withoutNode = player(false, false, Set.of());
        Player withNode = player(false, false, Set.of("videoplayer.action.seek"));
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> AreaPermissionDecision.ALLOW);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(withoutNode),
                VideoPermissionAction.SEEK,
                screenContext()
        ));
        assertTrue(VideoPermissions.allowed(
                VideoPermissions.player(withNode),
                VideoPermissionAction.SEEK,
                screenContext()
        ));
    }

    @Test
    void residenceDenyOverridesBukkitNodeAndGlobalChecker() {
        Player player = player(false, false, Set.of("videoplayer.action.play"));
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> AreaPermissionDecision.DENY);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.PLAY,
                screenContext()
        ));
    }

    @Test
    void wildernessRequiresBukkitNodeAndGlobalChecker() {
        Player withNode = player(false, false, Set.of("videoplayer.action.remove_screen"));
        Player withoutNode = player(false, false, Set.of());
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> AreaPermissionDecision.NOT_APPLICABLE);
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);

        assertTrue(VideoPermissions.allowed(
                VideoPermissions.player(withNode),
                VideoPermissionAction.REMOVE_SCREEN,
                screenContext()
        ));
        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(withoutNode),
                VideoPermissionAction.REMOVE_SCREEN,
                screenContext()
        ));

        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(withNode),
                VideoPermissionAction.REMOVE_SCREEN,
                screenContext()
        ));
    }

    @Test
    void legacyAreaCheckerStillControlsNamedContexts() {
        Player player = player(false, false, Set.of("videoplayer.action.set_metadata"));
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);
        VideoPermissions.setAreaChecker((ignoredPlayer, ignoredAction, ignoredContext) -> false);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.SET_METADATA,
                screenContext()
        ));
    }

    @Test
    void invalidResolverResultFailsClosed() {
        Player player = player(false, false, Set.of("videoplayer.action.play"));
        VideoPermissions.setGlobalChecker((ignoredPlayer, ignoredAction, ignoredContext) -> true);
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> null);

        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(player),
                VideoPermissionAction.PLAY,
                screenContext()
        ));
    }

    @Test
    void administratorBypassesResolverButOfflineAdministratorDoesNot() {
        Player administrator = player(true, false, Set.of());
        Player offlineAdministrator = player(true, true, Set.of());
        VideoPermissions.setAreaResolver((ignoredPlayer, ignoredAction, ignoredContext) -> AreaPermissionDecision.DENY);

        assertTrue(VideoPermissions.allowed(
                VideoPermissions.player(administrator),
                VideoPermissionAction.CREATE_AREA,
                screenContext()
        ));
        assertFalse(VideoPermissions.allowed(
                VideoPermissions.player(offlineAdministrator),
                VideoPermissionAction.CREATE_AREA,
                screenContext()
        ));
    }

    private static VideoPermissionContext screenContext() {
        return new VideoPermissionContext(
                "world",
                "area",
                "screen",
                new VideoPermissionContext.Position(0, 0, 0),
                new VideoPermissionContext.Position(10, 10, 10),
                new VideoPermissionContext.Position(5, 5, 5)
        );
    }

    private static Player player(boolean op, boolean offline, Set<String> permissions) {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> !offline;
                    case "isOp" -> op;
                    case "hasPermission" -> permissions.contains(args[0]);
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
