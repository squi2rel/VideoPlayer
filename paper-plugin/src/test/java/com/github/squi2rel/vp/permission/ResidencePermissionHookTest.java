package com.github.squi2rel.vp.permission;

import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResidencePermissionHookTest {
    @Test
    void boundsAllowsSelectionEntirelyInWilderness() {
        World world = world();
        Player player = player(true, false, false, world);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> null,
                ignored -> List.of(),
                (residence, checkedPlayer, flag, fallback) -> false
        );

        assertTrue(hook.allowedBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(4, 5, 6), new Vector3f(1, 2, 3)));
    }

    @Test
    void boundsRejectsWildernessAndClaimMix() {
        World world = world();
        Player player = player(true, false, false, world);
        ClaimedResidence residence = residence("one", 0, 0, 0, 3, 3, 3);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, fallback) -> true
        );

        assertFalse(hook.allowedBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(8)));
    }

    @Test
    void boundsRejectsFullyEnclosedSubzone() {
        World world = world();
        Player player = player(true, false, false, world);
        ClaimedResidence child = residence("child", 3, 3, 3, 6, 6, 6);
        ClaimedResidence parent = residence("parent", 0, 0, 0, 9, 9, 9, child);
        when(child.getParent()).thenReturn(parent);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> parent,
                ignored -> List.of(parent),
                (residence, checkedPlayer, flag, fallback) -> true
        );

        assertFalse(hook.allowedBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(10)));
    }

    @Test
    void boundsRejectsDifferentAdjacentClaimsWithoutWilderness() {
        World world = world();
        Player player = player(true, false, false, world);
        ClaimedResidence first = residence("first", 0, 0, 0, 3, 3, 3);
        ClaimedResidence second = residence("second", 4, 0, 0, 7, 3, 3);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> location.getX() < 4 ? first : second,
                ignored -> List.of(first, second),
                (residence, checkedPlayer, flag, fallback) -> true
        );

        assertFalse(hook.allowedBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(8, 4, 4)));
    }

    @Test
    void boundsUsesDeepestClaimWhenSelectionIsInsideSubzone() {
        World world = world();
        Player player = player(true, false, false, world);
        ClaimedResidence child = residence("child", 3, 3, 3, 6, 6, 6);
        ClaimedResidence parent = residence("parent", 0, 0, 0, 9, 9, 9, child);
        when(child.getParent()).thenReturn(parent);
        AtomicReference<ClaimedResidence> checked = new AtomicReference<>();
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> child,
                ignored -> List.of(parent),
                (residence, checkedPlayer, flag, fallback) -> {
                    checked.set(residence);
                    return true;
                }
        );

        assertTrue(hook.allowedBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(3), new Vector3f(7)));
        assertSame(child, checked.get());
    }

    @Test
    void areaContextRejectsInternalSubzoneInsteadOfUsingOnlyCenter() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        Player player = player(true, false, false, world);
        ClaimedResidence child = residence("child", 1, 1, 1, 2, 2, 2);
        ClaimedResidence parent = residence("parent", 0, 0, 0, 9, 9, 9, child);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player,
                location -> parent,
                ignored -> List.of(parent),
                (residence, checkedPlayer, flag, fallback) -> true
        );
        VideoPermissionContext context = new VideoPermissionContext(
                "world",
                "area",
                "screen",
                new VideoPermissionContext.Position(0, 0, 0),
                new VideoPermissionContext.Position(10, 10, 10),
                new VideoPermissionContext.Position(8, 8, 8)
        );

        assertFalse(hook.allowed(permissionPlayer(uuid, false), VideoPermissionAction.CREATE_SCREEN, context));
    }

    @Test
    void pointChecksAllowWildernessAndRejectOfflineOrApiFailure() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        Player online = player(true, false, false, world);
        Player offline = player(false, true, true, world);
        VideoPermissionPlayer permissionPlayer = permissionPlayer(uuid, false);
        ResidencePermissionHook wilderness = new ResidencePermissionHook(
                ignored -> online,
                location -> null,
                ignored -> List.of(),
                (residence, checkedPlayer, flag, fallback) -> false
        );
        ResidencePermissionHook unavailable = new ResidencePermissionHook(
                ignored -> online,
                location -> {
                    throw new IllegalStateException("unavailable");
                },
                ignored -> List.of(),
                (residence, checkedPlayer, flag, fallback) -> true
        );
        ResidencePermissionHook disconnected = new ResidencePermissionHook(
                ignored -> offline,
                location -> null,
                ignored -> List.of(),
                (residence, checkedPlayer, flag, fallback) -> true
        );

        assertTrue(wilderness.allowed(permissionPlayer, VideoPermissionAction.CREATE_SCREEN, VideoPermissionContext.global("world")));
        assertFalse(unavailable.allowed(permissionPlayer, VideoPermissionAction.PLAY, VideoPermissionContext.global("world")));
        assertFalse(disconnected.allowed(permissionPlayer, VideoPermissionAction.PLAY, VideoPermissionContext.global("world")));
    }

    @Test
    void administratorBypassesResidenceAndDefaultFlagsMatchPermissionDefaults() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        ClaimedResidence residence = residence("one");
        Player player = player(true, false, false, world);
        Player administrator = player(true, false, true, world);
        AtomicBoolean fallback = new AtomicBoolean();
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> {
                    fallback.set(defaultValue);
                    return defaultValue;
                }
        );
        ResidencePermissionHook adminHook = new ResidencePermissionHook(
                ignored -> administrator,
                location -> {
                    throw new IllegalStateException("must not query");
                },
                ignored -> List.of(),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> false
        );

        assertTrue(hook.allowed(permissionPlayer(uuid, false), VideoPermissionAction.AUTO_SYNC, VideoPermissionContext.global("world")));
        assertTrue(fallback.get());
        assertFalse(hook.allowed(permissionPlayer(uuid, false), VideoPermissionAction.CREATE_SCREEN, VideoPermissionContext.global("world")));
        assertFalse(fallback.get());
        assertTrue(adminHook.allowed(permissionPlayer(uuid, true), VideoPermissionAction.CREATE_AREA, VideoPermissionContext.global("world")));
    }

    @Test
    void administratorBypassesActionNodeBeforeAreaChecker() {
        World world = world();
        Player administrator = player(true, false, true, world);
        Player offlineAdministrator = player(false, true, true, world);
        VideoPermissions.setAreaChecker((permissionPlayer, action, context) -> false);
        try {
            assertTrue(VideoPermissions.allowed(
                    VideoPermissions.player(administrator),
                    VideoPermissionAction.CREATE_AREA,
                    new VideoPermissionContext("world", "area", null, null, null, null)
            ));
            assertFalse(VideoPermissions.allowed(
                    VideoPermissions.player(offlineAdministrator),
                    VideoPermissionAction.CREATE_AREA,
                    new VideoPermissionContext("world", "area", null, null, null, null)
            ));
        } finally {
            VideoPermissions.reset();
        }
    }

    private static ClaimedResidence residence(String name) {
        return mock(ClaimedResidence.class, name);
    }

    private static ClaimedResidence residence(
            String name,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            ClaimedResidence... children
    ) {
        ClaimedResidence residence = residence(name);
        CuboidArea area = mock(CuboidArea.class, name + "Area");
        when(area.getWorldName()).thenReturn("world");
        when(area.getLowVector()).thenReturn(new Vector(minX, minY, minZ));
        when(area.getHighVector()).thenReturn(new Vector(maxX, maxY, maxZ));
        when(residence.getWorldName()).thenReturn("world");
        when(residence.getAreaArray()).thenReturn(new CuboidArea[]{area});
        when(residence.getSubzones()).thenReturn(List.of(children));
        return residence;
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "world";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Player player(boolean online, boolean op, boolean admin, World world) {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online;
                    case "isOp" -> op;
                    case "hasPermission" -> admin && VideoPermissions.ADMIN.equals(args[0]);
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, 0.5, 0.5, 0.5);
                    case "getName" -> "player";
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static VideoPermissionPlayer permissionPlayer(UUID uuid, boolean administrator) {
        return new VideoPermissionPlayer() {
            @Override
            public UUID uuid() {
                return uuid;
            }

            @Override
            public String name() {
                return "player";
            }

            @Override
            public boolean opOrGameMaster() {
                return administrator;
            }
        };
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
