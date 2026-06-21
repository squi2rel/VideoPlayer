package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.ScreenSurface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class ClientSubtitleController {
    private static final int MAX_LINES = 3;
    private static final float MAX_WIDTH_RATIO = 0.82f;
    private static final float BOTTOM_MARGIN = 22.0f;
    private static final float LINE_GAP = 4.0f;
    private static final int SUBTITLE_COLOR = 0xFFFFFFFF;

    private final ClientVideoScreen screen;
    private List<BiliCcSubtitleFetcher.SubtitleOption> options = List.of();
    private List<BiliCcSubtitleFetcher.SubtitleCue> cues = List.of();
    private CompletableFuture<BiliBiliSourceInfo> sourceTask;
    private CompletableFuture<BiliCcSubtitleFetcher.SubtitleCatalog> catalogTask;
    private CompletableFuture<BiliCcSubtitleFetcher.SubtitleTrack> trackTask;
    private BiliBiliSourceInfo sourceInfo;
    private String currentInfoKey = "";
    private String selectedKey = "";
    private String loadedKey = "";
    private boolean sourceResolved;
    private boolean catalogResolved;

    public ClientSubtitleController(ClientVideoScreen screen) {
        this.screen = screen;
    }

    public float canvasWidth() {
        return screen.danmaku().canvasWidth();
    }

    public float canvasHeight() {
        return ClientDanmakuController.VIRTUAL_HEIGHT;
    }

    public void update() {
        VideoInfo info = screen.currentPlaybackInfo();
        String infoKey = infoKey(info);
        if (!Objects.equals(infoKey, currentInfoKey)) {
            resetForInfo(infoKey);
        }

        if (screen.surface == ScreenSurface.SPHERE_360 || info == null || !hasSubtitleSource(info)) {
            stopNetworkAndClear();
            return;
        }

        if (YouTubeSubtitleFetcher.canResolve(info)) {
            sourceResolved = true;
        } else {
            updateSourceTask(info);
            if (sourceInfo == null || !sourceInfo.vod()) return;
        }
        updateCatalogTask(info);
        updateTrackTask();
    }

    public void stop() {
        stopNetworkAndClear();
        resetForInfo("");
    }

    public boolean hasSelectedTrack() {
        return !selectedKey.isBlank() && optionByKey(selectedKey) != null;
    }

    public String selectedKey() {
        return selectedKey;
    }

    public String selectedLabel() {
        BiliCcSubtitleFetcher.SubtitleOption option = optionByKey(selectedKey);
        return option == null ? "" : option.label();
    }

    public List<Option> options() {
        ArrayList<Option> result = new ArrayList<>(options.size());
        for (BiliCcSubtitleFetcher.SubtitleOption option : options) {
            result.add(new Option(option.key(), option.label(), option.language()));
        }
        return List.copyOf(result);
    }

    public boolean catalogLoaded() {
        return catalogResolved;
    }

    public boolean availableForCurrentVideo() {
        VideoInfo info = screen.currentPlaybackInfo();
        return screen.surface != ScreenSurface.SPHERE_360 && hasSubtitleSource(info);
    }

    public void select(String key) {
        String next = key == null ? "" : key;
        if (Objects.equals(selectedKey, next)) return;
        selectedKey = next;
        cues = List.of();
        loadedKey = "";
        if (trackTask != null) trackTask.cancel(true);
        trackTask = null;
    }

    public List<ClientDanmakuController.RenderableDanmaku> renderables() {
        return renderables(canvasWidth(), canvasHeight());
    }

    public List<ClientDanmakuController.RenderableDanmaku> renderables(float canvasWidth, float canvasHeight) {
        if (!hasSelectedTrack() || cues.isEmpty()) return List.of();
        long progress = playbackProgress();
        if (progress < 0) return List.of();

        ArrayList<String> active = new ArrayList<>();
        for (BiliCcSubtitleFetcher.SubtitleCue cue : cues) {
            if (cue.active(progress)) active.add(cue.content());
        }
        if (active.isEmpty()) return List.of();
        canvasWidth = Math.max(1.0f, canvasWidth);
        canvasHeight = Math.max(1.0f, canvasHeight);
        return renderLines(wrappedLines(active, canvasWidth), canvasWidth, canvasHeight);
    }

    private void updateSourceTask(VideoInfo info) {
        if (sourceResolved) return;
        if (sourceTask == null) {
            sourceTask = BiliBiliSourceRegistry.resolve(info);
            return;
        }
        if (!sourceTask.isDone()) return;
        try {
            sourceInfo = sourceTask.get();
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve Bilibili CC subtitle source", e);
        } finally {
            sourceResolved = true;
            sourceTask = null;
        }
    }

    private void updateCatalogTask(VideoInfo info) {
        if (catalogResolved) return;
        if (catalogTask == null) {
            catalogTask = YouTubeSubtitleFetcher.canResolve(info)
                    ? YouTubeSubtitleFetcher.fetchCatalog(info)
                    : BiliCcSubtitleFetcher.fetchCatalog(sourceInfo);
            return;
        }
        if (!catalogTask.isDone()) return;
        try {
            BiliCcSubtitleFetcher.SubtitleCatalog catalog = catalogTask.get();
            options = catalog == null || catalog.options() == null ? List.of() : catalog.options();
            if (optionByKey(selectedKey) == null) select("");
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch Bilibili CC subtitle list", e);
            options = List.of();
            select("");
        } finally {
            catalogResolved = true;
            catalogTask = null;
        }
    }

    private void updateTrackTask() {
        if (selectedKey.isBlank()) return;
        if (Objects.equals(selectedKey, loadedKey)) return;
        BiliCcSubtitleFetcher.SubtitleOption option = optionByKey(selectedKey);
        if (option == null) return;
        if (trackTask == null) {
            trackTask = YouTubeSubtitleFetcher.canFetch(option)
                    ? YouTubeSubtitleFetcher.fetchTrack(option)
                    : BiliCcSubtitleFetcher.fetchTrack(option);
            return;
        }
        if (!trackTask.isDone()) return;
        try {
            BiliCcSubtitleFetcher.SubtitleTrack track = trackTask.get();
            if (track != null && Objects.equals(track.key(), selectedKey)) {
                cues = track.cues() == null ? List.of() : track.cues();
                loadedKey = selectedKey;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch Bilibili CC subtitles", e);
            cues = List.of();
            loadedKey = selectedKey;
        } finally {
            trackTask = null;
        }
    }

    private BiliCcSubtitleFetcher.SubtitleOption optionByKey(String key) {
        if (key == null || key.isBlank()) return null;
        for (BiliCcSubtitleFetcher.SubtitleOption option : options) {
            if (Objects.equals(option.key(), key)) return option;
        }
        return null;
    }

    private List<ClientDanmakuController.RenderableDanmaku> renderLines(List<String> lines) {
        return renderLines(lines, canvasWidth(), canvasHeight());
    }

    private List<ClientDanmakuController.RenderableDanmaku> renderLines(List<String> lines, float canvasWidth, float canvasHeight) {
        if (lines.isEmpty()) return List.of();
        float scale = subtitleScale();
        float textHeight = DanmakuTextLayoutCache.measureHeight(scale);
        float lineHeight = textHeight + LINE_GAP;
        float startY = Math.max(6.0f, canvasHeight - BOTTOM_MARGIN - lineHeight * lines.size());
        ArrayList<ClientDanmakuController.RenderableDanmaku> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float width = DanmakuTextLayoutCache.measureWidth(line, scale);
            float x = Math.max(4.0f, (canvasWidth - width) * 0.5f);
            float y = startY + i * lineHeight;
            result.add(new ClientDanmakuController.RenderableDanmaku(line, x, y, scale, SUBTITLE_COLOR, width, textHeight, true));
        }
        return result;
    }

    private List<String> wrappedLines(List<String> active) {
        return wrappedLines(active, canvasWidth());
    }

    private List<String> wrappedLines(List<String> active, float canvasWidth) {
        float maxWidth = Math.max(60.0f, Math.max(1.0f, canvasWidth) * MAX_WIDTH_RATIO);
        float scale = subtitleScale();
        ArrayList<String> lines = new ArrayList<>();
        for (String text : active) {
            appendWrapped(lines, text, maxWidth, scale);
        }
        while (lines.size() > MAX_LINES) lines.removeFirst();
        return lines;
    }

    private void appendWrapped(List<String> lines, String text, float maxWidth, float scale) {
        if (text == null || text.isBlank()) return;
        for (String paragraph : text.replace("\r", "").split("\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) continue;
            StringBuilder current = new StringBuilder();
            for (int offset = 0; offset < trimmed.length(); ) {
                int codePoint = trimmed.codePointAt(offset);
                String next = new String(Character.toChars(codePoint));
                String candidate = current + next;
                if (!current.isEmpty() && DanmakuTextLayoutCache.measureWidth(candidate, scale) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(next);
                offset += Character.charCount(codePoint);
            }
            if (!current.isEmpty()) lines.add(current.toString());
        }
    }

    private float subtitleScale() {
        return 1.5f;
    }

    private long playbackProgress() {
        if (screen.player == null) return -1;
        long progress = screen.player.getProgress();
        if (progress >= 0) return progress;
        return Math.max(0, System.currentTimeMillis() - screen.getStartTime());
    }

    private void resetForInfo(String infoKey) {
        currentInfoKey = infoKey == null ? "" : infoKey;
        cancelTasks();
        sourceInfo = null;
        options = List.of();
        cues = List.of();
        selectedKey = "";
        loadedKey = "";
        sourceResolved = false;
        catalogResolved = false;
    }

    private void stopNetworkAndClear() {
        cancelTasks();
        sourceInfo = null;
        options = List.of();
        cues = List.of();
        selectedKey = "";
        loadedKey = "";
        sourceResolved = false;
        catalogResolved = false;
    }

    private void cancelTasks() {
        if (sourceTask != null) sourceTask.cancel(true);
        if (catalogTask != null) catalogTask.cancel(true);
        if (trackTask != null) trackTask.cancel(true);
        sourceTask = null;
        catalogTask = null;
        trackTask = null;
    }

    private boolean hasSubtitleSource(VideoInfo info) {
        return info != null && (BiliBiliSourceRegistry.canResolve(info) || YouTubeSubtitleFetcher.canResolve(info));
    }

    private String infoKey(VideoInfo info) {
        if (info == null) return "";
        return (info.rawPath() == null ? "" : info.rawPath())
                + "|" + (info.path() == null ? "" : info.path())
                + "|" + Arrays.hashCode(info.params());
    }

    public record Option(String key, String label, String language) {
    }
}
