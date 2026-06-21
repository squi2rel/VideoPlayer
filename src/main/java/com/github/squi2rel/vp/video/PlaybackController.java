package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.provider.NamedProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class PlaybackController {
    private final VideoScreen screen;
    private final PlaybackQueue queue;
    private final ScreenBroadcaster broadcaster;

    private IVideoListener listener;
    private CompletableFuture<IVideoListener> nextTask;
    private VideoInfo currentInfo;
    private boolean idlePlaying;
    private boolean resolvingIdle;

    public PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster) {
        this.screen = screen;
        this.queue = queue;
        this.broadcaster = broadcaster;
    }

    public void playNext() {
        if (nextTask != null && !nextTask.isDone()) {
            if (resolvingIdle && queue.peek() != null) {
                nextTask.cancel(true);
                nextTask = null;
                resolvingIdle = false;
            } else {
                return;
            }
        }
        if (listener != null && listener.isPlaying()) {
            if (!idlePlaying || queue.peek() == null) return;
            stopCurrent();
        }
        listener = null;
        queue.clearVotes();
        PlaybackRequest request = nextRequest();
        if (request == null) {
            sendStopIfLoaded();
            return;
        }
        resolvingIdle = request.idle;
        nextTask = CompletableFuture.supplyAsync(() -> resolveAndStart(request));
        nextTask.thenAccept(resolved -> {
            VideoPlayerMain.server.execute(() -> {
                if (nextTask == null || nextTask.isCancelled()) return;
                nextTask = null;
                resolvingIdle = false;
                if (resolved == null) {
                    if (request.idle) {
                        schedulePlayNext();
                    } else if (queue.peek() != null) {
                        queue.poll();
                        broadcaster.syncPlaylist();
                        playNext();
                    }
                    return;
                }
                listener = resolved;
                idlePlaying = request.idle;
                AtomicReference<String> stopReason = new AtomicReference<>("stopped");
                resolved.playing(seekable -> LOGGER.info("Playback listener started for screen {} idle={} seekable={}", screen.name, request.idle, seekable));
                resolved.errored(() -> stopReason.set("errored"));
                resolved.timeout(() -> stopReason.set("timeout"));
                resolved.stopped(() -> VideoPlayerMain.scheduler.schedule(() -> VideoPlayerMain.server.execute(() -> {
                    LOGGER.info("Playback listener stopped for screen {} idle={} reason={}", screen.name, request.idle, stopReason.get());
                    if (!request.idle) queue.poll();
                    currentInfo = null;
                    idlePlaying = false;
                    playNext();
                }), 2, TimeUnit.SECONDS));
                resolved.listen();
            });
        }).exceptionally(e -> {
            VideoPlayerMain.server.execute(() -> {
                nextTask = null;
                resolvingIdle = false;
                LOGGER.warn("Failed to resolve next playback item for screen {}", screen.name, e);
            });
            return null;
        });
    }

    private PlaybackRequest nextRequest() {
        VideoInfo info = queue.peek();
        if (info != null) return new PlaybackRequest(info, null, false);
        String idleUrl = screen.nextIdlePlayUrl();
        return idleUrl == null ? null : new PlaybackRequest(null, idleUrl, true);
    }

    private IVideoListener resolveAndStart(PlaybackRequest request) {
        VideoInfo info = request.info;
        if (request.idle) {
            info = resolveIdle(request.idleUrl);
        }
        if (info == null) {
            return null;
        }
        LOGGER.info("playing info: {} {} {}", info.playerName(), info.name(), info.path());
        if (info.expire() > 0 && System.currentTimeMillis() > info.expire()) {
            try {
                LOGGER.info("expired, {} {}", info.expire(), info.name());
                info = Objects.requireNonNull(VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName()))).get();
            } catch (Exception ignored) {
            }
        }
        if (info == null || info.expire() > 0 && System.currentTimeMillis() > info.expire()) {
            return null;
        }
        VideoInfo requestInfo = info;
        VideoPlayerMain.server.execute(() -> {
            currentInfo = requestInfo;
            if (screen.area.hasPlayer()) {
                broadcaster.send(VideoPackets.request(screen, requestInfo, request.idle));
            }
            broadcaster.syncPlaylist();
        });
        if (!info.rawPath().isEmpty()) {
            try {
                info = Objects.requireNonNull(VideoProviders.from(info.rawPath(), new NamedProviderSource(info.playerName()))).get();
            } catch (Exception ignored) {
            }
        }
        return info == null ? null : VideoListeners.from(info);
    }

    private VideoInfo resolveIdle(String url) {
        try {
            CompletableFuture<VideoInfo> video = VideoProviders.from(url, new NamedProviderSource("IdlePlay"));
            return video == null ? null : video.get();
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve idleplay item for screen {}: {}", screen.name, url, e);
            return null;
        }
    }

    public void skip() {
        boolean skipIdle = idlePlaying || queue.peek() == null;
        stopCurrent();
        if (!skipIdle) queue.poll();
        playNext();
        broadcaster.syncPlaylist();
    }

    public void stopAndClear() {
        stopAndClear(true);
    }

    public void stopAndClear(boolean syncPlaylist) {
        stopCurrent();
        queue.clear();
        if (syncPlaylist) {
            broadcaster.syncPlaylist();
        }
    }

    private void stopCurrent() {
        if (nextTask != null) {
            nextTask.cancel(true);
            nextTask = null;
        }
        resolvingIdle = false;
        if (listener != null) {
            listener.cancel();
            listener = null;
        }
        currentInfo = null;
        idlePlaying = false;
    }

    public long getProgress() {
        VideoInfo info = currentInfo;
        if (listener == null || info == null || !info.seekable()) return -1;
        return listener.getProgress();
    }

    public void setProgress(long progress) {
        VideoInfo info = currentInfo;
        if (listener == null || info == null || !info.seekable()) return;
        listener.setProgress(progress);
    }

    public IVideoListener listener() {
        return listener;
    }

    public VideoInfo currentInfo() {
        return currentInfo;
    }

    public boolean isIdlePlaying() {
        return idlePlaying;
    }

    public void idleConfigChanged() {
        if (queue.peek() == null) {
            stopCurrent();
            playNext();
        }
    }

    private void sendStopIfLoaded() {
        VideoPlayerMain.server.execute(() -> {
            if (screen.area.hasPlayer()) {
                broadcaster.send(VideoPackets.skip(screen));
                broadcaster.syncPlaylist();
            }
        });
    }

    private void schedulePlayNext() {
        VideoPlayerMain.scheduler.schedule(() -> VideoPlayerMain.server.execute(this::playNext), 2, TimeUnit.SECONDS);
    }

    private record PlaybackRequest(VideoInfo info, String idleUrl, boolean idle) {
    }
}
