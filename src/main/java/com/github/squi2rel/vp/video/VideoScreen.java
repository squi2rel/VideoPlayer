package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.network.ByteBufUtils;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.provider.VideoInfo;
import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.util.*;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class VideoScreen {
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_PLAY_URL_LENGTH = 1024;
    public static final int MAX_IDLE_PLAY_ITEMS = 32;
    public static final int MAX_IDLE_PLAY_URL_LENGTH = MAX_PLAY_URL_LENGTH;
    public static final int DEFAULT_SPHERE_SEGMENTS = 32;
    public static final int MIN_SPHERE_SEGMENTS = 4;
    public static final int MAX_SPHERE_SEGMENTS = 128;

    public transient VideoArea area;
    public String name;
    public ArrayList<Vector3f> vertices = new ArrayList<>();
    public float u1 = 0, v1 = 0, u2 = 1, v2 = 1;
    public boolean fill;
    public float scaleX = 1, scaleY = 1;
    public String source;
    public ScreenSurface surface = ScreenSurface.FLAT;
    public boolean stereo3d;
    public boolean spherePreset;
    public Vector3f sphereCenter = new Vector3f();
    public float sphereRadius = 10;
    public int sphereLat = 32;
    public int sphereLon = 32;
    public float sphereRotX;
    public float sphereRotY;
    public float sphereRotZ;
    public boolean sphereSkybox;
    public float skipPercent = 0.5f;
    public ArrayList<String> idlePlayUrls = new ArrayList<>();
    public boolean idlePlayRandom;
    public ScreenMetadata metadata = new ScreenMetadata();
    public transient ArrayDeque<VideoInfo> infos = new ArrayDeque<>();
    private transient PlaybackQueue queue;
    private transient PlaybackController playback;
    private transient ScreenBroadcaster broadcaster;
    private transient ScreenGeometry geometry;
    private transient int idlePlayIndex;
    private transient ArrayList<Integer> idlePlayShuffle = new ArrayList<>();
    private transient int idlePlayShuffleIndex;
    private transient String lastIdlePlayUrl;

    public VideoScreen(VideoArea area, String name, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4, String source) {
        this(area, name, List.of(p1, p2, p3, p4), source);
    }

    public VideoScreen(VideoArea area, String name, List<Vector3f> vertices, String source) {
        this.area = area;
        this.name = name;
        setVertices(vertices);
        this.source = source;
        ensureValidState();
    }

    public void setVertices(List<Vector3f> vertices) {
        this.vertices = new ArrayList<>();
        if (vertices != null) {
            for (Vector3f vertex : vertices) {
                this.vertices.add(new Vector3f(vertex));
            }
        }
        this.geometry = null;
    }

    public ScreenGeometry geometry() {
        if (geometry == null) {
            geometry = ScreenGeometry.create(vertices);
        }
        return geometry;
    }

    public Vector3f anchor() {
        if (surface == ScreenSurface.SPHERE_360 && spherePreset) return new Vector3f(sphereCenter);
        return geometry().firstVertex();
    }

    public void ensureValidState() {
        if (source == null) source = "";
        if (metadata == null) metadata = new ScreenMetadata();
        if (surface == null) surface = ScreenSurface.FLAT;
        if (sphereCenter == null) sphereCenter = new Vector3f();
        if (surface == ScreenSurface.SPHERE_360 && vertices.isEmpty()) spherePreset = true;
        if (!Float.isFinite(sphereRadius) || sphereRadius <= 0) sphereRadius = 10;
        sphereLat = clampSphereSegments(sphereLat);
        sphereLon = clampSphereSegments(sphereLon);
        if (!Float.isFinite(sphereRotX)) sphereRotX = 0;
        if (!Float.isFinite(sphereRotY)) sphereRotY = 0;
        if (!Float.isFinite(sphereRotZ)) sphereRotZ = 0;
        if (scaleX == 0 || scaleY == 0) {
            fill = false;
            scaleX = 1;
            scaleY = 1;
        }
        sanitizeIdlePlay();
    }

    public void copyDisplayConfigFrom(VideoScreen other) {
        if (other == null) return;
        surface = other.surface == null ? ScreenSurface.FLAT : other.surface;
        stereo3d = other.stereo3d;
        spherePreset = other.spherePreset;
        sphereCenter = other.sphereCenter == null ? new Vector3f() : new Vector3f(other.sphereCenter);
        sphereRadius = other.sphereRadius;
        sphereLat = other.sphereLat;
        sphereLon = other.sphereLon;
        sphereRotX = other.sphereRotX;
        sphereRotY = other.sphereRotY;
        sphereRotZ = other.sphereRotZ;
        sphereSkybox = other.sphereSkybox;
        setIdlePlayConfig(other.idlePlayUrls, other.idlePlayRandom);
        ensureValidState();
    }

    public void setIdlePlayConfig(List<String> urls, boolean random) {
        idlePlayUrls = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url == null) continue;
                String trimmed = url.trim();
                if (trimmed.isEmpty()) continue;
                idlePlayUrls.add(trimmed);
                if (idlePlayUrls.size() >= MAX_IDLE_PLAY_ITEMS) break;
            }
        }
        idlePlayRandom = random;
        resetIdlePlayOrder();
    }

    public void idlePlayConfigChanged() {
        if (playback != null) playback.idleConfigChanged();
    }

    public void resetIdlePlayOrder() {
        idlePlayIndex = 0;
        idlePlayShuffle.clear();
        idlePlayShuffleIndex = 0;
        lastIdlePlayUrl = null;
    }

    public String nextIdlePlayUrl() {
        sanitizeIdlePlay();
        if (idlePlayUrls.isEmpty()) return null;
        String url;
        if (idlePlayRandom) {
            if (idlePlayShuffle.size() != idlePlayUrls.size() || idlePlayShuffleIndex >= idlePlayShuffle.size()) {
                rebuildIdlePlayShuffle();
            }
            url = idlePlayUrls.get(idlePlayShuffle.get(idlePlayShuffleIndex++));
        } else {
            int index = Math.floorMod(idlePlayIndex, idlePlayUrls.size());
            url = idlePlayUrls.get(index);
            idlePlayIndex = (index + 1) % idlePlayUrls.size();
        }
        lastIdlePlayUrl = url;
        return url;
    }

    private void rebuildIdlePlayShuffle() {
        idlePlayShuffle.clear();
        for (int i = 0; i < idlePlayUrls.size(); i++) {
            idlePlayShuffle.add(i);
        }
        Collections.shuffle(idlePlayShuffle);
        if (idlePlayShuffle.size() > 1 && lastIdlePlayUrl != null) {
            int first = idlePlayShuffle.getFirst();
            if (Objects.equals(idlePlayUrls.get(first), lastIdlePlayUrl)) {
                Collections.swap(idlePlayShuffle, 0, 1);
            }
        }
        idlePlayShuffleIndex = 0;
    }

    private void sanitizeIdlePlay() {
        if (idlePlayUrls == null) {
            idlePlayUrls = new ArrayList<>();
            return;
        }
        ArrayList<String> clean = new ArrayList<>();
        for (String url : idlePlayUrls) {
            if (url == null) continue;
            String trimmed = url.trim();
            if (trimmed.isEmpty()) continue;
            clean.add(trimmed);
            if (clean.size() >= MAX_IDLE_PLAY_ITEMS) break;
        }
        idlePlayUrls = clean;
        if (idlePlayShuffle == null) idlePlayShuffle = new ArrayList<>();
        if (idlePlayIndex < 0) idlePlayIndex = 0;
        if (!idlePlayUrls.isEmpty()) idlePlayIndex %= idlePlayUrls.size();
    }

    public void syncInfo() {
        broadcaster.syncPlaylist();
    }

    public static int clampSphereSegments(int value) {
        return Math.clamp(value <= 0 ? DEFAULT_SPHERE_SEGMENTS : value, MIN_SPHERE_SEGMENTS, MAX_SPHERE_SEGMENTS);
    }

    public void initServer() {
        ensureValidState();
        queue = new PlaybackQueue(this);
        infos = queue.rawInfos();
        broadcaster = new ScreenBroadcaster(this);
        playback = new PlaybackController(this, queue, broadcaster);
    }

    public int skipped() {
        return queue.skipped();
    }

    public void addInfo(VideoInfo info) {
        LOGGER.info("added info: {} {} {}", info.playerName(), info.name(), info.path());
        queue.add(info);
        playNext();
        syncInfo();
    }

    public long getProgress() {
        return playback.getProgress();
    }

    public void setProgress(long progress) {
        playback.setProgress(progress);
    }

    public void voteSkip(UUID uuid) {
        queue.voteSkip(uuid);
        if (queue.shouldSkip()) skip();
    }

    public void setSkipPercent(float skipPercent) {
        this.skipPercent = skipPercent;
        if (queue.shouldSkip()) skip();
    }

    public void skip() {
        playback.skip();
    }

    public void removePlayer(UUID uuid) {
        queue.removePlayer(uuid);
        if (queue.shouldSkip()) skip();
    }

    public void remove() {
        playback.stopAndClear(false);
    }

    public void playNext() {
        playback.playNext();
    }

    public VideoInfo currentPlaying() {
        return queue.peek();
    }

    public VideoInfo currentPlayback() {
        return playback == null ? null : playback.currentInfo();
    }

    public boolean currentPlaybackIdle() {
        return playback != null && playback.isIdlePlaying();
    }

    public IVideoListener getListener() {
        return playback.listener();
    }

    public static VideoScreen read(ByteBuf buf, VideoArea area) {
        String name = ByteBufUtils.readString(buf, MAX_NAME_LENGTH);
        int size = buf.readUnsignedByte();
        ArrayList<Vector3f> vertices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vertices.add(ByteBufUtils.readVec3(buf));
        }
        return new VideoScreen(
                area,
                name,
                vertices,
                ByteBufUtils.readString(buf, MAX_NAME_LENGTH)
        ).readDisplayConfig(buf);
    }

    public static void write(ByteBuf buf, VideoScreen screen) {
        screen.ensureValidState();
        ByteBufUtils.writeString(buf, screen.name);
        buf.writeByte(screen.vertices.size());
        for (Vector3f vertex : screen.vertices) {
            ByteBufUtils.writeVec3(buf, vertex);
        }
        ByteBufUtils.writeString(buf, screen.source);
        writeDisplayConfig(buf, screen);
    }

    private VideoScreen readDisplayConfig(ByteBuf buf) {
        surface = ScreenSurface.fromId(buf.readUnsignedByte());
        stereo3d = buf.readBoolean();
        spherePreset = buf.readBoolean();
        sphereCenter = ByteBufUtils.readVec3(buf);
        sphereRadius = buf.readFloat();
        sphereLat = buf.readInt();
        sphereLon = buf.readInt();
        sphereRotX = buf.readFloat();
        sphereRotY = buf.readFloat();
        sphereRotZ = buf.readFloat();
        sphereSkybox = buf.readBoolean();
        VideoPackets.readIdlePlayConfig(buf, this);
        ensureValidState();
        return this;
    }

    public static void readDisplayConfig(ByteBuf buf, VideoScreen screen) {
        screen.readDisplayConfig(buf);
    }

    public static void writeDisplayConfig(ByteBuf buf, VideoScreen screen) {
        screen.ensureValidState();
        buf.writeByte(screen.surface.ordinal());
        buf.writeBoolean(screen.stereo3d);
        buf.writeBoolean(screen.spherePreset);
        ByteBufUtils.writeVec3(buf, screen.sphereCenter);
        buf.writeFloat(screen.sphereRadius);
        buf.writeInt(screen.sphereLat);
        buf.writeInt(screen.sphereLon);
        buf.writeFloat(screen.sphereRotX);
        buf.writeFloat(screen.sphereRotY);
        buf.writeFloat(screen.sphereRotZ);
        buf.writeBoolean(screen.sphereSkybox);
        VideoPackets.writeIdlePlayConfig(buf, screen);
    }
}
