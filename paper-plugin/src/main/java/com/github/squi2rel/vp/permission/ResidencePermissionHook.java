package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.VideoPlayerMain;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class ResidencePermissionHook implements ResidencePermissionBridge.Delegate {
    private static final int MAX_COVERAGE_FRAGMENTS = 65_536;
    private static final Set<VideoPermissionAction> DEFAULT_ALLOWED = EnumSet.of(
            VideoPermissionAction.PLAY,
            VideoPermissionAction.SEEK,
            VideoPermissionAction.SYNC,
            VideoPermissionAction.VOTE_SKIP,
            VideoPermissionAction.FORCE_SKIP,
            VideoPermissionAction.SET_SKIP_PERCENT,
            VideoPermissionAction.UPDATE_SCREEN,
            VideoPermissionAction.SET_UV,
            VideoPermissionAction.SET_SCALE,
            VideoPermissionAction.SET_METADATA,
            VideoPermissionAction.SET_IDLE_PLAY,
            VideoPermissionAction.AUTO_SYNC,
            VideoPermissionAction.OPEN_MENU
    );
    private static final Set<VideoPermissionAction> STRUCTURE_ACTIONS = EnumSet.of(
            VideoPermissionAction.CREATE_AREA,
            VideoPermissionAction.REMOVE_AREA,
            VideoPermissionAction.CREATE_SCREEN,
            VideoPermissionAction.REMOVE_SCREEN
    );
    private final Function<UUID, Player> playerLookup;
    private final Function<Location, Claim> residenceLookup;
    private final Function<World, Collection<Claim>> claimLookup;
    private final FlagChecker flagChecker;

    ResidencePermissionHook() {
        ReflectionResidenceApi residence = ReflectionResidenceApi.load();
        residence.registerFlags();
        playerLookup = Bukkit::getPlayer;
        residenceLookup = residence::deepest;
        claimLookup = residence::claims;
        flagChecker = residence::permission;
        VideoPlayerMain.LOGGER.info("Registered {} VideoPlayer Residence flags", VideoPermissionNodes.residenceFlags().size());
    }

    ResidencePermissionHook(
            Function<UUID, Player> playerLookup,
            Function<Location, Claim> residenceLookup,
            Function<World, Collection<Claim>> claimLookup,
            FlagChecker flagChecker
    ) {
        this.playerLookup = Objects.requireNonNull(playerLookup);
        this.residenceLookup = Objects.requireNonNull(residenceLookup);
        this.claimLookup = Objects.requireNonNull(claimLookup);
        this.flagChecker = Objects.requireNonNull(flagChecker);
    }

    @Override
    public AreaPermissionDecision resolve(VideoPermissionPlayer permissionPlayer, VideoPermissionAction action, VideoPermissionContext context) {
        return prepare(permissionPlayer, context).resolve(permissionPlayer, action, context);
    }

    @Override
    public AreaPermissionResolver prepare(VideoPermissionPlayer permissionPlayer, VideoPermissionContext context) {
        if (permissionPlayer == null || permissionPlayer.uuid() == null) return fixed(AreaPermissionDecision.DENY);
        Player player = playerLookup.apply(permissionPlayer.uuid());
        if (player == null || !player.isOnline()) return fixed(AreaPermissionDecision.DENY);
        if (player.isOp() || player.hasPermission(VideoPermissions.ADMIN)) return fixed(AreaPermissionDecision.ALLOW);
        ClaimSelection selection = selection(player, context);
        if (selection.claim() == null) return fixed(selection.decision());
        return (ignoredPlayer, action, ignoredContext) -> resolveSelected(selection, player, action, DEFAULT_ALLOWED.contains(action));
    }

    private ClaimSelection selection(Player player, VideoPermissionContext context) {
        if (context != null && context.screenName() != null && context.hasScreenBounds()) {
            VideoPermissionContext.Position min = context.screenMin();
            VideoPermissionContext.Position max = context.screenMax();
            return selectBounds(
                    player,
                    new Vector3f((float) min.x(), (float) min.y(), (float) min.z()),
                    new Vector3f((float) max.x(), (float) max.y(), (float) max.z())
            );
        }
        if (context != null && context.anchor() != null) {
            Claim residence = residenceLookup.apply(location(player, context.anchor()));
            return new ClaimSelection(AreaPermissionDecision.NOT_APPLICABLE, residence);
        }
        if (context != null && context.screenName() != null && !context.screenName().isBlank()) {
            return new ClaimSelection(AreaPermissionDecision.DENY, null);
        }
        if (context != null && context.hasBounds()) {
            VideoPermissionContext.Position min = context.areaMin();
            VideoPermissionContext.Position max = context.areaMax();
            return selectBounds(
                    player,
                    new Vector3f((float) min.x(), (float) min.y(), (float) min.z()),
                    new Vector3f((float) max.x(), (float) max.y(), (float) max.z())
            );
        }
        return new ClaimSelection(AreaPermissionDecision.NOT_APPLICABLE, null);
    }

    @Override
    public AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        if (player == null || !player.isOnline()) return AreaPermissionDecision.DENY;
        if (player.isOp() || player.hasPermission(VideoPermissions.ADMIN)) return AreaPermissionDecision.ALLOW;
        return resolveBounds(player, action, first, second, false);
    }

    boolean allowed(VideoPermissionPlayer permissionPlayer, VideoPermissionAction action, VideoPermissionContext context) {
        return resolve(permissionPlayer, action, context) != AreaPermissionDecision.DENY;
    }

    boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        return resolveBounds(player, action, first, second) != AreaPermissionDecision.DENY;
    }

    @Override
    public ResidencePermissionBridge.Coverage coverage(VideoPermissionPlayer permissionPlayer, VideoPermissionContext context) {
        if (permissionPlayer == null || permissionPlayer.uuid() == null || context == null) {
            return ResidencePermissionBridge.Coverage.UNKNOWN;
        }
        Player player = playerLookup.apply(permissionPlayer.uuid());
        if (player == null || !player.isOnline()) return ResidencePermissionBridge.Coverage.UNKNOWN;
        if (context.screenName() != null && context.hasScreenBounds()) {
            VideoPermissionContext.Position min = context.screenMin();
            VideoPermissionContext.Position max = context.screenMax();
            return coverageBounds(
                    player,
                    new Vector3f((float) min.x(), (float) min.y(), (float) min.z()),
                    new Vector3f((float) max.x(), (float) max.y(), (float) max.z())
            );
        }
        if (context.anchor() != null) {
            Claim residence = residenceLookup.apply(location(player, context.anchor()));
            return residence == null
                    ? ResidencePermissionBridge.Coverage.WILDERNESS
                    : ResidencePermissionBridge.Coverage.PROTECTED;
        }
        if (context.hasBounds()) {
            VideoPermissionContext.Position min = context.areaMin();
            VideoPermissionContext.Position max = context.areaMax();
            return coverageBounds(
                    player,
                    new Vector3f((float) min.x(), (float) min.y(), (float) min.z()),
                    new Vector3f((float) max.x(), (float) max.y(), (float) max.z())
            );
        }
        return ResidencePermissionBridge.Coverage.UNKNOWN;
    }

    @Override
    public ResidencePermissionBridge.Coverage coverageBounds(Player player, Vector3f first, Vector3f second) {
        return coverageBounds(player, first, second, false);
    }

    private AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second, boolean defaultAllowed) {
        return resolveSelected(selectBounds(player, first, second), player, action, defaultAllowed);
    }

    private ClaimSelection selectBounds(Player player, Vector3f first, Vector3f second) {
        BlockBox selection = BlockBox.selection(first, second);
        if (selection == null) return new ClaimSelection(AreaPermissionDecision.DENY, null);
        Collection<Claim> found = claimLookup.apply(player.getWorld());
        if (found == null) throw new IllegalStateException("Residence claim lookup returned null");
        ArrayList<Claim> roots = new ArrayList<>();
        Set<Claim> rootIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Claim residence : found) {
            if (residence != null && residence.parent() == null && rootIdentity.add(residence)) roots.add(residence);
        }
        ArrayList<BlockBox> rootBoxes = new ArrayList<>();
        for (Claim root : roots) rootBoxes.addAll(intersections(root, selection, player.getWorld()));
        boolean wilderness = !covered(selection, rootBoxes);
        Set<Claim> deepest = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Claim> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Claim root : roots) collectDeepest(root, selection, player.getWorld(), deepest, visited);
        if (deepest.isEmpty()) return new ClaimSelection(wilderness ? AreaPermissionDecision.NOT_APPLICABLE : AreaPermissionDecision.DENY, null);
        if (wilderness || deepest.size() != 1) return new ClaimSelection(AreaPermissionDecision.DENY, null);
        return new ClaimSelection(null, deepest.iterator().next());
    }

    private AreaPermissionDecision resolveSelected(ClaimSelection selection, Player player, VideoPermissionAction action, boolean defaultAllowed) {
        if (selection.claim() == null) return selection.decision();
        Boolean result = actionPermission(selection.claim(), player, action);
        return decision(result == null ? defaultAllowed : result);
    }

    private ResidencePermissionBridge.Coverage coverageBounds(Player player, Vector3f first, Vector3f second, boolean unused) {
        if (player == null || !player.isOnline()) return ResidencePermissionBridge.Coverage.UNKNOWN;
        BlockBox selection = BlockBox.selection(first, second);
        if (selection == null) return ResidencePermissionBridge.Coverage.UNKNOWN;
        Collection<Claim> found = claimLookup.apply(player.getWorld());
        if (found == null) throw new IllegalStateException("Residence claim lookup returned null");
        for (Claim residence : found) {
            if (residence != null && !intersections(residence, selection, player.getWorld()).isEmpty()) {
                return ResidencePermissionBridge.Coverage.PROTECTED;
            }
        }
        return ResidencePermissionBridge.Coverage.WILDERNESS;
    }

    private static void collectDeepest(
            Claim residence,
            BlockBox selection,
            World world,
            Set<Claim> deepest,
            Set<Claim> visited
    ) {
        if (!visited.add(residence)) throw new IllegalStateException("Residence subzone cycle detected");
        List<BlockBox> own = intersections(residence, selection, world);
        if (own.isEmpty()) return;
        List<Claim> children = residence.subzones();
        if (children == null) children = List.of();
        ArrayList<BlockBox> childBoxes = new ArrayList<>();
        for (Claim child : children) {
            if (child != null) childBoxes.addAll(intersections(child, selection, world));
        }
        for (BlockBox box : own) {
            if (!covered(box, childBoxes)) {
                deepest.add(residence);
                break;
            }
        }
        for (Claim child : children) {
            if (child != null) collectDeepest(child, selection, world, deepest, visited);
        }
    }

    private static List<BlockBox> intersections(Claim residence, BlockBox selection, World world) {
        List<Area> areas = residence.areas();
        if (areas == null || areas.isEmpty()) return List.of();
        ArrayList<BlockBox> intersections = new ArrayList<>(areas.size());
        String worldName = world.getName();
        for (Area area : areas) {
            if (area == null) continue;
            String areaWorld = area.worldName();
            if (areaWorld == null || areaWorld.isBlank()) areaWorld = residence.worldName();
            if (areaWorld != null && !areaWorld.equalsIgnoreCase(worldName)) continue;
            Vector low = area.low();
            Vector high = area.high();
            if (low == null || high == null) throw new IllegalStateException("Residence cuboid is missing bounds");
            BlockBox intersection = selection.intersection(BlockBox.cuboid(low, high));
            if (intersection != null) intersections.add(intersection);
        }
        return intersections;
    }

    private static boolean covered(BlockBox target, List<BlockBox> covers) {
        ArrayList<BlockBox> remaining = new ArrayList<>();
        remaining.add(target);
        for (BlockBox cover : covers) {
            if (remaining.isEmpty()) return true;
            ArrayList<BlockBox> next = new ArrayList<>();
            for (BlockBox box : remaining) box.subtract(cover, next);
            if (next.size() > MAX_COVERAGE_FRAGMENTS) throw new IllegalStateException("Residence coverage is too complex");
            remaining = next;
        }
        return remaining.isEmpty();
    }

    private static AreaPermissionResolver fixed(AreaPermissionDecision decision) {
        return (player, action, context) -> decision;
    }

    private record ClaimSelection(AreaPermissionDecision decision, Claim claim) {
    }

    private Boolean actionPermission(Claim residence, Player player, VideoPermissionAction action) {
        Boolean result = flagChecker.allowed(residence, player, VideoPermissionNodes.current(action), false);
        if (result != null) return result;
        for (String legacy : VideoPermissionNodes.legacy(action)) {
            result = flagChecker.allowed(residence, player, legacy, false);
            if (result != null) return result;
        }
        if (STRUCTURE_ACTIONS.contains(action) && isResidenceController(residence, player)) {
            return true;
        }
        return null;
    }

    private static boolean isResidenceController(Claim residence, Player player) {
        if (residence == null || player == null) return false;
        UUID owner = residence.ownerUUID();
        if (owner != null && owner.equals(player.getUniqueId())) return true;
        Permissions permissions = residence.permissions();
        return permissions != null && permissions.playerHas(player, "admin", false);
    }

    static Boolean permission(Claim residence, Player player, String flag, boolean fallback) {
        Permissions permissions = residence.permissions();
        if (permissions == null) throw new IllegalStateException("Residence permissions are unavailable");
        if (permissions.playerHas(player, flag, false)) return true;
        if (!permissions.playerHas(player, flag, true)) return false;
        return fallback ? true : null;
    }

    private static Location location(Player player, VideoPermissionContext.Position point) {
        return new Location(player.getWorld(), point.x(), point.y(), point.z());
    }

    private static AreaPermissionDecision decision(boolean allowed) {
        return allowed ? AreaPermissionDecision.ALLOW : AreaPermissionDecision.DENY;
    }

    interface Claim {
        Claim parent();

        List<Claim> subzones();

        List<Area> areas();

        String worldName();

        Claim subzoneByLoc(Location location);

        Permissions permissions();

        default UUID ownerUUID() {
            return null;
        }
    }

    record Area(String worldName, Vector low, Vector high) {
    }

    @FunctionalInterface
    interface Permissions {
        boolean playerHas(Player player, String flag, boolean defaultValue);
    }

    @FunctionalInterface
    interface FlagChecker {
        Boolean allowed(Claim residence, Player player, String flag, boolean fallback);
    }

    private static final class ReflectionResidenceApi {
        private final Object manager;
        private final ResidenceReflection.Handle addFlag;
        private final ResidenceReflection.Handle getAllPossibleFlags;
        private final ResidenceReflection.Handle getByLoc;
        private final ResidenceReflection.Handle getResidences;
        private final ResidenceReflection.Handle getParent;
        private final ResidenceReflection.Handle getSubzones;
        private final ResidenceReflection.Handle getAreaArray;
        private final ResidenceReflection.Handle getClaimWorldName;
        private final ResidenceReflection.Handle getSubzoneByLoc;
        private final ResidenceReflection.Handle getPermissions;
        private final ResidenceReflection.Handle getOwnerUUID;
        private final ResidenceReflection.Handle getAreaWorldName;
        private final ResidenceReflection.Handle getLowVector;
        private final ResidenceReflection.Handle getHighVector;
        private final ResidenceReflection.Handle playerHas;

        private ReflectionResidenceApi(
                Object manager,
                Class<?> managerType,
                Class<?> flagPermissionsType,
                Class<?> claimedResidenceType,
                Class<?> cuboidAreaType,
                Class<?> residencePermissionsType
        ) {
            this.manager = manager;
            Class<?> cuboidAreaArrayType = Array.newInstance(cuboidAreaType, 0).getClass();
            addFlag = ResidenceReflection.staticMethod(flagPermissionsType, "addFlag", void.class, String.class);
            getAllPossibleFlags = ResidenceReflection.staticMethod(flagPermissionsType, "getAllPossibleFlags", Set.class);
            getByLoc = ResidenceReflection.virtualMethod(managerType, "getByLoc", claimedResidenceType, Location.class);
            getResidences = ResidenceReflection.virtualMethod(managerType, "getResidences", Map.class);
            getParent = ResidenceReflection.virtualMethod(claimedResidenceType, "getParent", claimedResidenceType);
            getSubzones = ResidenceReflection.virtualMethod(claimedResidenceType, "getSubzones", List.class);
            getAreaArray = ResidenceReflection.virtualMethod(claimedResidenceType, "getAreaArray", cuboidAreaArrayType);
            getClaimWorldName = ResidenceReflection.virtualMethod(claimedResidenceType, "getWorldName", String.class);
            getSubzoneByLoc = ResidenceReflection.virtualMethod(claimedResidenceType, "getSubzoneByLoc", claimedResidenceType, Location.class);
            getPermissions = ResidenceReflection.virtualMethod(claimedResidenceType, "getPermissions", residencePermissionsType);
            getOwnerUUID = optionalVirtualMethod(claimedResidenceType, "getOwnerUUID", UUID.class);
            getAreaWorldName = ResidenceReflection.virtualMethod(cuboidAreaType, "getWorldName", String.class);
            getLowVector = ResidenceReflection.virtualMethod(cuboidAreaType, "getLowVector", Vector.class);
            getHighVector = ResidenceReflection.virtualMethod(cuboidAreaType, "getHighVector", Vector.class);
            playerHas = ResidenceReflection.virtualMethod(
                    residencePermissionsType,
                    "playerHas",
                    boolean.class,
                    Player.class,
                    String.class,
                    boolean.class
            );
        }

        private static ResidenceReflection.Handle optionalVirtualMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters) {
            try {
                return ResidenceReflection.virtualMethod(owner, name, returnType, parameters);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static ReflectionResidenceApi load() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("Residence");
            if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("Residence is not ready");
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> residenceType = loadClass("com.bekvon.bukkit.residence.Residence", loader);
            if (!residenceType.isInstance(plugin)) throw new IllegalStateException("Residence plugin type is incompatible");
            Class<?> managerType = loadClass("com.bekvon.bukkit.residence.protection.ResidenceManager", loader);
            Object manager = ResidenceReflection.invoke(
                    ResidenceReflection.virtualMethod(residenceType, "getResidenceManager", managerType),
                    plugin
            );
            if (manager == null) throw new IllegalStateException("Residence manager is not ready");
            Class<?> flagPermissionsType = loadClass("com.bekvon.bukkit.residence.protection.FlagPermissions", loader);
            Class<?> claimedResidenceType = loadClass("com.bekvon.bukkit.residence.protection.ClaimedResidence", loader);
            Class<?> cuboidAreaType = loadClass("com.bekvon.bukkit.residence.protection.CuboidArea", loader);
            Class<?> residencePermissionsType = loadClass("com.bekvon.bukkit.residence.protection.ResidencePermissions", loader);
            return new ReflectionResidenceApi(
                    manager,
                    managerType,
                    flagPermissionsType,
                    claimedResidenceType,
                    cuboidAreaType,
                    residencePermissionsType
            );
        }

        private void registerFlags() {
            List<String> flags = VideoPermissionNodes.residenceFlags();
            for (String flag : flags) {
                ResidenceReflection.invoke(addFlag, flag);
            }
            Object allFlags = ResidenceReflection.invoke(getAllPossibleFlags);
            Set<String> registeredFlags = toStringSet(allFlags);
            for (String flag : flags) {
                if (!registeredFlags.contains(flag)) throw new IllegalStateException("Residence flag registration failed: " + flag);
            }
        }

        private Claim deepest(Location location) {
            ClaimContext context = new ClaimContext(this);
            Claim current = context.claim(ResidenceReflection.invoke(getByLoc, manager, location));
            Set<Claim> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (current != null && visited.add(current)) {
                Claim child = current.subzoneByLoc(location);
                if (child == null) return current;
                current = child;
            }
            if (current == null) return null;
            throw new IllegalStateException("Residence subzone cycle detected");
        }

        private Collection<Claim> claims(World ignored) {
            ClaimContext context = new ClaimContext(this);
            Object value = ResidenceReflection.invoke(getResidences, manager);
            Collection<?> found = values(value);
            ArrayList<Claim> result = new ArrayList<>(found.size());
            for (Object residence : found) {
                Claim claim = context.claim(residence);
                if (claim != null) result.add(claim);
            }
            return List.copyOf(result);
        }

        private Boolean permission(Claim residence, Player player, String flag, boolean fallback) {
            return ResidencePermissionHook.permission(residence, player, flag, fallback);
        }

        private static Collection<?> values(Object value) {
            if (value == null) return List.of();
            if (value instanceof Map<?, ?> map) return map.values();
            if (value instanceof Collection<?> collection) return collection;
            if (value instanceof Iterable<?> iterable) {
                ArrayList<Object> values = new ArrayList<>();
                for (Object item : iterable) values.add(item);
                return values;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                ArrayList<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) values.add(Array.get(value, i));
                return values;
            }
            throw new IllegalStateException("Residence returned an unsupported collection type");
        }

        private static Set<String> toStringSet(Object value) {
            Set<String> result = new java.util.HashSet<>();
            for (Object entry : values(value)) {
                if (entry != null) result.add(String.valueOf(entry));
            }
            return result;
        }

        private static Class<?> loadClass(String name, ClassLoader preferredLoader) {
            try {
                return Class.forName(name, true, preferredLoader);
            } catch (ClassNotFoundException first) {
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException second) {
                    second.addSuppressed(first);
                    throw new IllegalStateException("Residence API is unavailable: " + name, second);
                }
            }
        }

        private static final class ReflectionClaim implements Claim {
            private final Object value;
            private final ClaimContext context;

            private ReflectionClaim(Object value, ClaimContext context) {
                this.value = value;
                this.context = context;
            }

            @Override
            public Claim parent() {
                return context.claim(ResidenceReflection.invoke(context.api.getParent, value));
            }

            @Override
            public List<Claim> subzones() {
                Object result = ResidenceReflection.invoke(context.api.getSubzones, value);
                Collection<?> children = values(result);
                ArrayList<Claim> claims = new ArrayList<>(children.size());
                for (Object child : children) {
                    Claim claim = context.claim(child);
                    if (claim != null) claims.add(claim);
                }
                return List.copyOf(claims);
            }

            @Override
            public List<Area> areas() {
                Object result = ResidenceReflection.invoke(context.api.getAreaArray, value);
                Collection<?> rawAreas = values(result);
                ArrayList<Area> areas = new ArrayList<>(rawAreas.size());
                for (Object rawArea : rawAreas) {
                    if (rawArea == null) continue;
                    String worldName = stringValue(ResidenceReflection.invoke(context.api.getAreaWorldName, rawArea));
                    Vector low = (Vector) ResidenceReflection.invoke(context.api.getLowVector, rawArea);
                    Vector high = (Vector) ResidenceReflection.invoke(context.api.getHighVector, rawArea);
                    areas.add(new Area(worldName, low, high));
                }
                return List.copyOf(areas);
            }

            @Override
            public String worldName() {
                return stringValue(ResidenceReflection.invoke(context.api.getClaimWorldName, value));
            }

            @Override
            public Claim subzoneByLoc(Location location) {
                return context.claim(ResidenceReflection.invoke(context.api.getSubzoneByLoc, value, location));
            }

            @Override
            public Permissions permissions() {
                Object permission = ResidenceReflection.invoke(context.api.getPermissions, value);
                return (player, flag, defaultValue) -> Boolean.TRUE.equals(
                        ResidenceReflection.invoke(context.api.playerHas, permission, player, flag, defaultValue)
                );
            }

            @Override
            public UUID ownerUUID() {
                if (context.api.getOwnerUUID == null) return null;
                Object owner = ResidenceReflection.invoke(context.api.getOwnerUUID, value);
                return owner instanceof UUID uuid ? uuid : null;
            }

            private static String stringValue(Object value) {
                return value == null ? null : String.valueOf(value);
            }
        }

        private static final class ClaimContext {
            private final ReflectionResidenceApi api;
            private final IdentityHashMap<Object, ReflectionClaim> claims = new IdentityHashMap<>();

            private ClaimContext(ReflectionResidenceApi api) {
                this.api = api;
            }

            private Claim claim(Object value) {
                if (value == null) return null;
                ReflectionClaim existing = claims.get(value);
                if (existing != null) return existing;
                ReflectionClaim created = new ReflectionClaim(value, this);
                claims.put(value, created);
                return created;
            }
        }
    }

    private record BlockBox(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        private static BlockBox selection(Vector3f first, Vector3f second) {
            if (first == null || second == null
                    || !Float.isFinite(first.x) || !Float.isFinite(first.y) || !Float.isFinite(first.z)
                    || !Float.isFinite(second.x) || !Float.isFinite(second.y) || !Float.isFinite(second.z)) {
                return null;
            }
            long minX = (long) Math.floor(Math.min(first.x, second.x));
            long minY = (long) Math.floor(Math.min(first.y, second.y));
            long minZ = (long) Math.floor(Math.min(first.z, second.z));
            long maxX = (long) Math.ceil(Math.max(first.x, second.x));
            long maxY = (long) Math.ceil(Math.max(first.y, second.y));
            long maxZ = (long) Math.ceil(Math.max(first.z, second.z));
            return create(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private static BlockBox cuboid(Vector low, Vector high) {
            long minX = Math.min(low.getBlockX(), high.getBlockX());
            long minY = Math.min(low.getBlockY(), high.getBlockY());
            long minZ = Math.min(low.getBlockZ(), high.getBlockZ());
            long maxX = Math.max(low.getBlockX(), high.getBlockX()) + 1L;
            long maxY = Math.max(low.getBlockY(), high.getBlockY()) + 1L;
            long maxZ = Math.max(low.getBlockZ(), high.getBlockZ()) + 1L;
            return new BlockBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private BlockBox intersection(BlockBox other) {
            return create(
                    Math.max(minX, other.minX),
                    Math.max(minY, other.minY),
                    Math.max(minZ, other.minZ),
                    Math.min(maxX, other.maxX),
                    Math.min(maxY, other.maxY),
                    Math.min(maxZ, other.maxZ)
            );
        }

        private void subtract(BlockBox cover, List<BlockBox> output) {
            BlockBox overlap = intersection(cover);
            if (overlap == null) {
                output.add(this);
                return;
            }
            add(output, minX, minY, minZ, overlap.minX, maxY, maxZ);
            add(output, overlap.maxX, minY, minZ, maxX, maxY, maxZ);
            add(output, overlap.minX, minY, minZ, overlap.maxX, overlap.minY, maxZ);
            add(output, overlap.minX, overlap.maxY, minZ, overlap.maxX, maxY, maxZ);
            add(output, overlap.minX, overlap.minY, minZ, overlap.maxX, overlap.maxY, overlap.minZ);
            add(output, overlap.minX, overlap.minY, overlap.maxZ, overlap.maxX, overlap.maxY, maxZ);
        }

        private static BlockBox create(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
            if (minX >= maxX || minY >= maxY || minZ >= maxZ) return null;
            return new BlockBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private static void add(List<BlockBox> output, long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
            BlockBox box = create(minX, minY, minZ, maxX, maxY, maxZ);
            if (box != null) output.add(box);
        }
    }
}
