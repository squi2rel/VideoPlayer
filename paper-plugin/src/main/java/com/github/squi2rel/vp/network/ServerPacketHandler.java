package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.ClientVersionTracker;
import com.github.squi2rel.vp.PaperNativeRuntime;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.VideoUrlNormalizer;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.permission.VideoPermissionContext;
import com.github.squi2rel.vp.permission.VideoPermissionPlayer;
import com.github.squi2rel.vp.permission.VideoPermissions;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.MetaType;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.NativeDependencyDiagnostics;
import com.github.squi2rel.vp.video.OrderedPlayAdmissions;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoListeners;
import com.github.squi2rel.vp.video.VideoScreen;
import com.github.squi2rel.vp.video.VideoSourceGraph;
import io.netty.buffer.ByteBuf;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class ServerPacketHandler {
    private static final float EPSILON = 0.02f;

    public static void handle(Player player, ByteBuf buf) {
        handle(player, buf, System.currentTimeMillis());
    }

    public static void handle(Player player, ByteBuf buf, long receivedAt) {
        synchronized (DataHolder.LOCK) {
            handleLocked(player, buf, receivedAt);
        }
    }

    private static void handleLocked(Player player, ByteBuf buf, long receivedAt) {
        VideoPacketType type = VideoPackets.readType(buf);
        if (type == null) {
            DataHolder.disconnect(player, "Unknown packet type");
            return;
        }
        if (type != VideoPacketType.CONFIG && type != VideoPacketType.HANDSHAKE_ACK
                && !DataHolder.protocolActive(player.getUniqueId())) {
            return;
        }
        if (type != VideoPacketType.CONFIG) LOGGER.debug("server type: {}", type);
        switch (type) {
            case CONFIG -> {
                String remoteToken = ByteBufUtils.readString(buf, 16);
                ClientVersionTracker.clientDetected(player, remoteToken);
                if (!VideoProtocol.compatible(VideoPlayerMain.version, remoteToken)) {
                    if (DataHolder.rejectHandshake(player.getUniqueId())) {
                        sendTo(player, VideoPackets.protocolReject(VideoPlayerMain.version));
                        reject(player, VpTranslation.of(
                                "error.videoplayer.protocol_mismatch",
                                "VideoPlayer client version %s is not compatible with server %s",
                                VideoProtocol.displayVersion(remoteToken), VideoPlayerMain.version
                        ));
                    }
                    return;
                }
                boolean protocolChanged = DataHolder.recordHandshakeToken(player.getUniqueId(), remoteToken);
                String responseToken = DataHolder.handshakeToken(player.getUniqueId());
                VideoHandshakeState previous = DataHolder.handshakeState(player.getUniqueId());
                if (previous == VideoHandshakeState.NEEDS_RESET) {
                    DataHolder.acceptHandshake(player.getUniqueId());
                    long nonce = DataHolder.issueHandshakeNonce(player.getUniqueId());
                    sendTo(player, VideoPackets.resetClient(responseToken, DataHolder.config, nonce));
                } else if (previous == VideoHandshakeState.RESET_SENT) {
                    long nonce = DataHolder.handshakeNonce(player.getUniqueId());
                    if (nonce == 0L) nonce = DataHolder.issueHandshakeNonce(player.getUniqueId());
                    sendTo(player, VideoPackets.resetClient(responseToken, DataHolder.config, nonce));
                } else if (previous == VideoHandshakeState.ACTIVE) {
                    byte[] config = VideoPackets.config(responseToken, DataHolder.config);
                    if (protocolChanged) {
                        DataHolder.sendToCurrentThread(player, config);
                    } else {
                        sendTo(player, config);
                    }
                    sendGlobalPermissions(player);
                    if (protocolChanged) DataHolder.refreshPlayerProtocol(player);
                    Throwable backendError = PaperNativeRuntime.currentError();
                    if (backendError != null) {
                        message(player, nativeBackendUnavailableMessage(backendError));
                    }
                }
            }
            case HANDSHAKE_ACK -> {
                long nonce = buf.readLong();
                if (DataHolder.acceptHandshakeAck(player.getUniqueId(), nonce)) {
                    DataHolder.sendToCurrentThread(player, VideoPackets.config(
                            DataHolder.handshakeToken(player.getUniqueId()), DataHolder.config
                    ));
                    sendGlobalPermissions(player);
                    Throwable backendError = PaperNativeRuntime.currentError();
                    if (backendError != null) {
                        message(player, nativeBackendUnavailableMessage(backendError));
                    }
                }
            }
            case CLIENT_PLAYBACK_RESOLVED -> {
                ClientPlaybackResolutionRequest request = readClientPlaybackResolutionRequest(buf);
                if (request.resolution() != null) {
                    VideoArea area = getArea(player, request.areaName());
                    VideoScreen screen = area == null ? null : area.getScreen(request.screenName());
                    if (screen != null) {
                        screen.acceptClientPlaybackResolution(player.getUniqueId(), request.generation(),
                                request.reporterToken(), request.resolution(), request.durationMs());
                    }
                }
            }
            case REQUEST -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                String url = VideoUrlNormalizer.normalizeSubmittedUrl(ByteBufUtils.readString(buf, VideoScreen.MAX_PLAY_URL_LENGTH));
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.PLAY, VideoPermissionContext.screen(screen))) return;
                if (!VideoScreen.validPlayUrl(url)) {
                    reply.error(VpTranslation.of("error.videoplayer.play_url_invalid_length", "Video URL is invalid"));
                    return;
                }
                OrderedPlayAdmissions.Reservation reservation = screen.reservePlayAdmission(result -> {
                    if (result.success()) {
                        reply.ok();
                    } else {
                        PaperNativeRuntime.State state = nativeRuntimeFailure(result.error());
                        if (state != null) {
                            reply.error(state == PaperNativeRuntime.State.UNAVAILABLE || state == PaperNativeRuntime.State.STOPPED
                                 ? nativeBackendUnavailableMessage(PaperNativeRuntime.currentError())
                                 : VpTranslation.of("error.videoplayer.native_backend_initializing", "VideoPlayer server playback backend is still initializing; try again later"));
                        } else {
                            reply.error(VpTranslation.of("message.videoplayer.source_unresolved", "Unable to resolve video source"));
                        }
                    }
                });
                if (reservation == null) {
                    reply.error(VpTranslation.of("error.videoplayer.queue_full", "Video queue is full"));
                    return;
                }
                try {
                    int bilibiliLimit = BiliQuality.normalizeScreenLimit(
                            screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED)
                    );
                    int youtubeLimit = YouTubeQuality.normalizeScreenLimit(
                            screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO)
                    );
                    CompletableFuture<VideoInfo> future = VideoProviders.from(
                            url, new PlayerProviderSource(player, bilibiliLimit, youtubeLimit)
                    );
                    screen.attachPlayAdmission(reservation, guardNativeRuntime(future));
                    reply.defer();
                } catch (Throwable error) {
                    screen.failPlayAdmission(reservation, error);
                }
            });
            case SYNC -> handleRequest(player, buf, reply -> {
                ScreenGenerationRequest request = readScreenGenerationRequest(buf);
                reply.decoded();
                VideoArea area = getArea(player, request.areaName());
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(request.screenName());
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (request.generation() != screen.currentPlaybackGeneration()) {
                    reply.error(VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                if (screen.currentPlayback() == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_playing", "Screen is not playing"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SYNC, VideoPermissionContext.screen(screen))) return;
                sendTo(player, VideoPackets.sync(screen, screen.getProgress()));
                reply.ok();
            });
            case SEEK -> handleRequest(player, buf, reply -> {
                ScreenGenerationValueRequest request = readScreenGenerationValueRequest(buf);
                reply.decoded();
                VideoArea area = getArea(player, request.areaName());
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(request.screenName());
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SEEK, VideoPermissionContext.screen(screen))) return;
                if (request.generation() != screen.currentPlaybackGeneration()) {
                    reply.error(VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_seekable", "Screen is not playing seekable media"));
                    return;
                }
                long progress = Math.max(0, request.value());
                IVideoListener listener = screen.getListener();
                if (listener == null) {
                    reply.error(VpTranslation.of("error.videoplayer.listener_unavailable", "Playback listener is unavailable"));
                    return;
                }
                screen.setProgress(progress);
                long syncedProgress = listener.getProgress();
                if (syncedProgress < 0) syncedProgress = progress;
                if (area.hasPlayer()) {
                    sendToPlayers(area.playerSnapshot(), VideoPackets.sync(screen, syncedProgress));
                }
                reply.ok();
            });
            case CREATE_AREA -> handleRequest(player, buf, reply -> {
                Vector3f p1 = ByteBufUtils.readVec3(buf);
                Vector3f p2 = ByteBufUtils.readVec3(buf);
                String name = VideoPackets.readName(buf);
                reply.decoded();
                DataHolder.ensureWorldLoaded(player.getWorld());
                String dim = DataHolder.worldKey(player.getWorld());
                if (!DataHolder.worldConfigValid(dim)) {
                    reply.error(VpTranslation.of("error.videoplayer.world_config_invalid", "The VideoPlayer configuration for this world is invalid and must be repaired before it can be modified"));
                    return;
                }
                if (!DataHolder.isWorldTrackingReady(dim)) {
                    reply.error(VpTranslation.of("error.videoplayer.world_not_ready", "VideoPlayer has not finished loading this world"));
                    return;
                }
                if (!validAreaBounds(reply, p1, p2)) return;
                if (!validName(reply, name, "Area")) return;
                VideoPermissionContext context = VideoPermissionContext.bounds(dim, name, p1, p2);
                if (!requireCandidatePermission(player, reply, VideoPermissionAction.CREATE_AREA, context)) return;
                HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
                if (map == null) {
                    reply.error(VpTranslation.of("error.videoplayer.world_not_ready", "VideoPlayer has not finished loading this world"));
                    return;
                }
                if (map.containsKey(name)) {
                    reply.error(VpTranslation.of("error.videoplayer.area_exists", "A video area named %s already exists", name));
                    return;
                }
                if (map.size() >= VideoArea.MAX_AREAS_PER_WORLD) {
                    reply.error(VpTranslation.of("error.videoplayer.area_limit", "World can contain at most %s video areas", VideoArea.MAX_AREAS_PER_WORLD));
                    return;
                }
                VideoArea area = VideoArea.from(p1, p2, name, dim);
                area.initServer();
                map.put(area.name, area);
                DataHolder.invalidateWorldTracking(dim);
                DataHolder.queueWorldSave(dim);
                VpTranslation confirmation = VpTranslation.of("message.videoplayer.area_created", "Created video area %s in world %s", area.name, dim);
                message(player, confirmation);
                sendAreaPermissions(player, area);
                reply.ok(confirmation);
            });
            case REMOVE_AREA -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.REMOVE_AREA, VideoPermissionContext.area(area))) return;
                List<UUID> receivers = List.of();
                byte[] data = null;
                HashMap<String, VideoArea> map = DataHolder.areas.get(area.dim);
                VideoArea removed = map == null ? null : map.remove(area.name);
                if (removed == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found", "Video area no longer exists"));
                    return;
                }
                if (removed.hasPlayer()) {
                    data = VideoPackets.removeArea(removed);
                    receivers = removed.playerSnapshot();
                }
                DataHolder.invalidateWorldTracking(area.dim);
                DataHolder.queueWorldSave(area.dim);
                removed.remove();
                sendToPlayers(receivers, data);
                VpTranslation confirmation = VpTranslation.of("message.videoplayer.area_removed", "Removed video area %s from world %s", area.name, area.dim);
                message(player, confirmation);
                reply.ok(confirmation);
            });
            case CREATE_SCREEN -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                VideoScreen screen = VideoScreen.read(buf, null);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                screen.area = area;
                if (!validScreen(reply, area, screen)) return;
                if (!requireCandidatePermission(player, reply, VideoPermissionAction.CREATE_SCREEN, VideoPermissionContext.screen(screen))) return;
                screen.initServer();
                List<UUID> receivers = List.of();
                byte[] data = null;
                area.addScreen(screen);
                if (area.hasPlayer()) {
                    data = VideoPackets.createScreen(List.of(screen));
                    receivers = area.playerSnapshot();
                }
                DataHolder.queueWorldSave(area.dim);
                sendToPlayers(receivers, data);
                VpTranslation confirmation = VpTranslation.of("message.videoplayer.screen_created", "Created screen %s in video area %s", screen.name, area.name);
                message(player, confirmation);
                refreshPermissions(area);
                reply.ok(confirmation);
            });
            case REMOVE_SCREEN -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen existing = area.getScreen(screenName);
                if (existing == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.REMOVE_SCREEN, VideoPermissionContext.screen(existing))) return;
                if (area.isScreenReferenced(screenName)) {
                    reply.error(VpTranslation.of("error.videoplayer.source_screen_in_use", "Screen is still used as a source by another screen"));
                    return;
                }
                List<UUID> receivers = List.of();
                byte[] data = null;
                area.screens.remove(existing);
                VideoScreen screen = existing;
                if (screen != null && area.hasPlayer()) {
                    data = VideoPackets.removeScreen(screen);
                    receivers = area.playerSnapshot();
                }
                DataHolder.queueWorldSave(area.dim);
                screen.remove();
                sendToPlayers(receivers, data);
                if (screen != null) {
                    VpTranslation confirmation = VpTranslation.of("message.videoplayer.screen_removed", "Removed screen %s from video area %s", screen.name, area.name);
                    message(player, confirmation);
                    refreshPermissions(area);
                    reply.ok(confirmation);
                } else {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen no longer exists"));
                }
            });
            case SKIP -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                long generation = buf.readLong();
                boolean force = buf.readBoolean();
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (generation != screen.currentPlaybackGeneration()) {
                    reply.error(VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                if (force) {
                    if (!requirePermission(player, reply, VideoPermissionAction.FORCE_SKIP, VideoPermissionContext.screen(screen))) return;
                    screen.skip();
                    reply.ok();
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.VOTE_SKIP, VideoPermissionContext.screen(screen))) return;
                screen.voteSkip(player.getUniqueId());
                VpTranslation text = VpTranslation.of(
                        "message.videoplayer.skip_vote_broadcast",
                        "Player %s voted to skip the video on %s. %s more players required",
                        player.getName(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                );
                message(player, VpTranslation.of("message.videoplayer.skip_voted", "Voted to skip this video"));
                for (UUID target : area.playerSnapshot()) {
                    DataHolder.message(target, text);
                }
                reply.ok();
            });
            case SKIP_PERCENT -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                float skipPercent = buf.readFloat();
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SET_SKIP_PERCENT, VideoPermissionContext.screen(screen))) return;
                if (!Float.isFinite(skipPercent) || skipPercent < 0f || skipPercent > 1f) {
                    reply.error(VpTranslation.of("error.videoplayer.skip_percent_invalid", "Skip vote percent must be between 0 and 1"));
                    return;
                }
                screen.skipPercent = skipPercent;
                DataHolder.queueWorldSave(area.dim);
                screen.setSkipPercent(skipPercent);
                message(player, VpTranslation.of("message.videoplayer.skip_percent_set", "Set skip vote ratio for screen %s to %s", screen.name, screen.skipPercent));
                reply.ok();
            });
            case IDLE_PLAY -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                boolean mutations = DataHolder.supportsIdlePlayMutations(player.getUniqueId());
                IdlePlayMutation mutation = mutations ? VideoPackets.readIdlePlayMutation(buf) : null;
                VideoPackets.LegacyIdlePlayConfig legacy = mutations ? null : VideoPackets.readLegacyIdlePlayConfig(buf);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SET_IDLE_PLAY, VideoPermissionContext.screen(screen))) return;
                if (mutation != null && mutation.action() == IdlePlayAction.ADD) {
                    mutation = IdlePlayMutation.add(VideoUrlNormalizer.normalizeSubmittedUrl(mutation.url()), mutation.priority());
                    if (!VideoScreen.validIdlePlayUrl(mutation.url())) {
                        reply.error(VpTranslation.of("error.videoplayer.idle_play_url_invalid", "IdlePlay URL is invalid"));
                        return;
                    }
                }
                if (legacy != null) {
                    screen.replaceLegacyIdlePlayConfig(
                            legacy.urls(), legacy.random(), player.getUniqueId(), player.getName()
                    );
                } else if (!screen.applyIdlePlayMutation(mutation, player.getUniqueId(), player.getName())) {
                    reply.error(VpTranslation.of("error.videoplayer.idle_play_mutation_failed", "Unable to update IdlePlay entry"));
                    return;
                }
                DataHolder.queueWorldSave(area.dim);
                screen.idlePlayConfigChanged();
                if (area.hasPlayer()) {
                    screen.syncIdlePlay();
                }
                message(player, VpTranslation.of("message.videoplayer.idle_play_updated", "Updated IdlePlay list for screen %s", screen.name));
                reply.ok();
            });
            case SET_UV -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                float u1 = buf.readFloat();
                float v1 = buf.readFloat();
                float u2 = buf.readFloat();
                float v2 = buf.readFloat();
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SET_UV, VideoPermissionContext.screen(screen))) return;
                if (!finite(u1, v1, u2, v2)) {
                    reply.error(VpTranslation.of("error.videoplayer.uv_invalid", "UV values must be finite"));
                    return;
                }
                screen.u1 = u1;
                screen.v1 = v1;
                screen.u2 = u2;
                screen.v2 = v2;
                DataHolder.queueWorldSave(area.dim);
                if (area.hasPlayer()) {
                    sendToPlayers(area.playerSnapshot(), VideoPackets.setUv(screen, screen.u1, screen.v1, screen.u2, screen.v2));
                }
                reply.ok();
            });
            case OPEN_MENU -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                refreshPermissions(player);
                if (!requirePermission(player, reply, VideoPermissionAction.OPEN_MENU, VideoPermissionContext.screen(screen))) return;
                reply.ok();
            });
            case DIAGNOSTICS_REQUEST -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.OPEN_MENU, VideoPermissionContext.screen(screen))) return;
                DataHolder.sendTo(player, VideoPackets.diagnostics(screen,
                        screen.diagnostics(PaperNativeRuntime.currentState().name())));
                reply.ok();
            });
            case SET_SCREEN_METADATA -> handleRequest(player, buf, reply -> handleMetadata(player, buf, reply));
            case SET_SCALE -> handleRequest(player, buf, reply -> {
                String areaName = VideoPackets.readName(buf);
                String screenName = VideoPackets.readName(buf);
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.SET_SCALE, VideoPermissionContext.screen(screen))) return;
                if (!Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                        || scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                    reply.error(VpTranslation.of("error.videoplayer.scale_invalid", "Invalid scale value: %s %s", scaleX, scaleY));
                    return;
                }
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
                DataHolder.queueWorldSave(area.dim);
                if (area.hasPlayer()) {
                    sendToPlayers(area.playerSnapshot(), VideoPackets.setScale(screen, fill, scaleX, scaleY));
                }
                reply.ok();
            });
            case AUTO_SYNC -> handleRequest(player, buf, reply -> {
                ScreenGenerationValueRequest request = readScreenGenerationValueRequest(buf);
                reply.decoded();
                VideoArea area = getArea(player, request.areaName());
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(request.screenName());
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                if (!requirePermission(player, reply, VideoPermissionAction.AUTO_SYNC, VideoPermissionContext.screen(screen))) return;
                if (request.generation() != screen.currentPlaybackGeneration()) {
                    reply.error(VpTranslation.of("error.videoplayer.playback_stale", "Playback control request is stale"));
                    return;
                }
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_seekable", "Screen is not playing seekable media"));
                    return;
                }
                long clientTime = request.value();
                IVideoListener listener = screen.getListener();
                if (listener == null) {
                    reply.error(VpTranslation.of("error.videoplayer.listener_unavailable", "Playback listener is unavailable"));
                    return;
                }
                long progress = listener.getProgress();
                if (progress <= 0) {
                    reply.error(VpTranslation.of("error.videoplayer.progress_unavailable", "Playback progress is unavailable"));
                    return;
                }
                long serverDelay = System.currentTimeMillis() - receivedAt;
                DataHolder.sendToCurrentThread(player, VideoPackets.autoSync(screen, clientTime, progress, serverDelay));
                reply.ok();
            });
            case UPDATE_SCREEN -> handleRequest(player, buf, reply -> {
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
                reply.decoded();
                VideoArea area = getArea(player, areaName);
                if (area == null) {
                    reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                VideoScreen screen = area.getScreen(screenName);
                if (screen == null) {
                    reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
                    return;
                }
                displayConfig.area = area;
                if (!validScreenUpdate(reply, area, screen, vertices, source, displayConfig)) return;
                if (!requirePermission(player, reply, VideoPermissionAction.UPDATE_SCREEN, VideoPermissionContext.screen(screen))) return;
                if (!requireCandidatePermission(player, reply, VideoPermissionAction.UPDATE_SCREEN, VideoPermissionContext.screen(displayConfig))) return;
                screen.setVertices(vertices);
                screen.source = source == null ? "" : source;
                screen.copyDisplayConfigFrom(displayConfig);
                DataHolder.queueWorldSave(area.dim);
                if (area.hasPlayer()) {
                    sendToPlayers(area.playerSnapshot(), VideoPackets.updateScreen(screen, screen.vertices, screen.source));
                }
                VpTranslation confirmation = VpTranslation.of("message.videoplayer.screen_updated", "Updated screen %s", screen.name);
                message(player, confirmation);
                refreshPermissions(area);
                reply.ok(confirmation);
            });
            default -> DataHolder.disconnect(player, "Unknown packet type: " + type);
        }
        if (buf.readableBytes() > 0) {
            DataHolder.disconnect(player, "Illegal packet! Remaining: " + buf.readableBytes());
        }
    }

    private static void handleMetadata(Player player, ByteBuf buf, RequestReply reply) {
        String areaName = VideoPackets.readName(buf);
        String screenName = VideoPackets.readName(buf);
        String key = ByteBufUtils.readString(buf, 64);
        boolean remove = buf.readBoolean();
        MetaValue value = remove ? null : VideoPackets.readMetaValue(buf);
        reply.decoded();
        VideoArea area = getArea(player, areaName);
        if (area == null) {
            reply.error(VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
            return;
        }
        VideoScreen screen = area.getScreen(screenName);
        if (screen == null) {
            reply.error(VpTranslation.of("error.videoplayer.screen_not_found", "Screen not found"));
            return;
        }
        if (!requirePermission(player, reply, VideoPermissionAction.SET_METADATA, VideoPermissionContext.screen(screen))) return;
        if (!canModifyMetadata(player, reply, key)) return;
        if (remove) {
            try {
                screen.metadata.remove(key);
            } catch (IllegalArgumentException e) {
                reply.error(VpTranslations.from(e));
                return;
            }
            DataHolder.queueWorldSave(area.dim);
            if (area.hasPlayer()) {
                sendToPlayers(area.playerSnapshot(), VideoPackets.removeMetadata(screen, key));
            }
            reply.ok();
            return;
        }
        if (!validMetadata(reply, screen, key, value)) return;
        screen.metadata.set(key, value);
        DataHolder.queueWorldSave(area.dim);
        if (area.hasPlayer()) {
            sendToPlayers(area.playerSnapshot(), VideoPackets.setMetadata(screen, key, value));
        }
        reply.ok();
    }

    private static boolean requirePermission(Player player, RequestReply reply, VideoPermissionAction action, VideoPermissionContext context) {
        return requirePermission(player, reply, action, context, true);
    }

    private static boolean requireCandidatePermission(Player player, RequestReply reply, VideoPermissionAction action, VideoPermissionContext context) {
        return requirePermission(player, reply, action, context, false);
    }

    private static boolean requirePermission(Player player, RequestReply reply, VideoPermissionAction action, VideoPermissionContext context, boolean refreshCache) {
        VideoPermissionPlayer permissionPlayer = VideoPermissions.player(player);
        if (VideoPermissions.allowed(permissionPlayer, action, context)) return true;
        if (refreshCache) sendPermissionCache(player, context);
        reply.denied(VpTranslation.of("error.videoplayer.permission_denied", "Permission denied"));
        return false;
    }

    static ScreenGenerationRequest readScreenGenerationRequest(ByteBuf buf) {
        return new ScreenGenerationRequest(
                VideoPackets.readName(buf),
                VideoPackets.readName(buf),
                buf.readLong()
        );
    }

    static ScreenGenerationValueRequest readScreenGenerationValueRequest(ByteBuf buf) {
        return new ScreenGenerationValueRequest(
                VideoPackets.readName(buf),
                VideoPackets.readName(buf),
                buf.readLong(),
                buf.readLong()
        );
    }

    static ClientPlaybackResolutionRequest readClientPlaybackResolutionRequest(ByteBuf buf) {
        return new ClientPlaybackResolutionRequest(
                VideoPackets.readName(buf),
                VideoPackets.readName(buf),
                buf.readLong(),
                buf.readLong(),
                ClientPlaybackResolution.fromId(buf.readUnsignedByte()),
                buf.readLong()
        );
    }

    private static void handleRequest(Player player, ByteBuf buf, RequestHandler handler) {
        int requestId = buf.readInt();
        RequestReply reply = new RequestReply((status, message) ->
                sendTo(player, VideoPackets.requestResult(requestId, status, message)));
        try {
            handler.handle(reply);
        } catch (RuntimeException error) {
            if (reply.decoding()) {
                reply.abandon();
                throw error;
            }
            reply.error(VpTranslations.from(error, "error.videoplayer.request_failed", "Request failed: %s", errorMessage(error)));
            LOGGER.warn("VideoPlayer request failed for {}", player.getName(), error);
        } catch (Error error) {
            if (reply.decoding()) reply.abandon();
            LOGGER.error("VideoPlayer request encountered an unrecoverable error for {}", player.getName(), error);
            throw error;
        } finally {
            reply.finish();
        }
    }

    private static PaperNativeRuntime.State nativeRuntimeFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NativeRuntimeNotReady notReady) return notReady.state;
            current = current.getCause();
        }
        return null;
    }

    private static VpTranslation nativeBackendUnavailableMessage(Throwable error) {
        String detail = NativeDependencyDiagnostics.describe(error);
        String suffix = detail.startsWith("missing native dependencies:") ? " (" + detail + ")" : "";
        return VpTranslation.of("error.videoplayer.native_backend_unavailable_detail",
                "The server cannot use MPV playback%s. Ask the server administrator to install or repair a native runtime.", suffix);
    }

    static CompletableFuture<VideoInfo> guardNativeRuntime(CompletableFuture<VideoInfo> upstream) {
        if (upstream == null) return null;
        CompletableFuture<VideoInfo> guarded = new CompletableFuture<>();
        upstream.whenComplete((info, error) -> {
            if (guarded.isDone()) return;
            if (error != null) {
                guarded.completeExceptionally(error);
                return;
            }
            try {
                if (info != null && VideoListeners.requiresNativeStreamListener(info) && !PaperNativeRuntime.isReady()) {
                    throw new NativeRuntimeNotReady(PaperNativeRuntime.currentState());
                }
                guarded.complete(info);
            } catch (Throwable failure) {
                guarded.completeExceptionally(failure);
            }
        });
        guarded.whenComplete((info, error) -> {
            if (error != null) upstream.cancel(true);
        });
        return guarded;
    }

    public static void sendGlobalPermissions(Player player) {
        if (player == null) return;
        sendPermissionCache(player, VideoPermissionContext.global(DataHolder.worldKey(player.getWorld())));
    }

    public static void sendAreaPermissions(Player player, VideoArea area) {
        if (player == null || area == null) return;
        ArrayList<VideoPermissionContext> contexts = new ArrayList<>();
        synchronized (DataHolder.LOCK) {
            contexts.add(VideoPermissionContext.area(area));
            for (VideoScreen screen : area.screens) {
                contexts.add(VideoPermissionContext.screen(screen));
            }
        }
        sendPermissionContexts(player, contexts);
    }

    public static void refreshPermissions(Player player) {
        if (player == null) return;
        synchronized (DataHolder.LOCK) {
            sendGlobalPermissions(player);
            String dim = DataHolder.worldKey(player.getWorld());
            HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
            if (map == null) return;
            for (VideoArea area : map.values()) {
                if (area.containsPlayer(player.getUniqueId())) {
                    sendAreaPermissions(player, area);
                }
            }
        }
    }

    public static void refreshPermissions(Player player, VideoArea area) {
        sendAreaPermissions(player, area);
    }

    public static void refreshPermissions(VideoArea area) {
        if (area == null) return;
        List<UUID> players;
        ArrayList<VideoPermissionContext> contexts = new ArrayList<>();
        synchronized (DataHolder.LOCK) {
            if (!area.hasPlayer()) return;
            players = area.playerSnapshot();
            contexts.add(VideoPermissionContext.area(area));
            for (VideoScreen screen : area.screens) {
                contexts.add(VideoPermissionContext.screen(screen));
            }
        }
        List<VideoPermissionContext> snapshot = List.copyOf(contexts);
        for (UUID uuid : players) {
            DataHolder.runForPlayer(uuid, player -> sendPermissionContexts(player, snapshot));
        }
    }

    private static void sendPermissionContexts(Player player, List<VideoPermissionContext> contexts) {
        for (VideoPermissionContext context : contexts) {
            sendPermissionCache(player, context);
        }
    }

    private static void sendPermissionCache(Player player, VideoPermissionContext context) {
        if (player == null) return;
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        long mask = VideoPermissions.mask(VideoPermissions.player(player), safeContext);
        sendTo(player, VideoPackets.permissions(safeContext.areaName(), safeContext.screenName(), mask));
    }

    private static VideoArea getArea(Player player, String name) {
        String dim = DataHolder.worldKey(player.getWorld());
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
        VideoArea area = map == null ? null : map.get(name);
        return area != null && area.containsPlayer(player.getUniqueId()) ? area : null;
    }

    private static boolean validName(RequestReply reply, String name, String type) {
        if (name == null || name.isBlank()) {
            reply.error(VpTranslation.of("error.videoplayer.name_empty", "%s name must not be empty", type));
            return false;
        }
        if (!VideoScreen.validName(name)) {
            reply.error(VpTranslation.of(
                    "error.videoplayer.name_invalid_length",
                    "%s name must not exceed %s Unicode characters or %s UTF-8 bytes",
                    type, VideoScreen.MAX_NAME_LENGTH, VideoScreen.MAX_NAME_BYTES
            ));
            return false;
        }
        return true;
    }

    private static boolean validAreaBounds(RequestReply reply, Vector3f p1, Vector3f p2) {
        if (!validVector(p1) || !validVector(p2)) {
            reply.error(VpTranslation.of("error.videoplayer.area_coordinates_invalid", "Area coordinates are invalid"));
            return false;
        }
        if (Math.abs(p1.x - p2.x) < EPSILON || Math.abs(p1.y - p2.y) < EPSILON || Math.abs(p1.z - p2.z) < EPSILON) {
            reply.error(VpTranslation.of("error.videoplayer.area_volume_required", "Area must have a valid volume"));
            return false;
        }
        if (!VideoArea.validSize(p1, p2)) {
            reply.error(VpTranslation.of("error.videoplayer.area_size_limit", "Area dimensions must not exceed %s blocks", VideoArea.MAX_AXIS_LENGTH));
            return false;
        }
        return true;
    }

    private static boolean validScreen(RequestReply reply, VideoArea area, VideoScreen screen) {
        if (area.screens.size() >= VideoArea.MAX_SCREENS) {
            reply.error(VpTranslation.of("error.videoplayer.screen_limit", "Video area can contain at most %s screens", VideoArea.MAX_SCREENS));
            return false;
        }
        if (!validName(reply, screen.name, "Screen")) return false;
        if (area.getScreen(screen.name) != null) {
            reply.error(VpTranslation.of("error.videoplayer.screen_exists", "Video area %s already contains a screen named %s", area.name, screen.name));
            return false;
        }
        if (screen.source == null) screen.source = "";
        if (!validScreenSource(reply, area, screen.name, screen.source)) return false;
        screen.ensureValidState();
        if (!screen.hasValidDisplayConfig()) {
            reply.error(VpTranslation.of("error.videoplayer.screen_display_invalid", "Screen display configuration is invalid"));
            return false;
        }
        return validScreenShape(reply, area, screen, screen.vertices);
    }

    private static boolean validScreenUpdate(RequestReply reply, VideoArea area, VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        if (!validScreenSource(reply, area, screen.name, source == null ? "" : source)) return false;
        displayConfig.ensureValidState();
        if (!displayConfig.hasValidDisplayConfig()) {
            reply.error(VpTranslation.of("error.videoplayer.screen_display_invalid", "Screen display configuration is invalid"));
            return false;
        }
        return validScreenShape(reply, area, displayConfig, vertices);
    }

    private static boolean validMetadata(RequestReply reply, VideoScreen screen, String key, MetaValue value) {
        try {
            ScreenMetadata.validateKey(key);
            value.validateValue();
            validateBuiltInMetadata(screen, key, value);
            if (!screen.metadata.entries().containsKey(key) && screen.metadata.size() >= ScreenMetadata.MAX_ENTRIES) {
                throw invalid("error.videoplayer.meta.too_many", "Meta entry count must not exceed %s", ScreenMetadata.MAX_ENTRIES);
            }
            return true;
        } catch (IllegalArgumentException e) {
            reply.error(VpTranslations.from(e));
            return false;
        }
    }

    private static boolean canModifyMetadata(Player player, RequestReply reply, String key) {
        try {
            ScreenMetadata.validateKey(key);
        } catch (IllegalArgumentException e) {
            reply.error(VpTranslations.from(e));
            return false;
        }
        if (isUserMetadataOption(key) || ScreenMetadata.KEY_MAPPING_UVS.equals(key) || isAdmin(player)) {
            return true;
        }
        reply.denied(VpTranslation.of("error.videoplayer.meta.custom_admin_only", "Only administrators can set custom Meta"));
        return false;
    }

    private static boolean isUserMetadataOption(String key) {
        return switch (key) {
            case "mute", "interactable", "autoSync", ScreenMetadata.KEY_DANMAKU_ENABLED,
                    ScreenMetadata.KEY_DEFAULT_VOLUME, ScreenMetadata.KEY_BILIBILI_QUALITY,
                    ScreenMetadata.KEY_YOUTUBE_QUALITY, ScreenMetadata.KEY_SHOW_IDLE_IMAGE -> true;
            default -> false;
        };
    }

    private static boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission(VideoPermissions.ADMIN);
    }

    private static void validateBuiltInMetadata(VideoScreen screen, String key, MetaValue value) {
        switch (key) {
            case "mute", "interactable", "autoSync", "debug", ScreenMetadata.KEY_DANMAKU_ENABLED,
                    ScreenMetadata.KEY_SHOW_IDLE_IMAGE -> requireType(key, value, MetaType.BOOL);
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

    private static boolean validScreenSource(RequestReply reply, VideoArea area, String screenName, String source) {
        if (source == null || source.isEmpty()) return true;
        if (source.equals(screenName)) {
            reply.error(VpTranslation.of("error.videoplayer.source_screen_self", "Source Screen cannot point to itself"));
            return false;
        }
        if (area.getScreen(source) == null) {
            reply.error(VpTranslation.of("error.videoplayer.source_screen_not_found", "Source Screen not found: %s", source));
            return false;
        }
        if (VideoSourceGraph.wouldCreateCycle(area, screenName, source)) {
            reply.error(VpTranslation.of("error.videoplayer.source_screen_cycle", "Source Screen references would create a cycle"));
            return false;
        }
        return true;
    }

    private static boolean validScreenVertices(RequestReply reply, VideoArea area, List<Vector3f> vertices) {
        if (vertices == null || vertices.size() < ScreenGeometry.MIN_VERTICES || vertices.size() > ScreenGeometry.MAX_VERTICES) {
            reply.error(VpTranslation.of("error.videoplayer.screen_vertex_count", "Screen vertex count must be between %s and %s", ScreenGeometry.MIN_VERTICES, ScreenGeometry.MAX_VERTICES));
            return false;
        }
        for (Vector3f point : vertices) {
            if (!validVector(point)) {
                reply.error(VpTranslation.of("error.videoplayer.screen_coordinates_invalid", "Screen coordinates are invalid"));
                return false;
            }
            if (!inside(area, point)) {
                reply.error(VpTranslation.of("error.videoplayer.screen_vertices_outside_area", "Screen vertices must be inside the Area"));
                return false;
            }
        }
        try {
            ScreenGeometry.create(vertices);
        } catch (IllegalArgumentException e) {
            reply.error(VpTranslation.of("error.videoplayer.screen_shape_invalid", "Screen shape is invalid: %s", errorMessage(e)));
            return false;
        }
        return true;
    }

    private static boolean validScreenShape(RequestReply reply, VideoArea area, VideoScreen screen, List<Vector3f> vertices) {
        if (screen.surface == ScreenSurface.SPHERE_360) {
            return validSphere(reply, area, screen);
        }
        return validScreenVertices(reply, area, vertices);
    }

    private static boolean validSphere(RequestReply reply, VideoArea area, VideoScreen screen) {
        if (!screen.spherePreset) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_preset_required", "Define 360 parameters first"));
            return false;
        }
        if (!validVector(screen.sphereCenter)) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_center_invalid", "360 sphere center coordinates are invalid"));
            return false;
        }
        if (!inside(area, screen.sphereCenter)) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_center_outside_area", "360 sphere center must be inside the Area"));
            return false;
        }
        if (!Float.isFinite(screen.sphereRadius) || screen.sphereRadius <= EPSILON || screen.sphereRadius > 1024) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_radius_range", "360 radius must be between 0 and 1024"));
            return false;
        }
        if (screen.sphereLat < 4 || screen.sphereLat > 128 || screen.sphereLon < 4 || screen.sphereLon > 256) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_segments_range", "360 segment count is out of range"));
            return false;
        }
        if (!Float.isFinite(screen.sphereRotX) || !Float.isFinite(screen.sphereRotY) || !Float.isFinite(screen.sphereRotZ)) {
            reply.error(VpTranslation.of("error.videoplayer.sphere_rotation_invalid", "360 rotation parameters are invalid"));
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

    private static void reject(Player player, VpTranslation message) {
        message(player, message);
    }

    private static void message(Player player, VpTranslation message) {
        DataHolder.message(player, message);
    }

    public static void sendTo(Player player, byte[] bytes) {
        DataHolder.sendTo(player, bytes);
    }

    private static void sendToPlayers(List<UUID> players, byte[] bytes) {
        if (bytes == null) return;
        for (UUID uuid : players) {
            DataHolder.sendTo(uuid, bytes);
        }
    }

    @FunctionalInterface
    interface RequestHandler {
        void handle(RequestReply reply);
    }

    record ScreenGenerationRequest(String areaName, String screenName, long generation) {
    }

    record ScreenGenerationValueRequest(String areaName, String screenName, long generation, long value) {
    }

    record ClientPlaybackResolutionRequest(String areaName, String screenName, long generation, long reporterToken,
                                           ClientPlaybackResolution resolution, long durationMs) {
    }

    static final class RequestReply {
        private final BiConsumer<RequestResultStatus, VpTranslation> sender;
        private boolean completed;
        private boolean deferred;
        private boolean decoding = true;
        private boolean abandoned;

        RequestReply(BiConsumer<RequestResultStatus, VpTranslation> sender) {
            this.sender = sender;
        }

        void ok() {
            complete(RequestResultStatus.OK, VpTranslation.EMPTY);
        }

        void ok(VpTranslation message) {
            complete(RequestResultStatus.OK, message);
        }

        void denied(VpTranslation message) {
            complete(RequestResultStatus.DENIED, message);
        }

        void error(VpTranslation message) {
            complete(RequestResultStatus.ERROR, message);
        }

        void defer() {
            if (!completed) deferred = true;
        }

        void decoded() {
            decoding = false;
        }

        boolean decoding() {
            return decoding;
        }

        void abandon() {
            abandoned = true;
        }

        void finish() {
            if (!completed && !deferred && !abandoned) {
                error(VpTranslation.of("error.videoplayer.request_incomplete", "Request did not complete"));
            }
        }

        boolean completed() {
            return completed;
        }

        private void complete(RequestResultStatus status, VpTranslation message) {
            if (completed) return;
            completed = true;
            sender.accept(status, message == null ? VpTranslation.EMPTY : message);
        }
    }

    private static final class NativeRuntimeNotReady extends RuntimeException {
        private final PaperNativeRuntime.State state;

        private NativeRuntimeNotReady(PaperNativeRuntime.State state) {
            this.state = state;
        }
    }
}
