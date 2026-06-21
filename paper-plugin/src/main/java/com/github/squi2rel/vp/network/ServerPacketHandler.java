package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.i18n.VpTranslations;
import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.permission.VideoPermissionAction;
import com.github.squi2rel.vp.permission.VideoPermissionContext;
import com.github.squi2rel.vp.permission.VideoPermissionPlayer;
import com.github.squi2rel.vp.permission.VideoPermissions;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.MetaType;
import com.github.squi2rel.vp.video.MetaValue;
import com.github.squi2rel.vp.video.ScreenGeometry;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
        LOGGER.info("server type: {}", type);
        switch (type) {
            case CONFIG -> {
                ByteBufUtils.readString(buf, 16);
                DataHolder.allPlayers.add(player.getUniqueId());
                sendGlobalPermissions(player);
            }
            case REQUEST -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.PLAY, VideoPermissionContext.screen(screen))) return;
                String url = ByteBufUtils.readString(buf, VideoScreen.MAX_PLAY_URL_LENGTH);
                if (fetchSource(player, url, screen::addInfo)) return;
                requestOk(player, requestId);
            }
            case SYNC -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null || screen.currentPlayback() == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SYNC, VideoPermissionContext.screen(screen))) return;
                sendTo(player, VideoPackets.sync(screen, screen.getProgress()));
                requestOk(player, requestId);
            }
            case SEEK -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SEEK, VideoPermissionContext.screen(screen))) return;
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) return;
                long progress = Math.max(0, buf.readLong());
                IVideoListener listener = screen.getListener();
                if (listener == null) return;
                screen.setProgress(progress);
                long syncedProgress = listener.getProgress();
                if (syncedProgress < 0) syncedProgress = progress;
                if (area.hasPlayer()) {
                    sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.sync(screen, syncedProgress));
                }
                requestOk(player, requestId);
            }
            case CREATE_AREA -> {
                int requestId = buf.readInt();
                Vector3f p1 = ByteBufUtils.readVec3(buf);
                Vector3f p2 = ByteBufUtils.readVec3(buf);
                String name = VideoPackets.readName(buf);
                String dim = DataHolder.worldKey(player.getWorld());
                if (!requirePermission(player, requestId, VideoPermissionAction.CREATE_AREA, VideoPermissionContext.global(dim))) return;
                if (!validName(player, name, "Area")) return;
                if (!validAreaBounds(player, p1, p2)) return;
                HashMap<String, VideoArea> map = DataHolder.areas.computeIfAbsent(dim, k -> new HashMap<>());
                if (map.containsKey(name)) {
                    reject(player, VpTranslation.of("error.videoplayer.area_exists", "A video area named %s already exists", name));
                    return;
                }
                VideoArea area = VideoArea.from(p1, p2, name, dim);
                area.initServer();
                map.put(area.name, area);
                message(player, VpTranslation.of("message.videoplayer.area_created", "Created video area %s in world %s", area.name, dim));
                requestOk(player, requestId);
                sendAreaPermissions(player, area);
                DataHolder.saveWorld(dim);
            }
            case REMOVE_AREA -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.REMOVE_AREA, VideoPermissionContext.area(area))) return;
                List<Player> receivers = List.of();
                byte[] data = null;
                HashMap<String, VideoArea> map = DataHolder.areas.get(area.dim);
                VideoArea removed = map == null ? null : map.remove(area.name);
                if (removed == null) return;
                if (removed.hasPlayer()) {
                    data = VideoPackets.removeArea(removed);
                    receivers = DataHolder.players(removed.playerSnapshot());
                }
                removed.remove();
                sendToPlayers(receivers, data);
                message(player, VpTranslation.of("message.videoplayer.area_removed", "Removed video area %s from world %s", area.name, area.dim));
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            case CREATE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) {
                    reject(player, VpTranslation.of("error.videoplayer.area_not_found_or_not_inside", "Video area was not found, or you are not inside it"));
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.CREATE_SCREEN, VideoPermissionContext.area(area))) return;
                VideoScreen screen = VideoScreen.read(buf, area);
                if (!validScreen(player, area, screen)) return;
                screen.initServer();
                List<Player> receivers = List.of();
                byte[] data = null;
                area.addScreen(screen);
                if (area.hasPlayer()) {
                    data = VideoPackets.createScreen(List.of(screen));
                    receivers = DataHolder.players(area.playerSnapshot());
                }
                sendToPlayers(receivers, data);
                message(player, VpTranslation.of("message.videoplayer.screen_created", "Created screen %s in video area %s", screen.name, area.name));
                requestOk(player, requestId);
                refreshPermissions(area);
                DataHolder.saveWorld(area.dim);
            }
            case REMOVE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                String screenName = VideoPackets.readName(buf);
                VideoScreen existing = area.getScreen(screenName);
                if (existing == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.REMOVE_SCREEN, VideoPermissionContext.screen(existing))) return;
                List<Player> receivers = List.of();
                byte[] data = null;
                VideoScreen screen = area.removeScreen(screenName);
                if (screen != null && area.hasPlayer()) {
                    data = VideoPackets.removeScreen(screen);
                    receivers = DataHolder.players(area.playerSnapshot());
                }
                sendToPlayers(receivers, data);
                if (screen != null) {
                    message(player, VpTranslation.of("message.videoplayer.screen_removed", "Removed screen %s from video area %s", screen.name, area.name));
                    requestOk(player, requestId);
                    refreshPermissions(area);
                    DataHolder.saveWorld(area.dim);
                }
            }
            case SKIP -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                boolean force = buf.readBoolean();
                if (force) {
                    if (!requirePermission(player, requestId, VideoPermissionAction.FORCE_SKIP, VideoPermissionContext.screen(screen))) return;
                    screen.skip();
                    requestOk(player, requestId);
                    return;
                }
                if (!requirePermission(player, requestId, VideoPermissionAction.VOTE_SKIP, VideoPermissionContext.screen(screen))) return;
                screen.voteSkip(player.getUniqueId());
                VpTranslation text = VpTranslation.of(
                        "message.videoplayer.skip_vote_broadcast",
                        "Player %s voted to skip the video on %s. %s more players required",
                        player.getName(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                );
                message(player, VpTranslation.of("message.videoplayer.skip_voted", "Voted to skip this video"));
                for (Player target : DataHolder.players(area.playerSnapshot())) {
                    message(target, text);
                }
                requestOk(player, requestId);
            }
            case SKIP_PERCENT -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_SKIP_PERCENT, VideoPermissionContext.screen(screen))) return;
                screen.setSkipPercent(buf.readFloat());
                message(player, VpTranslation.of("message.videoplayer.skip_percent_set", "Set skip vote ratio for screen %s to %s", screen.name, screen.skipPercent));
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            case IDLE_PLAY -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_IDLE_PLAY, VideoPermissionContext.screen(screen))) return;
                VideoPackets.readIdlePlayConfig(buf, screen);
                screen.idlePlayConfigChanged();
                if (area.hasPlayer()) {
                    sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.idlePlay(screen));
                }
                message(player, VpTranslation.of("message.videoplayer.idle_play_updated", "Updated IdlePlay list for screen %s", screen.name));
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            case SET_UV -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_UV, VideoPermissionContext.screen(screen))) return;
                VideoPackets.readUv(buf, screen);
                if (area.hasPlayer()) {
                    sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.setUv(screen, screen.u1, screen.v1, screen.u2, screen.v2));
                }
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            case OPEN_MENU -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.OPEN_MENU, VideoPermissionContext.screen(screen))) return;
                requestOk(player, requestId);
            }
            case SET_SCREEN_METADATA -> handleMetadata(player, buf);
            case SET_SCALE -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.SET_SCALE, VideoPermissionContext.screen(screen))) return;
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                if (scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                    reject(player, VpTranslation.of("error.videoplayer.scale_invalid", "Invalid scale value: %s %s", scaleX, scaleY));
                    return;
                }
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
                if (area.hasPlayer()) {
                    sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.setScale(screen, fill, scaleX, scaleY));
                }
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            case AUTO_SYNC -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
                if (screen == null) return;
                if (!requirePermission(player, requestId, VideoPermissionAction.AUTO_SYNC, VideoPermissionContext.screen(screen))) return;
                VideoInfo info = screen.currentPlayback();
                if (info == null || !info.seekable()) return;
                long clientTime = buf.readLong();
                IVideoListener listener = screen.getListener();
                if (listener == null) return;
                long progress = listener.getProgress();
                if (progress <= 0) return;
                long serverDelay = System.currentTimeMillis() - receivedAt;
                DataHolder.sendToCurrentThread(player, VideoPackets.autoSync(screen, clientTime, progress, serverDelay));
                requestOk(player, requestId);
            }
            case UPDATE_SCREEN -> {
                int requestId = buf.readInt();
                VideoArea area = getArea(player, VideoPackets.readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
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
                if (!validScreenUpdate(player, area, screen, vertices, source, displayConfig)) return;
                screen.setVertices(vertices);
                screen.source = source == null ? "" : source;
                screen.copyDisplayConfigFrom(displayConfig);
                if (area.hasPlayer()) {
                    sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.updateScreen(screen, screen.vertices, screen.source));
                }
                message(player, VpTranslation.of("message.videoplayer.screen_updated", "Updated screen %s", screen.name));
                requestOk(player, requestId);
                DataHolder.saveWorld(area.dim);
            }
            default -> DataHolder.disconnect(player, "Unknown packet type: " + type);
        }
        if (buf.readableBytes() > 0) {
            DataHolder.disconnect(player, "Illegal packet! Remaining: " + buf.readableBytes());
        }
    }

    private static void handleMetadata(Player player, ByteBuf buf) {
        int requestId = buf.readInt();
        VideoArea area = getArea(player, VideoPackets.readName(buf));
        if (area == null) return;
        VideoScreen screen = area.getScreen(VideoPackets.readName(buf));
        if (screen == null) return;
        if (!requirePermission(player, requestId, VideoPermissionAction.SET_METADATA, VideoPermissionContext.screen(screen))) return;
        String key;
        boolean remove;
        try {
            key = ByteBufUtils.readString(buf, 64);
            remove = buf.readBoolean();
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            reject(player, VpTranslation.of("error.videoplayer.meta.key_read_invalid", "Meta key is invalid: %s", errorMessage(e)));
            return;
        }
        if (!canModifyMetadata(player, key)) return;
        if (remove) {
            try {
                screen.metadata.remove(key);
            } catch (IllegalArgumentException e) {
                reject(player, VpTranslations.from(e));
                return;
            }
            if (area.hasPlayer()) {
                sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.removeMetadata(screen, key));
            }
            requestOk(player, requestId);
            DataHolder.saveWorld(area.dim);
            return;
        }
        MetaValue value;
        try {
            value = VideoPackets.readMetaValue(buf);
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            reject(player, VpTranslations.from(e, "error.videoplayer.meta.data_invalid", "Meta data is invalid: %s", errorMessage(e)));
            return;
        }
        if (!validMetadata(player, screen, key, value)) return;
        screen.metadata.set(key, value);
        if (area.hasPlayer()) {
            sendToPlayers(DataHolder.players(area.playerSnapshot()), VideoPackets.setMetadata(screen, key, value));
        }
        requestOk(player, requestId);
        DataHolder.saveWorld(area.dim);
    }

    private static boolean requirePermission(Player player, int requestId, VideoPermissionAction action, VideoPermissionContext context) {
        VideoPermissionPlayer permissionPlayer = VideoPermissions.player(player);
        if (VideoPermissions.allowed(permissionPlayer, action, context)) return true;
        sendPermissionCache(player, context);
        sendTo(player, VideoPackets.requestResult(requestId, RequestResultStatus.DENIED,
                VpTranslation.of("error.videoplayer.permission_denied", "Permission denied")));
        return false;
    }

    private static void requestOk(Player player, int requestId) {
        sendTo(player, VideoPackets.requestResult(requestId, RequestResultStatus.OK, VpTranslation.EMPTY));
    }

    public static void sendGlobalPermissions(Player player) {
        if (player == null) return;
        sendPermissionCache(player, VideoPermissionContext.global(DataHolder.worldKey(player.getWorld())));
    }

    public static void sendAreaPermissions(Player player, VideoArea area) {
        if (player == null || area == null) return;
        sendPermissionCache(player, VideoPermissionContext.area(area));
        for (VideoScreen screen : area.screens) {
            sendPermissionCache(player, VideoPermissionContext.screen(screen));
        }
    }

    public static void refreshPermissions(Player player) {
        if (player == null) return;
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

    public static void refreshPermissions(Player player, VideoArea area) {
        sendAreaPermissions(player, area);
    }

    public static void refreshPermissions(VideoArea area) {
        if (area == null || !area.hasPlayer()) return;
        for (Player player : DataHolder.players(area.playerSnapshot())) {
            sendAreaPermissions(player, area);
        }
    }

    private static void sendPermissionCache(Player player, VideoPermissionContext context) {
        if (player == null) return;
        VideoPermissionContext safeContext = context == null ? VideoPermissionContext.global(null) : context;
        long mask = VideoPermissions.mask(VideoPermissions.player(player), safeContext);
        sendTo(player, VideoPackets.permissions(safeContext.areaName(), safeContext.screenName(), mask));
    }

    private static boolean fetchSource(Player player, String url, Consumer<VideoInfo> cb) {
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(player));
        if (video == null) {
            message(player, VpTranslation.of("message.videoplayer.source_unresolved", "Unable to resolve video source"));
            return true;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("start fetch");
                return video.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((v, error) -> DataHolder.executeState(() -> {
            if (error != null) {
                LOGGER.warn("Failed to resolve video source for {}", player.getName(), error);
                message(player, VpTranslation.of("message.videoplayer.source_unresolved", "Unable to resolve video source"));
                return;
            }
            if (v == null) {
                message(player, VpTranslation.of("message.videoplayer.source_unresolved", "Unable to resolve video source"));
                return;
            }
            cb.accept(v);
        }));
        return false;
    }

    private static VideoArea getArea(Player player, String name) {
        String dim = DataHolder.worldKey(player.getWorld());
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
        VideoArea area = map == null ? null : map.get(name);
        return area != null && area.containsPlayer(player.getUniqueId()) ? area : null;
    }

    private static boolean validName(Player player, String name, String type) {
        if (name == null || name.isBlank()) {
            reject(player, VpTranslation.of("error.videoplayer.name_empty", "%s name must not be empty", type));
            return false;
        }
        if (name.length() > VideoScreen.MAX_NAME_LENGTH) {
            reject(player, VpTranslation.of("error.videoplayer.name_too_long", "%s name must not exceed %s characters", type, VideoScreen.MAX_NAME_LENGTH));
            return false;
        }
        return true;
    }

    private static boolean validAreaBounds(Player player, Vector3f p1, Vector3f p2) {
        if (!validVector(p1) || !validVector(p2)) {
            reject(player, VpTranslation.of("error.videoplayer.area_coordinates_invalid", "Area coordinates are invalid"));
            return false;
        }
        if (Math.abs(p1.x - p2.x) < EPSILON || Math.abs(p1.y - p2.y) < EPSILON || Math.abs(p1.z - p2.z) < EPSILON) {
            reject(player, VpTranslation.of("error.videoplayer.area_volume_required", "Area must have a valid volume"));
            return false;
        }
        return true;
    }

    private static boolean validScreen(Player player, VideoArea area, VideoScreen screen) {
        if (!validName(player, screen.name, "Screen")) return false;
        if (area.getScreen(screen.name) != null) {
            reject(player, VpTranslation.of("error.videoplayer.screen_exists", "Video area %s already contains a screen named %s", area.name, screen.name));
            return false;
        }
        if (screen.source == null) screen.source = "";
        if (!validScreenSource(player, area, screen.name, screen.source)) return false;
        screen.ensureValidState();
        return validScreenShape(player, area, screen, screen.vertices);
    }

    private static boolean validScreenUpdate(Player player, VideoArea area, VideoScreen screen, List<Vector3f> vertices, String source, VideoScreen displayConfig) {
        if (!validScreenSource(player, area, screen.name, source == null ? "" : source)) return false;
        displayConfig.ensureValidState();
        return validScreenShape(player, area, displayConfig, vertices);
    }

    private static boolean validMetadata(Player player, VideoScreen screen, String key, MetaValue value) {
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

    private static boolean canModifyMetadata(Player player, String key) {
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
            case "mute", "interactable", "autoSync", ScreenMetadata.KEY_DANMAKU_ENABLED -> true;
            default -> false;
        };
    }

    private static boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission(VideoPermissions.ADMIN);
    }

    private static void validateBuiltInMetadata(VideoScreen screen, String key, MetaValue value) {
        switch (key) {
            case "mute", "interactable", "autoSync", "debug", ScreenMetadata.KEY_DANMAKU_ENABLED -> requireType(key, value, MetaType.BOOL);
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

    private static boolean validScreenSource(Player player, VideoArea area, String screenName, String source) {
        if (source == null || source.isEmpty()) return true;
        if (source.equals(screenName)) {
            reject(player, VpTranslation.of("error.videoplayer.source_screen_self", "Source Screen cannot point to itself"));
            return false;
        }
        if (area.getScreen(source) == null) {
            reject(player, VpTranslation.of("error.videoplayer.source_screen_not_found", "Source Screen not found: %s", source));
            return false;
        }
        return true;
    }

    private static boolean validScreenVertices(Player player, VideoArea area, List<Vector3f> vertices) {
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

    private static boolean validScreenShape(Player player, VideoArea area, VideoScreen screen, List<Vector3f> vertices) {
        if (screen.surface == ScreenSurface.SPHERE_360) {
            return validSphere(player, area, screen);
        }
        return validScreenVertices(player, area, vertices);
    }

    private static boolean validSphere(Player player, VideoArea area, VideoScreen screen) {
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
        if (screen.sphereLat < 4 || screen.sphereLat > 128 || screen.sphereLon < 4 || screen.sphereLon > 256) {
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

    private static void sendToPlayers(List<Player> players, byte[] bytes) {
        if (bytes == null) return;
        for (Player target : players) {
            sendTo(target, bytes);
        }
    }
}
