package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import com.github.squi2rel.vp.i18n.MinecraftTexts;
import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.permission.VideoPermissionContext;
import com.github.squi2rel.vp.permission.VideoPermissionPlayer;
import com.github.squi2rel.vp.permission.VideoPermissions;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.MetaType;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.OrderedPlayAdmissions;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.video.VideoSourceGraph;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class ServerPacketHandler {
    private static final float EPSILON = 0.02f;

    public static void handle(ServerPlayerEntity player, ByteBuf buf) {
        handle(player, buf, System.currentTimeMillis());
    }

    public static void handle(ServerPlayerEntity player, ByteBuf buf, long receivedAt) {
        VideoPacketType type = VideoPackets.readType(buf);
        if (type == null) {
            player.networkHandler.disconnect(Text.of("Unknown packet type"));
            return;
        }
        if (type != VideoPacketType.CONFIG && type != VideoPacketType.HANDSHAKE_ACK
                && !DataHolder.protocolActive(player.getUuid())) {
            return;
        }
        LOGGER.info("server type: {}", type);
        switch (type) {
            case CONFIG -> {
                String remoteToken = ByteBufUtils.readString(buf, 16);
                if (!VideoProtocol.compatible(VideoPlayerMain.version, remoteToken)) {
                    if (DataHolder.rejectHandshake(player.getUuid())) {
                        sendTo(player, VideoPackets.protocolReject(VideoPlayerMain.version));
                        reject(player, VpTranslation.of(
                                "error.videoplayer.protocol_mismatch",
                                "VideoPlayer client and server must use the same 2.0.1 build. Client: %s, server: %s",
                                VideoProtocol.displayVersion(remoteToken), VideoPlayerMain.version
                        ));
                    }
                    return;
                }
                VideoHandshakeState previous = DataHolder.handshakeState(player.getUuid());
                if (previous == VideoHandshakeState.NEEDS_RESET) {
                    DataHolder.acceptHandshake(player.getUuid());
                    long nonce = DataHolder.issueHandshakeNonce(player.getUuid());
                    sendTo(player, VideoPackets.resetClient(VideoProtocol.token(VideoPlayerMain.version), DataHolder.config, nonce));
                } else if (previous == VideoHandshakeState.RESET_SENT) {
                    long nonce = DataHolder.handshakeNonce(player.getUuid());
                    if (nonce == 0L) nonce = DataHolder.issueHandshakeNonce(player.getUuid());
                    sendTo(player, VideoPackets.resetClient(VideoProtocol.token(VideoPlayerMain.version), DataHolder.config, nonce));
                } else if (previous == VideoHandshakeState.ACTIVE) {
                    sendTo(player, VideoPackets.config(VideoProtocol.token(VideoPlayerMain.version), DataHolder.config));
                    sendGlobalPermissions(player);
                }
            }
            case HANDSHAKE_ACK -> {
                long nonce = buf.readLong();
                if (DataHolder.acceptHandshakeAck(player.getUuid(), nonce)) {
                    sendTo(player, VideoPackets.config(VideoProtocol.token(VideoPlayerMain.version), DataHolder.config));
                    sendGlobalPermissions(player);
                }
            }
            case REQUEST -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.PLAY, VideoPermissionContext.screen(screen))) return;
                String url = ByteBufUtils.readString(buf, VideoScreen.MAX_PLAY_URL_LENGTH);
                OrderedPlayAdmissions.Reservation reservation = screen.reservePlayAdmission(result -> {
                    if (result.success()) {
                        requestOk(player, requestId);
                    } else {
                        requestError(player, requestId, VpTranslation.of("message.videoplayer.source_unresolved", "Unable to resolve video source"));
                    }
                });
                if (reservation == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.queue_full", "Video queue is full"));
                    return;
                }
                try {
                    int bilibiliLimit = BiliQuality.normalizeScreenLimit(
                            screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED)
                    );
                    int youtubeLimit = YouTubeQuality.normalizeScreenLimit(
                            screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO)
                    );
                    screen.attachPlayAdmission(reservation, VideoProviders.from(
                            url, new PlayerProviderSource(player, bilibiliLimit, youtubeLimit)
                    ));
                } catch (Throwable error) {
                    screen.failPlayAdmission(reservation, error);
                }
            }
            case SYNC -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                long generation = buf.readLong();
                if (generation != screen.currentPlaybackGeneration()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                if (screen.currentPlayback() == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.nothing_playing", "Nothing is currently playing"));
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.SYNC, VideoPermissionContext.screen(screen))) return;
                sendTo(player, VideoPackets.sync(screen, screen.getProgress()));
                requestOk(player, requestId);
            }
            case SEEK -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SEEK, VideoPermissionContext.screen(screen))) return;
                long generation = buf.readLong();
                if (generation != screen.currentPlaybackGeneration()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.not_seekable", "The current video cannot be seeked"));
                    return;
                }
                long progress = Math.max(0, buf.readLong());
                IVideoListener listener = screen.getListener();
                if (listener == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.listener_unavailable", "Playback listener is unavailable"));
                    return;
                }
                screen.setProgress(progress);
                long syncedProgress = listener.getProgress();
                if (syncedProgress < 0) syncedProgress = progress;
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.sync(screen, syncedProgress));
                }
                requestOk(player, requestId);
            }
            case CREATE_AREA -> {
                int requestId = buf.readInt();
                Vector3f p1 = ByteBufUtils.readVec3(buf);
                Vector3f p2 = ByteBufUtils.readVec3(buf);
                String name = VideoPackets.readName(buf);
                String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
                DataHolder.loadWorld(player.getEntityWorld().getServer(), player.getEntityWorld());
                if (!DataHolder.worldConfigValid(dim)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.world_config_invalid", "The VideoPlayer configuration for this world is invalid and must be repaired before it can be modified"));
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.CREATE_AREA, VideoPermissionContext.global(dim))) return;
                if (!validName(player, name, "Area") || !validAreaBounds(player, p1, p2)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.area_invalid", "Video area configuration is invalid"));
                    return;
                }
                HashMap<String, VideoArea> map = DataHolder.areas.computeIfAbsent(dim, k -> new HashMap<>());
                if (map.size() >= VideoArea.MAX_AREAS_PER_WORLD) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.area_limit", "A world can contain at most %s video areas", VideoArea.MAX_AREAS_PER_WORLD));
                    return;
                }
                if (map.containsKey(name)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.area_exists", "A video area named %s already exists", name));
                    return;
                }
                VideoArea area = VideoArea.from(p1, p2, name, dim);
                area.initServer();
                map.put(area.name, area);
                message(player, VpTranslation.of("message.videoplayer.area_created", "Created video area %s in world %s", area.name, player.getEntityWorld().getRegistryKey().getValue()));
                requestOk(player, requestId);
                sendAreaPermissions(player, area);
            }
            case REMOVE_AREA -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.REMOVE_AREA, VideoPermissionContext.area(area))) return;
                List<ServerPlayerEntity> receivers = List.of();
                byte[] data = null;
                HashMap<String, VideoArea> map = DataHolder.areas.get(area.dim);
                VideoArea removed = map == null ? null : map.remove(area.name);
                if (removed == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.area_not_found", "Video area no longer exists"));
                    return;
                }
                if (removed.hasPlayer()) {
                    data = VideoPackets.removeArea(removed);
                    receivers = players(player.getEntityWorld().getServer().getPlayerManager(), removed.playerSnapshot());
                }
                removed.remove();
                sendToPlayers(receivers, data);
                message(player, VpTranslation.of("message.videoplayer.area_removed", "Removed video area %s from world %s", area.name, player.getEntityWorld().getRegistryKey().getValue()));
                requestOk(player, requestId);
            }
            case CREATE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) {
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.CREATE_SCREEN, VideoPermissionContext.area(area))) return;
                VideoScreen screen = VideoScreen.read(buf, area);
                if (!validScreen(player, area, screen)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.screen_invalid", "Video screen configuration is invalid"));
                    return;
                }
                screen.initServer();
                List<ServerPlayerEntity> receivers = List.of();
                byte[] data = null;
                area.addScreen(screen);
                if (area.hasPlayer()) {
                    data = VideoPackets.createScreen(List.of(screen));
                    receivers = players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot());
                }
                sendToPlayers(receivers, data);
                message(player, VpTranslation.of("message.videoplayer.screen_created", "Created screen %s in video area %s", screen.name, area.name));
                requestOk(player, requestId);
                refreshPermissions(area);
            }
            case REMOVE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                String screenName = VideoPackets.readName(buf);
                VideoScreen existing = requireScreen(player, requestId, area, screenName);
                if (existing == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.REMOVE_SCREEN, VideoPermissionContext.screen(existing))) return;
                if (area.isScreenReferenced(screenName)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.source_screen_in_use", "Screen is still used as a source by another screen"));
                    return;
                }
                VideoScreen screen;
                List<ServerPlayerEntity> receivers = List.of();
                byte[] data = null;
                screen = area.removeScreen(screenName);
                if (screen == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (screen != null && area.hasPlayer()) {
                    data = VideoPackets.removeScreen(screen);
                    receivers = players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot());
                }
                sendToPlayers(receivers, data);
                if (screen != null) {
                    message(player, VpTranslation.of("message.videoplayer.screen_removed", "Removed screen %s from video area %s", screen.name, area.name));
                    requestOk(player, requestId);
                    refreshPermissions(area);
                }
            }
            case SKIP -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                long generation = buf.readLong();
                if (generation != screen.currentPlaybackGeneration()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                boolean force = buf.readBoolean();
                if (force) {
                    if (!requirePermission(player, requestId, VideoPermissionAction.FORCE_SKIP, VideoPermissionContext.screen(screen))) return;
                    screen.skip();
                    requestOk(player, requestId);
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.VOTE_SKIP, VideoPermissionContext.screen(screen))) return;
                screen.voteSkip(player.getUuid());
                Text s = MinecraftTexts.tr(
                        "message.videoplayer.skip_vote_broadcast",
                        "Player %s voted to skip the video on %s. %s more players required",
                        player.getName(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                );
                player.sendMessage(MinecraftTexts.tr("message.videoplayer.skip_voted", "Voted to skip this video").formatted(Formatting.GOLD));
                for (ServerPlayerEntity target : players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot())) {
                    target.sendMessage(s);
                }
                requestOk(player, requestId);
            }
            case SKIP_PERCENT -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_SKIP_PERCENT, VideoPermissionContext.screen(screen))) return;
                float skipPercent = buf.readFloat();
                if (!Float.isFinite(skipPercent) || skipPercent < 0f || skipPercent > 1f) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.skip_percent_invalid", "Skip vote percent must be between 0 and 1"));
                    return;
                }
                screen.setSkipPercent(skipPercent);
                message(player, VpTranslation.of("message.videoplayer.skip_percent_set", "Set skip vote ratio for screen %s to %s", screen.name, screen.skipPercent));
                requestOk(player, requestId);
            }
            case IDLE_PLAY -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_IDLE_PLAY, VideoPermissionContext.screen(screen))) return;
                VideoPackets.readIdlePlayConfig(buf, screen);
                screen.idlePlayConfigChanged();
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.idlePlay(screen));
                }
                message(player, VpTranslation.of("message.videoplayer.idle_play_updated", "Updated IdlePlay list for screen %s", screen.name));
                requestOk(player, requestId);
            }
            case SET_UV -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_UV, VideoPermissionContext.screen(screen))) return;
                float u1 = buf.readFloat();
                float v1 = buf.readFloat();
                float u2 = buf.readFloat();
                float v2 = buf.readFloat();
                if (!finite(u1, v1, u2, v2)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.uv_invalid", "UV values must be finite"));
                    return;
                }
                screen.u1 = u1;
                screen.v1 = v1;
                screen.u2 = u2;
                screen.v2 = v2;
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.setUv(screen, screen.u1, screen.v1, screen.u2, screen.v2));
                }
                requestOk(player, requestId);
            }
            case OPEN_MENU -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.OPEN_MENU, VideoPermissionContext.screen(screen))) return;
                requestOk(player, requestId);
            }
            case SET_SCREEN_METADATA -> {
                int requestId = buf.readInt();
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                String key;
                boolean remove;
                try {
                    key = ByteBufUtils.readString(buf, 64);
                    remove = buf.readBoolean();
                } catch (IllegalStateException | IndexOutOfBoundsException e) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.meta.key_read_invalid", "Meta key is invalid: %s", errorMessage(e)));
                    return;
                }
                MetaValue value = null;
                if (!remove) {
                    try {
                        value = VideoPackets.readMetaValue(buf);
                    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
                        requestError(player, requestId, VpTranslations.from(e, "error.videoplayer.meta.data_invalid", "Meta data is invalid: %s", errorMessage(e)));
                        return;
                    }
                }
                VideoArea area = requireArea(player, requestId, areaName);
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, screenName);
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_METADATA, VideoPermissionContext.screen(screen))) return;
                if (!canModifyMetadata(player, key)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.meta.custom_admin_only", "Only administrators can set custom Meta"));
                    return;
                }
                if (remove) {
                    try {
                        screen.metadata.remove(key);
                    } catch (IllegalArgumentException e) {
                        requestError(player, requestId, VpTranslations.from(e));
                        return;
                    }
                    if (area.hasPlayer()) {
                        sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.removeMetadata(screen, key));
                    }
                    requestOk(player, requestId);
                    return;
                }
                if (!validMetadata(player, screen, key, value)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.meta.data_invalid", "Meta data is invalid"));
                    return;
                }
                screen.metadata.set(key, value);
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.setMetadata(screen, key, value));
                }
                requestOk(player, requestId);
            }
            case SET_SCALE -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_SCALE, VideoPermissionContext.screen(screen))) return;
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                        || scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.scale_invalid", "Invalid scale value: %s %s", scaleX, scaleY));
                    return;
                }
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.setScale(screen, fill, scaleX, scaleY));
                }
                requestOk(player, requestId);
            }
            case AUTO_SYNC -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.AUTO_SYNC, VideoPermissionContext.screen(screen))) return;
                long generation = buf.readLong();
                if (generation != screen.currentPlaybackGeneration()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.not_seekable", "The current video cannot be synchronized"));
                    return;
                }
                long clientTime = buf.readLong();
                IVideoListener listener = screen.getListener();
                if (listener == null) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.listener_unavailable", "Playback listener is unavailable"));
                    return;
                }
                long progress = listener.getProgress();
                if (progress <= 0) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.progress_unavailable", "Playback progress is unavailable"));
                    return;
                }
                long serverDelay = System.currentTimeMillis() - receivedAt;
                sendTo(player, VideoPackets.autoSync(screen, clientTime, progress, serverDelay));
                requestOk(player, requestId);
            }
            case UPDATE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = requireArea(player, requestId, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = requireScreen(player, requestId, area, VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.UPDATE_SCREEN, VideoPermissionContext.screen(screen))) return;
                short vertexCount = buf.readUnsignedByte();
                ArrayList<Vector3f> vertices = new ArrayList<>(vertexCount);
                for (int i = 0; i < vertexCount; i++) {
                    vertices.add(ByteBufUtils.readVec3(buf));
                }
                String source = VideoPackets.readName(buf);
                VideoScreen displayConfig = new VideoScreen(area, screen.name, vertices, source);
                VideoScreen.readDisplayConfig(buf, displayConfig);
                if (!validScreenUpdate(player, area, screen, vertices, source, displayConfig)) {
                    requestError(player, requestId, VpTranslation.of("error.videoplayer.screen_invalid", "Video screen configuration is invalid"));
                    return;
                }
                screen.setVertices(vertices);
                screen.source = source == null ? "" : source;
                screen.copyDisplayConfigFrom(displayConfig);
                if (area.hasPlayer()) {
                    sendToPlayers(players(player.getEntityWorld().getServer().getPlayerManager(), area.playerSnapshot()), VideoPackets.updateScreen(screen, screen.vertices, screen.source));
                }
                message(player, VpTranslation.of("message.videoplayer.screen_updated", "Updated screen %s", screen.name));
                requestOk(player, requestId);
            }
            default -> player.networkHandler.disconnect(Text.of("Unknown packet type: " + type));
        }
        if (buf.readableBytes() > 0) {
            player.networkHandler.disconnect(Text.of("Illegal packet! Remaining: " + buf.readableBytes()));
        }
    }

    private static boolean requirePermission(ServerPlayerEntity player, int requestId, VideoPermissionAction action, VideoPermissionContext context) {
        VideoPermissionPlayer permissionPlayer = VideoPermissions.player(player);
        if (VideoPermissions.allowed(permissionPlayer, action, context)) return true;
        sendPermissionCache(player, context);
        sendTo(player, VideoPackets.requestResult(requestId, RequestResultStatus.DENIED,
                VpTranslation.of("error.videoplayer.permission_denied", "Permission denied")));
        return false;
    }

    private static void requestOk(ServerPlayerEntity player, int requestId) {
        sendTo(player, VideoPackets.requestResult(requestId, RequestResultStatus.OK, VpTranslation.EMPTY));
    }

    private static void requestError(ServerPlayerEntity player, int requestId, VpTranslation message) {
        sendTo(player, VideoPackets.requestResult(requestId, RequestResultStatus.ERROR, message));
    }

    public static void sendGlobalPermissions(ServerPlayerEntity player) {
        if (player == null) return;
        String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
        sendPermissionCache(player, VideoPermissionContext.global(dim));
    }

    public static void sendAreaPermissions(ServerPlayerEntity player, VideoArea area) {
        if (player == null || area == null) return;
        sendPermissionCache(player, VideoPermissionContext.area(area));
        for (VideoScreen screen : area.screens) {
            sendPermissionCache(player, VideoPermissionContext.screen(screen));
        }
    }

    public static void refreshPermissions(ServerPlayerEntity player) {
        if (player == null) return;
        sendGlobalPermissions(player);
        String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
        if (map == null) return;
        for (VideoArea area : map.values()) {
            if (area.containsPlayer(player.getUuid())) {
                sendAreaPermissions(player, area);
            }
        }
    }

    public static void refreshPermissions(ServerPlayerEntity player, VideoArea area) {
        sendAreaPermissions(player, area);
    }

    public static void refreshPermissions(VideoArea area) {
        if (area == null || !area.hasPlayer() || DataHolder.server == null) return;
        PlayerManager playerManager = DataHolder.server.getPlayerManager();
        for (java.util.UUID uuid : area.playerSnapshot()) {
            sendAreaPermissions(playerManager.getPlayer(uuid), area);
        }
    }

    private static void sendPermissionCache(ServerPlayerEntity player, VideoPermissionContext context) {
        if (player == null) return;
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        long mask = VideoPermissions.mask(VideoPermissions.player(player), safeContext);
        sendTo(player, VideoPackets.permissions(safeContext.areaName(), safeContext.screenName(), mask));
    }

    private static VideoArea getArea(ServerPlayerEntity player, String name) {
        String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
        DataHolder.loadWorld(player.getEntityWorld().getServer(), player.getEntityWorld());
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
        VideoArea area = map == null ? null : map.get(name);
        return area != null && area.containsPlayer(player.getUuid()) ? area : null;
    }

    private static VideoArea requireArea(ServerPlayerEntity player, int requestId, String name) {
        VideoArea area = getArea(player, name);
        if (area == null) {
            requestError(player, requestId, VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
        }
        return area;
    }

    private static VideoScreen requireScreen(ServerPlayerEntity player, int requestId, VideoArea area, String name) {
        VideoScreen screen = area == null ? null : area.getScreen(name);
        if (screen == null) {
            requestError(player, requestId, VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
        }
        return screen;
    }

    private static boolean validName(ServerPlayerEntity player, String name, String type) {
        if (name == null || name.isBlank()) {
            reject(player, VpTranslation.of("error.videoplayer.name_empty", "%s name must not be empty", type));
            return false;
        }
        if (!VideoScreen.validName(name)) {
            reject(player, VpTranslation.of("error.videoplayer.name_too_long", "%s name must not exceed %s characters", type, VideoScreen.MAX_NAME_LENGTH));
            return false;
        }
        return true;
    }

    private static boolean validAreaBounds(ServerPlayerEntity player, Vector3f p1, Vector3f p2) {
        if (!validVector(p1) || !validVector(p2)) {
            reject(player, VpTranslation.of("error.videoplayer.area_coordinates_invalid", "Area coordinates are invalid"));
            return false;
        }
        if (Math.abs(p1.x - p2.x) < EPSILON || Math.abs(p1.y - p2.y) < EPSILON || Math.abs(p1.z - p2.z) < EPSILON) {
            reject(player, VpTranslation.of("error.videoplayer.area_volume_required", "Area must have a valid volume"));
            return false;
        }
        if (Math.abs(p1.x - p2.x) > VideoArea.MAX_AXIS_LENGTH
                || Math.abs(p1.y - p2.y) > VideoArea.MAX_AXIS_LENGTH
                || Math.abs(p1.z - p2.z) > VideoArea.MAX_AXIS_LENGTH) {
            reject(player, VpTranslation.of("error.videoplayer.area_too_large", "Area side length must not exceed %s blocks", VideoArea.MAX_AXIS_LENGTH));
            return false;
        }
        return true;
    }

    private static boolean validScreen(ServerPlayerEntity player, VideoArea area, VideoScreen screen) {
        if (area.screens.size() >= VideoArea.MAX_SCREENS) {
            reject(player, VpTranslation.of("error.videoplayer.screen_limit", "Video area can contain at most %s screens", VideoArea.MAX_SCREENS));
            return false;
        }
        if (!validName(player, screen.name, "Screen")) return false;
        if (area.getScreen(screen.name) != null) {
            reject(player, VpTranslation.of("error.videoplayer.screen_exists", "Video area %s already contains a screen named %s", area.name, screen.name));
            return false;
        }
        if (screen.source == null) screen.source = "";
        if (!validScreenSource(player, area, screen.name, screen.source)) return false;
        screen.ensureValidState();
        if (!screen.hasValidDisplayConfig()) {
            reject(player, VpTranslation.of("error.videoplayer.screen_display_invalid", "Screen display configuration is invalid"));
            return false;
        }
        return validScreenShape(player, area, screen, screen.vertices);
    }

    private static boolean validScreenUpdate(ServerPlayerEntity player, VideoArea area, VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        if (!validScreenSource(player, area, screen.name, source == null ? "" : source)) return false;
        displayConfig.ensureValidState();
        if (!displayConfig.hasValidDisplayConfig()) {
            reject(player, VpTranslation.of("error.videoplayer.screen_display_invalid", "Screen display configuration is invalid"));
            return false;
        }
        return validScreenShape(player, area, displayConfig, vertices);
    }

    private static boolean validMetadata(ServerPlayerEntity player, VideoScreen screen, String key, MetaValue value) {
        try {
            ScreenMetadata.validateKey(key);
            value.validateValue();
            validateBuiltInMetadata(screen, key, value);
            if (!screen.metadata.entries().containsKey(key) && screen.metadata.size() >= ScreenMetadata.MAX_ENTRIES) {
                throw invalid("error.videoplayer.meta.too_many", "Meta entry count must not exceed %s", ScreenMetadata.MAX_ENTRIES);
            }
            return true;
        } catch (IllegalArgumentException e) {
            reject(player, VpTranslations.from(e));
            return false;
        }
    }

    private static boolean canModifyMetadata(ServerPlayerEntity player, String key) {
        try {
            ScreenMetadata.validateKey(key);
        } catch (IllegalArgumentException e) {
            reject(player, VpTranslations.from(e));
            return false;
        }
        if (isUserMetadataOption(key) || ScreenMetadata.KEY_MAPPING_UVS.equals(key) || isAdmin(player)) {
            return true;
        }
        reject(player, VpTranslation.of("error.videoplayer.meta.custom_admin_only", "Only administrators can set custom Meta"));
        return false;
    }

    private static boolean isUserMetadataOption(String key) {
        return switch (key) {
            case "mute", "interactable", "autoSync", ScreenMetadata.KEY_DANMAKU_ENABLED,
                    ScreenMetadata.KEY_DEFAULT_VOLUME, ScreenMetadata.KEY_BILIBILI_QUALITY,
                    ScreenMetadata.KEY_YOUTUBE_QUALITY -> true;
            default -> false;
        };
    }

    private static boolean isAdmin(ServerPlayerEntity player) {
        return player.getCommandSource().getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS);
    }

    private static void validateBuiltInMetadata(VideoScreen screen, String key, MetaValue value) {
        switch (key) {
            case "mute", "interactable", "autoSync", "debug", ScreenMetadata.KEY_DANMAKU_ENABLED -> requireType(key, value, MetaType.BOOL);
            case ScreenMetadata.KEY_DEFAULT_VOLUME -> {
                requireType(key, value, MetaType.INT);
                int volume = value.intValue(-1);
                if (volume < 0 || volume > 100) {
                    throw invalid("error.videoplayer.meta.default_volume_range", "defaultVolume must be between 0 and 100");
                }
            }
            case ScreenMetadata.KEY_BILIBILI_QUALITY -> {
                requireType(key, value, MetaType.INT);
                int quality = value.intValue(Integer.MIN_VALUE);
                if (quality != BiliQuality.UNLIMITED && !BiliQuality.isOption(quality)) {
                    throw invalid("error.videoplayer.meta.bilibili_quality_invalid", "bilibiliQuality is invalid: %s", quality);
                }
            }
            case ScreenMetadata.KEY_YOUTUBE_QUALITY -> {
                requireType(key, value, MetaType.INT);
                int quality = value.intValue(Integer.MIN_VALUE);
                if (quality != YouTubeQuality.AUTO && !YouTubeQuality.isOption(quality)) {
                    throw invalid("error.videoplayer.meta.youtube_quality_invalid", "youtubeQuality is invalid: %s", quality);
                }
            }
            case ScreenMetadata.KEY_MAPPING_UVS -> {
                requireType(key, value, MetaType.FLOAT_ARRAY);
                int length = value.floatArray == null ? 0 : value.floatArray.length;
                int expected = screen.vertices.size() * 2;
                if (length != expected) throw invalid("error.videoplayer.meta.mapping_uv_length", "mapping.uvs length must be vertex count * 2: %s", expected);
            }
        }
    }

    private static void requireType(String key, MetaValue value, MetaType type) {
        if (value.type != type) {
            throw invalid("error.videoplayer.meta.type_required_key", "%s must be %s", key, type.label());
        }
    }

    private static boolean validScreenSource(ServerPlayerEntity player, VideoArea area, String screenName, String source) {
        if (source == null || source.isEmpty()) return true;
        if (source.equals(screenName)) {
            reject(player, VpTranslation.of("error.videoplayer.source_screen_self", "Source Screen cannot point to itself"));
            return false;
        }
        if (area.getScreen(source) == null) {
            reject(player, VpTranslation.of("error.videoplayer.source_screen_not_found", "Source Screen not found: %s", source));
            return false;
        }
        if (VideoSourceGraph.wouldCreateCycle(area, screenName, source)) {
            reject(player, VpTranslation.of("error.videoplayer.source_screen_cycle", "Source Screen references would create a cycle"));
            return false;
        }
        return true;
    }

    private static boolean validScreenVertices(ServerPlayerEntity player, VideoArea area, List<Vector3f> vertices) {
        if (vertices == null || vertices.size() < ScreenGeometry.MIN_VERTICES || vertices.size() > ScreenGeometry.MAX_VERTICES) {
            reject(player, VpTranslation.of("error.videoplayer.screen_vertex_count", "Screen vertex count must be between %s and %s", ScreenGeometry.MIN_VERTICES, ScreenGeometry.MAX_VERTICES));
            return false;
        }
        for (Vector3f point : vertices) {
            if (!validVector(point)) {
                reject(player, VpTranslation.of("error.videoplayer.screen_coordinates_invalid", "Screen coordinates are invalid"));
                return false;
            }
            if (!inside(area, point)) {
                reject(player, VpTranslation.of("error.videoplayer.screen_vertices_outside_area", "Screen vertices must be inside the Area"));
                return false;
            }
        }
        try {
            ScreenGeometry.create(vertices);
        } catch (IllegalArgumentException e) {
            reject(player, VpTranslation.of("error.videoplayer.screen_shape_invalid", "Screen shape is invalid: %s", errorMessage(e)));
            return false;
        }
        return true;
    }

    private static boolean validScreenShape(ServerPlayerEntity player, VideoArea area, VideoScreen screen, List<Vector3f> vertices) {
        if (screen.surface == ScreenSurface.SPHERE_360) {
            return validSphere(player, area, screen);
        }
        return validScreenVertices(player, area, vertices);
    }

    private static boolean validSphere(ServerPlayerEntity player, VideoArea area, VideoScreen screen) {
        if (!screen.spherePreset) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_preset_required", "Define 360 parameters first"));
            return false;
        }
        if (!validVector(screen.sphereCenter)) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_center_invalid", "360 sphere center coordinates are invalid"));
            return false;
        }
        if (!inside(area, screen.sphereCenter)) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_center_outside_area", "360 sphere center must be inside the Area"));
            return false;
        }
        if (!Float.isFinite(screen.sphereRadius) || screen.sphereRadius <= EPSILON || screen.sphereRadius > 1024) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_radius_range", "360 radius must be between 0 and 1024"));
            return false;
        }
        if (screen.sphereLat < VideoScreen.MIN_SPHERE_SEGMENTS || screen.sphereLat > VideoScreen.MAX_SPHERE_SEGMENTS
                || screen.sphereLon < VideoScreen.MIN_SPHERE_SEGMENTS || screen.sphereLon > VideoScreen.MAX_SPHERE_SEGMENTS) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_segments_range", "360 segment count is out of range"));
            return false;
        }
        if (!Float.isFinite(screen.sphereRotX) || !Float.isFinite(screen.sphereRotY) || !Float.isFinite(screen.sphereRotZ)) {
            reject(player, VpTranslation.of("error.videoplayer.sphere_rotation_invalid", "360 rotation parameters are invalid"));
            return false;
        }
        return true;
    }

    private static boolean validVector(Vector3f vector) {
        return vector != null && Float.isFinite(vector.x) && Float.isFinite(vector.y) && Float.isFinite(vector.z);
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static boolean inside(VideoArea area, Vector3f point) {
        return point.x >= area.min.x - EPSILON && point.y >= area.min.y - EPSILON && point.z >= area.min.z - EPSILON
                && point.x <= area.max.x + EPSILON && point.y <= area.max.y + EPSILON && point.z <= area.max.z + EPSILON;
    }

    private static TranslatableIllegalArgumentException invalid(String key, String fallback, Object... args) {
        return new TranslatableIllegalArgumentException(VpTranslation.of(key, fallback, args));
    }

    private static String errorMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "" : error.getMessage();
    }

    private static void reject(ServerPlayerEntity player, VpTranslation message) {
        player.sendMessage(MinecraftTexts.text(message).formatted(Formatting.RED));
    }

    private static void message(ServerPlayerEntity player, VpTranslation message) {
        player.sendMessage(MinecraftTexts.text(message).formatted(Formatting.GREEN));
    }

    public static void sendTo(ServerPlayerEntity player, byte[] bytes) {
        if (player == null || bytes == null) return;
        if (bytes.length > VideoPackets.MAX_PAYLOAD_BYTES) {
            LOGGER.warn("Dropped oversized VideoPlayer payload: {} bytes", bytes.length);
            return;
        }
        ServerPlayNetworking.send(player, new VideoPayload(bytes));
    }

    private static List<ServerPlayerEntity> players(PlayerManager pm, List<java.util.UUID> uuids) {
        ArrayList<ServerPlayerEntity> players = new ArrayList<>(uuids.size());
        for (var uuid : uuids) {
            ServerPlayerEntity target = pm.getPlayer(uuid);
            if (target != null) {
                players.add(target);
            }
        }
        return players;
    }

    private static void sendToPlayers(List<ServerPlayerEntity> players, byte[] bytes) {
        if (bytes == null) return;
        for (ServerPlayerEntity target : players) {
            sendTo(target, bytes);
        }
    }
}
