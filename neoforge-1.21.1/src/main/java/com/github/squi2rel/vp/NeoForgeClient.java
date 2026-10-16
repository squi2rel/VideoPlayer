package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.VideoPayload;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@Mod(value = VideoPlayerMain.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClient {
    private static boolean initialized;

    public NeoForgeClient(IEventBus modBus) {
        modBus.addListener(this::reloadListeners);
        modBus.addListener(this::keyMappings);
        var gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::tick);
        gameBus.addListener(this::joined);
        gameBus.addListener(this::disconnected);
        gameBus.addListener(this::commands);
        gameBus.addListener(this::renderWorld);
        gameBus.addListener(this::renderHud);
        gameBus.addListener(this::interaction);
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        new VideoPlayerClient().onInitializeClient();
    }

    private void reloadListeners(RegisterClientReloadListenersEvent event) {
        initialize();
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) ResourceManagerHelper::reload);
    }

    private void keyMappings(RegisterKeyMappingsEvent event) {
        initialize();
        KeyBindingHelper.registerAll(event);
    }

    private void tick(ClientTickEvent.Post event) { ClientTickEvents.END_CLIENT_TICK.invoke(Minecraft.getInstance()); }
    private void joined(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientPlayConnectionEvents.JOIN.invoke(Minecraft.getInstance().getConnection(), Minecraft.getInstance());
    }
    private void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlayConnectionEvents.DISCONNECT.invoke(Minecraft.getInstance().getConnection(), Minecraft.getInstance());
    }

    private void commands(RegisterClientCommandsEvent event) {
        for (String root : new String[]{"videoplayer", "vlc"}) {
            event.getDispatcher().register(Commands.literal(root)
                    .executes(context -> ClientCommandManager.execute(root))
                    .then(Commands.argument("arguments", StringArgumentType.greedyString())
                            .suggests((context, builder) -> {
                                var dispatcher = ClientCommandManager.getActiveDispatcher();
                                return dispatcher.getCompletionSuggestions(dispatcher.parse(context.getInput(), ClientCommandManager.getSource()));
                            })
                            .executes(context -> ClientCommandManager.execute(context.getInput()))));
        }
    }

    private void renderWorld(RenderLevelStageEvent event) {
        if (CameraRenderer.rendering) return;
        Minecraft client = Minecraft.getInstance();
        WorldRenderContext context = new WorldRenderContext(event.getPoseStack(), client.renderBuffers().bufferSource(), event.getCamera());
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) WorldRenderEvents.START_MAIN.invoke(context);
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) WorldRenderEvents.AFTER_ENTITIES.invoke(context);
    }

    private void renderHud(RenderGuiEvent.Post event) { HudElementRegistry.render(event.getGuiGraphics(), event.getPartialTick()); }

    private void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        boolean cancel = false;
        if (event.isUseItem() && client.hitResult instanceof BlockHitResult hit) {
            cancel |= UseBlockCallback.EVENT.invoke(client.player, client.level, event.getHand(), hit) != InteractionResult.PASS;
        }
        if (cancel) { event.setCanceled(true); event.setSwingHand(false); }
    }

    public static void handlePayload(VideoPayload payload, IPayloadContext context) {
        ClientPlayNetworking.receive(payload);
    }
}
