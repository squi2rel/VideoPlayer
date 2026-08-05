package com.github.squi2rel.vp;

import com.github.squi2rel.vp.command.VlcCommand;
import com.github.squi2rel.vp.command.VlcVersionCommand;
import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.permission.ResidencePermissionBridge;
import com.github.squi2rel.vp.permission.VideoPermissions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoPlayerPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    public static final String CHANNEL = "videoplayer:video";
    private FoliaScheduler.TaskHandle ytDlpTask;
    private FoliaScheduler.TaskHandle commandRepairTask;
    private PaperNativeRuntime nativeRuntime;
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private volatile boolean active;

    @Override
    public void onEnable() {
        active = true;
        FoliaScheduler.initialize(this);
        DisplayCleanupService.initialize(this);
        long epoch = lifecycleEpoch.incrementAndGet();
        VideoPlayerMain.version = getPluginMeta().getVersion();
        System.setProperty("videoplayer.version", VideoPlayerMain.version);
        VideoPlayerMain.error = null;
        VideoPlayerMain.resetScheduler();
        VideoPlayerMain.LOGGER.info("Starting VideoPlayer Paper/Folia plugin {}", VideoPlayerMain.version);
        System.setProperty("videoplayer.configDir", getDataFolder().toPath().toAbsolutePath().toString());
        saveDefaultConfig();
        reloadConfig();
        ClientVersionTracker.initialize(this);
        VlcCommand vlcCommand = new VlcCommand();
        PluginCommand vlcPluginCommand = Objects.requireNonNull(getCommand("vlc"));
        vlcPluginCommand.setExecutor(vlcCommand);
        vlcPluginCommand.setTabCompleter(vlcCommand);
        PluginCommand versionPluginCommand = Objects.requireNonNull(getCommand("vlcversion"));
        versionPluginCommand.setExecutor(new VlcVersionCommand());
        PaperNativeConfig nativeConfig = PaperNativeConfig.load(this);
        nativeConfig.apply();
        VideoProviders.register();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        DataHolder.start(this);
        nativeRuntime = PaperNativeRuntime.start(this, nativeConfig);
        ytDlpTask = FoliaScheduler.runAsync(() -> {
            try {
                nativeConfig.downloadYtDlpIfMissing(() -> active && lifecycleEpoch.get() == epoch);
            } catch (CancellationException ignored) {
            }
        });
        ResidencePermissionBridge.initialize(this);
        VideoPermissions.setAreaResolver(ResidencePermissionBridge::resolve);
        for (Player player : Bukkit.getOnlinePlayers()) {
            DataHolder.runStateForPlayer(player, () -> {
                ClientVersionTracker.playerJoined(player);
                DataHolder.playerJoin(player);
                DataHolder.scheduleReloadHandshake(player);
            });
        }
        commandRepairTask = FoliaScheduler.runGlobalDelayed(
                () -> repairCommandsAfterReload(epoch, vlcPluginCommand, versionPluginCommand),
                1L
        );
    }

    @Override
    public void onDisable() {
        active = false;
        ClientVersionTracker.shutdown();
        ResidencePermissionBridge.shutdown();
        if (ytDlpTask != null) {
            ytDlpTask.cancel();
            ytDlpTask = null;
        }
        if (commandRepairTask != null) {
            commandRepairTask.cancel();
            commandRepairTask = null;
        }
        DataHolder.stop();
        if (nativeRuntime != null) {
            nativeRuntime.stop();
            nativeRuntime = null;
        }
        HandlerList.unregisterAll((Listener) this);
        VideoPermissions.reset();
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        VideoPlayerMain.scheduler.shutdownNow();
        DisplayCleanupService.shutdown();
        FoliaScheduler.shutdown(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ClientVersionTracker.playerJoined(player);
        DataHolder.runStateForPlayer(player, () -> DataHolder.playerJoin(player));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ClientVersionTracker.playerLeft(uuid);
        DataHolder.playerLeave(uuid);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) return;
        if (message.length > com.github.squi2rel.vp.network.VideoPackets.MAX_PAYLOAD_BYTES) {
            DataHolder.disconnect(player, "VideoPlayer payload is too large");
            return;
        }
        long receivedAt = System.currentTimeMillis();
        byte[] copy = message.clone();
        DataHolder.runStateForPlayer(player, () -> {
            ByteBuf buf = Unpooled.wrappedBuffer(copy);
            try {
                ServerPacketHandler.handle(player, buf, receivedAt);
            } catch (Exception e) {
                VideoPlayerMain.LOGGER.warn("Disconnecting {} after illegal VideoPlayer packet", player.getName(), e);
                DataHolder.disconnect(player, e.toString());
            } finally {
                buf.release();
            }
        });
    }

    private void repairCommandsAfterReload(long epoch, Command vlcCommand, Command versionCommand) {
        if (!active || lifecycleEpoch.get() != epoch || !isEnabled()) return;
        boolean restored = restoreCommandMappings(
                Bukkit.getCommandMap().getKnownCommands(),
                this,
                vlcCommand,
                versionCommand
        );
        if (restored) VideoPlayerMain.LOGGER.info("Restored VideoPlayer command mappings after plugin reload");
        for (Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runAtEntity(player, () -> {
                if (active && lifecycleEpoch.get() == epoch && isEnabled() && player.isOnline()) {
                    player.updateCommands();
                }
            }, null);
        }
    }

    static boolean restoreCommandMappings(
            Map<String, Command> commands,
            Plugin plugin,
            Command vlcCommand,
            Command versionCommand
    ) {
        boolean changed = commands.put("videoplayer:vlc", vlcCommand) != vlcCommand;
        Command bareVlc = commands.get("vlc");
        if (bareVlc == vlcCommand || belongsToPlugin(bareVlc, plugin)) {
            changed |= commands.remove("vlc", bareVlc);
        }
        changed |= commands.put("videoplayer:vlcversion", versionCommand) != versionCommand;
        Command bareVersion = commands.get("vlcversion");
        if (bareVersion == null || bareVersion == versionCommand || belongsToPlugin(bareVersion, plugin)) {
            changed |= commands.put("vlcversion", versionCommand) != versionCommand;
        }
        return changed;
    }

    private static boolean belongsToPlugin(Command command, Plugin plugin) {
        if (!(command instanceof PluginIdentifiableCommand identifiable) || plugin == null) return false;
        Plugin commandPlugin = identifiable.getPlugin();
        return commandPlugin == plugin
                || commandPlugin != null && commandPlugin.getName().equalsIgnoreCase(plugin.getName());
    }
}
