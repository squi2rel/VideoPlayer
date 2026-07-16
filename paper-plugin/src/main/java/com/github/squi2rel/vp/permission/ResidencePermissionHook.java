package com.github.squi2rel.vp.permission;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.github.squi2rel.vp.VideoPlayerMain;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
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
            VideoPermissionAction.AUTO_SYNC,
            VideoPermissionAction.OPEN_MENU
    );
    private final Function<UUID, Player> playerLookup;
    private final Function<Location, ClaimedResidence> residenceLookup;
    private final Function<World, Collection<ClaimedResidence>> claimLookup;
    private final FlagChecker flagChecker;

    ResidencePermissionHook() {
        Residence residence = Residence.getInstance();
        if (residence == null || !residence.isEnabled() || residence.getResidenceManager() == null) {
            throw new IllegalStateException("Residence is not ready");
        }
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            FlagPermissions.addFlag(flag(action));
        }
        Set<String> registeredFlags = FlagPermissions.getAllPossibleFlags();
        for (VideoPermissionAction action : VideoPermissionAction.values()) {
            if (!registeredFlags.contains(flag(action))) throw new IllegalStateException("Residence flag registration failed");
        }
        playerLookup = Bukkit::getPlayer;
        residenceLookup = ResidencePermissionHook::deepest;
        claimLookup = world -> List.copyOf(residence.getResidenceManager().getResidences().values());
        flagChecker = ResidencePermissionHook::permission;
        VideoPlayerMain.LOGGER.info("Registered {} VideoPlayer Residence flags", VideoPermissionAction.values().length);
    }

    ResidencePermissionHook(
            Function<UUID, Player> playerLookup,
            Function<Location, ClaimedResidence> residenceLookup,
            Function<World, Collection<ClaimedResidence>> claimLookup,
            FlagChecker flagChecker
    ) {
        this.playerLookup = Objects.requireNonNull(playerLookup);
        this.residenceLookup = Objects.requireNonNull(residenceLookup);
        this.claimLookup = Objects.requireNonNull(claimLookup);
        this.flagChecker = Objects.requireNonNull(flagChecker);
    }

    @Override
    public boolean allowed(VideoPermissionPlayer permissionPlayer, VideoPermissionAction action, VideoPermissionContext context) {
        try {
            if (permissionPlayer == null || permissionPlayer.uuid() == null) return false;
            Player player = playerLookup.apply(permissionPlayer.uuid());
            if (player == null || !player.isOnline()) return false;
            if (player.isOp() || player.hasPermission(VideoPermissions.ADMIN)) return true;
            boolean defaultAllowed = DEFAULT_ALLOWED.contains(action);
            if (context != null && context.areaMin() != null && context.areaMax() != null) {
                VideoPermissionContext.Position min = context.areaMin();
                VideoPermissionContext.Position max = context.areaMax();
                return allowedBounds(
                        player,
                        action,
                        new Vector3f((float) min.x(), (float) min.y(), (float) min.z()),
                        new Vector3f((float) max.x(), (float) max.y(), (float) max.z()),
                        defaultAllowed
                );
            }
            return allowedAt(player, action, location(player, context), defaultAllowed);
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to check Residence permission for {}", playerName(permissionPlayer), error);
            return false;
        }
    }

    @Override
    public boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        if (player == null || !player.isOnline()) return false;
        if (player.isOp() || player.hasPermission(VideoPermissions.ADMIN)) return true;
        try {
            return allowedBounds(player, action, first, second, false);
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to check Residence bounds for {}", player.getName(), error);
            return false;
        }
    }

    private boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second, boolean defaultAllowed) {
        BlockBox selection = BlockBox.selection(first, second);
        if (selection == null) return false;
        Collection<ClaimedResidence> found = claimLookup.apply(player.getWorld());
        if (found == null) throw new IllegalStateException("Residence claim lookup returned null");
        ArrayList<ClaimedResidence> roots = new ArrayList<>();
        Set<ClaimedResidence> rootIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClaimedResidence residence : found) {
            if (residence != null && residence.getParent() == null && rootIdentity.add(residence)) roots.add(residence);
        }
        ArrayList<BlockBox> rootBoxes = new ArrayList<>();
        for (ClaimedResidence root : roots) rootBoxes.addAll(intersections(root, selection, player.getWorld()));
        boolean wilderness = !covered(selection, rootBoxes);
        Set<ClaimedResidence> deepest = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ClaimedResidence> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClaimedResidence root : roots) collectDeepest(root, selection, player.getWorld(), deepest, visited);
        if (deepest.isEmpty()) return wilderness;
        if (wilderness || deepest.size() != 1) return false;
        ClaimedResidence residence = deepest.iterator().next();
        Boolean result = flagChecker.allowed(residence, player, flag(action), defaultAllowed);
        return result == null ? defaultAllowed : result;
    }

    private static void collectDeepest(
            ClaimedResidence residence,
            BlockBox selection,
            World world,
            Set<ClaimedResidence> deepest,
            Set<ClaimedResidence> visited
    ) {
        if (!visited.add(residence)) throw new IllegalStateException("Residence subzone cycle detected");
        List<BlockBox> own = intersections(residence, selection, world);
        if (own.isEmpty()) return;
        List<ClaimedResidence> children = residence.getSubzones();
        if (children == null) children = List.of();
        ArrayList<BlockBox> childBoxes = new ArrayList<>();
        for (ClaimedResidence child : children) {
            if (child != null) childBoxes.addAll(intersections(child, selection, world));
        }
        for (BlockBox box : own) {
            if (!covered(box, childBoxes)) {
                deepest.add(residence);
                break;
            }
        }
        for (ClaimedResidence child : children) {
            if (child != null) collectDeepest(child, selection, world, deepest, visited);
        }
    }

    private static List<BlockBox> intersections(ClaimedResidence residence, BlockBox selection, World world) {
        CuboidArea[] areas = residence.getAreaArray();
        if (areas == null || areas.length == 0) return List.of();
        ArrayList<BlockBox> intersections = new ArrayList<>(areas.length);
        String worldName = world.getName();
        for (CuboidArea area : areas) {
            if (area == null) continue;
            String areaWorld = area.getWorldName();
            if (areaWorld == null || areaWorld.isBlank()) areaWorld = residence.getWorldName();
            if (areaWorld != null && !areaWorld.equalsIgnoreCase(worldName)) continue;
            Vector low = area.getLowVector();
            Vector high = area.getHighVector();
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

    private boolean allowedAt(Player player, VideoPermissionAction action, Location location, boolean defaultAllowed) {
        ClaimedResidence residence = residenceLookup.apply(location);
        if (residence == null) return true;
        Boolean result = flagChecker.allowed(residence, player, flag(action), defaultAllowed);
        return result == null ? defaultAllowed : result;
    }

    @SuppressWarnings("deprecation")
    private static Boolean permission(ClaimedResidence residence, Player player, String flag, boolean fallback) {
        boolean allowed = false;
        ClaimedResidence current = residence;
        while (current != null) {
            if (!current.getPermissions().playerHas(player, flag, true)) return false;
            if (current.getPermissions().playerHas(player, flag, false)) allowed = true;
            current = current.getParent();
        }
        return allowed ? true : fallback ? true : null;
    }

    private static ClaimedResidence deepest(Location location) {
        Residence residence = Residence.getInstance();
        if (residence == null || !residence.isEnabled() || residence.getResidenceManager() == null) {
            throw new IllegalStateException("Residence is not ready");
        }
        ClaimedResidence current = residence.getResidenceManager().getByLoc(location);
        if (current == null) return null;
        Set<ClaimedResidence> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(current)) {
            ClaimedResidence child = current.getSubzoneByLoc(location);
            if (child == null) return current;
            current = child;
        }
        throw new IllegalStateException("Residence subzone cycle detected");
    }

    private static Location location(Player player, VideoPermissionContext context) {
        if (context != null && context.anchor() != null) {
            VideoPermissionContext.Position point = context.anchor();
            return new Location(player.getWorld(), point.x(), point.y(), point.z());
        }
        if (context != null && context.areaMin() != null && context.areaMax() != null) {
            VideoPermissionContext.Position min = context.areaMin();
            VideoPermissionContext.Position max = context.areaMax();
            return new Location(player.getWorld(), (min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
        }
        return player.getLocation();
    }

    private static String flag(VideoPermissionAction action) {
        return "videoplayer.action." + action.name().toLowerCase(Locale.ROOT);
    }

    private static String playerName(VideoPermissionPlayer player) {
        if (player == null) return "unknown";
        try {
            return player.name();
        } catch (Throwable ignored) {
            return "unknown";
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

    @FunctionalInterface
    interface FlagChecker {
        Boolean allowed(ClaimedResidence residence, Player player, String flag, boolean fallback);
    }
}
