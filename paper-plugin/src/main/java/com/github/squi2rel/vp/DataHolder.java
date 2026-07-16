package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoHandshakeState;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.i18n.PaperTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.ScreenKey;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public final class DataHolder {
    public static final Object LOCK = new Object();
    static final long PLAYER_TRACKING_PERIOD_TICKS = 5L;
    public static ServerConfig config = new ServerConfig();
    public static final HashSet<UUID> allPlayers = new HashSet<>();
    public static final HashMap<UUID, String> playerDim = new HashMap<>();
    public static final HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final HashMap<String, Path> worldFiles = new HashMap<>();
    private static final HashMap<String, String> persistedWorldSnapshots = new HashMap<>();
    private static final HashSet<String> invalidWorldConfigs = new HashSet<>();
    private static final HashMap<UUID, ScheduledTask> playerTasks = new HashMap<>();
    private static final HashMap<UUID, Player> onlinePlayers = new HashMap<>();
    private static final HashMap<UUID, String> onlinePlayerNames = new HashMap<>();
    private static final HashMap<UUID, VideoHandshakeState> handshakes = new HashMap<>();
    private static final HashMap<UUID, Long> handshakeNonces = new HashMap<>();
    private static final HashMap<UUID, PlayerPosition> playerPositions = new HashMap<>();
    private static final ArrayList<VideoArea> legacyConfigAreas = new ArrayList<>();
    private static VideoPlayerPaperPlugin plugin;
    private static boolean sharedConfigLoaded;
    private static long lifecycleEpoch;
    private static boolean running;

    private DataHolder() {
    }

    public static void start(VideoPlayerPaperPlugin owner) {
        synchronized (LOCK) {
            plugin = owner;
            running = true;
            lifecycleEpoch++;
            config = new ServerConfig();
            sharedConfigLoaded = false;
            legacyConfigAreas.clear();
            handshakes.clear();
            handshakeNonces.clear();
            invalidWorldConfigs.clear();
            loadLegacyPluginConfig(owner);
            VideoPlayerMain.resetScheduler();
            long epoch = lifecycleEpoch;
            VideoPlayerMain.server = runnable -> executeState(epoch, runnable);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            running = false;
            lifecycleEpoch++;
            for (ScheduledTask task : playerTasks.values()) {
                task.cancel();
            }
            playerTasks.clear();
            for (String dim : new ArrayList<>(areas.keySet())) {
                saveWorld(dim);
            }
            for (HashMap<String, VideoArea> map : areas.values()) {
                for (VideoArea area : map.values()) {
                    unloadAreaForPlayers(area);
                    area.remove();
                }
            }
            areas.clear();
            worldFiles.clear();
            persistedWorldSnapshots.clear();
            invalidWorldConfigs.clear();
            allPlayers.clear();
            playerDim.clear();
            onlinePlayers.clear();
            onlinePlayerNames.clear();
            handshakes.clear();
            handshakeNonces.clear();
            playerPositions.clear();
            plugin = null;
        }
    }

    public static void executeState(Runnable runnable) {
        long epoch;
        synchronized (LOCK) {
            epoch = lifecycleEpoch;
        }
        executeState(epoch, runnable);
    }

    public static void executeState(long expectedEpoch, Runnable runnable) {
        VideoPlayerPaperPlugin owner;
        synchronized (LOCK) {
            owner = plugin;
            if (!running || lifecycleEpoch != expectedEpoch || owner == null || !owner.isEnabled() || runnable == null) return;
        }
        Bukkit.getGlobalRegionScheduler().run(owner, task -> {
            synchronized (LOCK) {
                if (!running || lifecycleEpoch != expectedEpoch || plugin != owner || !owner.isEnabled()) return;
                runnable.run();
            }
        });
    }

    public static void loadWorld(World world) {
        synchronized (LOCK) {
            String dim = worldKey(world);
            if (areas.containsKey(dim)) return;
            Path path = world.getWorldFolder().toPath().resolve("videoplayer.json");
            boolean existingConfig = Files.exists(path);
            ReadResult result;
            try {
                result = readConfig(path);
                invalidWorldConfigs.remove(dim);
            } catch (RuntimeException error) {
                invalidWorldConfigs.add(dim);
                worldFiles.put(dim, path);
                areas.put(dim, new HashMap<>());
                invalidateWorldTracking(dim);
                VideoPlayerMain.LOGGER.warn("Rejected invalid VideoPlayer world config {}; original file will not be modified", path, error);
                return;
            }
            ServerConfig loaded = result.config();
            if (existingConfig && result.hasSharedConfig() && (!sharedConfigLoaded || dim.equals("minecraft:overworld"))) {
                applySharedConfig(loaded);
                sharedConfigLoaded = true;
            }
            HashMap<String, VideoArea> map = new HashMap<>();
            if (loaded.areas != null) {
                if (loaded.areas.size() > VideoArea.MAX_AREAS_PER_WORLD) {
                    throw new IllegalArgumentException("VideoPlayer world contains more than " + VideoArea.MAX_AREAS_PER_WORLD + " areas");
                }
                for (VideoArea area : loaded.areas) {
                    area.dim = dim;
                    if (area.screens == null) area.screens = new ArrayList<>();
                    for (VideoScreen screen : area.screens) {
                        if (screen.metadata == null) screen.metadata = new com.github.squi2rel.vp.video.ScreenMetadata();
                        screen.metadata.ensureValid();
                    }
                    area.initServer();
                    area.afterLoad();
                    map.put(area.name, area);
                }
            }
            areas.put(dim, map);
            invalidateWorldTracking(dim);
            worldFiles.put(dim, path);
            rememberWorldSnapshot(dim);
            int imported = importLegacyConfigAreas(dim, map);
            if (result.migrated()) {
                Path backup;
                try {
                    backup = backup(path, ".1.6.5.bak");
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to back up legacy VideoPlayer world data " + path, e);
                }
                saveWorld(dim);
                VideoPlayerMain.LOGGER.info(
                        "Migrated VideoPlayer 1.6.5 world data at {}: {} areas, {} screens; backup: {}",
                        path, result.areaCount(), result.screenCount(), backup
                );
            } else if (imported > 0) {
                saveWorld(dim);
            }
            if (imported > 0) persistLegacyPluginConfig();
            VideoPlayerMain.LOGGER.info("Loaded {} VideoPlayer areas for world {} from {}", map.size(), dim, path);
        }
    }

    public static void unloadWorld(World world) {
        synchronized (LOCK) {
            String dim = worldKey(world);
            HashMap<String, VideoArea> map = areas.get(dim);
            if (map == null) return;
            saveWorld(dim);
            for (VideoArea area : map.values()) {
                unloadAreaForPlayers(area);
                area.remove();
            }
            areas.remove(dim);
            worldFiles.remove(dim);
            persistedWorldSnapshots.remove(dim);
            invalidWorldConfigs.remove(dim);
            playerDim.entrySet().removeIf(entry -> dim.equals(entry.getValue()));
            playerPositions.entrySet().removeIf(entry -> dim.equals(entry.getValue().dimension()));
            VideoPlayerMain.LOGGER.info("Unloaded VideoPlayer world {}", dim);
        }
    }

    public static void saveWorld(String dim) {
        if (invalidWorldConfigs.contains(dim)) {
            VideoPlayerMain.LOGGER.warn("Skipped saving VideoPlayer world {} because its loaded config was invalid", dim);
            return;
        }
        Path path = worldFiles.get(dim);
        HashMap<String, VideoArea> map = areas.get(dim);
        if (path == null || map == null) return;
        String previous = persistedWorldSnapshots.get(dim);
        String serialized = serializeWorld(dim, map);
        try {
            writeString(path, serialized);
            persistedWorldSnapshots.put(dim, serialized);
        } catch (RuntimeException error) {
            if (previous != null) {
                try {
                    restoreWorldSnapshot(dim, previous);
                } catch (RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
    }

    private static String serializeWorld(String dim, HashMap<String, VideoArea> map) {
        ServerConfig saved = new ServerConfig();
        saved.remoteControlName = config.remoteControlName;
        saved.remoteControlId = config.remoteControlId;
        saved.remoteControlRange = config.remoteControlRange;
        saved.noControlRange = config.noControlRange;
        saved.areas = new ArrayList<>(map.values());
        return gson.toJson(saved);
    }

    private static void rememberWorldSnapshot(String dim) {
        HashMap<String, VideoArea> map = areas.get(dim);
        if (map != null) persistedWorldSnapshots.put(dim, serializeWorld(dim, map));
    }

    private static void restoreWorldSnapshot(String dim, String serialized) {
        ServerConfig restored = gson.fromJson(serialized, ServerConfig.class);
        if (restored == null) throw new IllegalStateException("Persisted VideoPlayer world snapshot is empty");
        validateConfig(restored);
        HashMap<String, VideoArea> replacement = new HashMap<>();
        if (restored.areas != null) {
            for (VideoArea area : restored.areas) {
                area.dim = dim;
                if (area.screens == null) area.screens = new ArrayList<>();
                for (VideoScreen screen : area.screens) {
                    if (screen.metadata == null) screen.metadata = new com.github.squi2rel.vp.video.ScreenMetadata();
                    screen.metadata.ensureValid();
                }
                area.initServer();
                area.afterLoad();
                replacement.put(area.name, area);
            }
        }
        HashMap<String, VideoArea> current = areas.get(dim);
        if (current != null) {
            for (VideoArea area : current.values()) area.remove();
        }
        areas.put(dim, replacement);
        invalidateWorldTracking(dim);
    }

    public static void playerJoin(Player player) {
        if (player == null) return;
        synchronized (LOCK) {
            VideoPlayerPaperPlugin owner = plugin;
            long epoch = lifecycleEpoch;
            if (!running || owner == null || !owner.isEnabled() || !player.isOnline()) return;
            UUID uuid = player.getUniqueId();
            ScheduledTask old = playerTasks.remove(uuid);
            if (old != null) old.cancel();
            playerPositions.remove(uuid);
            ScheduledTask task;
            try {
                task = player.getScheduler().runAtFixedRate(
                        owner,
                        scheduled -> updatePlayer(player, owner, epoch, scheduled),
                        null,
                        1L,
                        PLAYER_TRACKING_PERIOD_TICKS
                );
            } catch (Throwable error) {
                handshakes.remove(uuid);
                onlinePlayers.remove(uuid);
                onlinePlayerNames.remove(uuid);
                VideoPlayerMain.LOGGER.warn("Failed to start VideoPlayer tracking for {}", player.getName(), error);
                return;
            }
            if (task == null) {
                handshakes.remove(uuid);
                onlinePlayers.remove(uuid);
                onlinePlayerNames.remove(uuid);
                return;
            }
            handshakes.put(uuid, VideoHandshakeState.NEEDS_RESET);
            handshakeNonces.remove(uuid);
            onlinePlayers.put(uuid, player);
            onlinePlayerNames.put(uuid, player.getName());
            playerTasks.put(uuid, task);
        }
    }

    public static void playerLeave(UUID uuid) {
        synchronized (LOCK) {
            allPlayers.remove(uuid);
            playerDim.remove(uuid);
            onlinePlayers.remove(uuid);
            onlinePlayerNames.remove(uuid);
            handshakes.remove(uuid);
            handshakeNonces.remove(uuid);
            playerPositions.remove(uuid);
            ScheduledTask task = playerTasks.remove(uuid);
            if (task != null) task.cancel();
            for (HashMap<String, VideoArea> map : areas.values()) {
                for (VideoArea area : map.values()) {
                    area.removePlayer(uuid);
                }
            }
        }
    }

    private static void updatePlayer(Player player, VideoPlayerPaperPlugin owner, long expectedEpoch, ScheduledTask scheduled) {
        UUID uuid = player.getUniqueId();
        World world = player.getWorld();
        Location location = player.getLocation();
        String dim = worldKey(world);
        PlayerPosition position = new PlayerPosition(dim, location.getX(), location.getY(), location.getZ());
        synchronized (LOCK) {
            if (!running || plugin != owner || lifecycleEpoch != expectedEpoch || !owner.isEnabled() || !player.isOnline()) {
                scheduled.cancel();
                playerTasks.remove(uuid, scheduled);
                playerPositions.remove(uuid);
                return;
            }
            if (!allPlayers.contains(uuid)) return;
            PlayerPosition previousPosition = playerPositions.get(uuid);
            if (!shouldScan(previousPosition, position)) return;
            String previousDim = playerDim.get(uuid);
            if (previousDim != null && !previousDim.equals(dim)) {
                removeFromWorld(uuid, previousDim, player);
            }
            HashMap<String, VideoArea> all = areas.get(dim);
            if (all != null) {
                for (VideoArea area : all.values()) {
                    if (area.inBounds(location.getX(), location.getY(), location.getZ())) {
                        if (area.addPlayer(uuid)) {
                            sendAreaSnapshot(player, area);
                            actionBar(player, VpTranslation.of("message.videoplayer.area_enter", "Entered video area %s", area.name), NamedTextColor.DARK_AQUA);
                            area.playerEntered();
                        }
                    } else if (area.removePlayer(uuid)) {
                        sendTo(player, VideoPackets.unloadArea(area));
                        sendTo(player, VideoPackets.removeArea(area));
                        actionBar(player, VpTranslation.of("message.videoplayer.area_leave", "Left video area %s", area.name), NamedTextColor.DARK_AQUA);
                    }
                }
            }
            playerDim.put(uuid, dim);
            playerPositions.put(uuid, position);
        }
    }

    public static void invalidateWorldTracking(String dim) {
        if (dim == null) return;
        synchronized (LOCK) {
            playerPositions.entrySet().removeIf(entry -> dim.equals(entry.getValue().dimension()));
        }
    }

    public static boolean worldConfigValid(String dim) {
        synchronized (LOCK) {
            return dim != null && !invalidWorldConfigs.contains(dim);
        }
    }

    static boolean shouldScan(PlayerPosition previous, PlayerPosition current) {
        return current != null && !current.equals(previous);
    }

    public static void runForPlayer(Player player, Runnable runnable) {
        if (player == null || runnable == null) return;
        VideoPlayerPaperPlugin owner;
        long expectedEpoch;
        synchronized (LOCK) {
            owner = plugin;
            expectedEpoch = lifecycleEpoch;
            if (!running || owner == null || !owner.isEnabled()) return;
        }
        player.getScheduler().run(owner, task -> {
            synchronized (LOCK) {
                if (running && plugin == owner && lifecycleEpoch == expectedEpoch && owner.isEnabled()) runnable.run();
            }
        }, null);
    }

    public static void runStateForPlayer(Player player, Runnable runnable) {
        if (player == null || runnable == null) return;
        VideoPlayerPaperPlugin owner;
        long expectedEpoch;
        synchronized (LOCK) {
            owner = plugin;
            expectedEpoch = lifecycleEpoch;
            if (!running || owner == null || !owner.isEnabled()) return;
        }
        player.getScheduler().run(owner, task -> {
            synchronized (LOCK) {
                if (running && plugin == owner && lifecycleEpoch == expectedEpoch && owner.isEnabled()) runnable.run();
            }
        }, null);
    }

    public static void sendTo(Player player, byte[] bytes) {
        if (player == null || bytes == null) return;
        if (bytes.length > VideoPackets.MAX_PAYLOAD_BYTES) {
            VideoPlayerMain.LOGGER.warn("Dropped oversized VideoPlayer payload: {} bytes", bytes.length);
            return;
        }
        runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, VideoPlayerPaperPlugin.CHANNEL, bytes);
            }
        });
    }

    public static void sendToCurrentThread(Player player, byte[] bytes) {
        if (player == null || bytes == null || plugin == null || !plugin.isEnabled()) return;
        if (bytes.length > VideoPackets.MAX_PAYLOAD_BYTES) {
            VideoPlayerMain.LOGGER.warn("Dropped oversized VideoPlayer payload: {} bytes", bytes.length);
            return;
        }
        if (player.isOnline()) {
            player.sendPluginMessage(plugin, VideoPlayerPaperPlugin.CHANNEL, bytes);
        }
    }

    public static void message(Player player, String message) {
        if (player == null || message == null || message.isBlank()) return;
        runForPlayer(player, () -> {
            if (player.isOnline()) player.sendMessage(message);
        });
    }

    public static void message(Player player, VpTranslation message) {
        if (player == null || message == null || message.isEmpty()) return;
        runForPlayer(player, () -> {
            if (player.isOnline()) player.sendMessage(PaperTexts.text(message));
        });
    }

    public static void message(UUID uuid, long epoch, String message) {
        Player player;
        synchronized (LOCK) {
            if (!running || lifecycleEpoch != epoch) return;
            player = onlinePlayers.get(uuid);
        }
        message(player, message);
    }

    public static void message(UUID uuid, long epoch, VpTranslation message) {
        Player player;
        synchronized (LOCK) {
            if (!running || lifecycleEpoch != epoch) return;
            player = onlinePlayers.get(uuid);
        }
        message(player, message);
    }

    private static void actionBar(Player player, VpTranslation message, NamedTextColor color) {
        if (player == null || message == null || message.isEmpty()) return;
        runForPlayer(player, () -> {
            if (player.isOnline()) player.sendActionBar(PaperTexts.text(message).color(color));
        });
    }

    public static void disconnect(Player player, String message) {
        if (player == null) return;
        runForPlayer(player, () -> player.kickPlayer(message == null ? "Disconnected" : message));
    }

    public static ArrayList<Player> players(java.util.List<UUID> uuids) {
        synchronized (LOCK) {
            ArrayList<Player> players = new ArrayList<>(uuids.size());
            for (UUID uuid : uuids) {
                Player target = onlinePlayers.get(uuid);
                if (target != null) {
                    players.add(target);
                }
            }
            return players;
        }
    }

    public static UUID onlinePlayerUuid(String name) {
        if (name == null) return null;
        synchronized (LOCK) {
            for (Map.Entry<UUID, String> entry : onlinePlayerNames.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
            }
        }
        return null;
    }

    public static long lifecycleEpoch() {
        synchronized (LOCK) {
            return lifecycleEpoch;
        }
    }

    public static boolean lifecycleActive(long epoch) {
        synchronized (LOCK) {
            return running && lifecycleEpoch == epoch;
        }
    }

    public static VideoHandshakeState handshakeState(UUID uuid) {
        synchronized (LOCK) {
            return handshakes.getOrDefault(uuid, VideoHandshakeState.NEEDS_RESET);
        }
    }

    public static VideoHandshakeState acceptHandshake(UUID uuid) {
        synchronized (LOCK) {
            VideoHandshakeState state = handshakes.getOrDefault(uuid, VideoHandshakeState.NEEDS_RESET);
            if (state == VideoHandshakeState.REJECTED) return state;
            if (state == VideoHandshakeState.NEEDS_RESET) {
                handshakes.put(uuid, VideoHandshakeState.RESET_SENT);
                return VideoHandshakeState.RESET_SENT;
            }
            if (state == VideoHandshakeState.RESET_SENT) {
                handshakes.put(uuid, VideoHandshakeState.ACTIVE);
                allPlayers.add(uuid);
                return VideoHandshakeState.ACTIVE;
            }
            return VideoHandshakeState.ACTIVE;
        }
    }

    public static long issueHandshakeNonce(UUID uuid) {
        synchronized (LOCK) {
            long nonce;
            do {
                nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong();
            } while (nonce == 0L);
            handshakeNonces.put(uuid, nonce);
            return nonce;
        }
    }

    public static long handshakeNonce(UUID uuid) {
        synchronized (LOCK) {
            return handshakeNonces.getOrDefault(uuid, 0L);
        }
    }

    public static boolean acceptHandshakeAck(UUID uuid, long nonce) {
        synchronized (LOCK) {
            if (nonce == 0L || handshakeNonces.getOrDefault(uuid, 0L) != nonce) return false;
            if (handshakes.get(uuid) != VideoHandshakeState.RESET_SENT) return false;
            handshakeNonces.remove(uuid);
            handshakes.put(uuid, VideoHandshakeState.ACTIVE);
            allPlayers.add(uuid);
            return true;
        }
    }

    public static boolean rejectHandshake(UUID uuid) {
        synchronized (LOCK) {
            VideoHandshakeState previous = handshakes.put(uuid, VideoHandshakeState.REJECTED);
            allPlayers.remove(uuid);
            return previous != VideoHandshakeState.REJECTED;
        }
    }

    public static boolean protocolActive(UUID uuid) {
        synchronized (LOCK) {
            return handshakes.get(uuid) == VideoHandshakeState.ACTIVE;
        }
    }

    public static VideoScreen findScreen(ScreenKey key) {
        if (key == null) return null;
        synchronized (LOCK) {
            HashMap<String, VideoArea> world = areas.get(key.dimension());
            if (world == null) return null;
            VideoArea area = world.get(key.areaName());
            return area == null ? null : area.getScreen(key.screenName());
        }
    }

    public static String worldKey(World world) {
        return world.getKey().toString();
    }

    private static void removeFromWorld(UUID uuid, String dim, Player player) {
        HashMap<String, VideoArea> map = areas.get(dim);
        if (map == null) return;
        for (VideoArea area : map.values()) {
            if (area.removePlayer(uuid)) {
                sendTo(player, VideoPackets.unloadArea(area));
                sendTo(player, VideoPackets.removeArea(area));
            }
        }
    }

    private static void unloadAreaForPlayers(VideoArea area) {
        if (!area.hasPlayer()) return;
        byte[] unload = VideoPackets.unloadArea(area);
        byte[] remove = VideoPackets.removeArea(area);
        for (UUID uuid : area.playerSnapshot()) {
            Player player = onlinePlayers.get(uuid);
            if (player != null) {
                sendTo(player, unload);
                sendTo(player, remove);
            }
        }
    }

    private static void sendAreaSnapshot(Player player, VideoArea area) {
        sendToCurrentThread(player, VideoPackets.createArea(area));
        ServerPacketHandler.sendAreaPermissions(player, area);
        for (VideoScreen screen : area.screens) {
            sendToCurrentThread(player, VideoPackets.createScreen(java.util.List.of(screen)));
            for (Map.Entry<String, com.github.squi2rel.vp.video.MetaValue> entry : screen.metadata.entries().entrySet()) {
                sendToCurrentThread(player, VideoPackets.setMetadata(screen, entry.getKey(), entry.getValue()));
            }
            if (!screen.idlePlayUrls.isEmpty() || screen.idlePlayRandom) {
                sendToCurrentThread(player, VideoPackets.idlePlay(screen));
            }
        }
        boolean loadedPlayback = false;
        for (VideoScreen screen : area.screens) {
            if (screen.currentPlayback() != null) {
                sendToCurrentThread(player, VideoPackets.loadArea(area, screen));
                loadedPlayback = true;
            }
        }
        if (!loadedPlayback) {
            sendToCurrentThread(player, VideoPackets.loadArea(area, null));
        }
        for (VideoScreen screen : area.screens) {
            sendToCurrentThread(player, VideoPackets.updatePlaylist(java.util.List.of(screen)));
        }
    }

    private static ReadResult readConfig(Path path) {
        try {
            if (!Files.exists(path)) return new ReadResult(new ServerConfig(), false, false, 0, 0);
            JsonElement root = JsonParser.parseString(Files.readString(path));
            RecoveryResult recovery = recoverIncorrectLegacyMigration(path, root);
            root = recovery.root();
            boolean hasSharedConfig = root.isJsonObject() && (
                    root.getAsJsonObject().has("remoteControlName")
                            || root.getAsJsonObject().has("remoteControlId")
                            || root.getAsJsonObject().has("remoteControlRange")
                            || root.getAsJsonObject().has("noControlRange")
            );
            LegacyConfigMigrator.Result migration = LegacyConfigMigrator.migrate(root);
            ServerConfig read = gson.fromJson(migration.root(), ServerConfig.class);
            if (read == null) read = new ServerConfig();
            validateConfig(read);
            return new ReadResult(read, migration.migrated() || recovery.recovered(), hasSharedConfig, migration.areas(), migration.screens());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read VideoPlayer world config " + path + "; original file was not modified", e);
        }
    }

    private static RecoveryResult recoverIncorrectLegacyMigration(Path path, JsonElement currentRoot) throws IOException {
        if (!currentRoot.isJsonObject()) return new RecoveryResult(currentRoot, false);
        JsonObject current = currentRoot.getAsJsonObject();
        if (current.has("dataVersion") && current.get("dataVersion").isJsonPrimitive()
                && current.get("dataVersion").getAsInt() >= 2) {
            return new RecoveryResult(currentRoot, false);
        }
        Path backup = path.resolveSibling(path.getFileName() + ".1.6.5.bak");
        if (!Files.isRegularFile(backup)) return new RecoveryResult(currentRoot, false);
        JsonElement original = JsonParser.parseString(Files.readString(backup));
        if (!original.isJsonArray()) return new RecoveryResult(currentRoot, false);
        LegacyConfigMigrator.Result recovered = LegacyConfigMigrator.migrate(original);
        JsonObject restored = recovered.root();
        for (String key : new String[]{"remoteControlName", "remoteControlId", "remoteControlRange", "noControlRange"}) {
            if (current.has(key)) restored.add(key, current.get(key).deepCopy());
        }
        VideoPlayerMain.LOGGER.warn("Repairing incorrectly oriented legacy screens in {} from backup {}", path, backup);
        return new RecoveryResult(restored, true);
    }

    static void validateConfig(ServerConfig loaded) {
        VideoConfigValidator.validate(loaded);
        for (VideoArea area : loaded.areas) {
            for (VideoScreen screen : area.screens) {
                screen.ensureValidState();
                if (screen.surface != ScreenSurface.SPHERE_360 && screen.vertices.size() < 3) {
                    throw new IllegalArgumentException("VideoPlayer flat screen must contain at least three vertices");
                }
                for (var vertex : screen.vertices) {
                    if (vertex == null || !finite(vertex.x, vertex.y, vertex.z)) {
                        throw new IllegalArgumentException("VideoPlayer screen contains an invalid vertex");
                    }
                }
            }
        }
    }

    private static boolean finite(float x, float y, float z) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }

    private static void loadLegacyPluginConfig(VideoPlayerPaperPlugin owner) {
        var old = owner.getConfig();
        if (old.contains("remoteControlName")) config.remoteControlName = old.getString("remoteControlName", config.remoteControlName);
        if (old.contains("remoteControlId")) config.remoteControlId = (float) old.getDouble("remoteControlId", config.remoteControlId);
        if (old.contains("remoteControlRange")) config.remoteControlRange = (float) old.getDouble("remoteControlRange", config.remoteControlRange);
        if (old.contains("noControlRange")) config.noControlRange = (float) old.getDouble("noControlRange", config.noControlRange);
        String areasJson = old.getString("areas");
        if (areasJson == null || areasJson.isBlank() || areasJson.trim().equals("[]")) return;
        LegacyConfigMigrator.Result migration = LegacyConfigMigrator.migrate(JsonParser.parseString(areasJson));
        ServerConfig legacy = gson.fromJson(migration.root(), ServerConfig.class);
        if (legacy == null) return;
        validateConfig(legacy);
        legacyConfigAreas.addAll(legacy.areas);
        VideoPlayerMain.LOGGER.info(
                "Found {} legacy VideoPlayer areas with {} screens in plugin config.yml",
                migration.areas(), migration.screens()
        );
    }

    private static int importLegacyConfigAreas(String dim, HashMap<String, VideoArea> map) {
        int imported = 0;
        var iterator = legacyConfigAreas.iterator();
        while (iterator.hasNext()) {
            VideoArea area = iterator.next();
            if (!dim.equals(area.dim)) continue;
            if (map.containsKey(area.name)) {
                VideoPlayerMain.LOGGER.warn(
                        "Kept conflicting legacy area {} in config.yml because the 2.0 world file already contains that name in {}",
                        area.name, dim
                );
                continue;
            } else if (map.size() >= VideoArea.MAX_AREAS_PER_WORLD) {
                VideoPlayerMain.LOGGER.warn(
                        "Kept legacy area {} in config.yml because world {} already contains {} areas",
                        area.name, dim, VideoArea.MAX_AREAS_PER_WORLD
                );
                continue;
            } else {
                area.dim = dim;
                area.initServer();
                area.afterLoad();
                map.put(area.name, area);
                imported++;
            }
            iterator.remove();
        }
        return imported;
    }

    private static void persistLegacyPluginConfig() {
        try {
            Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            Path backup = backup(configPath, ".1.6.5.bak");
            plugin.getConfig().set("areas", gson.toJson(legacyConfigAreas));
            writeString(configPath, plugin.getConfig().saveToString());
            VideoPlayerMain.LOGGER.info("Updated legacy VideoPlayer config areas; backup: {}", backup);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update legacy VideoPlayer config.yml", e);
        }
    }

    private static Path backup(Path path, String suffix) throws IOException {
        Path backup = path.resolveSibling(path.getFileName() + suffix);
        if (Files.exists(backup) && Files.mismatch(path, backup) == -1) return backup;
        int index = 1;
        while (Files.exists(backup)) {
            backup = path.resolveSibling(path.getFileName() + suffix + "." + index++);
        }
        Files.copy(path, backup);
        return backup;
    }

    private static void applySharedConfig(ServerConfig loaded) {
        if (loaded.remoteControlName != null && !loaded.remoteControlName.isBlank()) {
            config.remoteControlName = loaded.remoteControlName;
        }
        config.remoteControlId = loaded.remoteControlId;
        config.remoteControlRange = loaded.remoteControlRange;
        config.noControlRange = loaded.noControlRange;
    }

    private static void writeString(Path path, String str) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, str, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record ReadResult(ServerConfig config, boolean migrated, boolean hasSharedConfig, int areaCount, int screenCount) {
    }

    private record RecoveryResult(JsonElement root, boolean recovered) {
    }

    record PlayerPosition(String dimension, double x, double y, double z) {
    }
}
