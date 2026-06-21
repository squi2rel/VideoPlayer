package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.creation.StartupGuideScreen;
import com.github.squi2rel.vp.creation.VideoCreationEditor;
import com.github.squi2rel.vp.creation.BiliLoginScreen;
import com.github.squi2rel.vp.creation.MpvFilterGraphScreen;
import com.github.squi2rel.vp.danmaku.BiliAuthRefresher;
import com.github.squi2rel.vp.danmaku.BiliCookie;
import com.github.squi2rel.vp.danmaku.ClientDanmakuController;
import com.github.squi2rel.vp.danmaku.ClientDanmakuRenderer;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.video.*;
import com.github.squi2rel.vp.vivecraft.Vivecraft;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.VideoPlayerMain.error;

@SuppressWarnings({"DataFlowIssue"})
public class VideoPlayerClient implements ClientModInitializer {
    public static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("videoplayer").resolve("videoplayer-client.json");
    public static final MinecraftClient client = MinecraftClient.getInstance();
    public static Config config;
    private static final Gson gson = new Gson();

    public static final HashMap<String, ClientVideoArea> areas = new HashMap<>();
    public static final ArrayList<ClientVideoScreen> screens = new ArrayList<>();
    private static final TouchHandler touchHandler = new TouchHandler();
    private static ClientVideoScreen currentLooking, currentScreen;
    private static boolean isInArea = false;
    private static BossBar bossBar = null;
    private static boolean bossBarAdded = false;
    private static boolean keyPressed = false;
    private static boolean startupGuideOpened = false;
    private static boolean pendingStartupGuideScreen = false;
    private static boolean pendingBiliLoginScreen = false;
    private static boolean pendingMpvFilterGraphScreen = false;

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
            ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.player.sendMessage(VpTexts.tr(
                    "message.videoplayer.backend_load_failed",
                    "VideoPlayer error: video backend failed to load\n%s\nSee logs for more information",
                    error
            ).formatted(Formatting.RED), false));
        }
        loadConfig();
        BiliBiliProvider.setCookieSupplier(BiliCookie::header);
        BiliAuthRefresher.checkOnStartup();
        ClientTickEvents.END_CLIENT_TICK.register(client -> BiliAuthRefresher.tick());
        registerStartupGuide();
        registerStartupGuideScreenOpener();
        registerBiliLoginScreenOpener();
        registerMpvFilterGraphScreenOpener();
        VideoProviders.register();
        disconnectHandler = () -> client.execute(VideoPlayerClient::cleanupClientState);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> cleanupClientState());
        if (Vivecraft.loaded) LOGGER.info("Found Vivecraft");
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> {
            if (config.alwaysConnected) ClientPacketHandler.config(VideoPlayerMain.version);
        });
        WorldRenderEvents.START_MAIN.register(e -> VideoPlayerClient.update());
        WorldRenderEvents.AFTER_ENTITIES.register(ScreenRenderer::render);
        WorldRenderEvents.END_MAIN.register(e -> VideoPlayerClient.postUpdate());
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
        ClientCommandRegistrationCallback.EVENT.register((d, c) -> d.register(ClientCommandManager.literal("vlc")
                .then(ClientCommandManager.literal("play")
                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.request(currentScreen.getScreen(), s.getArgument("url", String.class));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("playthat")
                        .then(ClientCommandManager.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommandManager.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                                .executes(s -> {
                                                    ClientVideoScreen screen = getScreen(s);
                                                    if (screen == null) return 0;
                                                    ClientPacketHandler.request(screen.getScreen(), s.getArgument("url", String.class));
                                                    return 1;
                                                })))))
                .then(ClientCommandManager.literal("skip")
                        .then(ClientCommandManager.argument("force", BoolArgumentType.bool())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skip(currentScreen.getScreen(), s.getArgument("force", Boolean.class));
                                    return 1;
                                }))
                        .then(ClientCommandManager.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommandManager.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(ClientCommandManager.argument("force", BoolArgumentType.bool())
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
                .then(ClientCommandManager.literal("volume")
                        .then(ClientCommandManager.argument("volume", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    int v = s.getArgument("volume", Integer.class);
                                    config.volume = v;
                                    saveConfig();
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.volume_set", "Volume set to %s%%", v).formatted(Formatting.GREEN));
                                    applyConfiguredVolume();
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("backend")
                        .then(ClientCommandManager.literal(VideoBackends.VLC)
                                .executes(s -> setVideoBackend(s, VideoBackends.VLC)))
                        .then(ClientCommandManager.literal(VideoBackends.MPV)
                                .executes(s -> setVideoBackend(s, VideoBackends.MPV)))
                        .executes(s -> {
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.current_backend", "Current playback backend: %s", VideoBackends.normalize(config.videoBackend)).formatted(Formatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("boot")
                        .executes(VideoPlayerClient::openStartupGuide))
                .then(ClientCommandManager.literal("mpvFilter")
                        .executes(VideoPlayerClient::openMpvFilterGraph))
                .then(biliAuthCommand("biliAuth"))
                .then(ClientCommandManager.literal("danmaku")
                        .executes(s -> {
                            if (checkInvalid(s, false)) return 0;
                            boolean enabled = ClientDanmakuController.toggleGlobal();
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.danmaku_state", "Danmaku: %s",
                                    (enabled ? VpTexts.tr("label.videoplayer.on", "On") : VpTexts.tr("label.videoplayer.off", "Off")).getString()
                            ).formatted(Formatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("createArea")
                        .then(ClientCommandManager.argument("x1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("x2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("name", StringArgumentType.string())
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
                .then(ClientCommandManager.literal("removeArea")
                        .then(ClientCommandManager.argument("name", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .executes(s -> {
                                    if (checkInvalid(s, false)) return 0;
                                    String name = s.getArgument("name", String.class);
                                    ClientPacketHandler.removeArea(name);
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("createScreen")
                        .then(ClientCommandManager.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                        .then(ClientCommandManager.argument("name", StringArgumentType.string())
                        .then(ClientCommandManager.argument("x1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("x2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("x3", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y3", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z3", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("x4", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("y4", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("z4", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("source", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
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
                .then(ClientCommandManager.literal("removeScreen")
                        .then(ClientCommandManager.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommandManager.argument("name", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
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
                .then(ClientCommandManager.literal("skipPercent")
                        .then(ClientCommandManager.argument("percent", FloatArgumentType.floatArg(0, 1.01f))
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skipPercent(currentScreen, s.getArgument("percent", Float.class));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("list")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            String str = currentScreen.getScreen().infos.stream()
                                    .map(i -> VpTexts.tr("message.videoplayer.queue_item", "%s requested by: %s", i.name(), i.playerName()).getString())
                                    .collect(Collectors.joining("\n"));
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.queue_list", "Video area %s screen %s\n%s",
                                    currentScreen.area.name, currentScreen.name,
                                    str.isEmpty() ? VpTexts.tr("message.videoplayer.queue_empty", "Queue is empty").getString() : str
                            ).formatted(Formatting.GOLD));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("sync")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            ClientPacketHandler.sync(currentScreen);
                            return 1;
                        }))
                .then(ClientCommandManager.literal("brightness")
                        .then(ClientCommandManager.argument("brightness", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    config.brightness = s.getArgument("brightness", Integer.class);
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.brightness_set", "Brightness set to %s%%", config.brightness).formatted(Formatting.GREEN));
                                    saveConfig();
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("slice")
                        .then(ClientCommandManager.argument("u1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("v1", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("u2", FloatArgumentType.floatArg())
                        .then(ClientCommandManager.argument("v2", FloatArgumentType.floatArg())
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    float u1 = s.getArgument("u1", Float.class);
                                    float v1 = s.getArgument("v1", Float.class);
                                    float u2 = s.getArgument("u2", Float.class);
                                    float v2 = s.getArgument("v2", Float.class);
                                    ClientPacketHandler.setUV(currentLooking, u1, v1, u2, v2);
                                    return 1;
                                }))))))
                .then(ClientCommandManager.literal("stop")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            currentScreen.clearPlaybackState();
                            if (currentScreen.player != null) currentScreen.player.stop();
                            return 1;
                        }))
                .then(ClientCommandManager.literal("setmeta")
                        .then(ClientCommandManager.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(ClientCommandManager.argument("screen", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
                                        .then(ClientCommandManager.literal("mute")
                                                .then(ClientCommandManager.argument("mute", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "mute", MetaValue.ofBool(s.getArgument("mute", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommandManager.literal("interactable")
                                                .then(ClientCommandManager.argument("interactable", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "interactable", MetaValue.ofBool(s.getArgument("interactable", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommandManager.literal("autoSync")
                                                .then(ClientCommandManager.argument("autoSync", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMetadata(screen, "autoSync", MetaValue.ofBool(s.getArgument("autoSync", Boolean.class)));
                                                            return 1;
                                                        })))
                                        .then(ClientCommandManager.literal("custom")
                                                .then(ClientCommandManager.literal("set")
                                                        .then(ClientCommandManager.argument("key", StringArgumentType.string())
                                                                .then(ClientCommandManager.argument("value", IntegerArgumentType.integer())
                                                                        .executes(s -> {
                                                                            ClientVideoScreen screen = getScreen(s);
                                                                            if (screen == null) return 0;
                                                                            ClientPacketHandler.setMetadata(screen, s.getArgument("key", String.class), MetaValue.ofInt(s.getArgument("value", Integer.class)));
                                                                            return 1;
                                                                        }))))
                                                .then(ClientCommandManager.literal("get")
                                                        .then(ClientCommandManager.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    String key = s.getArgument("key", String.class);
                                                                    MetaValue value = screen.metadata.get(key);
                                                                    s.getSource().sendFeedback(Text.of(key + "=" + (value == null ? "null" : value.toDisplayString())));
                                                                    return 1;
                                                                })))
                                                .then(ClientCommandManager.literal("remove")
                                                        .then(ClientCommandManager.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    ClientPacketHandler.removeMetadata(screen, s.getArgument("key", String.class));
                                                                    return 1;
                                                                })))
                                                .then(ClientCommandManager.literal("list")
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            s.getSource().sendFeedback(Text.of(screen.metadata.entries().toString()));
                                                            return 1;
                                                        })))
                                )))
                .then(ClientCommandManager.literal("scale")
                        .then(ClientCommandManager.literal("stretch")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, true, 1, 1);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("auto")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, false, 1, 1);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("scaleX", FloatArgumentType.floatArg(0.0625f, 16f))
                                        .then(ClientCommandManager.argument("scaleY", FloatArgumentType.floatArg(0.0625f, 16f))
                                                .executes(s -> {
                                                    if (checkInvalidLooking(s)) return 0;
                                                    ClientPacketHandler.setScale(currentLooking, false, s.getArgument("scaleX", Float.class), s.getArgument("scaleY", Float.class));
                                                    return 1;
                                                })))))
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || client.currentScreen != null || currentLooking == null) return;
            boolean pressed = client.options.useKey.isPressed();
            if (pressed && !keyPressed) {
                keyPressed = true;
                if (remoteControl || client.player.getStackInHand(Hand.MAIN_HAND).isEmpty() && client.player.getStackInHand(Hand.OFF_HAND).isEmpty()) {
                    VideoCreationEditor.instance().openConfigScreen(currentLooking);
                }
            } else if (!pressed) {
                keyPressed = false;
            }
        });
        bossBar = new ClientBossBar(UUID.randomUUID(), Text.of(""), 0, BossBar.Color.WHITE, BossBar.Style.PROGRESS, false, false, false);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> biliAuthCommand(String literal) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.literal("login")
                        .executes(VideoPlayerClient::openBiliLogin))
                .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("cookie", StringArgumentType.greedyString())
                                .executes(s -> {
                                    BiliCookie.set(s.getArgument("cookie", String.class));
                                    s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.bilibili_cookie_saved", "Bilibili auth saved locally").formatted(Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("clear")
                        .executes(s -> {
                            BiliCookie.clear();
                            s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.bilibili_cookie_cleared", "Bilibili auth cleared").formatted(Formatting.GREEN));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("status")
                        .executes(s -> {
                            s.getSource().sendFeedback(VpTexts.text(BiliCookie.status()).formatted(Formatting.GREEN));
                            return 1;
                        }));
    }

    private static int openBiliLogin(CommandContext<FabricClientCommandSource> s) {
        pendingBiliLoginScreen = true;
        return 1;
    }

    private static int openStartupGuide(CommandContext<FabricClientCommandSource> s) {
        pendingStartupGuideScreen = true;
        return 1;
    }

    private static int openMpvFilterGraph(CommandContext<FabricClientCommandSource> s) {
        pendingMpvFilterGraphScreen = true;
        return 1;
    }

    private static int setVideoBackend(CommandContext<FabricClientCommandSource> s, String backend) {
        config.videoBackend = VideoBackends.normalize(backend);
        saveConfig();
        if (VideoBackends.MPV.equals(config.videoBackend) && !MpvVideoBackend.isAvailable()) {
            LOGGER.warn("MPV backend selected but libmpv is not available", MpvVideoBackend.loadError());
            s.getSource().sendFeedback(VpTexts.tr(
                    "message.videoplayer.backend_mpv_unavailable",
                    "Playback backend set to MPV, but libmpv failed to load on this system. New videos will fall back to VLC. Install system libmpv."
            ).formatted(Formatting.YELLOW));
            return 1;
        }
        s.getSource().sendFeedback(VpTexts.tr("message.videoplayer.backend_set", "Playback backend set to %s. Only newly started videos are affected.", config.videoBackend).formatted(Formatting.GREEN));
        return 1;
    }

    private ClientVideoArea getArea(CommandContext<FabricClientCommandSource> s) {
        if (checkInvalid(s, false)) return null;
        String name = s.getArgument("area", String.class);
        ClientVideoArea area = areas.get(name);
        if (area == null) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.area_named_not_found", "No video area named %s", name).formatted(Formatting.RED));
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
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.screen_not_found", "Screen not found").formatted(Formatting.RED));
            return null;
        }
        return screen;
    }

    private boolean checkInvalid(CommandContext<FabricClientCommandSource> s, boolean checkScreen) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_connected", "Not connected to server").formatted(Formatting.RED));
            return true;
        }
        if (checkScreen && currentScreen == null) {
            if (isInArea) {
                s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.current_area_no_main_screen", "Current video area has no main screen").formatted(Formatting.RED));
            } else {
                s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_inside_area", "You are not inside a video area").formatted(Formatting.RED));
            }
            return true;
        }
        return false;
    }

    private boolean checkInvalidLooking(CommandContext<FabricClientCommandSource> s) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_connected", "Not connected to server").formatted(Formatting.RED));
            return true;
        }
        if (currentLooking == null) {
            s.getSource().sendFeedback(VpTexts.tr("error.videoplayer.not_looking_at_screen", "You are not looking at a screen").formatted(Formatting.RED));
            return true;
        }
        return false;
    }

    private static void updateBossBar() {
        if (currentLooking != null) {
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            if (!bossBarAdded) {
                handler.onBossBar(BossBarS2CPacket.add(bossBar));
                bossBarAdded = true;
            }
            ClientVideoScreen screen = currentLooking.getScreen();
            VideoInfo info = screen.currentDisplayInfo();
            if (info != null && screen.player != null) {
                String name = info.name();
                long progress = System.currentTimeMillis() - screen.getStartTime();
                long totalProgress = screen.player.getTotalProgress();
                String time;
                if (totalProgress > 0) {
                    boolean showHour = progress >= 3600000 || totalProgress >= 3600000;
                    time = formatDuration(progress, showHour) + "/" + formatDuration(totalProgress, showHour);
                    bossBar.setPercent((float) progress / totalProgress);
                } else {
                    time = formatDuration(progress, progress >= 3600000) + "/LIVE";
                    bossBar.setPercent(0);
                }
                bossBar.setName(Text.of(name + " " + time));
            } else {
                bossBar.setName(VpTexts.tr("label.videoplayer.none", "None"));
                bossBar.setPercent(1);
            }
            handler.onBossBar(BossBarS2CPacket.updateName(bossBar));
            handler.onBossBar(BossBarS2CPacket.updateProgress(bossBar));
        } else if (bossBarAdded) {
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            handler.onBossBar(BossBarS2CPacket.remove(bossBar.getUuid()));
            bossBarAdded = false;
        }
    }

    private static void checkInteract() {
        MinecraftClient client = VideoPlayerClient.client;
        if (client == null) return;

        isInArea = false;
        currentLooking = null;
        currentScreen = null;
        if (screens.isEmpty()) {
            touchHandler.handle(null);
            return;
        }

        float delta = VideoPlayerClient.client.getRenderTickCounter().getTickProgress(true);
        Vec3d eyePos = client.player.getCameraPosVec(delta);
        Vec3d lookVec = client.player.getRotationVec(delta);

        Vector3f lineStart = new Vector3f(eyePos.toVector3f());

        remoteControl = false;
        for (ItemStack item : List.of(client.player.getMainHandStack(), client.player.getOffHandStack())) {
            if (!Registries.ITEM.getId(item.getItem()).toString().equals(remoteControlName)) continue;
            CustomModelDataComponent data = item.getComponents().get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (data == null) continue;
            List<Float> id = data.floats();
            if (id.isEmpty() || !id.contains(remoteControlId)) continue;
            remoteControl = true;
        }
        Vector3f lineEnd = eyePos.add(lookVec.multiply(remoteControl ? remoteControlRange : noControlRange)).toVector3f();

        ArrayList<Intersection.Result> list = new ArrayList<>();
        for (ClientVideoScreen s : screens) {
            if (!s.interactable) continue;
            ClientVideoScreen screen = s.getTrackingScreen();
            if (screen == null)  continue;
            Intersection.Result result = Intersection.intersect(lineStart, lineEnd, screen);
            if (result.intersects) list.add(result);
        }
        Intersection.Result target = list.isEmpty() ? null : Collections.min(list, Comparator.comparing(s -> s.distance));
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
        String[] p1 = StringUtils.split(v, '.');
        String[] p2 = StringUtils.split(VideoPlayerMain.version, '.');
        if (p1.length < 2 || p2.length < 2) return false;
        return p1[0].equals(p2[0]) && p1[1].equals(p2[1]);
    }

    public static void update() {
        if (updated) return;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (screen.isPostUpdate()) continue;
            screen.swapTexture();
            screen.update();
        }
        profiler.swap("checkInteract");
        checkInteract();
        profiler.swap("updateBossBar");
        updateBossBar();
        profiler.pop();
        profiler.pop();
    }

    private static void cleanupClientState() {
        connected = false;
        for (ClientVideoArea area : new ArrayList<>(areas.values())) {
            area.remove();
        }
        areas.clear();
        for (ClientVideoScreen screen : new ArrayList<>(screens)) {
            screen.cleanup();
        }
        screens.clear();
        ScreenVolumeCache.clear();
        ClientDanmakuRenderer.clearCache();
        Degree360Player.clearMeshCache();
        ClientPermissionCache.clear();
        currentLooking = null;
        VideoCreationEditor.instance().clear();
    }

    public static void postUpdate() {
        if (updated) return;
        updated = true;
        Profiler profiler = Profilers.get();
        profiler.push("video");
        profiler.push("updateFrame");
        for (ClientVideoScreen screen : screens) {
            if (!screen.isPostUpdate()) continue;
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
        try {
            applyNativePlatformConfig();
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, gson.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void reloadConfig() {
        loadConfig();
    }

    public static void markStartupGuideShown() {
        if (config == null) return;
        config.startupGuideShown = true;
        saveConfig();
    }

    public static void applyNativePlatformConfig() {
        if (config == null) return;
        config.nativeVlcPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeVlcPlatform);
        config.nativeMpvPlatform = NativeDownloadConfig.normalizePlatformForCurrentOs(config.nativeMpvPlatform);
        NativePackageManager.selectPlatform(NativePackageManager.BACKEND_VLC, config.nativeVlcPlatform);
        NativePackageManager.selectPlatform(NativePackageManager.BACKEND_MPV, config.nativeMpvPlatform);
        StreamListener.configureProxy(config.nativeDownloadProxy);
        YouTubeProvider.configureProxy(config.nativeDownloadProxy);
        StreamListener.configureYtdlPath(config.mpvYtdlPath);
        YouTubeProvider.configureYtdlPath(config.mpvYtdlPath);
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

    public static void applyConfiguredVolume() {
        config.volume = Math.clamp(config.volume, 0, 100);
        for (ClientVideoScreen screen : screens) {
            if (screen.player instanceof VideoPlayer player && VideoBackends.VLC.equals(player.backendName())) {
                player.setVolume(config.volume);
            }
        }
    }

    private static void loadConfig() {
        boolean existed = Files.exists(configPath);
        boolean changed = false;
        try {
            config = gson.fromJson(Files.readString(configPath), Config.class);
            if (config == null) config = new Config();
        } catch (Exception e) {
            config = new Config();
            changed = true;
        }
        config.nativeDownloadUrls = NativeDownloadConfig.load();
        if (config.startupGuideShown == null) {
            config.startupGuideShown = existed;
            changed = true;
        }
        config.videoBackend = VideoBackends.normalize(config.videoBackend);
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
        if (changed) saveConfig();
    }

    private static void registerStartupGuide() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (startupGuideOpened || config == null || Boolean.TRUE.equals(config.startupGuideShown)) return;
            if (client.world != null || client.currentScreen == null || client.currentScreen instanceof StartupGuideScreen) return;
            startupGuideOpened = true;
            client.setScreen(new StartupGuideScreen(client.currentScreen));
        });
    }

    private static void registerStartupGuideScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingStartupGuideScreen) return;
            pendingStartupGuideScreen = false;
            if (client.currentScreen instanceof StartupGuideScreen) return;
            client.setScreen(new StartupGuideScreen(client.currentScreen));
        });
    }

    private static void registerBiliLoginScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingBiliLoginScreen) return;
            pendingBiliLoginScreen = false;
            if (client.currentScreen instanceof BiliLoginScreen) return;
            client.setScreen(new BiliLoginScreen(client.currentScreen));
        });
    }

    private static void registerMpvFilterGraphScreenOpener() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!pendingMpvFilterGraphScreen) return;
            pendingMpvFilterGraphScreen = false;
            if (client.currentScreen instanceof MpvFilterGraphScreen) return;
            client.setScreen(new MpvFilterGraphScreen(client.currentScreen));
        });
    }
}
