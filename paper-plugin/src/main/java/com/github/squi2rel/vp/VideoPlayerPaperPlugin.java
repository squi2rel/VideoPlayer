package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.permission.ResidencePermissionBridge;
import com.github.squi2rel.vp.permission.VideoPermissions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoPlayerPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    public static final String CHANNEL = "videoplayer:video";
    private ScheduledTask ytDlpTask;
    private PaperNativeRuntime nativeRuntime;
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private volatile boolean active;

    @Override
    public void onEnable() {
        active = true;
        long epoch = lifecycleEpoch.incrementAndGet();
        VideoPlayerMain.version = getPluginMeta().getVersion();
        System.setProperty("videoplayer.version", VideoPlayerMain.version);
        VideoPlayerMain.error = null;
        VideoPlayerMain.resetScheduler();
        VideoPlayerMain.LOGGER.info("Starting VideoPlayer Paper/Folia plugin {}", VideoPlayerMain.version);
        System.setProperty("videoplayer.configDir", getDataFolder().toPath().toAbsolutePath().toString());
        saveDefaultConfig();
        reloadConfig();
        PaperNativeConfig nativeConfig = PaperNativeConfig.load(this);
        nativeConfig.apply();
        VideoProviders.register();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        DataHolder.start(this);
        nativeRuntime = PaperNativeRuntime.start(this, nativeConfig);
        ytDlpTask = getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                nativeConfig.downloadYtDlpIfMissing(() -> active && lifecycleEpoch.get() == epoch);
            } catch (CancellationException ignored) {
            }
        });
        ResidencePermissionBridge.initialize();
        VideoPermissions.setAreaChecker(ResidencePermissionBridge::allowed);
        for (World world : Bukkit.getWorlds()) {
            DataHolder.loadWorld(world);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            DataHolder.runStateForPlayer(player, () -> DataHolder.playerJoin(player));
        }
    }

    @Override
    public void onDisable() {
        active = false;
        if (nativeRuntime != null) {
            nativeRuntime.stop();
            nativeRuntime = null;
        }
        if (ytDlpTask != null) {
            ytDlpTask.cancel();
            ytDlpTask = null;
        }
        DataHolder.stop();
        VideoPermissions.reset();
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        VideoPlayerMain.scheduler.shutdownNow();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        DataHolder.loadWorld(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        DataHolder.unloadWorld(event.getWorld());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        DataHolder.playerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        DataHolder.playerLeave(event.getPlayer().getUniqueId());
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
}
