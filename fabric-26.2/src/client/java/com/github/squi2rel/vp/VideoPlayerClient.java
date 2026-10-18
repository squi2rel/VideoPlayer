package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.network.VideoProtocol;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import com.github.squi2rel.vp.creation.StartupGuideScreen;
import com.github.squi2rel.vp.creation.VideoCreationEditor;
import com.github.squi2rel.vp.creation.BiliLoginScreen;
import com.github.squi2rel.vp.creation.ServerStateScreen;
import com.github.squi2rel.vp.creation.VideoManagementScreen;
import com.github.squi2rel.vp.creation.YouTubeAuthScreen;
import com.github.squi2rel.vp.danmaku.BiliAuthRefresher;
import com.github.squi2rel.vp.danmaku.BiliCookie;
import com.github.squi2rel.vp.danmaku.ClientDanmakuController;
import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.command.VideoPlayerCommandHelp;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.video.*;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.VideoPlayerMain.error;


@SuppressWarnings({"DataFlowIssue"})
public class VideoPlayerClient implements ClientModInitializer {
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000L;
    public static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("videoplayer").resolve("videoplayer-client.json");
    private static final Path startupGuideVersionPath = configPath.getParent().resolve("startup-guide-version.txt");
    public static final Minecraft client = Minecraft.getInstance();
    private static final VideoConnectionDiagnostics connectionDiagnostics = new VideoConnectionDiagnostics(
            HANDSHAKE_TIMEOUT_MS,
            System::currentTimeMillis,
            VideoPlayerClient::logConnectionEvent
    );
    public static Config config;
    private static int savedVolume = 100;
    private static final Gson gson = new Gson();
    private static volatile AudioChannelMode activeAudioChannelMode = AudioChannelMode.STEREO;

    public static final HashMap<String, ClientVideoArea> areas = new HashMap<>();
    public static final ArrayList<ClientVideoScreen> screens = new ArrayList<>();
    private static final TouchHandler touchHandler = new TouchHandler();
    private static ClientVideoScreen currentLooking, currentScreen;
    private static boolean isInArea = false;
    private static final BossEvent bossBar = new LerpingBossEvent(UUID.randomUUID(), Component.nullToEmpty(""), 0, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
    private static boolean bossBarAdded = false;
    private static boolean keyPressed = false;
    private static boolean startupGuideOpened = false;
    private static boolean pendingStartupGuideScreen = false;
    private static boolean pendingBiliLoginScreen = false;
    private static boolean pendingYouTubeAuthScreen = false;
    private static boolean joinHandshakePending;
    private static boolean protocolRejected;
    private static boolean protocolMismatchShown;
    private static long handshakeNonce;

    public static boolean connected = false;
    public static String remoteControlName = "minecraft:iron_ingot";
    public static float remoteControlId = -1;
    public static float remoteControlRange = 64;
    public static float noControlRange = 16;
    public static boolean remoteControl = false;

    public static boolean updated = false;
    public static Runnable disconnectHandler = () -> {};

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_AREAS = (context, builder) -> {
        for (ClientVideoArea a : areas.values()) {
            if (a.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + a.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_REAL_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!screen.source.isEmpty() || !((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    @Override
    public void onInitializeClient() {
        if (error != null) {
            ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.player.sendSystemMessage(VpTexts.tr(
                    "message.videoplayer.backend_load_failed",
                    "VideoPlayer error: video backend failed to load\n%s\nSee logs for more information",
                    error
            ).withStyle(ChatFormatting.RED)));
        }
        loadConfig();
        ScreenVolumeCache.initialize(configPath.getParent().resolve("screen-volumes.json"));
        registerExternalTextureReload();
        activeAudioChannelMode = AudioChannelMode.normalize(config.audioChannelMode);
        BiliBiliProvider.setCookieSupplier(BiliCookie::header);
        YouTubeProvider.configureMissingYtdlHandler(() -> {
            YtDlpManager.EnsureResult result = ClientYtDlpInstaller.ensureBlocking();
            return result == null || result.detection() == null ? "" : result.detection().executable();
        });
        BiliAuthRefresher.checkOnStartup();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BiliAuthRefresher.tick();
            tickHandshake(client);
        });
        registerStartupGuide();
        registerStartupGuideScreenOpener();
        registerBiliLoginScreenOpener();
        registerYouTubeAuthScreenOpener();
        VideoProviders.register();
        if (!VideoPlayerMain.android) ClientYtDlpInstaller.ensureAsync();
        disconnectHandler = () -> client.execute(VideoPlayerClient::cleanupClientState);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> {
            cleanupClientState();
            connectionDiagnostics.disconnected();
        });
        if (Vivecraft.loaded) LOGGER.info("Found Vivecraft");
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            joinHandshakePending = true;
            handshakeNonce = 0L;
            connected = false;
            protocolRejected = false;
            protocolMismatchShown = false;
            connectionDiagnostics.beginJoin(currentServerAddress(), VideoPlayerMain.version);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> connectionDiagnostics.disconnected());
        LevelExtractionEvents.END_EXTRACTION.register(ScreenRenderer::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(ScreenRenderer::submit);
        VideoCreationEditor.register();
        ClientPlayNetworking.registerGlobalReceiver(VideoPayload.ID, (p, c) -> {
            long receivedAt = System.currentTimeMillis();
            client.execute(() -> {
                ByteBuf buf = Unpooled.wrappedBuffer(p.data());
                try {
                    ClientPacketHandler.handle(buf, receivedAt);
                } catch (Exception e) {
                    LOGGER.error("Exception while handling packet", e);
                } finally {
                    buf.release();
                }
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((d, c) -> {
            LiteralCommandNode<FabricClientCommandSource> videoplayerRoot = d.register(ClientCommands.literal("videoplayer")
                .executes(VideoPlayerClient::showCommandHelp)
                .then(commandHelp())
                .then(ClientCommands.literal("play")
                        .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.request(currentScreen.getScreen(), s.getArgument("url", String.class));
                                    return 1;
                                })))
                .then(ClientCommands.literal("playthat")
                        .then(ClientCommands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                                .executes(s -> {
                                                    ClientVideoScreen screen = getScreen(s);
                                                    if (screen == null) return 0;
                                                    ClientPacketHandler.request(screen.getScreen(), s.getArgument("url", String.class));
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("skip")
                        .then(ClientCommands.argument("force", BoolArgumentType.bool())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skip(currentScreen.getScreen(), s.getArgument("force", Boolean.class));
                                    return 1;
                                }))
                        .then(ClientCommands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(ClientCommands.argument("force", BoolArgumentType.bool())
                                                .executes(s -> {
                                                    ClientVideoScreen screen = getScreen(s);
                                                    if (screen == null) return 0;
                                                    ClientPacketHandler.skip(screen.getScreen(), s.getArgument("force", Boolean.class));
                                                    return 1;
                                                })
                                        )))
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            ClientPacketHandler.skip(currentScreen.getScreen(), false);
                            return 1;
                        })
                )
                .then(ClientCommands.literal("volume")
                        .then(ClientCommands.argument("volume", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    int v = s.getArgument("volume", Integer.class);
                                    config.volume = v;
                                    saveConfig();
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.volume_set", "Volume set to %s%%", v).withStyle(ChatFormatting.GREEN));
                                    applyConfiguredVolume();
                                    return 1;
                                })))
                .then(ClientCommands.literal("backend")
                        .then(ClientCommands.literal(VideoBackends.VLC)
                                .executes(s -> setVideoBackend(s, VideoBackends.VLC)))
                        .then(ClientCommands.literal(VideoBackends.MPV)
                                .executes(s -> setVideoBackend(s, VideoBackends.MPV)))
                        .executes(s -> {
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.current_backend", "Current playback backend: %s", VideoBackends.normalize(config.videoBackend)).withStyle(ChatFormatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommands.literal("audio")
                        .then(ClientCommands.literal(AudioChannelMode.STEREO.configValue())
                                .executes(s -> setAudioChannelMode(s, AudioChannelMode.STEREO)))
                        .then(ClientCommands.literal(AudioChannelMode.AUTO.configValue())
                                .executes(s -> setAudioChannelMode(s, AudioChannelMode.AUTO)))
                        .executes(VideoPlayerClient::showAudioChannelMode))
                .then(ClientCommands.literal("boot")
                        .executes(VideoPlayerClient::openStartupGuide))
                .then(biliAuthCommand("biliAuth"))
                .then(youtubeAuthCommand("youtubeAuth"))
                .then(youtubeAuthCommand("youtube-auth"))
                .then(ClientCommands.literal("danmaku")
                        .executes(s -> {
                            if (checkInvalid(s, false)) return 0;
                            boolean enabled = ClientDanmakuController.toggleGlobal();
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.danmaku_state", "Danmaku: %s",
                                    (enabled ? VpTexts.tr("label.videoplayer.on", "On") : VpTexts.tr("label.videoplayer.off", "Off")).getString()
                            ).withStyle(ChatFormatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommands.literal("createArea")
                        .then(ClientCommands.argument("x1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("x2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("name", StringArgumentType.string())
                                .executes(s -> {
                                    if (checkInvalid(s, false)) return 0;
                                    ClientPacketHandler.createArea(
                                            new Vector3f(
                                                s.getArgument("x1", Float.class),
                                                s.getArgument("y1", Float.class),
                                                s.getArgument("z1", Float.class)
                                            ),
                                            new Vector3f(
                                                s.getArgument("x2", Float.class),
                                                s.getArgument("y2", Float.class),
                                                s.getArgument("z2", Float.class)
                                            ),
                                            s.getArgument("name", String.class)
                                    );
                                    return 1;
                                })))))))))
                .then(ClientCommands.literal("removeArea")
                        .then(ClientCommands.argument("name", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .executes(s -> {
                                    if (checkInvalid(s, false)) return 0;
                                    String name = s.getArgument("name", String.class);
                                    ClientPacketHandler.removeArea(name);
                                    return 1;
                                })))
                .then(ClientCommands.literal("createScreen")
                        .then(ClientCommands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                        .then(ClientCommands.argument("name", StringArgumentType.string())
                        .then(ClientCommands.argument("x1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("x2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("x3", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y3", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z3", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("x4", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("y4", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("z4", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("source", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                .executes(s -> {
                                    ClientVideoArea area = getArea(s);
                                    if (area == null) return 0;
                                    ClientPacketHandler.createScreen(new VideoScreen(
                                            area,
                                            s.getArgument("name", String.class),
                                            new Vector3f(
                                                    s.getArgument("x1", Float.class),
                                                    s.getArgument("y1", Float.class),
                                                    s.getArgument("z1", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x2", Float.class),
                                                    s.getArgument("y2", Float.class),
                                                    s.getArgument("z2", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x3", Float.class),
                                                    s.getArgument("y3", Float.class),
                                                    s.getArgument("z3", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x4", Float.class),
                                                    s.getArgument("y4", Float.class),
                                                    s.getArgument("z4", Float.class)
                                            ),
                                            s.getArgument("source", String.class)
                                    ));
                                    return 1;
                                })))))))))))))))))
                .then(ClientCommands.literal("removeScreen")
                        .then(ClientCommands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommands.argument("name", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
                                        .executes(s -> {
                                            ClientVideoArea area = getArea(s);
                                            if (area == null) return 0;
                                            String screenName = s.getArgument("name", String.class);
                                            VideoScreen screen = area.getScreen(screenName);
                                            if (screen == null) {
                                                s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.screen_named_not_found", "No screen named %s", screenName));
                                                return 0;
                                            }
                                            ClientPacketHandler.removeScreen(screen);
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("skipPercent")
                        .then(ClientCommands.argument("percent", FloatArgumentType.floatArg(0, 1.01f))
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skipPercent(currentScreen, s.getArgument("percent", Float.class));
                                    return 1;
                                })))
                .then(ClientCommands.literal("list")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            String str = currentScreen.getScreen().infos.stream()
                                    .map(i -> VpTexts.tr("message.videoplayer.queue_item", "%s requested by: %s", i.name(), i.playerName()).getString())
                                    .collect(Collectors.joining("\n"));
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.queue_list", "Video area %s screen %s\n%s",
                                    currentScreen.area.name, currentScreen.name,
                                    str.isEmpty() ? VpTexts.tr("message.videoplayer.queue_empty", "Queue is empty").getString() : str
                            ).withStyle(ChatFormatting.GOLD));
                            return 1;
                        }))
                .then(ClientCommands.literal("sync")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            ClientPacketHandler.sync(currentScreen);
                            return 1;
                        }))
                .then(ClientCommands.literal("brightness")
                        .then(ClientCommands.argument("brightness", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    config.brightness = s.getArgument("brightness", Integer.class);
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.brightness_set", "Brightness set to %s%%", config.brightness).withStyle(ChatFormatting.GREEN));
                                    saveConfig();
                                    return 1;
                                })))
                .then(ClientCommands.literal("slice")
                        .then(ClientCommands.argument("u1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("v1", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("u2", FloatArgumentType.floatArg())
                        .then(ClientCommands.argument("v2", FloatArgumentType.floatArg())
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    float u1 = s.getArgument("u1", Float.class);
                                    float v1 = s.getArgument("v1", Float.class);
                                    float u2 = s.getArgument("u2", Float.class);
                                    float v2 = s.getArgument("v2", Float.class);
                                    ClientPacketHandler.setUV(currentLooking, u1, v1, u2, v2);
                                    return 1;
                                }))))))
                .then(ClientCommands.literal("stop")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            currentScreen.clearPlaybackState();
                            if (currentScreen.player != null) currentScreen.player.stop();
                            return 1;
                        }))
                .then(ClientCommands.literal("setmeta")
                        .then(ClientCommands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
                                        .then(ClientCommands.literal("mute")
                                                .then(ClientCommands.argument("mute", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "mute", MetaValue.ofBool(s.getArgument("mute", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommands.literal("interactable")
                                                .then(ClientCommands.argument("interactable", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "interactable", MetaValue.ofBool(s.getArgument("interactable", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommands.literal("autoSync")
                                                .then(ClientCommands.argument("autoSync", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "autoSync", MetaValue.ofBool(s.getArgument("autoSync", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommands.literal("custom")
                                                .then(ClientCommands.literal("set")
                                                        .then(ClientCommands.argument("key", StringArgumentType.string())
                                                                .then(ClientCommands.argument("value", IntegerArgumentType.integer())
                                                                        .executes(s -> {
                                                                            ClientVideoScreen screen = getScreen(s);
                                                                            if (screen == null) return 0;
                                                                            ClientPacketHandler.setMetadata(screen, s.getArgument("key", String.class), MetaValue.ofInt(s.getArgument("value", Integer.class)));
                                                                            return 1;
                                                                        }))))
                                                .then(ClientCommands.literal("get")
                                                        .then(ClientCommands.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    String key = s.getArgument("key", String.class);
                                                                    MetaValue value = screen.metadata.get(key);
                                                                    s.getSource().sendFeedback(Component.literal(key + "=" + (value == null ? "null" : value.toDisplayString())));
                                                                    return 1;
                                                                })))
                                                .then(ClientCommands.literal("remove")
                                                        .then(ClientCommands.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    ClientPacketHandler.removeMetadata(screen, s.getArgument("key", String.class));
                                                                    return 1;
                                                                })))
                                                .then(ClientCommands.literal("list")
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            s.getSource().sendFeedback(Component.literal(screen.metadata.entries().toString()));
                                                            return 1;
                                                        })))
                                )))
                .then(ClientCommands.literal("scale")
                        .then(ClientCommands.literal("stretch")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, true, 1, 1);
                                    return 1;
                                }))
                        .then(ClientCommands.literal("auto")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, false, 1, 1);
                                    return 1;
                                }))
                        .then(ClientCommands.literal("set")
                                .then(ClientCommands.argument("scaleX", FloatArgumentType.floatArg(0.0625f, 16f))
                                        .then(ClientCommands.argument("scaleY", FloatArgumentType.floatArg(0.0625f, 16f))
                                                .executes(s -> {
                                                    if (checkInvalidLooking(s)) return 0;
                                                    ClientPacketHandler.setScale(currentLooking, false, s.getArgument("scaleX", Float.class), s.getArgument("scaleY", Float.class));
                                                    return 1;
                                                })))))
        );
            d.register(ClientCommands.literal("vlc")
                    .executes(VideoPlayerClient::showCommandHelp)
                    .redirect(videoplayerRoot));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || client.gui.screen() != null || currentLooking == null) return;
            boolean pressed = client.options.keyUse.isDown();
            if (pressed && !keyPressed) {
                keyPressed = true;
                if (remoteControl || client.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() && client.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty()) {
                    ClientVideoScreen selected = currentLooking;
                    ClientPacketHandler.openMenu(selected, result -> {
                        if (!ClientPacketHandler.failed(result) && client.gui.screen() == null) {
                            VideoCreationEditor.instance().openConfigScreen(selected);
                        }
                    });
                }
            } else if (!pressed) {
                keyPressed = false;
            }
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commandHelp() {
        return ClientCommands.literal("help")
                .executes(VideoPlayerClient::showCommandHelp)
                .then(ClientCommands.argument("subcommand", StringArgumentType.word()).suggests((context, builder) -> {
                    for (VideoPlayerCommandHelp.Entry entry : VideoPlayerCommandHelp.entries()) {
                        if (entry.name().toLowerCase(Locale.ROOT).startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                            builder.suggest(entry.name());
                        }
                    }
                    return builder.buildFuture();
                }).executes(VideoPlayerClient::showCommandHelp));
    }

    private static int showCommandHelp(CommandContext<FabricClientCommandSource> context) {
        String subcommand = null;
        try {
            subcommand = context.getArgument("subcommand", String.class);
        } catch (IllegalArgumentException ignored) {
        }
        if (subcommand == null || subcommand.isBlank()) {
            context.getSource().sendFeedback(VpTexts.tr(
                    "command.videoplayer.help.header",
                    "VideoPlayer client commands. Use /videoplayer help <subcommand> for details."
            ).withStyle(ChatFormatting.GOLD));
            for (VideoPlayerCommandHelp.Entry entry : VideoPlayerCommandHelp.entries()) {
                String detailKey = "command.videoplayer.help." + entry.name().toLowerCase(Locale.ROOT) + ".detail";
                context.getSource().sendFeedback(VpTexts.tr(
                        "command.videoplayer.help." + entry.name().toLowerCase(Locale.ROOT) + ".summary",
                        "%1$s - %2$s",
                        entry.usage().isBlank() ? "/videoplayer " + entry.name() : "/videoplayer " + entry.name() + " " + entry.usage(),
                        VpTexts.tr(detailKey, entry.details()).getString()
                ));
            }
            context.getSource().sendFeedback(VpTexts.tr(
                    "command.videoplayer.help.alias",
                    "/vlc remains a compatible alias for /videoplayer."
            ).withStyle(ChatFormatting.GRAY));
            return 1;
        }
        Optional<VideoPlayerCommandHelp.Entry> found = VideoPlayerCommandHelp.find(subcommand);
        if (found.isEmpty()) {
            context.getSource().sendFeedback(VpTexts.tr(
                    "command.videoplayer.help.unknown",
                    "Unknown subcommand '%s'. Use /videoplayer help to list available commands.",
                    subcommand
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        VideoPlayerCommandHelp.Entry entry = found.get();
        String usage = entry.usage().isBlank()
                ? "/videoplayer " + entry.name()
                : "/videoplayer " + entry.name() + " " + entry.usage();
        context.getSource().sendFeedback(Component.literal(usage).withStyle(ChatFormatting.AQUA));
        context.getSource().sendFeedback(VpTexts.tr(
                "command.videoplayer.help." + entry.name().toLowerCase(Locale.ROOT) + ".detail",
                entry.details()
        ));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> biliAuthCommand(String literal) {
        return ClientCommands.literal(literal)
                .executes(VideoPlayerClient::showBiliAuthHelp)
                .then(ClientCommands.literal("login")
                        .executes(VideoPlayerClient::openBiliLogin))
                .then(ClientCommands.literal("set")
                        .then(ClientCommands.argument("cookie", StringArgumentType.greedyString())
                                .executes(s -> {
                                    BiliCookie.set(s.getArgument("cookie", String.class));
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.bilibili_cookie_saved", "Bilibili auth saved locally").withStyle(ChatFormatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommands.literal("clear")
                        .executes(s -> {
                            BiliCookie.clear();
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.bilibili_cookie_cleared", "Bilibili auth cleared").withStyle(ChatFormatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(s -> {
                            s.getSource().sendFeedback(VpTexts.text(BiliCookie.status()).withStyle(ChatFormatting.GREEN));
                            return 1;
                        }));
    }

    private static int showBiliAuthHelp(CommandContext<FabricClientCommandSource> context) {
        VideoPlayerCommandHelp.find("biliAuth").ifPresent(entry -> context.getSource().sendFeedback(VpTexts.tr(
                "command.videoplayer.help.biliauth.detail", entry.details()
        )));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> youtubeAuthCommand(String literal) {
        return ClientCommands.literal(literal)
                .executes(VideoPlayerClient::openYouTubeAuth)
                .then(ClientCommands.literal("login").executes(VideoPlayerClient::openYouTubeAuth))
                .then(ClientCommands.literal("clear")
                        .executes(s -> {
                            config.youtubeCookiesFile = "";
                            config.youtubeCookiesFromBrowser = "";
                            saveConfig();
                            applyNativePlatformConfig();
                            s.getSource().sendFeedback(VpTexts.tr(
                                    "message.videoplayer.youtube_auth_cleared",
                                    "YouTube authentication settings cleared"
                            ).withStyle(ChatFormatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(s -> {
                            boolean file = config.youtubeCookiesFile != null && !config.youtubeCookiesFile.isBlank();
                            boolean browser = config.youtubeCookiesFromBrowser != null && !config.youtubeCookiesFromBrowser.isBlank();
                            String configured = VpTexts.tr("label.videoplayer.configured", "Configured").getString();
                            String notConfigured = VpTexts.tr("label.videoplayer.not_configured", "Not configured").getString();
                            s.getSource().sendFeedback(VpTexts.tr(
                                    "message.videoplayer.youtube_auth_status",
                                    "YouTube authentication: cookie file=%s, browser profile=%s",
                                    file ? configured : notConfigured,
                                    browser ? configured : notConfigured
                            ).withStyle(ChatFormatting.GREEN));
                            return 1;
                        }));
    }

    public static void openBiliLoginScreen() {
        pendingBiliLoginScreen = true;
    }

    private static int openBiliLogin(CommandContext<FabricClientCommandSource> s) {
        openBiliLoginScreen();
        return 1;
    }

    private static int openStartupGuide(CommandContext<FabricClientCommandSource> s) {
        pendingStartupGuideScreen = true;
        return 1;
    }

    private static int setVideoBackend(CommandContext<FabricClientCommandSource> s, String backend) {
        config.videoBackend = VideoBackends.normalize(backend);
        saveConfig();
        if (VideoBackends.MPV.equals(config.videoBackend) && !MpvVideoBackend.isAvailable()) {
            LOGGER.warn("MPV backend selected but libmpv is not available", MpvVideoBackend.loadError());
            pendingStartupGuideScreen = true;
            s.getSource().sendFeedback(VpTexts.tr(
                    "message.videoplayer.backend_mpv_unavailable",
                    "MPV is unavailable. The setup guide will open; download the MPV runtime there. New videos use VLC until installation finishes."
            ).withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.backend_set", "Playback backend set to %s. Only newly started videos are affected.", config.videoBackend).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int showAudioChannelMode(CommandContext<FabricClientCommandSource> context) {
        AudioChannelMode configured = AudioChannelMode.normalize(config.audioChannelMode);
        boolean restartRequired = configured != activeAudioChannelMode;
        context.getSource().sendFeedback(VpTexts.tr(
                restartRequired ? "message.videoplayer.audio_channel_status_restart_required" : "message.videoplayer.audio_channel_status",
                restartRequired
                        ? "Audio channel mode: configured %s, active %s. Restart Minecraft to apply the configured mode."
                        : "Audio channel mode: configured %s, active %s.",
                audioChannelModeLabel(configured).getString(),
                audioChannelModeLabel(activeAudioChannelMode).getString()
        ).withStyle(restartRequired ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        return 1;
    }

    private static int setAudioChannelMode(CommandContext<FabricClientCommandSource> context, AudioChannelMode mode) {
        config.audioChannelMode = mode.configValue();
        saveConfig();
        boolean restartRequired = mode != activeAudioChannelMode;
        context.getSource().sendFeedback(VpTexts.tr(
                restartRequired ? "message.videoplayer.audio_channel_mode_restart_required" : "message.videoplayer.audio_channel_mode_set",
                restartRequired
                        ? "Audio channel mode saved as %s. Restart Minecraft to apply."
                        : "Audio channel mode saved as %s and is already active.",
                audioChannelModeLabel(mode).getString()
        ).withStyle(restartRequired ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        return 1;
    }

    private static Component audioChannelModeLabel(AudioChannelMode mode) {
        return VpTexts.tr(
                "label.videoplayer.audio_channel_mode." + mode.configValue(),
                mode == AudioChannelMode.AUTO ? "Auto" : "Stereo"
        );
    }

    private ClientVideoArea getArea(CommandContext<FabricClientCommandSource> s) {
        if (checkInvalid(s, false)) return null;
        String name = s.getArgument("area", String.class);
        ClientVideoArea area = areas.get(name);
        if (area == null) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.area_named_not_found", "No video area named %s", name).withStyle(ChatFormatting.RED));
            return null;
        }
        return area;
    }

    private ClientVideoScreen getScreen(CommandContext<FabricClientCommandSource> s) {
        if (checkInvalid(s, false)) return null;
        ClientVideoArea area = getArea(s);
        if (area == null) return null;
        String name = s.getArgument("screen", String.class);
        ClientVideoScreen screen = area.getScreen(name);
        if (screen == null) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.screen_not_found", "Screen not found").withStyle(ChatFormatting.RED));
            return null;
        }
        return screen;
    }

    private boolean checkInvalid(CommandContext<FabricClientCommandSource> s, boolean checkScreen) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_connected", "Not connected to server").withStyle(ChatFormatting.RED));
            return true;
        }
        if (checkScreen && currentScreen == null) {
            if (isInArea) {
                s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.current_area_no_main_screen", "Current video area has no main screen").withStyle(ChatFormatting.RED));
            } else {
                s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_inside_area", "You are not inside a video area").withStyle(ChatFormatting.RED));
            }
            return true;
        }
        return false;
    }

    private boolean checkInvalidLooking(CommandContext<FabricClientCommandSource> s) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_connected", "Not connected to server").withStyle(ChatFormatting.RED));
            return true;
        }
        if (currentLooking == null) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_looking_at_screen", "You are not looking at a screen").withStyle(ChatFormatting.RED));
            return true;
        }
        return false;
    }

    private static void updateBossBar() {
        ClientPacketListener handler = client.getConnection();
        if (handler == null) {
            bossBarAdded = false;
            return;
        }
        if (currentLooking != null) {
            if (!bossBarAdded) {
                handler.handleBossUpdate(ClientboundBossEventPacket.createAddPacket(bossBar));
                bossBarAdded = true;
            }
            ClientVideoScreen screen = currentLooking.getScreen();
            VideoInfo info = screen.currentDisplayInfo();
            VideoInfo playbackInfo = screen.currentPlaybackInfo();
            if (info != null && screen.player != null) {
                String name = info.name();
                long progress = System.currentTimeMillis() - screen.getStartTime();
                long totalProgress = screen.player.getTotalProgress();
                boolean live = playbackInfo != null && !playbackInfo.seekable() && playbackInfo.durationMs() == 0L;
                String time;
                if (!live && totalProgress > 0) {
                    boolean showHour = progress >= 3600000 || totalProgress >= 3600000;
                    time = formatDuration(progress, showHour) + "/" + formatDuration(totalProgress, showHour);
                    bossBar.setProgress((float) progress / totalProgress);
                } else {
                    time = formatDuration(progress, progress >= 3600000) + "/LIVE";
                    bossBar.setProgress(0);
                }
                bossBar.setName(Component.nullToEmpty(name + " " + time));
            } else {
                bossBar.setName(VpTexts.tr("label.videoplayer.none", "None"));
                bossBar.setProgress(1);
            }
            handler.handleBossUpdate(ClientboundBossEventPacket.createUpdateNamePacket(bossBar));
            handler.handleBossUpdate(ClientboundBossEventPacket.createUpdateProgressPacket(bossBar));
        } else if (bossBarAdded) {
            handler.handleBossUpdate(ClientboundBossEventPacket.createRemovePacket(bossBar.getId()));
            bossBarAdded = false;
        }
    }

    private static void checkInteract() {
        Minecraft client = VideoPlayerClient.client;
        if (client == null) return;

        isInArea = false;
        currentLooking = null;
        currentScreen = null;
        if (screens.isEmpty()) {
            touchHandler.handle(null);
            return;
        }

        float delta = VideoPlayerClient.client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 eyePos = client.player.getEyePosition(delta);
        Vec3 lookVec = client.player.getViewVector(delta);

        Vector3d lineStart = new Vector3d(eyePos.x, eyePos.y, eyePos.z);

        remoteControl = false;
        for (ItemStack item : List.of(client.player.getMainHandItem(), client.player.getOffhandItem())) {
            if (!BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(remoteControlName)) continue;
            CustomModelData data = item.getComponents().get(DataComponents.CUSTOM_MODEL_DATA);
            if (data == null) continue;
            List<Float> id = data.floats();
            if (id.isEmpty() || !id.contains(remoteControlId)) continue;
            remoteControl = true;
        }
        Vec3 end = eyePos.add(lookVec.scale(remoteControl ? remoteControlRange : noControlRange));
        Vector3d lineEnd = new Vector3d(end.x, end.y, end.z);

        ArrayList<Intersection.Result> list = new ArrayList<>();
        for (ClientVideoScreen s : screens) {
            if (!s.interactable) continue;
            ClientVideoScreen screen = s.getTrackingScreen();
            if (screen == null)  continue;
            Intersection.Result result = Intersection.intersect(lineStart, lineEnd, screen);
            if (result.intersects) list.add(result);
        }
        Intersection.Result target = list.isEmpty() ? null : Collections.min(list, Comparator.comparingDouble(s -> s.preciseDistance));
        currentLooking = target == null || target.screen == null ? null : target.screen;
        touchHandler.handle(target);

        if (currentLooking != null) {
            currentScreen = currentLooking;
            return;
        }

        currentScreen = null;
        for (ClientVideoArea area : areas.values()) {
            if (!area.loaded) continue;
            isInArea = true;
            for (VideoScreen screen : area.screens) {
                ClientVideoScreen s = (ClientVideoScreen) screen;
                if (s.interactable) {
                    currentScreen = s;
                    break;
                }
            }
        }
    }

    public static boolean checkVersion(String v) {
        return VideoProtocol.compatible(VideoPlayerMain.version, v);
    }

    public static void update() {
        ClientPacketHandler.tickPendingRequests();
        if (updated) return;
        ProfilerFiller profiler = Profiler.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (screen.isPostUpdate()) continue;
            screen.swapTexture();
            screen.update();
        }
        profiler.popPush("checkInteract");
        checkInteract();
        profiler.popPush("updateBossBar");
        updateBossBar();
        profiler.pop();
        profiler.pop();
    }

    private static void cleanupClientState() {
        saveVolumeSettings();
        if (client.gui.screen() instanceof ServerStateScreen) {
            client.gui.setScreen(null);
            if (client.player != null) {
                client.player.sendSystemMessage(VpTexts.tr("error.videoplayer.server_state_reset", "VideoPlayer server state was reset").withStyle(ChatFormatting.RED));
            }
        }
        connected = false;
        handshakeNonce = 0L;
        joinHandshakePending = false;
        for (ClientVideoArea area : new ArrayList<>(areas.values())) {
            area.remove();
        }
        areas.clear();
        for (ClientVideoScreen screen : new ArrayList<>(screens)) {
            screen.cleanup();
        }
        screens.clear();
        ScreenRenderer.clearExternalTextures();
        ClientPacketHandler.resetPendingRequests();
        ScreenVolumeCache.clear();
        ClientDanmakuRenderer.clearCache();
        Degree360Player.clearMeshCache();
        ClientPermissionCache.clear();
        isInArea = false;
        currentLooking = null;
        currentScreen = null;
        remoteControl = false;
        touchHandler.handle(null);
        if (client.getConnection() != null) {
            updateBossBar();
        } else {
            bossBarAdded = false;
        }
        VideoCreationEditor.instance().clear();
    }

    private static void registerExternalTextureReload() {
        Identifier reloaderId = Identifier.fromNamespaceAndPath("videoplayer", "external_textures");
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(reloaderId, new SimpleReloadListener<Void>() {
            @Override
            protected Void prepare(PreparableReloadListener.SharedState state) {
                return null;
            }

            @Override
            protected void apply(Void prepared, PreparableReloadListener.SharedState state) {
                ScreenRenderer.clearExternalTextures();
            }
        });
        loader.addListenerOrdering(ResourceReloaderKeys.Client.TEXTURES, reloaderId);
    }

    public static void resetServerState() {
        cleanupClientState();
    }

    public static boolean protocolRejected() {
        return protocolRejected;
    }

    public static void acceptProtocol() {
        protocolRejected = false;
    }

    static void handshakeResponse(String remoteVersion) {
        connectionDiagnostics.handshakeResponse(remoteVersion);
    }

    static void connectionEstablished(String remoteVersion) {
        connectionDiagnostics.connected(remoteVersion);
    }

    static void setHandshakeNonce(long nonce) {
        handshakeNonce = nonce;
    }

    static void serverHandshakeReset() {
        joinHandshakePending = false;
    }

    public static void rejectProtocol(String remoteVersion) {
        if (!protocolRejected) cleanupClientState();
        protocolRejected = true;
        connected = false;
        joinHandshakePending = false;
        connectionDiagnostics.versionMismatch(remoteVersion);
        if (connectionDiagnostics.snapshot().trigger() == VideoConnectionDiagnostics.Trigger.MANUAL_RETRY
                || protocolMismatchShown || client.player == null) return;
        protocolMismatchShown = true;
        client.player.sendSystemMessage(VpTexts.tr(
                "message.videoplayer.version_mismatch",
                "VideoPlayer client version %s is not compatible with server %s",
                VideoPlayerMain.version, remoteVersion == null || remoteVersion.isBlank() ? "unknown" : remoteVersion
        ).withStyle(ChatFormatting.RED));
    }

    private static void tickHandshake(Minecraft client) {
        if (client.getConnection() == null || client.player == null) {
            joinHandshakePending = false;
            connectionDiagnostics.disconnected();
            return;
        }
        if (protocolRejected) return;
        if (!ClientPlayNetworking.canSend(VideoPayload.ID)) {
            connected = false;
            connectionDiagnostics.channelUnavailable();
            return;
        }
        connectionDiagnostics.channelAvailable();
        connectionDiagnostics.tick();
        if (!joinHandshakePending) return;
        joinHandshakePending = false;
        connectionDiagnostics.handshakeSent();
        ClientPacketHandler.config(VideoPlayerMain.version);
    }

    public static VideoConnectionDiagnostics.Snapshot connectionSnapshot() {
        return connectionDiagnostics.snapshot();
    }

    public static void reconnectServer() {
        if (client.getConnection() == null || client.player == null) {
            LOGGER.warn("VideoPlayer connection: trigger=manual_retry address={} state=failed reason=no_active_minecraft_connection",
                    logField(currentServerAddress()));
            return;
        }
        if (!connectionDiagnostics.beginManualRetry(currentServerAddress(), VideoPlayerMain.version)) return;
        protocolRejected = false;
        protocolMismatchShown = false;
        connected = false;
        handshakeNonce = 0L;
        joinHandshakePending = false;
        if (!ClientPlayNetworking.canSend(VideoPayload.ID)) {
            connectionDiagnostics.channelUnavailable();
            return;
        }
        connectionDiagnostics.handshakeSent();
        ClientPacketHandler.config(VideoPlayerMain.version);
    }

    private static String currentServerAddress() {
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return "local";
        return server.ip;
    }

    private static void logConnectionEvent(VideoConnectionDiagnostics.Event event) {
        VideoConnectionDiagnostics.Snapshot snapshot = event.snapshot();
        String trigger = snapshot.trigger().name().toLowerCase(Locale.ROOT);
        String address = logField(snapshot.address());
        switch (event.type()) {
            case ATTEMPT_STARTED -> LOGGER.info(
                    "VideoPlayer connection: trigger={} address={} state=connecting local_version={}",
                    trigger, address, logField(snapshot.localVersion()));
            case CHANNEL_UNAVAILABLE -> LOGGER.warn(
                    "VideoPlayer connection: trigger={} address={} state=failed reason=payload_channel_unavailable payload={} attempts={} elapsed_ms={}",
                    trigger, address, VideoPayload.VIDEO_PAYLOAD_ID, snapshot.attempts(), snapshot.elapsedMillis());
            case CHANNEL_AVAILABLE -> LOGGER.info(
                    "VideoPlayer connection: trigger={} address={} state=connecting reason=payload_channel_available attempts={}",
                    trigger, address, snapshot.attempts());
            case TIMED_OUT -> LOGGER.warn(
                    "VideoPlayer connection: trigger={} address={} state=timed_out reason=no_handshake_response attempts={} elapsed_ms={}",
                    trigger, address, snapshot.attempts(), snapshot.elapsedMillis());
            case CONNECTED -> LOGGER.info(
                    "VideoPlayer connection: trigger={} address={} state=connected remote_version={} attempts={} elapsed_ms={}",
                    trigger, address, logField(snapshot.remoteVersion()), snapshot.attempts(), snapshot.elapsedMillis());
            case VERSION_MISMATCH -> LOGGER.warn(
                    "VideoPlayer connection: trigger={} address={} state=failed reason=version_mismatch local_version={} remote_version={} attempts={} elapsed_ms={}",
                    trigger, address, logField(snapshot.localVersion()), logField(snapshot.remoteVersion()), snapshot.attempts(), snapshot.elapsedMillis());
            case RETRY_BLOCKED -> LOGGER.warn(
                    "VideoPlayer connection: trigger=manual_retry address={} state=failed reason=version_mismatch_retry_blocked local_version={} remote_version={}",
                    address, logField(snapshot.localVersion()), logField(snapshot.remoteVersion()));
            case DISCONNECTED -> LOGGER.info(
                    "VideoPlayer connection: trigger={} address={} state=disconnected attempts={} elapsed_ms={}",
                    trigger, address, snapshot.attempts(), snapshot.elapsedMillis());
        }
        notifyManualReconnect(event);
    }

    private static void notifyManualReconnect(VideoConnectionDiagnostics.Event event) {
        VideoConnectionDiagnostics.Snapshot snapshot = event.snapshot();
        if (snapshot.trigger() != VideoConnectionDiagnostics.Trigger.MANUAL_RETRY || client.player == null) return;
        Component message;
        ChatFormatting formatting;
        switch (event.type()) {
            case ATTEMPT_STARTED -> {
                message = VpTexts.tr("message.videoplayer.reconnect_started", "Reconnecting to the VideoPlayer server...");
                formatting = ChatFormatting.YELLOW;
            }
            case CHANNEL_UNAVAILABLE -> {
                message = VpTexts.tr("message.videoplayer.reconnect_channel_unavailable",
                        "VideoPlayer server reconnect failed: the server did not register the communication channel");
                formatting = ChatFormatting.RED;
            }
            case TIMED_OUT -> {
                message = VpTexts.tr("message.videoplayer.reconnect_timed_out",
                        "VideoPlayer server reconnect failed: no handshake response within 10 seconds");
                formatting = ChatFormatting.RED;
            }
            case CONNECTED -> {
                message = VpTexts.tr("message.videoplayer.reconnect_success",
                        "VideoPlayer server reconnected. Server version: %s", reconnectVersion(snapshot.remoteVersion()));
                formatting = ChatFormatting.GREEN;
            }
            case VERSION_MISMATCH, RETRY_BLOCKED -> {
                message = VpTexts.tr("message.videoplayer.reconnect_version_mismatch",
                        "VideoPlayer server reconnect failed: local version %s is incompatible with server version %s",
                        reconnectVersion(snapshot.localVersion()), reconnectVersion(snapshot.remoteVersion()));
                formatting = ChatFormatting.RED;
            }
            case CHANNEL_AVAILABLE, DISCONNECTED -> {
                return;
            }
            default -> {
                return;
            }
        }
        client.player.sendSystemMessage(message.copy().withStyle(formatting));
    }

    private static String reconnectVersion(String version) {
        if (version != null && !version.isBlank()) return version;
        return VpTexts.tr("label.videoplayer.connection.unknown", "Unknown").getString();
    }

    private static String logField(String value) {
        if (value == null || value.isBlank()) return "unknown";
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            sanitized.append(Character.isISOControl(character) || Character.isWhitespace(character) ? '_' : character);
        }
        return sanitized.toString();
    }

    public static void postUpdate() {
        if (updated) return;
        updated = true;
        ProfilerFiller profiler = Profiler.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (!screen.isPostUpdate()) continue;
            screen.swapTexture();
            screen.update();
        }
        profiler.pop();
        profiler.pop();
    }

    private static String formatDuration(long millis, boolean showHour) {
        long all = millis / 1000;
        long hours = all / 3600;
        long minutes = (all % 3600) / 60;
        long seconds = all % 60;

        if (showHour) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    public static void saveConfig() {
        Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            applyNativePlatformConfig();
            Files.createDirectories(configPath.getParent());
            Files.writeString(temporary, gson.toJson(config), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            savedVolume = config.volume;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    public static void reloadConfig() {
        loadConfig();
    }

    public static void markStartupGuideShown() {
        if (config == null) return;
        config.startupGuideShown = true;
        saveConfig();
        try {
            Files.writeString(startupGuideVersionPath, VideoPlayerMain.version);
        } catch (IOException e) {
            LOGGER.warn("Failed to write startup guide version file {}", startupGuideVersionPath, e);
        }
    }

    public static void applyNativePlatformConfig() {
        if (config == null) return;
        if (VideoPlayerMain.android) {
            config.videoBackend = VideoBackends.VLC;
            config.nativeVlcPlatform = NativeDownloadConfig.platformKey();
            NativePackageManager.selectPlatform(NativePackageManager.BACKEND_VLC, config.nativeVlcPlatform);
            StreamListener.configurePreferredBackend(VideoBackends.VLC);
        } else {
            config.nativeVlcPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeVlcPlatform);
            config.nativeMpvPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeMpvPlatform);
            NativePackageManager.selectPlatform(NativePackageManager.BACKEND_VLC, config.nativeVlcPlatform);
            NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, config.nativeMpvPlatform);
        }
        StreamListener.configureProxy(config.nativeDownloadProxy);
        YouTubeProvider.configureProxy(config.nativeDownloadProxy);
        YouTubeProvider.configureCookies(config.youtubeCookiesFile, config.youtubeCookiesFromBrowser);
        String effectiveYtdlPath = YtDlpManager.effectiveExecutable(config.mpvYtdlPath);
        StreamListener.configureYtdlPath(effectiveYtdlPath);
        YouTubeProvider.configureYtdlPath(effectiveYtdlPath);
    }

    public static NativeDownloadConfig nativeDownloadConfig() {
        if (config == null) {
            return NativeDownloadConfig.load();
        }
        if (config.nativeDownloadUrls == null) {
            config.nativeDownloadUrls = NativeDownloadConfig.load();
        }
        return config.nativeDownloadUrls;
    }

    public static void saveVolumeSettings() {
        ScreenVolumeCache.flush();
        if (config == null || config.volume == savedVolume) return;
        try {
            saveConfig();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to save client volume; keeping the in-memory setting", e);
        }
    }

    public static void applyConfiguredVolume() {
        config.volume = Math.clamp(config.volume, 0, 100);
        for (ClientVideoScreen screen : screens) {
            if (screen.player instanceof VideoPlayer player && VideoBackends.VLC.equals(player.backendName())) {
                player.setVolume(config.volume);
            }
        }
    }

    public static AudioChannelMode activeAudioChannelMode() {
        return activeAudioChannelMode;
    }

    private static void loadConfig() {
        boolean existed = Files.exists(configPath);
        boolean changed = false;
        boolean preserveInvalidFile = false;
        try {
            String serializedConfig = Files.readString(configPath);
            JsonElement configJson = JsonParser.parseString(serializedConfig);
            if (!configJson.isJsonObject()) throw new IllegalArgumentException("Client configuration root must be an object");
            JsonObject configObject = configJson.getAsJsonObject();
            JsonElement configuredAudioChannelMode = configObject.get("audioChannelMode");
            if (!AudioChannelMode.isCanonicalJsonValue(configuredAudioChannelMode)) {
                configObject.addProperty("audioChannelMode", AudioChannelMode.normalizeJson(configuredAudioChannelMode).configValue());
                changed = true;
            }
            config = gson.fromJson(configObject, Config.class);
            if (config == null) config = new Config();
        } catch (Exception e) {
            config = new Config();
            changed = true;
            preserveInvalidFile = existed;
            LOGGER.warn("Failed to read client configuration {}; keeping the original file", configPath, e);
        }
        config.nativeDownloadUrls = NativeDownloadConfig.load();
        if (config.startupGuideShown == null) {
            config.startupGuideShown = existed;
            changed = true;
        }
        boolean currentGuideVersionShown = false;
        try {
            currentGuideVersionShown = Files.isRegularFile(startupGuideVersionPath)
                    && VideoPlayerMain.version.equals(Files.readString(startupGuideVersionPath).trim());
        } catch (IOException e) {
            LOGGER.warn("Failed to read startup guide version file {}", startupGuideVersionPath, e);
        }
        if (!currentGuideVersionShown && !Boolean.FALSE.equals(config.startupGuideShown)) {
            config.startupGuideShown = false;
            changed = true;
        }
        config.videoBackend = VideoBackends.normalize(config.videoBackend);
        String audioChannelMode = AudioChannelMode.normalize(config.audioChannelMode).configValue();
        if (!Objects.equals(config.audioChannelMode, audioChannelMode)) {
            config.audioChannelMode = audioChannelMode;
            changed = true;
        }
        String nativeVlcPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeVlcPlatform);
        if (!Objects.equals(config.nativeVlcPlatform, nativeVlcPlatform)) {
            config.nativeVlcPlatform = nativeVlcPlatform;
            changed = true;
        }
        String nativeMpvPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeMpvPlatform);
        if (!Objects.equals(config.nativeMpvPlatform, nativeMpvPlatform)) {
            config.nativeMpvPlatform = nativeMpvPlatform;
            changed = true;
        }
        if (config.nativeDownloadProxy == null) {
            config.nativeDownloadProxy = "";
            changed = true;
        }
        if (config.mpvYtdlPath == null) {
            config.mpvYtdlPath = "";
            changed = true;
        } else if (YtDlpManager.isCurrentManagedExecutable(config.mpvYtdlPath)) {
            config.mpvYtdlPath = "";
            changed = true;
        }
        if (config.youtubeCookiesFile == null) {
            config.youtubeCookiesFile = "";
            changed = true;
        }
        if (config.youtubeCookiesFromBrowser == null) {
            config.youtubeCookiesFromBrowser = "";
            changed = true;
        }
        applyNativePlatformConfig();
        config.volume = Math.clamp(config.volume, 0, 100);
        config.brightness = Math.clamp(config.brightness, 0, 100);
        config.danmakuRollingRangePercent = switch (config.danmakuRollingRangePercent) {
            case 25, 50, 75, 100 -> config.danmakuRollingRangePercent;
            default -> 50;
        };
        config.danmakuSpeedPreset = Math.clamp(config.danmakuSpeedPreset, 0, 4);
        config.danmakuDensityPreset = Math.clamp(config.danmakuDensityPreset, 0, 2);
        config.danmakuOpacity = Math.clamp(config.danmakuOpacity, 20, 100);
        config.danmakuScalePercent = Math.clamp(config.danmakuScalePercent, 50, 170);
        int bilibiliQuality = BiliQuality.normalizeClient(config.bilibiliQuality);
        if (config.bilibiliQuality != bilibiliQuality) {
            config.bilibiliQuality = bilibiliQuality;
            changed = true;
        }
        int youtubeQuality = YouTubeQuality.normalizeClient(config.youtubeQuality);
        if (config.youtubeQuality != youtubeQuality) {
            config.youtubeQuality = youtubeQuality;
            changed = true;
        }
        savedVolume = config.volume;
        if (changed && !preserveInvalidFile) saveConfig();
    }

    private static void registerStartupGuide() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (startupGuideOpened || config == null || Boolean.TRUE.equals(config.startupGuideShown)) return;
            if (client.level != null || client.gui.screen() == null || client.gui.screen() instanceof StartupGuideScreen) return;
            startupGuideOpened = true;
            client.gui.setScreen(new StartupGuideScreen(client.gui.screen()));
        });
    }

    private static void registerStartupGuideScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingStartupGuideScreen) return;
            pendingStartupGuideScreen = false;
            if (client.gui.screen() instanceof StartupGuideScreen) return;
            client.gui.setScreen(new StartupGuideScreen(client.gui.screen()));
        });
    }

    private static void registerBiliLoginScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingBiliLoginScreen) return;
            pendingBiliLoginScreen = false;
            if (client.gui.screen() instanceof BiliLoginScreen) return;
            client.gui.setScreen(new BiliLoginScreen(client.gui.screen()));
        });
    }

    public static int openYouTubeAuth(CommandContext<FabricClientCommandSource> ignored) {
        pendingYouTubeAuthScreen = true;
        return 1;
    }

    private static void registerYouTubeAuthScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingYouTubeAuthScreen) return;
            pendingYouTubeAuthScreen = false;
            if (client.gui.screen() instanceof YouTubeAuthScreen) return;
            client.gui.setScreen(new YouTubeAuthScreen(client.gui.screen()));
        });
    }

}
