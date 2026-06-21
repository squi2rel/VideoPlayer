package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.video.StreamListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

public final class VideoPlayerPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    public static final String CHANNEL = "videoplayer:video";

    @Override
    public void onEnable() {
        VideoPlayerMain.version = getPluginMeta().getVersion();
        VideoPlayerMain.error = null;
        VideoPlayerMain.LOGGER.info("Starting VideoPlayer Paper/Folia plugin {}", VideoPlayerMain.version);
        System.setProperty("videoplayer.configDir", getDataFolder().toPath().toAbsolutePath().toString());
        saveDefaultConfig();
        reloadConfig();
        PaperNativeConfig nativeConfig = PaperNativeConfig.load(this);
        nativeConfig.apply();
        if (VideoPlayerMain.android) {
            VideoPlayerMain.LOGGER.warn("Android device detected; native playback backends may be unstable");
            Android.load();
        }
        nativeConfig.downloadIfMissing();
        if (!StreamListener.load()) {
            VideoPlayerMain.error = new IllegalStateException("Cannot load stream listener backend");
            VideoPlayerMain.LOGGER.error("Cannot load stream listener backend; plugin channel will remain available for clients");
        }
        VideoProviders.register();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        DataHolder.start(this);
        for (World world : Bukkit.getWorlds()) {
            DataHolder.loadWorld(world);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, task -> DataHolder.playerJoin(player), null);
        }
    }

    @Override
    public void onDisable() {
        DataHolder.stop();
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
        long receivedAt = System.currentTimeMillis();
        byte[] copy = message.clone();
        DataHolder.runForPlayer(player, () -> {
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
