package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(VideoPlayerMain.MOD_ID)
public final class VideoPlayerMain {
    public static final String MOD_ID = "videoplayer";
    public static String version = System.getProperty("videoplayer.version", "unknown");
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final boolean android = Files.exists(Path.of("/system/build.prop"));
    public static Throwable error;
    public static MinecraftServer server;
    public static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, VideoPlayerMain::newDaemon);

    public VideoPlayerMain(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        System.setProperty("videoplayer.version", version);
        VideoProviders.register();
        modBus.addListener(this::registerPayloads);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::serverStarted);
        gameBus.addListener(this::serverStopping);
        gameBus.addListener(this::levelLoaded);
        gameBus.addListener(this::levelUnloaded);
        gameBus.addListener(this::levelSaved);
        gameBus.addListener(this::serverTick);
        gameBus.addListener(this::playerJoined);
        gameBus.addListener(this::playerLeft);
        gameBus.addListener(this::registerCommands);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("vp5").optional()
                .executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK);
        registrar.playBidirectional(VideoPayload.ID, VideoPayload.CODEC, (payload, context) -> {
            if (context.flow() == PacketFlow.CLIENTBOUND) {
                NeoForgeClient.handlePayload(payload, context);
                return;
            }
            if (!(context.player() instanceof ServerPlayer player)) return;
            long receivedAt = System.currentTimeMillis();
            byte[] copy = payload.data().clone();
            if (copy.length > VideoPackets.MAX_PAYLOAD_BYTES) {
                context.disconnect(Component.literal("VideoPlayer payload is too large"));
                return;
            }
            context.enqueueWork(() -> {
                ByteBuf buf = Unpooled.wrappedBuffer(copy);
                try {
                    ServerPacketHandler.handle(player, buf, receivedAt);
                } catch (Exception exception) {
                    LOGGER.error("Failed to handle VideoPlayer packet", exception);
                    context.disconnect(Component.literal(exception.toString()));
                } finally {
                    buf.release();
                }
            });
        });
    }

    private void serverStarted(ServerStartedEvent event) {
        server = event.getServer();
        DataHolder.load(server);
    }

    private void serverStopping(ServerStoppingEvent event) {
        DataHolder.stop(event.getServer());
    }

    private void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.getServer() == server) DataHolder.loadWorld(server, level);
    }

    private void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.getServer() == server) DataHolder.unloadWorld(server, level);
    }

    private void levelSaved(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel && server != null) DataHolder.save();
    }

    private void serverTick(ServerTickEvent.Pre event) {
        if (event.getServer() == server) DataHolder.update();
    }

    private void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DataHolder.playerJoin(player);
    }

    private void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DataHolder.playerLeave(player.getUUID());
    }

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("").then(Commands.argument("command", StringArgumentType.greedyString()).executes(context -> {
            if (!context.getSource().isPlayer()) return 0;
            ServerPacketHandler.sendTo(context.getSource().getPlayer(), VideoPackets.execute(context.getArgument("command", String.class)));
            return 1;
        })));
    }

    private static Thread newDaemon(Runnable task) {
        Thread thread = new Thread(task, "VideoPlayer-Scheduler");
        thread.setDaemon(true);
        return thread;
    }
}
