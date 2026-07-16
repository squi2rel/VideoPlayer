package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ScreenMetadata;
import com.github.squi2rel.vp.video.ScreenSurface;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class ClientDanmakuController {
    public static final float VIRTUAL_WIDTH = 640.0f;
    public static final float VIRTUAL_HEIGHT = 360.0f;
    private static final int SEGMENT_MS = 360_000;
    private static final int MAX_ACTIVE = 512;
    private static final int FIXED_LANES = 4;
    private static final float LANE_HEIGHT = 18.0f;
    private static final long FIXED_DURATION_MS = 4000;
    private static final long BASE_MIN_ROLLING_DURATION_MS = 6500;
    private static final long BASE_MAX_ROLLING_DURATION_MS = 9000;
    private static final long MIN_ROLLING_DURATION_MS = 3500;
    private static final long MAX_ROLLING_DURATION_MS = 14000;
    private static final long ROLLING_LANE_EMIT_DELAY_MS = 200;
    private static final float[] SPEED_MULTIPLIERS = {1.6f, 1.25f, 1.0f, 0.75f, 0.55f};
    private static final int DENSITY_NORMAL = 0;
    private static final int DENSITY_MORE = 1;
    private static final int DENSITY_OVERLAP = 2;
    private static final Random RANDOM = new Random();

    private final ClientVideoScreen screen;
    private final ConcurrentLinkedQueue<DanmakuEntry> liveIncoming = new ConcurrentLinkedQueue<>();
    private final ArrayList<ActiveDanmaku> active = new ArrayList<>();
    private final ArrayList<DanmakuEntry> vodEntries = new ArrayList<>();
    private final Set<String> vodEntryKeys = new HashSet<>();
    private final Set<String> emittedKeys = new HashSet<>();
    private final Set<Integer> loadedSegments = new HashSet<>();
    private final Set<Integer> loadingSegments = new HashSet<>();

    private static Boolean enabledOverride;
    private VideoInfo currentInfo;
    private String currentInfoKey = "";
    private BiliBiliSourceInfo sourceInfo;
    private CompletableFuture<BiliBiliSourceInfo> sourceTask;
    private BiliLiveDanmakuClient liveClient;
    private int nextVodIndex;
    private long lastProgress = -1;
    private long animationTime;
    private long lastWallTime;
    private int topLaneCursor;
    private int bottomLaneCursor;

    public ClientDanmakuController(ClientVideoScreen screen) {
        this.screen = screen;
    }

    public static boolean canRenderOn(ClientVideoScreen displayScreen) {
        if (displayScreen == null || displayScreen.surface == ScreenSurface.SPHERE_360) return false;
        ClientVideoScreen playback = displayScreen.getScreen();
        return playback != null
                && playback.surface != ScreenSurface.SPHERE_360
                && BiliBiliSourceRegistry.canResolve(playback.currentPlaybackInfo());
    }

    public static boolean isEnabledOn(ClientVideoScreen displayScreen) {
        if (!canRenderOn(displayScreen)) return false;
        ClientVideoScreen playback = displayScreen.getScreen();
        return playback != null && playback.danmaku().enabled() && isScreenEnabled(displayScreen);
    }

    public static boolean toggleOn(ClientVideoScreen displayScreen) {
        ClientVideoScreen playback = displayScreen == null ? null : displayScreen.getScreen();
        if (playback == null) return toggleGlobal();
        return playback.danmaku().toggle();
    }

    public static boolean isGlobalEnabled() {
        return enabledOverride == null ? VideoPlayerClient.config.danmakuDefaultEnabled : enabledOverride;
    }

    public static boolean toggleGlobal() {
        enabledOverride = !isGlobalEnabled();
        if (!enabledOverride) stopAllNetworkAndClear();
        return enabledOverride;
    }

    public static boolean isScreenEnabled(ClientVideoScreen displayScreen) {
        return displayScreen != null
                && (displayScreen.metadata == null || displayScreen.metadata.getBool(ScreenMetadata.KEY_DANMAKU_ENABLED, true));
    }

    public boolean enabled() {
        return isGlobalEnabled();
    }

    public boolean toggle() {
        return toggleGlobal();
    }

    private static void stopAllNetworkAndClear() {
        for (ClientVideoScreen screen : VideoPlayerClient.screens) {
            screen.danmaku().stopNetworkAndClear();
        }
    }

    public float canvasWidth() {
        try {
            float aspect = screen.geometry().width() / Math.max(1.0f, screen.geometry().height());
            if (Float.isFinite(aspect) && aspect > 0) {
                return Math.clamp(VIRTUAL_HEIGHT * aspect, 240.0f, 1280.0f);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return VIRTUAL_WIDTH;
    }

    public float canvasHeight() {
        return VIRTUAL_HEIGHT;
    }

    public void update() {
        VideoInfo info = screen.currentPlaybackInfo();
        String infoKey = infoKey(info);
        if (!Objects.equals(infoKey, currentInfoKey)) {
            resetForInfo(info, infoKey);
        }

        advanceAnimationClock();
        updateActive();
        if (screen.surface == ScreenSurface.SPHERE_360 || info == null || !enabled() || !BiliBiliSourceRegistry.canResolve(info)) {
            stopNetworkAndClear();
            return;
        }

        updateSourceTask(info);
        if (sourceInfo == null) return;

        if (sourceInfo.live()) {
            ensureLiveClient();
            drainLiveIncoming();
        } else {
            stopLiveClient();
            updateVod();
        }
    }

    public void seek(long progress) {
        active.clear();
        emittedKeys.clear();
        nextVodIndex = lowerBound(Math.max(0, progress - 1000));
        lastProgress = progress;
        resetLanes();
    }

    public void stop() {
        stopNetworkAndClear();
        resetForInfo(null, "");
    }

    public List<RenderableDanmaku> renderables() {
        return renderables(canvasWidth(), canvasHeight());
    }

    public List<RenderableDanmaku> renderables(float canvasWidth, float canvasHeight) {
        ArrayList<RenderableDanmaku> result = new ArrayList<>(active.size());
        canvasWidth = Math.max(1.0f, canvasWidth);
        canvasHeight = Math.max(1.0f, canvasHeight);
        for (ActiveDanmaku item : active) {
            if (blockedByLocalSettings(item.mode(), item.color())) continue;
            long duration = durationMs(item, canvasWidth);
            long elapsed = animationTime - item.startTime();
            if (elapsed < 0 || elapsed > duration) continue;
            float x;
            float y;
            if (item.rolling()) {
                if (item.lane() >= rollingLaneCount(item.height())) continue;
                float travel = canvasWidth + item.width();
                float progress = Math.clamp(elapsed / (float) duration, 0.0f, 1.0f);
                x = item.leftToRight() ? -item.width() + progress * travel : canvasWidth - progress * travel;
                y = 6.0f + item.lane() * LANE_HEIGHT;
            } else if (item.fixedBottom()) {
                x = Math.max(4.0f, (canvasWidth - item.width()) * 0.5f);
                y = canvasHeight - 8.0f - (item.lane() + 1) * LANE_HEIGHT;
            } else {
                x = Math.max(4.0f, (canvasWidth - item.width()) * 0.5f);
                y = 6.0f + item.lane() * LANE_HEIGHT;
            }
            if (x + item.width() < -0.5f || x > canvasWidth + 0.5f || y + item.height() < 0 || y > canvasHeight) continue;
            result.add(new RenderableDanmaku(item.text(), x, y, item.scale(), item.color(), item.width(), item.height(), !item.rolling()));
        }
        return result;
    }

    private void resetForInfo(VideoInfo info, String infoKey) {
        currentInfo = info;
        currentInfoKey = infoKey;
        sourceInfo = null;
        if (sourceTask != null) sourceTask.cancel(true);
        sourceTask = null;
        stopLiveClient();
        active.clear();
        liveIncoming.clear();
        vodEntries.clear();
        vodEntryKeys.clear();
        emittedKeys.clear();
        loadedSegments.clear();
        loadingSegments.clear();
        nextVodIndex = 0;
        lastProgress = -1;
        animationTime = 0;
        lastWallTime = 0;
        resetLanes();
    }

    private void updateSourceTask(VideoInfo info) {
        if (sourceInfo != null) return;
        if (sourceTask == null) {
            sourceTask = BiliBiliSourceRegistry.resolve(info);
            return;
        }
        if (!sourceTask.isDone()) return;
        try {
            sourceInfo = sourceTask.get();
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve Bilibili danmaku source", e);
        } finally {
            sourceTask = null;
        }
    }

    private void updateVod() {
        long progress = playbackProgress();
        if (progress < 0) return;
        if (lastProgress >= 0 && progress < lastProgress - 1500) {
            seek(progress);
        }
        lastProgress = progress;
        int segment = (int) (progress / SEGMENT_MS) + 1;
        loadSegment(segment);
        loadSegment(segment + 1);
        enqueueVodDue(progress);
    }

    private long playbackProgress() {
        if (screen.player == null) return -1;
        long progress = screen.player.getProgress();
        if (progress >= 0) return progress;
        return Math.max(0, System.currentTimeMillis() - screen.getStartTime());
    }

    private void loadSegment(int segment) {
        if (sourceInfo == null || !sourceInfo.vod() || segment <= 0 || loadedSegments.contains(segment) || loadingSegments.contains(segment)) return;
        String expectedInfoKey = currentInfoKey;
        loadingSegments.add(segment);
        BiliVodDanmakuFetcher.fetchSegment(sourceInfo, segment).thenAccept(entries -> MinecraftClient.getInstance().execute(() -> {
            if (!Objects.equals(expectedInfoKey, currentInfoKey)) return;
            loadingSegments.remove(segment);
            loadedSegments.add(segment);
            addVodEntries(entries);
        })).exceptionally(e -> {
            MinecraftClient.getInstance().execute(() -> {
                if (!Objects.equals(expectedInfoKey, currentInfoKey)) return;
                loadingSegments.remove(segment);
                loadedSegments.add(segment);
            });
            LOGGER.warn("Failed to fetch Bilibili danmaku segment {}", segment, e);
            return null;
        });
    }

    private void addVodEntries(List<DanmakuEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (DanmakuEntry entry : entries) {
            if (entry == null || !entry.renderable()) continue;
            if (vodEntryKeys.add(entry.key())) vodEntries.add(entry);
        }
        vodEntries.sort(Comparator.comparingLong(DanmakuEntry::progressMs));
        if (lastProgress >= 0) nextVodIndex = Math.min(nextVodIndex, lowerBound(Math.max(0, lastProgress - 1000)));
    }

    private void enqueueVodDue(long progress) {
        while (nextVodIndex < vodEntries.size()) {
            DanmakuEntry entry = vodEntries.get(nextVodIndex);
            if (entry.progressMs() > progress + 120) break;
            nextVodIndex++;
            if (entry.progressMs() < progress - 1000) continue;
            if (emittedKeys.add(entry.key())) enqueue(entry);
        }
    }

    private int lowerBound(long progress) {
        int left = 0;
        int right = vodEntries.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (vodEntries.get(mid).progressMs() < progress) left = mid + 1;
            else right = mid;
        }
        return left;
    }

    private void ensureLiveClient() {
        if (liveClient != null) return;
        liveClient = new BiliLiveDanmakuClient(sourceInfo, liveIncoming::offer);
        liveClient.start();
    }

    private void drainLiveIncoming() {
        DanmakuEntry entry;
        while ((entry = liveIncoming.poll()) != null) {
            enqueue(entry);
        }
    }

    private void enqueue(DanmakuEntry entry) {
        if (entry == null || !entry.renderable()) return;
        if (blockedByLocalSettings(entry)) return;
        spawn(entry);
    }

    private boolean spawn(DanmakuEntry entry) {
        if (entry == null) return true;
        float scale = entry.scale() * scaleMultiplier();
        String text = entry.content();
        float width = DanmakuTextLayoutCache.measureWidth(text, scale);
        float height = DanmakuTextLayoutCache.measureHeight(scale);
        int lane;
        if (entry.fixedTop()) {
            lane = fixedLane(false);
            if (lane < 0) return false;
            topLaneCursor++;
        } else if (entry.fixedBottom()) {
            lane = fixedLane(true);
            if (lane < 0) return false;
            bottomLaneCursor++;
        } else {
            lane = rollingLane(height, width, entry.leftToRight());
            if (lane < 0) return false;
        }
        if (active.size() >= MAX_ACTIVE) active.removeFirst();
        active.add(new ActiveDanmaku(text, entry.mode(), entry.argb(), scale, width, height, lane, animationTime));
        return true;
    }

    private long rollingDuration(float canvasWidth, float width) {
        float widthContribution = Math.min(width, canvasWidth * 0.5f);
        long base = Math.clamp(Math.round((canvasWidth + widthContribution) * 8.0f), BASE_MIN_ROLLING_DURATION_MS, BASE_MAX_ROLLING_DURATION_MS);
        return Math.clamp(Math.round(base * speedMultiplier()), MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS);
    }

    private long durationMs(ActiveDanmaku item, float canvasWidth) {
        return item.rolling() ? rollingDuration(canvasWidth, item.width()) : FIXED_DURATION_MS;
    }

    private float speedMultiplier() {
        int preset = VideoPlayerClient.config == null ? 2 : Math.clamp(VideoPlayerClient.config.danmakuSpeedPreset, 0, SPEED_MULTIPLIERS.length - 1);
        return SPEED_MULTIPLIERS[preset];
    }

    private float scaleMultiplier() {
        int percent = VideoPlayerClient.config == null ? 100 : Math.clamp(VideoPlayerClient.config.danmakuScalePercent, 50, 170);
        return percent / 100.0f;
    }

    private int densityPreset() {
        if (VideoPlayerClient.config == null || VideoPlayerClient.config.danmakuRollingRangePercent != 100) return DENSITY_NORMAL;
        return Math.clamp(VideoPlayerClient.config.danmakuDensityPreset, DENSITY_NORMAL, DENSITY_OVERLAP);
    }

    private int rollingLaneCount(float height) {
        float usableHeight = rollingRangeHeight() - 12.0f - height;
        return Math.max(1, (int) Math.floor(usableHeight / LANE_HEIGHT) + 1);
    }

    private float rollingRangeHeight() {
        int percent = VideoPlayerClient.config == null ? 50 : VideoPlayerClient.config.danmakuRollingRangePercent;
        percent = switch (percent) {
            case 25, 50, 75, 100 -> percent;
            default -> 50;
        };
        if (VideoPlayerClient.config != null && VideoPlayerClient.config.danmakuBottomGuard) {
            percent = Math.min(percent, 85);
        }
        return canvasHeight() * percent / 100.0f;
    }

    private int fixedLane(boolean bottom) {
        boolean[] occupied = new boolean[FIXED_LANES];
        for (ActiveDanmaku item : active) {
            if (item.rolling() || item.fixedBottom() != bottom) continue;
            long elapsed = animationTime - item.startTime();
            if (elapsed >= 0 && elapsed <= durationMs(item, canvasWidth()) && item.lane() >= 0 && item.lane() < occupied.length) {
                occupied[item.lane()] = true;
            }
        }
        int cursor = bottom ? bottomLaneCursor : topLaneCursor;
        for (int i = 0; i < FIXED_LANES; i++) {
            int lane = Math.floorMod(cursor + i, FIXED_LANES);
            if (!occupied[lane]) return lane;
        }
        return -1;
    }

    private int rollingLane(float height, float width, boolean leftToRight) {
        int laneCount = rollingLaneCount(height);
        int density = densityPreset();
        for (int i = 0; i < laneCount; i++) {
            int lane = i;
            if (!rollingLaneEmitDelayed(lane) && !rollingLaneBlocked(lane, width, leftToRight, density)) {
                return lane;
            }
        }
        if (density == DENSITY_OVERLAP) {
            int available = 0;
            for (int lane = 0; lane < laneCount; lane++) {
                if (!rollingLaneEmitDelayed(lane)) available++;
            }
            if (available > 0) {
                int selected = RANDOM.nextInt(available);
                for (int lane = 0; lane < laneCount; lane++) {
                    if (!rollingLaneEmitDelayed(lane) && selected-- == 0) return lane;
                }
            }
        }
        return -1;
    }

    private boolean rollingLaneEmitDelayed(int lane) {
        for (ActiveDanmaku item : active) {
            if (!item.rolling() || item.lane() != lane) continue;
            long elapsed = animationTime - item.startTime();
            if (elapsed >= 0 && elapsed < ROLLING_LANE_EMIT_DELAY_MS) return true;
        }
        return false;
    }

    private boolean rollingLaneBlocked(int lane, float width, boolean leftToRight, int density) {
        float canvasWidth = canvasWidth();
        float gap = density == DENSITY_MORE || density == DENSITY_OVERLAP
                ? Math.max(28.0f, width * 0.25f)
                : Math.max(72.0f, width * 0.75f);
        for (ActiveDanmaku item : active) {
            if (!item.rolling() || item.lane() != lane) continue;
            long duration = durationMs(item, canvasWidth);
            long elapsed = animationTime - item.startTime();
            if (elapsed < 0 || elapsed > duration) continue;
            float x = rollingX(item, elapsed, duration, canvasWidth);
            if (leftToRight) {
                if (x < gap) return true;
            } else if (x + item.width() > canvasWidth - gap) {
                return true;
            }
        }
        return false;
    }

    private static float rollingX(ActiveDanmaku item, long elapsed, long duration, float canvasWidth) {
        float travel = canvasWidth + item.width();
        float progress = Math.clamp(elapsed / (float) duration, 0.0f, 1.0f);
        return item.leftToRight() ? -item.width() + progress * travel : canvasWidth - progress * travel;
    }

    private void advanceAnimationClock() {
        long now = System.currentTimeMillis();
        if (lastWallTime == 0) {
            lastWallTime = now;
            return;
        }
        long delta = Math.clamp(now - lastWallTime, 0, 100);
        lastWallTime = now;
        if (screen.player == null || !screen.player.isPaused() || sourceInfo != null && sourceInfo.live()) {
            animationTime += delta;
        }
    }

    private void updateActive() {
        Iterator<ActiveDanmaku> iterator = active.iterator();
        while (iterator.hasNext()) {
            ActiveDanmaku item = iterator.next();
            if (blockedByLocalSettings(item.mode(), item.color()) || animationTime - item.startTime() > durationMs(item, canvasWidth())) {
                iterator.remove();
            }
        }
    }

    private static boolean blockedByLocalSettings(DanmakuEntry entry) {
        return entry != null && blockedByLocalSettings(entry.mode(), entry.argb());
    }

    private static boolean blockedByLocalSettings(int mode, int color) {
        if (VideoPlayerClient.config == null) return false;
        boolean rolling = mode == 1 || mode == 2 || mode == 3 || mode == 6;
        boolean fixed = mode == 4 || mode == 5;
        if (VideoPlayerClient.config.danmakuBlockRolling && rolling) return true;
        if (VideoPlayerClient.config.danmakuBlockFixed && fixed) return true;
        if (VideoPlayerClient.config.danmakuBottomGuard && mode == 4) return true;
        return VideoPlayerClient.config.danmakuBlockColored && (color & 0x00FFFFFF) != 0x00FFFFFF;
    }

    private void stopNetworkAndClear() {
        stopLiveClient();
        if (sourceTask != null) {
            sourceTask.cancel(true);
            sourceTask = null;
        }
        active.clear();
        liveIncoming.clear();
    }

    private void stopLiveClient() {
        if (liveClient != null) {
            liveClient.stop();
            liveClient = null;
        }
    }

    private void resetLanes() {
        topLaneCursor = 0;
        bottomLaneCursor = 0;
    }

    private String infoKey(VideoInfo info) {
        if (info == null) return "";
        return (info.rawPath() == null ? "" : info.rawPath()) + "|" + (info.path() == null ? "" : info.path());
    }

    public record RenderableDanmaku(String text, float x, float y, float scale, int color, float width, float height, boolean fixed) {
    }

    private record ActiveDanmaku(String text, int mode, int color, float scale, float width, float height, int lane, long startTime) {
        boolean rolling() {
            return mode == 1 || mode == 2 || mode == 3 || mode == 6;
        }

        boolean leftToRight() {
            return mode == 6;
        }

        boolean fixedBottom() {
            return mode == 4;
        }
    }
}
