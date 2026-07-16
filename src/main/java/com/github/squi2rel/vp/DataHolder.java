package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoHandshakeState;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.i18n.MinecraftTexts;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.video.ScreenKey;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.Formatting;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DataHolder {
    public static ServerConfig config = new ServerConfig();
    public static HashSet<UUID> allPlayers = new HashSet<>();
    public static HashMap<UUID, String> playerDim = new HashMap<>();

    public static HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final HashMap<String, Path> worldFiles = new HashMap<>();
    private static final HashSet<String> invalidWorldConfigs = new HashSet<>();
    private static final HashMap<UUID, VideoHandshakeState> handshakes = new HashMap<>();
    private static final HashMap<UUID, Long> handshakeNonces = new HashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, UUID> onlinePlayerNames = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long lifecycleEpoch;
    private static volatile boolean running;

    public static volatile MinecraftServer server;

    public static void update() {
        PlayerManager pm = server.getPlayerManager();
        ArrayList<Runnable> notifications = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : new ArrayList<>(playerDim.entrySet())) {
            ServerPlayerEntity player = pm.getPlayer(entry.getKey());
            if (player == null) continue;
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            if (dim.equals(entry.getValue())) continue;
            HashMap<String, VideoArea> map = areas.get(entry.getValue());
            if (map == null) continue;
            for (VideoArea area : map.values()) {
                if (area.removePlayer(player.getUuid())) {
                    ServerPacketHandler.sendTo(player, VideoPackets.unloadArea(area));
                    ServerPacketHandler.sendTo(player, VideoPackets.removeArea(area));
                }
            }
        }
        for (UUID uuid : allPlayers) {
            ServerPlayerEntity player = pm.getPlayer(uuid);
            if (player == null) continue;
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            HashMap<String, VideoArea> all = areas.get(dim);
            if (all == null) {
                loadWorld(server, player.getEntityWorld());
                all = areas.get(dim);
            }
            if (all == null || all.isEmpty()) continue;
            for (VideoArea area : all.values()) {
                if (area.inBounds(player.getEntityPos())) {
                    if (area.addPlayer(player.getUuid())) {
                        sendAreaSnapshot(player, area);
                        area.playerEntered();
                        notifications.add(() -> player.sendMessage(MinecraftTexts.tr(
                                "message.videoplayer.area_enter",
                                "Entered video area %s",
                                area.name
                        ).formatted(Formatting.DARK_AQUA), true));
                    }
                } else {
                    if (area.removePlayer(player.getUuid())) {
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.unloadArea(area)));
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.removeArea(area)));
                        notifications.add(() -> player.sendMessage(MinecraftTexts.tr(
                                "message.videoplayer.area_leave",
                                "Left video area %s",
                                area.name
                        ).formatted(Formatting.DARK_AQUA), true));
                    }
                }
            }
        }
        for (ServerPlayerEntity player : PlayerLookup.all(server)) {
            playerDim.put(player.getUuid(), player.getEntityWorld().getRegistryKey().getValue().toString());
        }
        notifications.forEach(Runnable::run);
    }

    public static void unload(MinecraftServer s) {
        PlayerManager pm = s.getPlayerManager();
        for (HashMap<String, VideoArea> map : areas.values()) {
            for (VideoArea area : map.values()) {
                unloadArea(pm, area);
                area.remove();
            }
        }
    }

    public static void playerJoin(ServerPlayerEntity player) {
        handshakes.put(player.getUuid(), VideoHandshakeState.NEEDS_RESET);
        handshakeNonces.remove(player.getUuid());
        onlinePlayerNames.put(player.getGameProfile().name().toLowerCase(Locale.ROOT), player.getUuid());
    }

    public static void playerLeave(UUID uuid) {
        allPlayers.remove(uuid);
        playerDim.remove(uuid);
        handshakes.remove(uuid);
        handshakeNonces.remove(uuid);
        onlinePlayerNames.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
        if (server != null) {
            server.execute(() -> {
                for (HashMap<String, VideoArea> value : areas.values()) {
                    for (VideoArea area : value.values()) {
                        area.removePlayer(uuid);
                    }
                }
            });
        }
    }

    public static void stop(MinecraftServer server) {
        running = false;
        lifecycleEpoch++;
        save();
        unload(server);
        areas.clear();
        worldFiles.clear();
        invalidWorldConfigs.clear();
        allPlayers.clear();
        playerDim.clear();
        handshakes.clear();
        handshakeNonces.clear();
        onlinePlayerNames.clear();
    }

    public static void save() {
        for (String dim : new ArrayList<>(areas.keySet())) {
            saveWorld(dim);
        }
    }

    public static void load(MinecraftServer server) {
        DataHolder.server = server;
        running = true;
        lifecycleEpoch++;
        config = new ServerConfig();
        areas.clear();
        worldFiles.clear();
        invalidWorldConfigs.clear();
        allPlayers.clear();
        playerDim.clear();
        handshakes.clear();
        handshakeNonces.clear();
        onlinePlayerNames.clear();
        for (ServerWorld world : server.getWorlds()) {
            loadWorld(server, world);
        }
    }

    public static void loadWorld(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) return;
        DataHolder.server = server;
        String dim = world.getRegistryKey().getValue().toString();
        if (areas.containsKey(dim)) return;

        Path path = worldDirectory(server, world).resolve("videoplayer.json");
        worldFiles.put(dim, path);

        boolean existingConfig = Files.exists(path);
        ServerConfig loaded;
        try {
            loaded = readConfig(path);
            invalidWorldConfigs.remove(dim);
        } catch (RuntimeException error) {
            invalidWorldConfigs.add(dim);
            areas.put(dim, new HashMap<>());
            VideoPlayerMain.LOGGER.warn("Rejected invalid VideoPlayer world config {}; original file will not be modified", path, error);
            return;
        }
        if (existingConfig) applySharedConfig(loaded);

        HashMap<String, VideoArea> map = new HashMap<>();
        if (loaded.areas != null) {
            for (VideoArea area : loaded.areas) {
                area.dim = dim;
                if (area.screens == null) area.screens = new ArrayList<>();
                prepareArea(area);
                map.put(area.name, area);
            }
        }
        areas.put(dim, map);
        VideoPlayerMain.LOGGER.info("Loaded {} VideoPlayer areas for world {} from {}", map.size(), dim, path);
    }

    public static void unloadWorld(MinecraftServer server, ServerWorld world) {
        if (world == null) return;
        String dim = world.getRegistryKey().getValue().toString();
        saveWorld(dim);
        HashMap<String, VideoArea> map = areas.remove(dim);
        if (map != null) {
            PlayerManager pm = server == null ? null : server.getPlayerManager();
            for (VideoArea area : map.values()) {
                unloadArea(pm, area);
                area.remove();
            }
        }
        worldFiles.remove(dim);
        invalidWorldConfigs.remove(dim);
        playerDim.entrySet().removeIf(entry -> dim.equals(entry.getValue()));
        VideoPlayerMain.LOGGER.info("Unloaded VideoPlayer world {}", dim);
    }

    public static void saveWorld(String dim) {
        if (invalidWorldConfigs.contains(dim)) {
            VideoPlayerMain.LOGGER.warn("Skipped saving VideoPlayer world {} because its loaded config was invalid", dim);
            return;
        }
        Path path = worldFiles.get(dim);
        HashMap<String, VideoArea> map = areas.get(dim);
        if (path == null || map == null) return;
        ServerConfig saved = new ServerConfig();
        saved.remoteControlName = config.remoteControlName;
        saved.remoteControlId = config.remoteControlId;
        saved.remoteControlRange = config.remoteControlRange;
        saved.noControlRange = config.noControlRange;
        saved.areas = new ArrayList<>(map.values());
        writeString(path, gson.toJson(saved));
    }

    public static boolean worldConfigValid(String dim) {
        return dim != null && !invalidWorldConfigs.contains(dim);
    }

    private static void prepareArea(VideoArea area) {
        VideoConfigValidator.validateArea(area);
        for (VideoScreen screen : area.screens) {
            screen.ensureValidState();
        }
        area.initServer();
        area.afterLoad();
    }

    private static void unloadArea(PlayerManager pm, VideoArea area) {
        if (pm == null || area == null || !area.hasPlayer()) return;
        byte[] unload = VideoPackets.unloadArea(area);
        byte[] remove = VideoPackets.removeArea(area);
        for (UUID uuid : area.playerSnapshot()) {
            ServerPlayerEntity player = pm.getPlayer(uuid);
            ServerPacketHandler.sendTo(player, unload);
            ServerPacketHandler.sendTo(player, remove);
        }
    }

    private static void sendAreaSnapshot(ServerPlayerEntity player, VideoArea area) {
        ServerPacketHandler.sendTo(player, VideoPackets.createArea(area));
        ServerPacketHandler.sendAreaPermissions(player, area);
        for (VideoScreen screen : area.screens) {
            ServerPacketHandler.sendTo(player, VideoPackets.createScreen(List.of(screen)));
            for (Map.Entry<String, com.github.squi2rel.vp.video.MetaValue> entry : screen.metadata.entries().entrySet()) {
                ServerPacketHandler.sendTo(player, VideoPackets.setMetadata(screen, entry.getKey(), entry.getValue()));
            }
            if (!screen.idlePlayUrls.isEmpty() || screen.idlePlayRandom) {
                ServerPacketHandler.sendTo(player, VideoPackets.idlePlay(screen));
            }
        }
        boolean loadedPlayback = false;
        for (VideoScreen screen : area.screens) {
            if (screen.currentPlayback() != null) {
                ServerPacketHandler.sendTo(player, VideoPackets.loadArea(area, screen));
                loadedPlayback = true;
            }
        }
        if (!loadedPlayback) {
            ServerPacketHandler.sendTo(player, VideoPackets.loadArea(area, null));
        }
        for (VideoScreen screen : area.screens) {
            ServerPacketHandler.sendTo(player, VideoPackets.updatePlaylist(List.of(screen)));
        }
    }

    private static ServerConfig readConfig(Path path) {
        try {
            if (!Files.exists(path)) return new ServerConfig();
            ServerConfig read = gson.fromJson(Files.readString(path), ServerConfig.class);
            if (read == null) read = new ServerConfig();
            VideoConfigValidator.validate(read);
            return read;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read VideoPlayer world config " + path, e);
        }
    }

    private static void applySharedConfig(ServerConfig loaded) {
        if (loaded == null) return;
        if (loaded.remoteControlName != null && !loaded.remoteControlName.isBlank()) {
            config.remoteControlName = loaded.remoteControlName;
        }
        config.remoteControlId = loaded.remoteControlId;
        config.remoteControlRange = loaded.remoteControlRange;
        config.noControlRange = loaded.noControlRange;
    }

    private static Path worldDirectory(MinecraftServer server, ServerWorld world) {
        return DimensionType.getSaveDirectory(world.getRegistryKey(), server.getSavePath(WorldSavePath.ROOT));
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeString(Path path, String str) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, str, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    public static VideoHandshakeState acceptHandshake(UUID uuid) {
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

    public static long issueHandshakeNonce(UUID uuid) {
        long nonce;
        do {
            nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        } while (nonce == 0L);
        handshakeNonces.put(uuid, nonce);
        return nonce;
    }

    public static long handshakeNonce(UUID uuid) {
        return handshakeNonces.getOrDefault(uuid, 0L);
    }

    public static boolean acceptHandshakeAck(UUID uuid, long nonce) {
        if (nonce == 0L || handshakeNonces.getOrDefault(uuid, 0L) != nonce) return false;
        if (handshakes.get(uuid) != VideoHandshakeState.RESET_SENT) return false;
        handshakeNonces.remove(uuid);
        handshakes.put(uuid, VideoHandshakeState.ACTIVE);
        allPlayers.add(uuid);
        return true;
    }

    public static VideoHandshakeState handshakeState(UUID uuid) {
        return handshakes.getOrDefault(uuid, VideoHandshakeState.NEEDS_RESET);
    }

    public static boolean rejectHandshake(UUID uuid) {
        VideoHandshakeState previous = handshakes.put(uuid, VideoHandshakeState.REJECTED);
        allPlayers.remove(uuid);
        return previous != VideoHandshakeState.REJECTED;
    }

    public static boolean protocolActive(UUID uuid) {
        return handshakes.get(uuid) == VideoHandshakeState.ACTIVE;
    }

    public static long lifecycleEpoch() {
        return lifecycleEpoch;
    }

    public static boolean lifecycleActive(long epoch) {
        return running && lifecycleEpoch == epoch;
    }

    public static void executeState(long epoch, Runnable runnable) {
        MinecraftServer current = server;
        if (current == null || runnable == null) return;
        current.execute(() -> {
            if (lifecycleActive(epoch)) runnable.run();
        });
    }

    public static UUID onlinePlayerUuid(String name) {
        if (name == null) return null;
        return onlinePlayerNames.get(name.toLowerCase(Locale.ROOT));
    }

    public static void message(UUID uuid, long epoch, String message) {
        MinecraftServer current = server;
        if (current == null || message == null) return;
        current.execute(() -> {
            if (!lifecycleActive(epoch)) return;
            ServerPlayerEntity player = current.getPlayerManager().getPlayer(uuid);
            if (player != null) player.sendMessage(net.minecraft.text.Text.of(message));
        });
    }

    public static void message(UUID uuid, long epoch, com.github.squi2rel.vp.i18n.VpTranslation message) {
        MinecraftServer current = server;
        if (current == null || message == null) return;
        current.execute(() -> {
            if (!lifecycleActive(epoch)) return;
            ServerPlayerEntity player = current.getPlayerManager().getPlayer(uuid);
            if (player != null) player.sendMessage(MinecraftTexts.text(message));
        });
    }

    public static VideoScreen findScreen(ScreenKey key) {
        if (key == null) return null;
        HashMap<String, VideoArea> world = areas.get(key.dimension());
        if (world == null) return null;
        VideoArea area = world.get(key.areaName());
        return area == null ? null : area.getScreen(key.screenName());
    }
}
