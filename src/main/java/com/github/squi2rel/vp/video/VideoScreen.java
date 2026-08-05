package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.network.ByteBufUtils;
import com.github.squi2rel.vp.network.ClientPlaybackResolution;
import com.github.squi2rel.vp.network.IdlePlayMutation;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import io.netty.buffer.ByteBuf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class VideoScreen {
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_NAME_BYTES = MAX_NAME_LENGTH * 4;
    public static final int MAX_PLAY_URL_BYTES = 1024;
    public static final int MAX_PLAY_URL_LENGTH = MAX_PLAY_URL_BYTES;
    public static final int MAX_IDLE_PLAY_ITEMS = 32;
    public static final int MAX_IDLE_PLAY_URL_BYTES = MAX_PLAY_URL_BYTES;
    public static final int MAX_IDLE_PLAY_URL_LENGTH = MAX_IDLE_PLAY_URL_BYTES;
    public static final int MAX_IDLE_PLAY_TOTAL_BYTES = 24_000;
    public static final long MAX_RESUME_PROGRESS_MS = TimeUnit.DAYS.toMillis(7);
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
    public ArrayList<IdlePlayEntry> idlePlayEntries = new ArrayList<>();
    public ArrayList<String> idlePlayUrls = new ArrayList<>();
    public boolean idlePlayRandom;
    public ScreenMetadata metadata = new ScreenMetadata();
    public ArrayList<VideoInfo> playlist = new ArrayList<>();
    public long playbackResumeProgress = -1L;
    public transient ArrayDeque<VideoInfo> infos = new ArrayDeque<>();
    private transient PlaybackQueue queue;
    private transient PlaybackController playback;
    private transient OrderedPlayAdmissions admissions;
    private transient ScreenBroadcaster broadcaster;
    private transient ScreenGeometry geometry;
    private transient ArrayList<UUID> idlePlayOrder = new ArrayList<>();
    private transient int idlePlayOrderIndex;
    private transient UUID lastIdlePlayId;
    private transient long serverPluginEpoch;
    private transient long serverScreenEpoch;
    private transient boolean serverActive;
    private transient long pendingResumeProgress = -1L;

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
        if (vertices == null) vertices = new ArrayList<>();
        if (source == null) source = "";
        if (metadata == null) metadata = new ScreenMetadata();
        if (playlist == null) playlist = new ArrayList<>();
        if (playbackResumeProgress < -1L || playbackResumeProgress > MAX_RESUME_PROGRESS_MS) playbackResumeProgress = -1L;
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

    public boolean hasValidDisplayConfig() {
        return Float.isFinite(u1) && Float.isFinite(v1)
                && Float.isFinite(u2) && Float.isFinite(v2)
                && Float.isFinite(scaleX) && Float.isFinite(scaleY)
                && scaleX >= 0.0625f && scaleX <= 16f
                && scaleY >= 0.0625f && scaleY <= 16f;
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
        ensureValidState();
    }

    public void setIdlePlayConfig(List<String> urls, boolean random) {
        ArrayList<IdlePlayEntry> entries = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.isBlank()) entries.add(IdlePlayEntry.legacy(url));
            }
        }
        setIdlePlayEntries(entries, random);
    }

    public void setIdlePlayEntries(List<IdlePlayEntry> entries, boolean random) {
        idlePlayEntries = normalizeIdlePlayEntries(entries, true);
        idlePlayUrls = new ArrayList<>();
        idlePlayRandom = random;
        resetIdlePlayOrder();
    }

    public void replaceLegacyIdlePlayConfig(List<String> urls, boolean random, UUID addedBy, String addedByName) {
        List<String> validated = validatedIdlePlayConfig(urls);
        sanitizeIdlePlay();
        ArrayList<IdlePlayEntry> existing = new ArrayList<>(idlePlayEntries);
        boolean[] retained = new boolean[existing.size()];
        ArrayList<IdlePlayEntry> next = new ArrayList<>(validated.size());
        for (String url : validated) {
            IdlePlayEntry match = null;
            for (int i = 0; i < existing.size(); i++) {
                if (!retained[i] && existing.get(i).url().equals(url)) {
                    retained[i] = true;
                    match = existing.get(i);
                    break;
                }
            }
            next.add(match == null
                    ? IdlePlayEntry.create(url, addedBy, addedByName, IdlePlayEntry.MIN_PRIORITY)
                    : match);
        }
        setIdlePlayEntries(next, random);
    }

    public boolean addIdlePlayEntry(String url, UUID addedBy, String addedByName, int priority) {
        sanitizeIdlePlay();
        if (idlePlayEntries.size() >= MAX_IDLE_PLAY_ITEMS) return false;
        IdlePlayEntry entry = IdlePlayEntry.create(url, addedBy, addedByName, priority);
        if (entry.url().isBlank()) return false;
        int bytes = ByteBufUtils.utf8Length(entry.url());
        if (bytes > MAX_IDLE_PLAY_URL_BYTES) return false;
        int totalBytes = bytes;
        for (IdlePlayEntry existing : idlePlayEntries) totalBytes += ByteBufUtils.utf8Length(existing.url());
        if (totalBytes > MAX_IDLE_PLAY_TOTAL_BYTES) return false;
        ArrayList<IdlePlayEntry> next = new ArrayList<>(idlePlayEntries);
        next.add(entry);
        idlePlayEntries = normalizeIdlePlayEntries(next, false);
        resetIdlePlayOrder();
        return true;
    }

    public boolean removeIdlePlayEntry(UUID id) {
        sanitizeIdlePlay();
        boolean removed = idlePlayEntries.removeIf(entry -> entry.id().equals(id));
        if (removed) resetIdlePlayOrder();
        return removed;
    }

    public boolean setIdlePlayPriority(UUID id, int priority) {
        if (priority < IdlePlayEntry.MIN_PRIORITY || priority > IdlePlayEntry.MAX_PRIORITY) return false;
        sanitizeIdlePlay();
        for (int i = 0; i < idlePlayEntries.size(); i++) {
            IdlePlayEntry entry = idlePlayEntries.get(i);
            if (!entry.id().equals(id)) continue;
            idlePlayEntries.set(i, entry.withPriority(priority));
            resetIdlePlayOrder();
            return true;
        }
        return false;
    }

    public boolean adjustIdlePlayPriority(UUID id, int delta) {
        sanitizeIdlePlay();
        for (IdlePlayEntry entry : idlePlayEntries) {
            if (!entry.id().equals(id)) continue;
            return setIdlePlayPriority(id, Math.clamp((long) entry.priority() + delta, IdlePlayEntry.MIN_PRIORITY, IdlePlayEntry.MAX_PRIORITY));
        }
        return false;
    }

    public void clearIdlePlayEntries() {
        sanitizeIdlePlay();
        if (idlePlayEntries.isEmpty()) return;
        idlePlayEntries.clear();
        resetIdlePlayOrder();
    }

    public boolean applyIdlePlayMutation(IdlePlayMutation mutation, UUID addedBy, String addedByName) {
        if (mutation == null || mutation.action() == null) return false;
        return switch (mutation.action()) {
            case ADD -> addIdlePlayEntry(mutation.url(), addedBy, addedByName, mutation.priority());
            case REMOVE -> removeIdlePlayEntry(mutation.entryId());
            case SET_PRIORITY -> setIdlePlayPriority(mutation.entryId(), mutation.priority());
            case ADJUST_PRIORITY -> adjustIdlePlayPriority(mutation.entryId(), mutation.delta());
            case CLEAR -> {
                clearIdlePlayEntries();
                yield true;
            }
            case SET_MODE -> {
                idlePlayRandom = mutation.random();
                resetIdlePlayOrder();
                yield true;
            }
        };
    }

    public void idlePlayConfigChanged() {
        if (playback != null) playback.idleConfigChanged();
    }

    public void resetIdlePlayOrder() {
        if (idlePlayOrder == null) idlePlayOrder = new ArrayList<>();
        idlePlayOrder.clear();
        idlePlayOrderIndex = 0;
        lastIdlePlayId = null;
    }

    public String nextIdlePlayUrl() {
        sanitizeIdlePlay();
        if (idlePlayEntries.isEmpty()) return null;
        if (idlePlayOrder.size() != idlePlayEntries.size() || idlePlayOrderIndex >= idlePlayOrder.size()) {
            rebuildIdlePlayOrder();
        }
        UUID id = idlePlayOrder.get(idlePlayOrderIndex++);
        for (IdlePlayEntry entry : idlePlayEntries) {
            if (entry.id().equals(id)) {
                lastIdlePlayId = id;
                return entry.url();
            }
        }
        rebuildIdlePlayOrder();
        if (idlePlayOrder.isEmpty()) return null;
        lastIdlePlayId = idlePlayOrder.get(idlePlayOrderIndex++);
        return findIdlePlayUrl(lastIdlePlayId);
    }

    private void rebuildIdlePlayOrder() {
        idlePlayOrder.clear();
        ArrayList<IdlePlayEntry> sorted = new ArrayList<>(idlePlayEntries);
        sorted.sort(Comparator.comparingInt(IdlePlayEntry::priority).reversed());
        if (!idlePlayRandom) {
            for (IdlePlayEntry entry : sorted) idlePlayOrder.add(entry.id());
        } else {
            int offset = 0;
            while (offset < sorted.size()) {
                int priority = sorted.get(offset).priority();
                int end = offset + 1;
                while (end < sorted.size() && sorted.get(end).priority() == priority) end++;
                ArrayList<UUID> group = new ArrayList<>();
                for (int i = offset; i < end; i++) group.add(sorted.get(i).id());
                Collections.shuffle(group);
                if (offset == 0 && group.size() > 1 && group.getFirst().equals(lastIdlePlayId)) {
                    Collections.swap(group, 0, 1);
                }
                idlePlayOrder.addAll(group);
                offset = end;
            }
        }
        idlePlayOrderIndex = 0;
    }

    private String findIdlePlayUrl(UUID id) {
        for (IdlePlayEntry entry : idlePlayEntries) {
            if (entry.id().equals(id)) return entry.url();
        }
        return null;
    }

    private void sanitizeIdlePlay() {
        if ((idlePlayEntries == null || idlePlayEntries.isEmpty()) && idlePlayUrls != null && !idlePlayUrls.isEmpty()) {
            ArrayList<IdlePlayEntry> migrated = new ArrayList<>();
            for (String url : idlePlayUrls) {
                if (url != null && !url.isBlank()) migrated.add(IdlePlayEntry.legacy(url));
            }
            idlePlayEntries = migrated;
        }
        idlePlayEntries = normalizeIdlePlayEntries(idlePlayEntries, false);
        idlePlayUrls = new ArrayList<>();
        if (idlePlayOrder == null) idlePlayOrder = new ArrayList<>();
        if (idlePlayOrderIndex < 0) idlePlayOrderIndex = 0;
        if (idlePlayOrder.size() != idlePlayEntries.size()) resetIdlePlayOrder();
    }

    public void syncInfo() {
        broadcaster.syncPlaylist();
    }

    public void syncIdlePlay() {
        if (broadcaster != null) broadcaster.syncIdlePlay();
    }

    public static int clampSphereSegments(int value) {
        return Math.clamp(value <= 0 ? DEFAULT_SPHERE_SEGMENTS : value, MIN_SPHERE_SEGMENTS, MAX_SPHERE_SEGMENTS);
    }

    public static boolean validName(String value) {
        return value != null && !value.isBlank() && validNameInput(value);
    }

    public static boolean validNameInput(String value) {
        return value != null
                && value.codePointCount(0, value.length()) <= MAX_NAME_LENGTH
                && ByteBufUtils.utf8Length(value) <= MAX_NAME_BYTES;
    }

    public static boolean validPlayUrl(String value) {
        return value != null && !value.isBlank() && validPlayUrlInput(value.trim());
    }

    public static boolean validPlayUrlInput(String value) {
        return value != null && ByteBufUtils.utf8Length(value) <= MAX_PLAY_URL_BYTES;
    }

    public static boolean validIdlePlayUrl(String value) {
        return value != null && !value.isBlank() && validIdlePlayUrlInput(value.trim());
    }

    public static boolean validIdlePlayUrlInput(String value) {
        return value != null && ByteBufUtils.utf8Length(value) <= MAX_IDLE_PLAY_URL_BYTES;
    }

    public static boolean validIdlePlayConfig(List<String> urls) {
        try {
            validatedIdlePlayConfig(urls);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean shouldKeepFallbackFrame(boolean hasPlaybackContent, boolean showIdleImage) {
        return hasPlaybackContent || showIdleImage;
    }

    public static List<String> validatedIdlePlayConfig(List<String> urls) {
        return List.copyOf(normalizeIdlePlay(urls, true));
    }

    public static boolean validIdlePlayEntries(List<IdlePlayEntry> entries) {
        try {
            validatedIdlePlayEntries(entries);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static List<IdlePlayEntry> validatedIdlePlayEntries(List<IdlePlayEntry> entries) {
        return List.copyOf(normalizeIdlePlayEntries(entries, true));
    }

    public static boolean validIdlePlayLimits(List<IdlePlayEntry> entries, List<String> legacyUrls) {
        if (entries == null || entries.isEmpty()) return validIdlePlayConfig(legacyUrls);
        int items = 0;
        int totalBytes = 0;
        for (IdlePlayEntry entry : entries) {
            if (entry == null || entry.url() == null || entry.url().isBlank()) continue;
            int bytes = ByteBufUtils.utf8Length(entry.url());
            if (bytes > MAX_IDLE_PLAY_URL_BYTES) return false;
            items++;
            totalBytes += bytes;
            if (items > MAX_IDLE_PLAY_ITEMS || totalBytes > MAX_IDLE_PLAY_TOTAL_BYTES) return false;
        }
        return true;
    }

    private static ArrayList<String> normalizeIdlePlay(List<String> urls, boolean rejectInvalid) {
        ArrayList<String> clean = new ArrayList<>();
        if (urls == null) return clean;
        int totalBytes = 0;
        for (String url : urls) {
            if (url == null) continue;
            String trimmed = url.trim();
            if (trimmed.isEmpty()) continue;
            int bytes = ByteBufUtils.utf8Length(trimmed);
            boolean valid = bytes <= MAX_IDLE_PLAY_URL_BYTES
                    && clean.size() < MAX_IDLE_PLAY_ITEMS
                    && totalBytes + bytes <= MAX_IDLE_PLAY_TOTAL_BYTES;
            if (!valid) {
                if (rejectInvalid) throw new IllegalArgumentException("IdlePlay configuration exceeds protocol limits");
                continue;
            }
            clean.add(trimmed);
            totalBytes += bytes;
        }
        return clean;
    }

    private static ArrayList<IdlePlayEntry> normalizeIdlePlayEntries(List<IdlePlayEntry> entries, boolean rejectInvalid) {
        ArrayList<IdlePlayEntry> clean = new ArrayList<>();
        if (entries == null) return clean;
        HashSet<UUID> ids = new HashSet<>();
        int totalBytes = 0;
        for (IdlePlayEntry value : entries) {
            if (value == null) continue;
            IdlePlayEntry entry = new IdlePlayEntry(value.id(), value.url(), value.addedBy(), value.addedByName(), value.priority());
            UUID id = entry.id();
            if (IdlePlayEntry.UNKNOWN_UUID.equals(id) || ids.contains(id)) {
                if (rejectInvalid) throw new IllegalArgumentException("IdlePlay entry id is invalid");
                entry = new IdlePlayEntry(UUID.randomUUID(), entry.url(), entry.addedBy(), entry.addedByName(), entry.priority());
                id = entry.id();
            }
            int bytes = ByteBufUtils.utf8Length(entry.url());
            boolean valid = !entry.url().isBlank()
                    && bytes <= MAX_IDLE_PLAY_URL_BYTES
                    && clean.size() < MAX_IDLE_PLAY_ITEMS
                    && totalBytes + bytes <= MAX_IDLE_PLAY_TOTAL_BYTES
                    && ByteBufUtils.utf8Length(entry.addedByName()) <= IdlePlayEntry.MAX_ADDED_BY_NAME_BYTES;
            if (!valid) {
                if (rejectInvalid) throw new IllegalArgumentException("IdlePlay configuration exceeds protocol limits");
                continue;
            }
            clean.add(entry);
            ids.add(id);
            totalBytes += bytes;
        }
        return clean;
    }

    public void initServer() {
        ensureValidState();
        serverPluginEpoch = DataHolder.lifecycleEpoch();
        serverScreenEpoch++;
        serverActive = true;
        queue = new PlaybackQueue(this);
        queue.restore(playlist);
        infos = queue.rawInfos();
        pendingResumeProgress = playbackResumeProgress;
        broadcaster = new ScreenBroadcaster(this);
        playback = new PlaybackController(this, queue, broadcaster);
        admissions = new OrderedPlayAdmissions(this);
    }

    public int skipped() {
        return queue.skipped();
    }

    public void addInfo(VideoInfo info) {
        addResolvedInfos(List.of(info));
    }

    public void addResolvedInfos(List<VideoInfo> resolved) {
        if (!serverActive || resolved == null || resolved.isEmpty()) return;
        ArrayList<VideoInfo> accepted = new ArrayList<>();
        for (VideoInfo info : resolved) {
            if (info == null || queue.size() + accepted.size() >= PlaybackQueue.MAX_ITEMS) continue;
            LOGGER.info("added info: {} {} {}", info.playerName(), info.name(), VideoProviders.redactedSource(info.path()));
            accepted.add(info);
        }
        if (queue.addAll(accepted) == 0) return;
        playNext();
        syncInfo();
    }

    public long getProgress() {
        return playback.getProgress();
    }

    public void setProgress(long progress) {
        VideoInfo active = currentPlayback();
        if (active == null || !active.seekable()) return;
        playback.setProgress(progress);
        rememberPlaybackResumeProgress(progress);
        queueChanged();
    }

    public boolean acceptClientPlaybackResolution(UUID reporter, long generation, long reporterToken,
                                                  ClientPlaybackResolution resolution, long durationMs) {
        return playback != null && playback.acceptClientPlaybackResolution(
                reporter, generation, reporterToken, resolution, durationMs
        );
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
        if (playback != null) playback.clientPlaybackReporterLeft(uuid);
        if (queue == null) return;
        queue.removePlayer(uuid);
        if (queue.shouldSkip()) skip();
    }

    public void addPlayer(UUID uuid) {
        if (playback != null) playback.clientPlaybackReporterAvailable();
    }

    public void remove() {
        serverActive = false;
        serverScreenEpoch++;
        if (admissions != null) admissions.close();
        if (playback != null) playback.close();
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

    public long currentPlaybackGeneration() {
        return playback == null ? 0L : playback.generation();
    }

    public IVideoListener getListener() {
        return playback == null ? null : playback.listener();
    }

    public ScreenLifecycleToken captureLifecycleToken() {
        return new ScreenLifecycleToken(serverPluginEpoch, serverScreenEpoch, ScreenKey.of(this));
    }

    public boolean isLifecycleCurrent(ScreenLifecycleToken token) {
        return token != null
                && serverActive
                && serverPluginEpoch == token.pluginEpoch()
                && serverScreenEpoch == token.screenEpoch()
                && ScreenKey.of(this).equals(token.key())
                && DataHolder.lifecycleActive(serverPluginEpoch);
    }

    public boolean serverActive() {
        return serverActive && DataHolder.lifecycleActive(serverPluginEpoch);
    }

    public long serverPluginEpoch() {
        return serverPluginEpoch;
    }

    public int queueSize() {
        return queue == null ? 0 : queue.size();
    }

    public PlaybackDiagnostics diagnostics(String backendState) {
        return playback == null ? PlaybackDiagnostics.empty(backendState) : playback.diagnostics(backendState);
    }

    void queueChanged() {
        if (queue != null) playlist = new ArrayList<>(queue.snapshot());
        if (queue == null || queue.peek() == null) {
            playbackResumeProgress = -1L;
            pendingResumeProgress = -1L;
        }
        if (serverActive && area != null && area.dim != null && !area.dim.isBlank()) {
            DataHolder.queueWorldSave(area.dim);
        }
    }

    public void prepareForPersistence() {
        if (queue != null) playlist = new ArrayList<>(queue.snapshot());
        if (queue == null || queue.peek() == null) {
            playbackResumeProgress = -1L;
            return;
        }
        if (playback == null || playback.isIdlePlaying() || playback.currentInfo() == null) return;
        long progress = playback.getProgress();
        if (progress >= 0L) playbackResumeProgress = progress;
    }

    long consumePlaybackResumeProgress() {
        long progress = pendingResumeProgress;
        pendingResumeProgress = -1L;
        playbackResumeProgress = -1L;
        return progress < 0L || progress > MAX_RESUME_PROGRESS_MS ? -1L : progress;
    }

    void rememberPlaybackResumeProgress(long progress) {
        if (progress >= 0L && progress <= MAX_RESUME_PROGRESS_MS) playbackResumeProgress = progress;
    }

    void clearPlaybackResumeProgress() {
        playbackResumeProgress = -1L;
        pendingResumeProgress = -1L;
    }

    public int pendingPlayAdmissions() {
        return admissions == null ? 0 : admissions.pendingCount();
    }

    public OrderedPlayAdmissions.Reservation reservePlayAdmission(java.util.function.Consumer<OrderedPlayAdmissions.Result> callback) {
        return admissions == null ? null : admissions.reserve(callback);
    }

    public void attachPlayAdmission(OrderedPlayAdmissions.Reservation reservation, java.util.concurrent.CompletableFuture<VideoInfo> future) {
        if (admissions == null) {
            if (future != null) future.cancel(true);
            return;
        }
        admissions.attach(reservation, future);
    }

    public void failPlayAdmission(OrderedPlayAdmissions.Reservation reservation, Throwable error) {
        if (admissions != null) admissions.fail(reservation, error);
    }

    public static VideoScreen read(ByteBuf buf, VideoArea area) {
        String name = ByteBufUtils.readString(buf, MAX_NAME_BYTES);
        int size = buf.readUnsignedByte();
        if (size > ScreenGeometry.MAX_VERTICES) {
            throw new IllegalStateException("Screen vertex count exceeds " + ScreenGeometry.MAX_VERTICES);
        }
        ArrayList<Vector3f> vertices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vertices.add(ByteBufUtils.readVec3(buf));
        }
        return new VideoScreen(
                area,
                name,
                vertices,
                ByteBufUtils.readString(buf, MAX_NAME_BYTES)
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
    }
}
