package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.i18n.MinecraftTexts;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
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
import java.util.*;

public class DataHolder {
    public static ServerConfig config = new ServerConfig();
    public static HashSet<UUID> allPlayers = new HashSet<>();
    public static HashMap<UUID, String> playerDim = new HashMap<>();

    public static HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final HashMap<String, Path> worldFiles = new HashMap<>();

    public static MinecraftServer server;

    public static void update() {
        PlayerManager pm = server.getPlayerManager();
        ArrayList<Runnable> notifications = new ArrayList<>();
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
                        area.playerEntered();
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.createArea(area)));
                        notifications.add(() -> ServerPacketHandler.sendAreaPermissions(player, area));
                        if (area.screens.isEmpty()) {
                            notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.loadArea(area)));
                            continue;
                        }
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.createScreen(area.screens)));
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.loadArea(area)));
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.updatePlaylist(area.screens)));
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
        for (Map.Entry<UUID, String> entry : playerDim.entrySet()) {
            ServerPlayerEntity player = pm.getPlayer(entry.getKey());
            if (player == null) continue;
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!dim.equals(entry.getValue())) {
                HashMap<String, VideoArea> map = areas.get(entry.getValue());
                if (map == null) continue;
                for (VideoArea area : map.values()) {
                    if (area.removePlayer(player.getUuid())) {
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.unloadArea(area)));
                        notifications.add(() -> ServerPacketHandler.sendTo(player, VideoPackets.removeArea(area)));
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
        ServerPacketHandler.sendTo(player, VideoPackets.config(VideoPlayerMain.version, config));
        ServerPacketHandler.sendGlobalPermissions(player);
    }

    public static void playerLeave(UUID uuid) {
        allPlayers.remove(uuid);
        playerDim.remove(uuid);
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
        save();
        unload(server);
        areas.clear();
        worldFiles.clear();
        allPlayers.clear();
        playerDim.clear();
    }

    public static void save() {
        for (String dim : new ArrayList<>(areas.keySet())) {
            saveWorld(dim);
        }
    }

    public static void load(MinecraftServer server) {
        DataHolder.server = server;
        config = new ServerConfig();
        areas.clear();
        worldFiles.clear();
        allPlayers.clear();
        playerDim.clear();
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
        ServerConfig loaded = readConfig(path);
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
        playerDim.entrySet().removeIf(entry -> dim.equals(entry.getValue()));
        VideoPlayerMain.LOGGER.info("Unloaded VideoPlayer world {}", dim);
    }

    public static void saveWorld(String dim) {
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

    private static void prepareArea(VideoArea area) {
        if (area.screens == null) area.screens = new ArrayList<>();
        for (VideoScreen screen : area.screens) {
            if (screen.metadata == null) screen.metadata = new com.github.squi2rel.vp.video.ScreenMetadata();
            screen.metadata.ensureValid();
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

    private static ServerConfig readConfig(Path path) {
        try {
            if (!Files.exists(path)) return new ServerConfig();
            ServerConfig read = gson.fromJson(Files.readString(path), ServerConfig.class);
            return read == null ? new ServerConfig() : read;
        } catch (Exception e) {
            VideoPlayerMain.LOGGER.warn("Failed to read VideoPlayer world config {}", path, e);
            return new ServerConfig();
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
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
