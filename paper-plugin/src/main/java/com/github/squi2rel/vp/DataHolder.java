package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.i18n.PaperTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.google.gson.Gson;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public final class DataHolder {
    public static final Object LOCK = new Object();
    public static ServerConfig config = new ServerConfig();
    public static final HashSet<UUID> allPlayers = new HashSet<>();
    public static final HashMap<UUID, String> playerDim = new HashMap<>();
    public static final HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final HashMap<String, Path> worldFiles = new HashMap<>();
    private static final HashMap<UUID, ScheduledTask> playerTasks = new HashMap<>();
    private static final HashMap<UUID, Player> onlinePlayers = new HashMap<>();
    private static VideoPlayerPaperPlugin plugin;

    private DataHolder() {
    }

    public static void start(VideoPlayerPaperPlugin owner) {
        plugin = owner;
        config = new ServerConfig();
        VideoPlayerMain.resetScheduler();
        VideoPlayerMain.server = DataHolder::executeState;
    }

    public static void stop() {
        synchronized (LOCK) {
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
            allPlayers.clear();
            playerDim.clear();
            onlinePlayers.clear();
        }
    }

    public static void executeState(Runnable runnable) {
        VideoPlayerPaperPlugin owner = plugin;
        if (owner == null || !owner.isEnabled()) {
            synchronized (LOCK) {
                runnable.run();
            }
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(owner, task -> {
            synchronized (LOCK) {
                runnable.run();
            }
        });
    }

    public static void loadWorld(World world) {
        synchronized (LOCK) {
            String dim = worldKey(world);
            if (areas.containsKey(dim)) return;
            Path path = world.getWorldFolder().toPath().resolve("videoplayer.json");
            worldFiles.put(dim, path);
            boolean existingConfig = Files.exists(path);
            ServerConfig loaded = readConfig(path);
            if (existingConfig) applySharedConfig(loaded);
            HashMap<String, VideoArea> map = new HashMap<>();
            if (loaded.areas != null) {
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
            playerDim.entrySet().removeIf(entry -> dim.equals(entry.getValue()));
            VideoPlayerMain.LOGGER.info("Unloaded VideoPlayer world {}", dim);
        }
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

    public static void playerJoin(Player player) {
        startTracking(player);
        sendHandshake(player, true);
        VideoPlayerPaperPlugin owner = plugin;
        if (owner != null && owner.isEnabled()) {
            player.getScheduler().runDelayed(owner, task -> sendHandshake(player, false), null, 20L);
        }
    }

    private static void sendHandshake(Player player, boolean includeBackendError) {
        sendTo(player, VideoPackets.config(VideoPlayerMain.version, config));
        ServerPacketHandler.sendGlobalPermissions(player);
        if (includeBackendError && VideoPlayerMain.error != null) {
            message(player, VpTranslation.of(
                    "message.videoplayer.backend_load_failed_short",
                    "VideoPlayer error: video backend failed to load: %s",
                    VideoPlayerMain.error
            ));
        }
    }

    public static void playerLeave(UUID uuid) {
        synchronized (LOCK) {
            allPlayers.remove(uuid);
            playerDim.remove(uuid);
            onlinePlayers.remove(uuid);
            ScheduledTask task = playerTasks.remove(uuid);
            if (task != null) task.cancel();
            for (HashMap<String, VideoArea> map : areas.values()) {
                for (VideoArea area : map.values()) {
                    area.removePlayer(uuid);
                }
            }
        }
    }

    public static void updatePlayer(Player player) {
        synchronized (LOCK) {
            UUID uuid = player.getUniqueId();
            if (!allPlayers.contains(uuid)) return;
            World world = player.getWorld();
            String dim = worldKey(world);
            if (!areas.containsKey(dim)) {
                loadWorld(world);
            }
            String previousDim = playerDim.get(uuid);
            if (previousDim != null && !previousDim.equals(dim)) {
                removeFromWorld(uuid, previousDim, player);
            }
            HashMap<String, VideoArea> all = areas.get(dim);
            Location location = player.getLocation();
            if (all != null) {
                for (VideoArea area : all.values()) {
                    if (area.inBounds(location.getX(), location.getY(), location.getZ())) {
                        if (area.addPlayer(uuid)) {
                            sendTo(player, VideoPackets.createArea(area));
                            ServerPacketHandler.sendAreaPermissions(player, area);
                            if (!area.screens.isEmpty()) {
                                sendTo(player, VideoPackets.createScreen(area.screens));
                            }
                            sendTo(player, VideoPackets.loadArea(area));
                            if (!area.screens.isEmpty()) {
                                sendTo(player, VideoPackets.updatePlaylist(area.screens));
                            }
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
        }
    }

    public static void runForPlayer(Player player, Runnable runnable) {
        VideoPlayerPaperPlugin owner = plugin;
        if (owner == null || !owner.isEnabled()) return;
        player.getScheduler().run(owner, task -> runnable.run(), null);
    }

    public static void sendTo(Player player, byte[] bytes) {
        if (player == null || bytes == null) return;
        runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, VideoPlayerPaperPlugin.CHANNEL, bytes);
            }
        });
    }

    public static void sendToCurrentThread(Player player, byte[] bytes) {
        if (player == null || bytes == null || plugin == null || !plugin.isEnabled()) return;
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
        ArrayList<Player> players = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            Player target = onlinePlayers.get(uuid);
            if (target != null) {
                players.add(target);
            }
        }
        return players;
    }

    public static String worldKey(World world) {
        return world.getKey().toString();
    }

    private static void startTracking(Player player) {
        synchronized (LOCK) {
            onlinePlayers.put(player.getUniqueId(), player);
            ScheduledTask old = playerTasks.remove(player.getUniqueId());
            if (old != null) old.cancel();
            ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> updatePlayer(player), null, 1L, 1L);
            playerTasks.put(player.getUniqueId(), task);
        }
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
        if (loaded.remoteControlName != null && !loaded.remoteControlName.isBlank()) {
            config.remoteControlName = loaded.remoteControlName;
        }
        config.remoteControlId = loaded.remoteControlId;
        config.remoteControlRange = loaded.remoteControlRange;
        config.noControlRange = loaded.noControlRange;
    }

    private static void writeString(Path path, String str) {
        try {
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
