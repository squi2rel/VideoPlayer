package com.github.squi2rel.vp.permission;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidencePermissionHookTest {
    @Test
    void completeMaskTraversesLargeClaimCollectionOnlyOnce() {
        Player player = player(true, false, false, world());
        AtomicInteger lookups = new AtomicInteger();
        List<ResidencePermissionHook.Claim> claims = new ArrayList<>();
        claims.add(residence("selected", 0, 0, 0, 9, 9, 9));
        for (int i = 1; i <= 2000; i++) claims.add(residence("other" + i, i * 20, 0, 0, i * 20 + 9, 9, 9));
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player, location -> null, ignored -> {
                    lookups.incrementAndGet();
                    return claims;
                }, (claim, checkedPlayer, flag, fallback) -> flag.equals("videoplayer.action.play"));
        VideoPermissionPlayer permissionPlayer = permissionPlayer(player.getUniqueId(), false);
        VideoPermissionContext context = VideoPermissionContext.bounds("world", "area", new Vector3f(1), new Vector3f(8));
        long expected = 0L;
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            if (hook.resolve(permissionPlayer, action, context) == AreaPermissionDecision.ALLOW) expected |= action.bit();
        }
        lookups.set(0);
        VideoPermissions.setAreaResolver(hook);
        try {
            assertEquals(expected, VideoPermissions.mask(permissionPlayer, context));
            assertEquals(1, lookups.get());
        } finally {
            VideoPermissions.reset();
        }
    }

    @Test
    void preparedPointReusesClaimButDoesNotCacheFlagValuesAcrossMasks() {
        Player player = player(true, false, false, world());
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<Boolean> flag = new AtomicReference<>(true);
        TestClaim claim = residence("point");
        ResidencePermissionHook hook = new ResidencePermissionHook(ignored -> player, location -> {
            lookups.incrementAndGet();
            return claim;
        }, ignored -> List.of(), (residence, checkedPlayer, name, fallback) -> flag.get());
        VideoPermissions.setAreaResolver(hook);
        try {
            VideoPermissionPlayer permissionPlayer = permissionPlayer(player.getUniqueId(), false);
            assertTrue(VideoPermissions.mask(permissionPlayer, pointContext()) != 0L);
            assertEquals(1, lookups.get());
            flag.set(false);
            assertEquals(0L, VideoPermissions.mask(permissionPlayer, pointContext()));
            assertEquals(2, lookups.get());
        } finally {
            VideoPermissions.reset();
        }
    }

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

        assertEquals(
                AreaPermissionDecision.NOT_APPLICABLE,
                hook.resolveBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(4, 5, 6), new Vector3f(1, 2, 3))
        );
    }

    @Test
    void boundsRejectsWildernessAndClaimMix() {
        World world = world();
        Player player = player(true, false, false, world);
        ResidencePermissionHook.Claim residence = residence("one", 0, 0, 0, 3, 3, 3);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, fallback) -> true
        );

        assertEquals(
                AreaPermissionDecision.DENY,
                hook.resolveBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(8))
        );
    }

    @Test
    void boundsRejectsFullyEnclosedSubzone() {
        World world = world();
        Player player = player(true, false, false, world);
        TestClaim child = residence("child", 3, 3, 3, 6, 6, 6);
        ResidencePermissionHook.Claim parent = residence("parent", 0, 0, 0, 9, 9, 9, child);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> parent,
                ignored -> List.of(parent),
                (residence, checkedPlayer, flag, fallback) -> true
        );

        assertEquals(
                AreaPermissionDecision.DENY,
                hook.resolveBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(10))
        );
    }

    @Test
    void boundsRejectsDifferentAdjacentClaimsWithoutWilderness() {
        World world = world();
        Player player = player(true, false, false, world);
        ResidencePermissionHook.Claim first = residence("first", 0, 0, 0, 3, 3, 3);
        ResidencePermissionHook.Claim second = residence("second", 4, 0, 0, 7, 3, 3);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> location.getX() < 4 ? first : second,
                ignored -> List.of(first, second),
                (checkedResidence, checkedPlayer, flag, fallback) -> true
        );

        assertEquals(
                AreaPermissionDecision.DENY,
                hook.resolveBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(), new Vector3f(8, 4, 4))
        );
    }

    @Test
    void boundsUsesDeepestClaimWhenSelectionIsInsideSubzone() {
        World world = world();
        Player player = player(true, false, false, world);
        TestClaim child = residence("child", 3, 3, 3, 6, 6, 6);
        ResidencePermissionHook.Claim parent = residence("parent", 0, 0, 0, 9, 9, 9, child);
        AtomicReference<ResidencePermissionHook.Claim> checked = new AtomicReference<>();
        ResidencePermissionHook hook = new ResidencePermissionHook(
                uuid -> player,
                location -> child,
                ignored -> List.of(parent),
                (residence, checkedPlayer, flag, fallback) -> {
                    checked.set(residence);
                    return true;
                }
        );

        assertEquals(
                AreaPermissionDecision.ALLOW,
                hook.resolveBounds(player, VideoPermissionAction.CREATE_AREA, new Vector3f(3), new Vector3f(7))
        );
        assertSame(child, checked.get());
    }

    @Test
    void screenContextUsesAnchorInsteadOfAreaBounds() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        Player player = player(true, false, false, world);
        ResidencePermissionHook.Claim anchorClaim = residence("anchor");
        AtomicReference<Location> checkedLocation = new AtomicReference<>();
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player,
                location -> {
                    checkedLocation.set(location);
                    return anchorClaim;
                },
                ignored -> {
                    throw new IllegalStateException("bounds must not be queried");
                },
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

        assertEquals(
                AreaPermissionDecision.ALLOW,
                hook.resolve(permissionPlayer(uuid, false), VideoPermissionAction.CREATE_SCREEN, context)
        );
        assertEquals(8.0, checkedLocation.get().getX());
        assertEquals(8.0, checkedLocation.get().getY());
        assertEquals(8.0, checkedLocation.get().getZ());
    }

    @Test
    void namedScreenWithoutAnchorFailsClosedInsteadOfUsingAreaBounds() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        Player player = player(true, false, false, world);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player,
                location -> {
                    throw new IllegalStateException("point must not be queried");
                },
                ignored -> {
                    throw new IllegalStateException("bounds must not be queried");
                },
                (residence, checkedPlayer, flag, fallback) -> true
        );
        VideoPermissionContext context = new VideoPermissionContext(
                "world",
                "area",
                "screen",
                new VideoPermissionContext.Position(0, 0, 0),
                new VideoPermissionContext.Position(10, 10, 10),
                null
        );

        assertEquals(
                AreaPermissionDecision.DENY,
                hook.resolve(permissionPlayer(uuid, false), VideoPermissionAction.PLAY, context)
        );
    }

    @Test
    void deepestPermissionRespectsChildOverridesAndInheritanceConfiguration() {
        World world = world();
        Player player = player(true, false, false, world);
        TestClaim childAllow = residence("child-allow").withPermissions(effectivePermission(true, false, true));
        TestClaim childDeny = residence("child-deny").withPermissions(effectivePermission(false, true, true));
        TestClaim inheritedAllow = residence("inherited-allow").withPermissions(effectivePermission(null, true, true));
        TestClaim inheritanceDisabled = residence("inheritance-disabled").withPermissions(effectivePermission(null, true, false));

        assertEquals(true, ResidencePermissionHook.permission(childAllow, player, "flag", false));
        assertEquals(false, ResidencePermissionHook.permission(childDeny, player, "flag", true));
        assertEquals(true, ResidencePermissionHook.permission(inheritedAllow, player, "flag", false));
        assertNull(ResidencePermissionHook.permission(inheritanceDisabled, player, "flag", false));
        assertEquals(true, ResidencePermissionHook.permission(inheritanceDisabled, player, "flag", true));
    }

    @Test
    void pointChecksFallBackInWildernessRejectOfflineAndPropagateApiFailure() {
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

        assertEquals(
                AreaPermissionDecision.NOT_APPLICABLE,
                wilderness.resolve(permissionPlayer, VideoPermissionAction.CREATE_SCREEN, pointContext())
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> unavailable.resolve(permissionPlayer, VideoPermissionAction.PLAY, pointContext())
        );
        assertEquals(
                AreaPermissionDecision.DENY,
                disconnected.resolve(permissionPlayer, VideoPermissionAction.PLAY, pointContext())
        );
    }

    @Test
    void administratorBypassesResidenceAndDefaultFlagsMatchPermissionDefaults() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        ResidencePermissionHook.Claim residence = residence("one");
        Player player = player(true, false, false, world);
        Player administrator = player(true, false, true, world);
        ResidencePermissionHook hook = new ResidencePermissionHook(
                ignored -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> null
        );
        ResidencePermissionHook adminHook = new ResidencePermissionHook(
                ignored -> administrator,
                location -> {
                    throw new IllegalStateException("must not query");
                },
                ignored -> List.of(),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> false
        );

        assertEquals(
                AreaPermissionDecision.ALLOW,
                hook.resolve(permissionPlayer(uuid, false), VideoPermissionAction.AUTO_SYNC, pointContext())
        );
        assertEquals(
                AreaPermissionDecision.ALLOW,
                hook.resolve(permissionPlayer(uuid, false), VideoPermissionAction.UPDATE_SCREEN, pointContext())
        );
        assertEquals(
                AreaPermissionDecision.DENY,
                hook.resolve(permissionPlayer(uuid, false), VideoPermissionAction.CREATE_SCREEN, pointContext())
        );
        UUID playerId = player.getUniqueId();
        TestClaim owned = residence("owned").withOwner(playerId);
        ResidencePermissionHook ownerHook = new ResidencePermissionHook(
                ignored -> player,
                location -> owned,
                ignored -> List.of(owned),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> null
        );
        assertEquals(
                AreaPermissionDecision.ALLOW,
                ownerHook.resolve(permissionPlayer(playerId, false), VideoPermissionAction.CREATE_SCREEN, pointContext())
        );
        TestClaim padd = residence("padd").withPermissions((checkedPlayer, flag, defaultValue) -> "admin".equals(flag));
        ResidencePermissionHook paddHook = new ResidencePermissionHook(
                ignored -> player,
                location -> padd,
                ignored -> List.of(padd),
                (checkedResidence, checkedPlayer, flag, defaultValue) -> null
        );
        assertEquals(
                AreaPermissionDecision.ALLOW,
                paddHook.resolve(permissionPlayer(playerId, false), VideoPermissionAction.REMOVE_AREA, pointContext())
        );
        assertEquals(
                AreaPermissionDecision.ALLOW,
                adminHook.resolve(permissionPlayer(uuid, true), VideoPermissionAction.CREATE_AREA, VideoPermissionContext.global("world"))
        );
    }

    @Test
    void currentResidenceFlagOverridesLegacyAndLegacyIsFallback() {
        World world = world();
        UUID uuid = UUID.randomUUID();
        Player player = player(true, false, false, world);
        ResidencePermissionHook.Claim residence = residence("one");
        ArrayList<String> checked = new ArrayList<>();
        ResidencePermissionHook legacyAllowed = new ResidencePermissionHook(
                ignored -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, fallback) -> {
                    checked.add(flag);
                    return flag.equals("videoplayer.screen.request") ? true : null;
                }
        );

        assertEquals(
                AreaPermissionDecision.ALLOW,
                legacyAllowed.resolve(permissionPlayer(uuid, false), VideoPermissionAction.PLAY, pointContext())
        );
        assertEquals(List.of("videoplayer.action.play", "videoplayer.screen.request"), checked);

        checked.clear();
        ResidencePermissionHook currentDenied = new ResidencePermissionHook(
                ignored -> player,
                location -> residence,
                ignored -> List.of(residence),
                (checkedResidence, checkedPlayer, flag, fallback) -> {
                    checked.add(flag);
                    return flag.equals("videoplayer.action.play") ? false : true;
                }
        );

        assertEquals(
                AreaPermissionDecision.DENY,
                currentDenied.resolve(permissionPlayer(uuid, false), VideoPermissionAction.PLAY, pointContext())
        );
        assertEquals(List.of("videoplayer.action.play"), checked);
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

    private static TestClaim residence(String name) {
        return new TestClaim(name, List.of(), List.of());
    }

    private static ResidencePermissionHook.Permissions effectivePermission(Boolean local, Boolean parent, boolean inherit) {
        return (player, flag, defaultValue) -> {
            if (local != null) return local;
            if (inherit && parent != null) return parent;
            return defaultValue;
        };
    }

    private static VideoPermissionContext pointContext() {
        return new VideoPermissionContext(
                "world",
                "area",
                "screen",
                null,
                null,
                new VideoPermissionContext.Position(1, 2, 3)
        );
    }

    private static TestClaim residence(
            String name,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            TestClaim... children
    ) {
        return new TestClaim(
                name,
                List.of(new ResidencePermissionHook.Area("world", new Vector(minX, minY, minZ), new Vector(maxX, maxY, maxZ))),
                List.of(children)
        );
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

    private static final class TestClaim implements ResidencePermissionHook.Claim {
        private final String name;
        private final List<ResidencePermissionHook.Area> areas;
        private final List<ResidencePermissionHook.Claim> children;
        private ResidencePermissionHook.Claim parent;
        private ResidencePermissionHook.Permissions permissions = (player, flag, defaultValue) -> false;
        private UUID ownerUUID;

        private TestClaim(String name, List<ResidencePermissionHook.Area> areas, List<TestClaim> children) {
            this.name = name;
            this.areas = List.copyOf(areas);
            this.children = new ArrayList<>(children);
            for (TestClaim child : children) child.parent = this;
        }

        @Override
        public UUID ownerUUID() {
            return ownerUUID;
        }

        private TestClaim withOwner(UUID owner) {
            this.ownerUUID = owner;
            return this;
        }

        @Override
        public ResidencePermissionHook.Claim parent() {
            return parent;
        }

        @Override
        public List<ResidencePermissionHook.Claim> subzones() {
            return List.copyOf(children);
        }

        @Override
        public List<ResidencePermissionHook.Area> areas() {
            return areas;
        }

        @Override
        public String worldName() {
            return "world";
        }

        @Override
        public ResidencePermissionHook.Claim subzoneByLoc(Location location) {
            for (ResidencePermissionHook.Claim child : children) {
                if (child instanceof TestClaim test && test.contains(location)) return child;
            }
            return null;
        }

        @Override
        public ResidencePermissionHook.Permissions permissions() {
            return permissions;
        }

        private TestClaim withPermissions(ResidencePermissionHook.Permissions permissions) {
            this.permissions = permissions;
            return this;
        }

        private boolean contains(Location location) {
            if (areas.isEmpty()) return false;
            ResidencePermissionHook.Area area = areas.getFirst();
            Vector low = area.low();
            Vector high = area.high();
            return location.getX() >= low.getX() && location.getX() <= high.getX()
                    && location.getY() >= low.getY() && location.getY() <= high.getY()
                    && location.getZ() >= low.getZ() && location.getZ() <= high.getZ();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
