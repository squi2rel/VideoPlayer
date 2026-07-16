package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ByteBufUtils;
import com.github.squi2rel.vp.network.RequestResultStatus;
import com.github.squi2rel.vp.network.VideoPacketType;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.network.VideoProtocol;
import com.github.squi2rel.vp.i18n.VpTexts;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.provider.LocalPlaybackInfo;
import com.github.squi2rel.vp.provider.NamedProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.YouTubeProvider;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.bilibili.BiliBiliVideoProvider;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.VideoPlayerClient.areas;
import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.network.ByteBufUtils.writeString;

public class ClientPacketHandler {
    private static final long REQUEST_TTL_MS = 60_000L;
    private static int nextRequestId = 1;
    private static final Map<Integer, PendingRequest> pendingRequests = new HashMap<>();

    public static void handle(ByteBuf buf) {
        handle(buf, System.currentTimeMillis());
    }

    public static void handle(ByteBuf buf, long receivedAt) {
        cleanupPendingRequests();
        VideoPacketType type = VideoPackets.readType(buf);
        if (type == null) {
            LOGGER.warn("Unknown packet type");
            return;
        }
        if (VideoPlayerClient.protocolRejected() && !VideoProtocol.allowedForRejectedClient(type)) {
            return;
        }

        switch (type) {
            case CONFIG -> handleConfig(buf, false);
            case REQUEST -> handleRequest(buf, receivedAt);
            case SYNC -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long generation = buf.readLong();
                long progress = buf.readLong();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen != null && screen.acceptServerPlaybackGeneration(generation)) screen.setProgress(progress);
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
            case LOAD_AREA -> handleLoadArea(buf, receivedAt);
            case UNLOAD_AREA -> {
                ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
                if (area != null) area.unload();
            }
            case UPDATE_PLAYLIST -> handleUpdatePlaylist(buf);
            case SKIP -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long generation = buf.readLong();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                if (!screen.acceptServerPlaybackGeneration(generation)) return;
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
                if (!finite(u1, v1, u2, v2)) return;
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
                if (ScreenMetadata.KEY_BILIBILI_QUALITY.equals(key)
                        || ScreenMetadata.KEY_YOUTUBE_QUALITY.equals(key)) {
                    reloadQualityPlayback(screen);
                }
            }
            case SET_SCALE -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                if (!finite(scaleX, scaleY)) return;
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
            }
            case AUTO_SYNC -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long generation = buf.readLong();
                long clientTime = buf.readLong();
                long progress = buf.readLong();
                long serverDelay = Math.max(0L, buf.readLong());
                ClientVideoScreen screen = screenOrNull(areaName, screenName);
                if (screen == null) return;
                if (generation != screen.serverPlaybackGeneration()) return;
                long roundTrip = Math.max(0L, receivedAt - clientTime - serverDelay);
                screen.autoSync(roundTrip, progress);
            }
            case UPDATE_SCREEN -> handleUpdateScreen(buf);
            case REQUEST_RESULT -> handleRequestResult(buf);
            case PERMISSIONS -> handlePermissions(buf);
            case RESET_CLIENT -> handleConfig(buf, true);
            case PROTOCOL_REJECT -> handleProtocolReject(buf);
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

    private static void handleConfig(ByteBuf buf, boolean reset) {
        String remoteToken = ByteBufUtils.readString(buf, 16);
        if (reset) VideoPlayerClient.resetServerState();
        if (!VideoProtocol.compatible(VideoPlayerMain.version, remoteToken)) {
            VideoPlayerClient.rejectProtocol(VideoProtocol.displayVersion(remoteToken));
            return;
        }
        VideoPlayerClient.acceptProtocol();
        VideoPlayerClient.remoteControlName = ByteBufUtils.readString(buf, 256);
        VideoPlayerClient.remoteControlId = buf.readFloat();
        VideoPlayerClient.remoteControlRange = buf.readFloat();
        VideoPlayerClient.noControlRange = buf.readFloat();
        if (reset) {
            long nonce = buf.readLong();
            if (nonce == 0L) {
                VideoPlayerClient.rejectProtocol("invalid handshake reset");
                return;
            }
            VideoPlayerClient.setHandshakeNonce(nonce);
            handshakeAck(nonce);
            VideoPlayerClient.connected = false;
        } else {
            VideoPlayerClient.setHandshakeNonce(0L);
            VideoPlayerClient.connected = true;
        }
    }

    private static void handleProtocolReject(ByteBuf buf) {
        String remoteToken = ByteBufUtils.readString(buf, 16);
        VideoPlayerClient.rejectProtocol(VideoProtocol.displayVersion(remoteToken));
    }

    private static void handleRequest(ByteBuf buf, long receivedAt) {
        String areaName = VideoPackets.readName(buf);
        String screenName = VideoPackets.readName(buf);
        long generation = buf.readLong();
        long progress = buf.readLong();
        long serverSentAt = buf.readLong();
        VideoInfo info = VideoInfo.read(buf);
        boolean idle = buf.readBoolean();
        ClientVideoScreen screen = screenOrNull(areaName, screenName);
        if (screen == null) return;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        int playbackToken = screen.beginServerPlaybackRequest(generation);
        if (playbackToken < 0) return;
        CompletableFuture<VideoInfo> video = resolveForLocalPlayback(screen, info);
        screen.trackPlaybackFuture(playbackToken, video);
        video.whenComplete((v, error) -> {
            if (error != null || v == null) {
                MinecraftClient.getInstance().execute(() -> {
                    if (screen.canAcceptPlayback(playbackToken)) {
                        screen.failPlaybackRequest(playbackToken);
                        player.sendMessage(VpTexts.tr("message.videoplayer.source_unresolved", "Unable to resolve video source"), false);
                    }
                });
                return;
            }
            MinecraftClient.getInstance().execute(() -> {
                if (!screen.canAcceptPlayback(playbackToken)) return;
                if (info.seekable() && progress >= 0) {
                    long transport = Math.max(0L, receivedAt - serverSentAt);
                    transport = Math.min(transport, 30_000L);
                    screen.setToSeek(progress + transport + Math.max(0L, System.currentTimeMillis() - receivedAt));
                }
                screen.play(v, idle);
            });
        });
    }

    static CompletableFuture<VideoInfo> resolveForLocalPlayback(ClientVideoScreen screen, VideoInfo info) {
        if (info == null) return CompletableFuture.completedFuture(null);
        if (info.rawPath() == null || info.rawPath().isEmpty()) return CompletableFuture.completedFuture(info);
        CompletableFuture<VideoInfo> video = resolveLocalProvider(screen, info);
        if (video == null) return CompletableFuture.completedFuture(LocalPlaybackInfo.select(info, null));
        CompletableFuture<VideoInfo> selected = new CompletableFuture<>();
        video.whenComplete((resolved, error) -> {
            if (error != null) LOGGER.warn("Failed to resolve local playback source {}", VideoProviders.redactedSource(info.rawPath()), error);
            if (!selected.isCancelled()) selected.complete(LocalPlaybackInfo.select(info, resolved));
        });
        selected.whenComplete((resolved, error) -> {
            if (selected.isCancelled()) video.cancel(true);
        });
        return selected;
    }

    private static CompletableFuture<VideoInfo> resolveLocalProvider(ClientVideoScreen screen, VideoInfo info) {
        int localQuality = VideoPlayerClient.config == null ? BiliQuality.DEFAULT_QN : VideoPlayerClient.config.bilibiliQuality;
        int screenLimit = screen == null || screen.metadata == null
                ? BiliQuality.UNLIMITED
                : screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED);
        int bilibiliQuality = BiliQuality.effective(localQuality, screenLimit);
        int localYoutube = VideoPlayerClient.config == null ? YouTubeQuality.AUTO : VideoPlayerClient.config.youtubeQuality;
        int youtubeLimit = screen == null || screen.metadata == null
                ? YouTubeQuality.AUTO
                : screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO);
        int youtubeQuality = YouTubeQuality.effective(localYoutube, youtubeLimit);
        return VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName(), bilibiliQuality, youtubeQuality));
    }

    public static void reloadQualityPlayback(ClientVideoScreen screen) {
        if (screen == null || screen.player == null) return;
        VideoInfo current = screen.currentPlaybackInfo();
        if (current == null || current.rawPath() == null || current.rawPath().isBlank()) return;
        if (!BiliBiliVideoProvider.isBiliVideoRawPath(current.rawPath())
                && !YouTubeProvider.isYouTubeRawPath(current.rawPath())) return;
        long progress = Math.max(0L, screen.player.getProgress());
        boolean idle = screen.isIdlePlaying();
        boolean paused = screen.player.isPaused();
        int playbackToken = screen.beginPlaybackRequest();
        CompletableFuture<VideoInfo> video = resolveLocalProvider(screen, current);
        if (video == null) {
            screen.failPlaybackRequest(playbackToken);
            return;
        }
        screen.trackPlaybackFuture(playbackToken, video);
        video.whenComplete((resolved, error) -> {
            if (error != null) {
                LOGGER.warn("Failed to reload quality-limited source {}", VideoProviders.redactedSource(current.rawPath()), error);
                MinecraftClient.getInstance().execute(() -> screen.failPlaybackRequest(playbackToken));
                return;
            }
            if (!LocalPlaybackInfo.playable(resolved)) {
                MinecraftClient.getInstance().execute(() -> screen.failPlaybackRequest(playbackToken));
                return;
            }
            VideoInfo selected = LocalPlaybackInfo.select(current, resolved);
            MinecraftClient.getInstance().execute(() -> {
                if (!screen.canAcceptPlayback(playbackToken)) return;
                if (progress > 0) screen.setToSeek(progress);
                screen.play(selected, idle);
                if (paused && screen.player != null) {
                    screen.player.pause(true);
                }
            });
        });
    }

    private static void handleCreateScreen(ByteBuf buf) {
        ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
        short size = buf.readUnsignedByte();
        if (size > VideoArea.MAX_SCREENS) throw new IllegalStateException("Video screen count exceeds " + VideoArea.MAX_SCREENS);
        for (int i = 0; i < size; i++) {
            VideoScreen base = VideoScreen.read(buf, area);
            if (area == null) {
                VideoPackets.readUv(buf, base);
                VideoPackets.readScale(buf, base);
                continue;
            }
            ClientVideoScreen screen = ClientVideoScreen.from(base);
            VideoPackets.readUv(buf, screen);
            VideoPackets.readScale(buf, screen);
            screen.metadataChanged();
            area.addScreen(screen);
        }
    }

    private static void handleLoadArea(ByteBuf buf, long receivedAt) {
        ClientVideoArea area = areaOrNull(VideoPackets.readName(buf));
        while (buf.readableBytes() != 0) {
            String screenName = VideoPackets.readName(buf);
            long generation = buf.readLong();
            VideoInfo info = VideoInfo.read(buf);
            long seek = buf.readLong();
            boolean idle = buf.readBoolean();
            if (area == null) continue;
            ClientVideoScreen screen = area.getScreen(screenName);
            if (screen == null) continue;
            int playbackToken = screen.beginServerPlaybackRequest(generation);
            if (playbackToken < 0) continue;
            CompletableFuture<VideoInfo> video = resolveForLocalPlayback(screen, info);
            screen.trackPlaybackFuture(playbackToken, video);
            video.whenComplete((resolved, error) -> {
                if (error != null || resolved == null) {
                    MinecraftClient.getInstance().execute(() -> screen.failPlaybackRequest(playbackToken));
                    return;
                }
                MinecraftClient.getInstance().execute(() -> {
                    if (!screen.isPlaybackRequestCurrent(playbackToken)) return;
                    if (info.seekable() && seek >= 0) {
                        screen.setToSeek(seek + Math.max(0L, System.currentTimeMillis() - receivedAt));
                    }
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
        if (size > VideoArea.MAX_SCREENS) throw new IllegalStateException("Playlist screen count exceeds " + VideoArea.MAX_SCREENS);
        for (int i = 0; i < size; i++) {
            String screenName = VideoPackets.readName(buf);
            short len = buf.readUnsignedByte();
            if (len > com.github.squi2rel.vp.video.PlaybackQueue.MAX_ITEMS) {
                throw new IllegalStateException("Video queue exceeds " + com.github.squi2rel.vp.video.PlaybackQueue.MAX_ITEMS + " items");
            }
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

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static long serverGeneration(VideoScreen screen) {
        return screen instanceof ClientVideoScreen clientScreen ? clientScreen.serverPlaybackGeneration() : 0L;
    }

    private static ByteBuf controlled(VideoPacketType type, VideoPermissionAction action, String areaName, String screenName,
                                      Consumer<RequestResult> callback) {
        cleanupPendingRequests();
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
            Map.Entry<Integer, PendingRequest> entry = iterator.next();
            if (now - entry.getValue().createdAt() <= REQUEST_TTL_MS) continue;
            iterator.remove();
            Consumer<RequestResult> callback = entry.getValue().callback();
            if (callback != null) {
                callback.accept(new RequestResult(entry.getKey(), RequestResultStatus.ERROR,
                        VpTranslation.of("error.videoplayer.request_timeout", "VideoPlayer request timed out")));
            }
        }
    }

    public static void resetPendingRequests() {
        if (pendingRequests.isEmpty()) return;
        ArrayList<Map.Entry<Integer, PendingRequest>> pending = new ArrayList<>(pendingRequests.entrySet());
        pendingRequests.clear();
        VpTranslation message = VpTranslation.of("error.videoplayer.server_state_reset", "VideoPlayer server state was reset");
        for (Map.Entry<Integer, PendingRequest> entry : pending) {
            Consumer<RequestResult> callback = entry.getValue().callback();
            if (callback != null) {
                callback.accept(new RequestResult(entry.getKey(), RequestResultStatus.ERROR, message));
            }
        }
    }

    public static void tickPendingRequests() {
        cleanupPendingRequests();
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    public static boolean denied(RequestResult result) {
        return result != null && result.status() == RequestResultStatus.DENIED;
    }

    public static boolean failed(RequestResult result) {
        return result == null || result.status() != RequestResultStatus.OK;
    }

    public static void config(String version) {
        ByteBuf buf = VideoPackets.create(VideoPacketType.CONFIG);
        writeString(buf, VideoProtocol.token(version));
        send(VideoPackets.toByteArray(buf));
    }

    private static void handshakeAck(long nonce) {
        send(VideoPackets.handshakeAck(nonce));
    }

    public static void request(VideoScreen screen, String path) {
        request(screen, path, null);
    }

    public static void request(VideoScreen screen, String path, Consumer<RequestResult> callback) {
        String normalized = path == null ? "" : path.trim();
        if (!VideoScreen.validPlayUrl(normalized)) {
            localError(callback, VpTranslation.of(
                    "error.videoplayer.play_url_invalid_length",
                    "Video URL must not be empty or exceed %s UTF-8 bytes",
                    VideoScreen.MAX_PLAY_URL_BYTES
            ));
            return;
        }
        ByteBuf buf = controlled(VideoPacketType.REQUEST, VideoPermissionAction.PLAY, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        writeString(buf, normalized);
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
        buf.writeLong(serverGeneration(screen));
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
        buf.writeLong(serverGeneration(screen));
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
        buf.writeLong(serverGeneration(screen));
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
        if (!VideoScreen.validIdlePlayConfig(urls)) {
            localError(callback, VpTranslation.of(
                    "error.videoplayer.idle_play_payload_invalid",
                    "IdlePlay must contain at most %s URLs, each at most %s UTF-8 bytes and %s bytes in total",
                    VideoScreen.MAX_IDLE_PLAY_ITEMS,
                    VideoScreen.MAX_IDLE_PLAY_URL_BYTES,
                    VideoScreen.MAX_IDLE_PLAY_TOTAL_BYTES
            ));
            return;
        }
        ByteBuf buf = controlled(VideoPacketType.IDLE_PLAY, VideoPermissionAction.SET_IDLE_PLAY, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        VideoPackets.writeIdlePlayConfig(buf, urls, random);
        send(VideoPackets.toByteArray(buf));
    }

    private static void localError(Consumer<RequestResult> callback, VpTranslation message) {
        if (callback != null) callback.accept(new RequestResult(0, RequestResultStatus.ERROR, message));
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

    public static void autoSync(VideoScreen screen, long clientTime, Consumer<RequestResult> callback) {
        ByteBuf buf = controlled(VideoPacketType.AUTO_SYNC, VideoPermissionAction.AUTO_SYNC, screen.area.name, screen.name, callback);
        if (buf == null) return;
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(serverGeneration(screen));
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
