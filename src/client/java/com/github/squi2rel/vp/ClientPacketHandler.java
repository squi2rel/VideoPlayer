package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ByteBufUtils;
import com.github.squi2rel.vp.network.RequestResultStatus;
import com.github.squi2rel.vp.network.VideoPacketType;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.provider.NamedProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.video.ClientVideoArea;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.IVideoPlayer;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.VideoPlayerClient.areas;
import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.network.ByteBufUtils.writeString;

public class ClientPacketHandler {
    private static final long REQUEST_TTL_MS = 30_000L;
    private static int nextRequestId = 1;
    private static final Map<Integer, PendingRequest> pendingRequests = new HashMap<>();

    public static void handle(ByteBuf buf) {
        handle(buf, System.currentTimeMillis());
    }

    public static void handle(ByteBuf buf, long receivedAt) {
        VideoPacketType type = VideoPackets.readType(buf);
        if (type == null) {
            LOGGER.warn("Unknown packet type");
            return;
        }

        switch (type) {
            case CONFIG -> handleConfig(buf);
            case REQUEST -> handleRequest(buf);
            case SYNC -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long progress = buf.readLong();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen != null) screen.setProgress(progress);
            }
            case CREATE_AREA -> areas.put(VideoPackets.readName(buf), ClientVideoArea.read(buf));
            case REMOVE_AREA -> {
                String areaName = VideoPackets.readName(buf);
                ClientVideoArea area = areas.remove(areaName);
                ClientPermissionCache.removeArea(areaName);
                if (area != null) {
                    area.remove();
                }
            }
            case CREATE_SCREEN -> handleCreateScreen(buf);
            case REMOVE_SCREEN -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                ClientVideoArea area = areaOrNull(areaName);
                if (area != null) {
                    area.remove(screenName);
                }
                ClientPermissionCache.removeScreen(areaName, screenName);
            }
            case LOAD_AREA -> handleLoadArea(buf);
            case UNLOAD_AREA -> {
                ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
                if (area != null) area.unload();
            }
            case UPDATE_PLAYLIST -> handleUpdatePlaylist(buf);
            case SKIP -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                IVideoPlayer player = screen.player;
                screen.clearPlaybackState();
                if (player != null) MinecraftClient.getInstance().execute(player::stop);
            }
            case EXECUTE -> handleExecute(buf);
            case IDLE_PLAY -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) {
                    VideoScreen discard = new VideoScreen(null, screenName, List.of(), "");
                    VideoPackets.readIdlePlayConfig(buf, discard);
                    return;
                }
                VideoPackets.readIdlePlayConfig(buf, screen);
            }
            case SET_UV -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                float u1 = buf.readFloat();
                float v1 = buf.readFloat();
                float u2 = buf.readFloat();
                float v2 = buf.readFloat();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                screen.u1 = u1;
                screen.v1 = v1;
                screen.u2 = u2;
                screen.v2 = v2;
            }
            case SET_SCREEN_METADATA -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                String key = ByteBufUtils.readString(buf, 64);
                boolean remove = buf.readBoolean();
                MetaValue value = remove ? null : VideoPackets.readMetaValue(buf);
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                if (remove) {
                    screen.metadata.remove(key);
                } else {
                    screen.metadata.set(key, value);
                }
                screen.metadataChanged();
            }
            case SET_SCALE -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
            }
            case AUTO_SYNC -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long clientTime = buf.readLong();
                long progress = buf.readLong();
                long serverDelay = Math.max(0L, buf.readLong());
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                long roundTrip = Math.max(0L, receivedAt - clientTime - serverDelay);
                screen.autoSync(roundTrip, progress);
            }
            case UPDATE_SCREEN -> handleUpdateScreen(buf);
            case REQUEST_RESULT -> handleRequestResult(buf);
            case PERMISSIONS -> handlePermissions(buf);
            default -> LOGGER.warn("Unknown packet type: {}", type);
        }

        if (buf.readableBytes() > 0) {
            LOGGER.warn("Bytes remaining: {}, type {}", buf.readableBytes(), type);
        }
    }

    private static void handleRequestResult(ByteBuf buf) {
        int requestId = buf.readInt();
        RequestResultStatus status = RequestResultStatus.fromId(buf.readUnsignedByte());
        VpTranslation message = VideoPackets.readTranslation(buf);
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending == null) return;
        if (status == RequestResultStatus.DENIED) {
            ClientPermissionCache.setAllowed(pending.action(), pending.areaName(), pending.screenName(), false);
        } else if (status == RequestResultStatus.OK) {
            ClientPermissionCache.setAllowed(pending.action(), pending.areaName(), pending.screenName(), true);
        }
        if (pending.callback() != null) {
            pending.callback().accept(new RequestResult(requestId, status, message));
        }
    }

    private static void handlePermissions(ByteBuf buf) {
        String areaName = VideoPackets.readName(buf);
        String screenName = VideoPackets.readName(buf);
        long allowedMask = buf.readLong();
        ClientPermissionCache.update(areaName, screenName, allowedMask);
    }

    private static void handleConfig(ByteBuf buf) {
        String version = ByteBufUtils.readString(buf, 16);
        if (!VideoPlayerClient.checkVersion(version)) {
            Objects.requireNonNull(MinecraftClient.getInstance().player).sendMessage(VpTexts.tr(
                    "message.videoplayer.version_mismatch",
                    "Server VideoPlayer version does not match the local version. Local: %s, server: %s",
                    VideoPlayerMain.version, version
            ), false);
            return;
        }
        VideoPlayerClient.remoteControlName = ByteBufUtils.readString(buf, 256);
        VideoPlayerClient.remoteControlId = buf.readFloat();
        VideoPlayerClient.remoteControlRange = buf.readFloat();
        VideoPlayerClient.noControlRange = buf.readFloat();
        VideoPlayerClient.connected = true;
        config(VideoPlayerMain.version);
    }

    private static void handleRequest(ByteBuf buf) {
        String areaName = VideoPackets.readName(buf);
        String screenName = VideoPackets.readName(buf);
        VideoInfo info = VideoInfo.read(buf);
        boolean idle = buf.readBoolean();
        ClientVideoScreen screen = screenOrNull(areaName, screenName);
        if (screen == null) return;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        int playbackToken = screen.beginPlaybackRequest();
        if (screen.player != null) screen.player.stop();
        CompletableFuture<VideoInfo> video = resolveForLocalPlayback(screen, info);
        if (video == null) {
            player.sendMessage(VpTexts.tr("message.videoplayer.source_unresolved", "Unable to resolve video source"), false);
            return;
        }
        video.thenAccept(v -> {
            if (v == null) {
                MinecraftClient.getInstance().execute(() -> {
                    if (screen.canAcceptPlayback(playbackToken)) {
                        player.sendMessage(VpTexts.tr("message.videoplayer.source_unresolved", "Unable to resolve video source"), false);
                    }
                });
                return;
            }
            MinecraftClient.getInstance().execute(() -> {
                if (screen.canAcceptPlayback(playbackToken)) screen.play(v, idle);
            });
        });
    }

    private static CompletableFuture<VideoInfo> resolveForLocalPlayback(ClientVideoScreen screen, VideoInfo info) {
        if (info == null) return CompletableFuture.completedFuture(null);
        if (info.rawPath() == null || info.rawPath().isEmpty()) return CompletableFuture.completedFuture(info);
        int localQuality = VideoPlayerClient.config == null ? BiliQuality.DEFAULT_QN : VideoPlayerClient.config.bilibiliQuality;
        int screenLimit = screen == null || screen.metadata == null
                ? BiliQuality.UNLIMITED
                : screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED);
        int bilibiliQuality = BiliQuality.effective(localQuality, screenLimit);
        CompletableFuture<VideoInfo> video = VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName(), bilibiliQuality));
        if (video == null) return null;
        return CompletableFuture.supplyAsync(() -> {
            try {
                VideoInfo resolved = video.get();
                if (resolved == null) return null;
                if (resolved.path() == null || resolved.path().isEmpty()) return null;
                long durationMs = resolved.durationMs() > 0 ? resolved.durationMs() : info.durationMs();
                return new VideoInfo(info.playerName(), info.name(), resolved.path(), resolved.rawPath(), resolved.expire(), resolved.seekable(), resolved.params(), durationMs);
            } catch (Exception e) {
                LOGGER.error(e.toString());
                return null;
            }
        });
    }

    private static void handleCreateScreen(ByteBuf buf) {
        ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
        short size = buf.readUnsignedByte();
        for (int i = 0; i < size; i++) {
            VideoScreen base = VideoScreen.read(buf, area);
            if (area == null) {
                VideoPackets.readUv(buf, base);
                VideoPackets.readScale(buf, base);
                VideoPackets.readMeta(buf, base);
                continue;
            }
            ClientVideoScreen screen = ClientVideoScreen.from(base);
            VideoPackets.readUv(buf, screen);
            VideoPackets.readScale(buf, screen);
            VideoPackets.readMeta(buf, screen);
            screen.metadataChanged();
            area.addScreen(screen);
        }
    }

    private static void handleLoadArea(ByteBuf buf) {
        ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
        while (buf.readableBytes() != 0) {
            String screenName = VideoPackets.readName(buf);
            VideoInfo info = VideoInfo.read(buf);
            long seek = buf.readLong();
            boolean idle = buf.readBoolean();
            if (area == null) continue;
            ClientVideoScreen screen = area.getScreen(screenName);
            if (screen == null) continue;
            int playbackToken = screen.beginPlaybackRequest();
            screen.setToSeek(seek);
            CompletableFuture<VideoInfo> video = resolveForLocalPlayback(screen, info);
            if (video == null) continue;
            video.thenAccept(resolved -> {
                if (resolved == null) return;
                MinecraftClient.getInstance().execute(() -> {
                    if (!screen.isPlaybackRequestCurrent(playbackToken)) return;
                    screen.setToPlay(resolved, idle);
                    if (screen.canAcceptPlayback(playbackToken)) screen.play(resolved, idle);
                });
            });
        }
        if (area != null) area.load();
    }

    private static void handleUpdatePlaylist(ByteBuf buf) {
        ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
        short size = buf.readUnsignedByte();
        for (int i = 0; i < size; i++) {
            String screenName = VideoPackets.readName(buf);
            short len = buf.readUnsignedByte();
            VideoInfo[] infos = new VideoInfo[len];
            for (int j = 0; j < len; j++) {
                String playerName = ByteBufUtils.readString(buf, 256);
                String name = ByteBufUtils.readString(buf, 256);
                boolean seekable = buf.readBoolean();
                infos[j] = new VideoInfo(playerName, name, null, null, -1, seekable, null);
            }
            if (area == null) continue;
            ClientVideoScreen screen = area.getScreen(screenName);
            if (screen != null) screen.updatePlaylist(infos);
        }
    }

    private static void handleExecute(ByteBuf buf) {
        MinecraftClient client = MinecraftClient.getInstance();
        CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommandManager.getActiveDispatcher();
        if (dispatcher == null || client.player == null) return;
        try {
            dispatcher.execute("vlc " + ByteBufUtils.readString(buf, 1024), (FabricClientCommandSource) client.player.networkHandler.getCommandSource());
        } catch (CommandSyntaxException e) {
            client.player.sendMessage(VpTexts.tr("message.videoplayer.command_failed", "Command failed: %s", e).formatted(Formatting.RED), false);
        }
    }

    private static void handleUpdateScreen(ByteBuf buf) {
        String areaName = VideoPackets.readName(buf);
        String screenName = VideoPackets.readName(buf);
        short vertexCount = buf.readUnsignedByte();
        ArrayList<Vector3f> vertices = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            vertices.add(ByteBufUtils.readVec3(buf));
        }
        String source = VideoPackets.readName(buf);
        VideoScreen displayConfig = new VideoScreen(null, screenName, vertices, source);
        VideoScreen.readDisplayConfig(buf, displayConfig);
        ClientVideoScreen screen = screenOrNull(areaName, screenName);
        if (screen != null) screen.applyUpdate(vertices, source, displayConfig);
    }

    private static void send(byte[] bytes) {
        ClientPlayNetworking.send(new VideoPayload(bytes));
    }

    private static ByteBuf controlled(VideoPacketType type, VideoPermissionAction action, String areaName, String screenName,
                                      Consumer<RequestResult> callback) {
        cleanupPendingRequests();
        if (ClientPermissionCache.isDenied(action, areaName, screenName)) {
            if (callback != null) {
                callback.accept(new RequestResult(0, RequestResultStatus.DENIED,
                        VpTranslation.of("error.videoplayer.permission_denied", "Permission denied")));
            }
            return null;
        }
        int requestId = nextRequestId();
        ByteBuf buf = VideoPackets.create(type);
        buf.writeInt(requestId);
        pendingRequests.put(requestId, new PendingRequest(action, normalize(areaName), normalize(screenName), callback, System.currentTimeMillis()));
        return buf;
    }

    private static int nextRequestId() {
        int requestId = nextRequestId++;
        if (requestId <= 0) {
            nextRequestId = 2;
            requestId = 1;
        }
        return requestId;
    }

    private static void cleanupPendingRequests() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, PendingRequest>> iterator = pendingRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().createdAt() > REQUEST_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    public static boolean denied(RequestResult result) {
        return result != null && result.status() == RequestResultStatus.DENIED;
    }

    public static void config(String version) {
        ByteBuf buf = VideoPackets.create(VideoPacketType.CONFIG);
        writeString(buf, version);
        send(VideoPackets.toByteArray(buf));
    }

    public static void request(VideoScreen screen, String path) {
        request(screen, path, null);
    }

    public static void request(VideoScreen screen, String path, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.REQUEST, VideoPermissionAction.PLAY, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        writeString(buf, path);
        send(VideoPackets.toByteArray(buf));
    }

    public static void sync(VideoScreen screen) {
        sync(screen, null);
    }

    public static void sync(VideoScreen screen, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SYNC, VideoPermissionAction.SYNC, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        send(VideoPackets.toByteArray(buf));
    }

    public static void seek(VideoScreen screen, long progress) {
        seek(screen, progress, null);
    }

    public static void seek(VideoScreen screen, long progress, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SEEK, VideoPermissionAction.SEEK, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(Math.max(0, progress));
        send(VideoPackets.toByteArray(buf));
    }

    public static void createArea(Vector3f p1, Vector3f p2, String name) {
        createArea(p1, p2, name, null);
    }

    public static void createArea(Vector3f p1, Vector3f p2, String name, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.CREATE_AREA, VideoPermissionAction.CREATE_AREA, "", "", callback);
        if (buf == null) return;
        ByteBufUtils.writeVec3(buf, p1);
        ByteBufUtils.writeVec3(buf, p2);
        writeString(buf, name);
        send(VideoPackets.toByteArray(buf));
    }

    public static void removeArea(String area) {
        removeArea(area, null);
    }

    public static void removeArea(String area, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.REMOVE_AREA, VideoPermissionAction.REMOVE_AREA, area, "", callback);
        if (buf == null) return;
        writeString(buf, area);
        send(VideoPackets.toByteArray(buf));
    }

    public static void createScreen(VideoScreen screen) {
        createScreen(screen, null);
    }

    public static void createScreen(VideoScreen screen, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.CREATE_SCREEN, VideoPermissionAction.CREATE_SCREEN, screen.area.name, "", callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        VideoScreen.write(buf, screen);
        send(VideoPackets.toByteArray(buf));
    }

    public static void removeScreen(VideoScreen screen) {
        removeScreen(screen, null);
    }

    public static void removeScreen(VideoScreen screen, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.REMOVE_SCREEN, VideoPermissionAction.REMOVE_SCREEN, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        send(VideoPackets.toByteArray(buf));
    }

    public static void skip(VideoScreen screen, boolean force) {
        skip(screen, force, null);
    }

    public static void skip(VideoScreen screen, boolean force, Consumer<RequestResult> callback) {
        VideoPermissionAction action = force ? VideoPermissionAction.FORCE_SKIP : VideoPermissionAction.VOTE_SKIP;
        ByteBuf buf = controlled(VideoPacketType.SKIP, action, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeBoolean(force);
        send(VideoPackets.toByteArray(buf));
    }

    public static void skipPercent(VideoScreen screen, float percent) {
        skipPercent(screen, percent, null);
    }

    public static void skipPercent(VideoScreen screen, float percent, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SKIP_PERCENT, VideoPermissionAction.SET_SKIP_PERCENT, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeFloat(percent);
        send(VideoPackets.toByteArray(buf));
    }

    public static void setIdlePlay(VideoScreen screen, List<String> urls, boolean random) {
        setIdlePlay(screen, urls, random, null);
    }

    public static void setIdlePlay(VideoScreen screen, List<String> urls, boolean random, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.IDLE_PLAY, VideoPermissionAction.SET_IDLE_PLAY, screen.area.name, screen.name, callback);
        if (buf == null) return;
        screen.setIdlePlayConfig(urls, random);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        VideoPackets.writeIdlePlayConfig(buf, screen);
        send(VideoPackets.toByteArray(buf));
    }

    public static void setUV(VideoScreen screen, float u1, float v1, float u2, float v2) {
        setUV(screen, u1, v1, u2, v2, null);
    }

    public static void setUV(VideoScreen screen, float u1, float v1, float u2, float v2, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SET_UV, VideoPermissionAction.SET_UV, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeFloat(u1);
        buf.writeFloat(v1);
        buf.writeFloat(u2);
        buf.writeFloat(v2);
        send(VideoPackets.toByteArray(buf));
    }

    public static void openMenu(VideoScreen screen) {
        openMenu(screen, null);
    }

    public static void openMenu(VideoScreen screen, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.OPEN_MENU, VideoPermissionAction.OPEN_MENU, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        send(VideoPackets.toByteArray(buf));
    }

    public static void setMetadata(VideoScreen screen, String key, MetaValue value) {
        setMetadata(screen, key, value, null);
    }

    public static void setMetadata(VideoScreen screen, String key, MetaValue value, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SET_SCREEN_METADATA, VideoPermissionAction.SET_METADATA, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeBoolean(false);
        VideoPackets.writeMetaValue(buf, value);
        send(VideoPackets.toByteArray(buf));
    }

    public static void removeMetadata(VideoScreen screen, String key) {
        removeMetadata(screen, key, null);
    }

    public static void removeMetadata(VideoScreen screen, String key, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SET_SCREEN_METADATA, VideoPermissionAction.SET_METADATA, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeBoolean(true);
        send(VideoPackets.toByteArray(buf));
    }

    public static void setScale(VideoScreen screen, boolean fill, float scaleX, float scaleY) {
        setScale(screen, fill, scaleX, scaleY, null);
    }

    public static void setScale(VideoScreen screen, boolean fill, float scaleX, float scaleY, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.SET_SCALE, VideoPermissionAction.SET_SCALE, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeBoolean(fill);
        buf.writeFloat(scaleX);
        buf.writeFloat(scaleY);
        send(VideoPackets.toByteArray(buf));
    }

    public static void updateScreen(VideoScreen screen, List<Vector3f> vertices, String source) {
        updateScreen(screen, vertices, source, screen, null);
    }

    public static void updateScreen(VideoScreen screen, List<Vector3f> vertices, String source, Consumer<RequestResult> callback) {
        updateScreen(screen, vertices, source, screen, callback);
    }

    public static void updateScreen(VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        updateScreen(screen, vertices, source, displayConfig, null);
    }

    public static void updateScreen(VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.UPDATE_SCREEN, VideoPermissionAction.UPDATE_SCREEN, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeByte(vertices.size());
        for (Vector3f vertex : vertices) {
            ByteBufUtils.writeVec3(buf, vertex);
        }
        writeString(buf, source == null ? "" : source);
        VideoScreen.writeDisplayConfig(buf, displayConfig == null ? screen : displayConfig);
        send(VideoPackets.toByteArray(buf));
    }

    public static void autoSync(VideoScreen screen, long clientTime) {
        ByteBuf buf = controlled(VideoPacketType.AUTO_SYNC, VideoPermissionAction.AUTO_SYNC, screen.area.name, screen.name, null);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(clientTime);
        send(VideoPackets.toByteArray(buf));
    }

    public record RequestResult(int requestId, RequestResultStatus status, VpTranslation message) {
    }

    private record PendingRequest(VideoPermissionAction action, String areaName, String screenName,
                                  Consumer<RequestResult> callback, long createdAt) {
    }

    private static ClientVideoArea areaOrNull(String areaName) {
        ClientVideoArea area = areas.get(areaName);
        if (area == null) {
            LOGGER.warn("Unknown video area: {}", areaName);
        }
        return area;
    }

    private static ClientVideoScreen screenOrNull(String areaName, String screenName) {
        ClientVideoArea area = areas.get(areaName);
        if (area == null) {
            LOGGER.warn("Unknown video area: {}", areaName);
            return null;
        }
        ClientVideoScreen screen = area.getScreen(screenName);
        if (screen == null) {
            LOGGER.warn("Unknown video screen: {} in {}", screenName, areaName);
        }
        return screen;
    }
}
