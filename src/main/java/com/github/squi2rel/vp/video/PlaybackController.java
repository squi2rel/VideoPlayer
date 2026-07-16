package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.provider.NamedProviderSource;
import com.github.squi2rel.vp.provider.MediaAddressPolicy;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class PlaybackController {
    private final VideoScreen screen;
    private final PlaybackQueue queue;
    private final ScreenBroadcaster broadcaster;
    private final Function<VideoInfo, VideoInfo> queuedResolver;
    private final Function<String, VideoInfo> idleResolver;
    private final Function<VideoInfo, IVideoListener> listenerFactory;
    private final Executor resolutionExecutor;
    private final Executor serverExecutor;
    private final DelayedExecutor delayedExecutor;
    private final ScreenLifecycleToken lifecycleToken;

    private IVideoListener listener;
    private CompletableFuture<VideoInfo> nextTask;
    private volatile Thread resolutionThread;
    private VideoInfo currentInfo;
    private boolean idlePlaying;
    private boolean resolvingIdle;
    private long playbackGeneration;

    public PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster) {
        this(
                screen,
                queue,
                broadcaster,
                info -> resolveQueuedInfo(screen, info),
                url -> resolveIdle(screen, url),
                VideoListeners::from,
                CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS),
                stateExecutor(screen),
                delayedExecutor(screen),
                screen.captureLifecycleToken()
        );
    }

    PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster,
                       Function<VideoInfo, VideoInfo> queuedResolver,
                       Function<String, VideoInfo> idleResolver,
                       Function<VideoInfo, IVideoListener> listenerFactory,
                       Executor resolutionExecutor, Executor serverExecutor, DelayedExecutor delayedExecutor) {
        this(screen, queue, broadcaster, queuedResolver, idleResolver, listenerFactory,
                resolutionExecutor, serverExecutor, delayedExecutor, null);
    }

    private PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster,
                               Function<VideoInfo, VideoInfo> queuedResolver,
                               Function<String, VideoInfo> idleResolver,
                               Function<VideoInfo, IVideoListener> listenerFactory,
                               Executor resolutionExecutor, Executor serverExecutor, DelayedExecutor delayedExecutor,
                               ScreenLifecycleToken lifecycleToken) {
        this.screen = screen;
        this.queue = queue;
        this.broadcaster = broadcaster;
        this.queuedResolver = queuedResolver;
        this.idleResolver = idleResolver;
        this.listenerFactory = listenerFactory;
        this.resolutionExecutor = resolutionExecutor;
        this.serverExecutor = serverExecutor;
        this.delayedExecutor = delayedExecutor;
        this.lifecycleToken = lifecycleToken;
    }

    public void playNext() {
        if (!lifecycleCurrent()) return;
        if (!screen.area.hasPlayer()) return;
        if (nextTask != null) {
            if (resolvingIdle && queue.peek() != null) {
                nextTask.cancel(true);
                nextTask = null;
                Thread thread = resolutionThread;
                if (thread != null) thread.interrupt();
                resolvingIdle = false;
            } else {
                return;
            }
        }
        if (listener != null) {
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
        long generation = ++playbackGeneration;
        CompletableFuture<VideoInfo> task = CompletableFuture.supplyAsync(() -> {
            resolutionThread = Thread.currentThread();
            try {
                return resolve(request);
            } finally {
                if (resolutionThread == Thread.currentThread()) resolutionThread = null;
            }
        }, resolutionExecutor);
        nextTask = task;
        task.whenComplete((resolved, error) -> serverExecutor.execute(() -> completeResolution(
                request, resolved, error, task, generation
        )));
    }

    private void completeResolution(PlaybackRequest request, VideoInfo resolved, Throwable error,
                                    CompletableFuture<VideoInfo> task, long generation) {
        if (!lifecycleCurrent()) return;
        if (generation != playbackGeneration || task != nextTask || task.isCancelled()) return;
        nextTask = null;
        resolvingIdle = false;
        if (error != null) {
            LOGGER.warn("Failed to resolve next playback item for screen {}", screen.name, error);
            failRequest(request);
            return;
        }
        if (resolved == null) {
            failRequest(request);
            return;
        }
        IVideoListener resolvedListener;
        try {
            resolvedListener = listenerFactory.apply(resolved);
        } catch (Throwable listenerError) {
            LOGGER.warn("Failed to create playback listener for screen {}", screen.name, listenerError);
            failRequest(request);
            return;
        }
        if (resolvedListener == null) {
            failRequest(request);
            return;
        }
        startResolved(request, new ResolvedPlayback(resolved, resolvedListener), generation);
    }

    private void startResolved(PlaybackRequest request, ResolvedPlayback resolved, long generation) {
        if (!lifecycleCurrent()) {
            resolved.listener.cancel();
            return;
        }
        currentInfo = resolved.info;
        listener = resolved.listener;
        idlePlaying = request.idle;
        try {
            resolved.listener.playing(seekable -> {});
            resolved.listener.errored(() -> {});
            resolved.listener.timeout(() -> {});
            resolved.listener.stopped(() -> delayedExecutor.execute(
                    () -> playbackStopped(request, resolved, generation), 2
            ));
            resolved.listener.listen();
        } catch (Throwable error) {
            LOGGER.warn("Failed to start playback listener for screen {}", screen.name, error);
            try {
                resolved.listener.cancel();
            } catch (Throwable cancelError) {
                error.addSuppressed(cancelError);
            }
            if (generation == playbackGeneration && listener == resolved.listener) {
                listener = null;
                currentInfo = null;
                idlePlaying = false;
                failRequest(request);
            }
            return;
        }
        if (generation != playbackGeneration || listener != resolved.listener) return;
        if (screen.area.hasPlayer()) {
            long progress = resolved.info.seekable() ? Math.max(0L, resolved.listener.getProgress()) : -1L;
            broadcaster.send(VideoPackets.request(screen, resolved.info, request.idle, generation, progress));
        }
        broadcaster.syncPlaylist();
    }

    private void playbackStopped(PlaybackRequest request, ResolvedPlayback resolved, long generation) {
        if (!lifecycleCurrent()) return;
        if (generation != playbackGeneration || listener != resolved.listener) return;
        if (!request.idle) removeQueuedRequest(request);
        listener = null;
        currentInfo = null;
        idlePlaying = false;
        broadcaster.syncPlaylist();
        playNext();
    }

    private void failRequest(PlaybackRequest request) {
        if (!lifecycleCurrent()) return;
        if (request.idle) {
            schedulePlayNext();
            return;
        }
        removeQueuedRequest(request);
        broadcaster.syncPlaylist();
        playNext();
    }

    private void removeQueuedRequest(PlaybackRequest request) {
        VideoInfo head = queue.peek();
        if (head == request.info || Objects.equals(head, request.info)) {
            queue.poll();
        }
    }

    private PlaybackRequest nextRequest() {
        VideoInfo info = queue.peek();
        if (info != null) return new PlaybackRequest(info, null, false);
        String idleUrl = screen.nextIdlePlayUrl();
        return idleUrl == null ? null : new PlaybackRequest(null, idleUrl, true);
    }

    private VideoInfo resolve(PlaybackRequest request) {
        VideoInfo resolved = request.idle ? idleResolver.apply(request.idleUrl) : queuedResolver.apply(request.info);
        if (resolved == null) return null;
        String path = resolved.path();
        if (path != null && !path.isBlank() && !MediaAddressPolicy.isAllowed(path)) {
            LOGGER.warn("Rejected resolved playback address {}", VideoProviders.redactedSource(path));
            return null;
        }
        if (VideoParams.hasDisallowedMediaUrls(resolved.params())) {
            LOGGER.warn("Rejected resolved playback parameter URL for {}", VideoProviders.redactedSource(resolved.rawPath()));
            return null;
        }
        return resolved;
    }

    private static VideoInfo resolveQueuedInfo(VideoScreen screen, VideoInfo info) {
        if (info == null) return null;
        boolean expired = expired(info);
        if (hasPath(info) && !expired) return info;
        if (info.rawPath() != null && !info.rawPath().isBlank()) {
            CompletableFuture<VideoInfo> future = null;
            try {
                if (expired) LOGGER.info("expired, {} {}", info.expire(), info.name());
                future = VideoProviders.from(
                        info.rawPath(), providerSource(screen, info.playerName())
                );
                VideoInfo resolved = future == null ? null : future.get();
                if (resolved != null && !expired(resolved)) return resolved;
            } catch (InterruptedException interrupted) {
                if (future != null) future.cancel(true);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception error) {
                LOGGER.warn("Failed to refresh playback source {}", VideoProviders.redactedSource(info.rawPath()), error);
            }
        }
        return expired ? null : info;
    }

    private static boolean hasPath(VideoInfo info) {
        return info.path() != null && !info.path().isBlank();
    }

    private static boolean expired(VideoInfo info) {
        return info.expire() > 0 && System.currentTimeMillis() >= info.expire();
    }

    private static VideoInfo resolveIdle(VideoScreen screen, String url) {
        CompletableFuture<VideoInfo> video = null;
        try {
            video = VideoProviders.from(url, providerSource(screen, "IdlePlay"));
            return video == null ? null : video.get();
        } catch (InterruptedException interrupted) {
            if (video != null) video.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve idleplay item for screen {}: {}", screen.name, VideoProviders.redactedSource(url), e);
            return null;
        }
    }

    private static NamedProviderSource providerSource(VideoScreen screen, String name) {
        int bilibiliLimit = screen == null || screen.metadata == null
                ? BiliQuality.UNLIMITED
                : screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED);
        int youtubeLimit = screen == null || screen.metadata == null
                ? YouTubeQuality.AUTO
                : screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO);
        return new NamedProviderSource(name, bilibiliLimit, youtubeLimit);
    }

    public void skip() {
        boolean hadPlayback = listener != null || currentInfo != null || idlePlaying;
        boolean skipIdle = idlePlaying || queue.peek() == null;
        boolean hasNext = (!skipIdle && queue.size() > 1) || !screen.idlePlayUrls.isEmpty();
        stopCurrent();
        if (hadPlayback && hasNext && screen.area.hasPlayer()) {
            broadcaster.send(VideoPackets.skip(screen));
        }
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
        playbackGeneration++;
        if (nextTask != null) {
            nextTask.cancel(true);
            nextTask = null;
        }
        Thread thread = resolutionThread;
        if (thread != null) thread.interrupt();
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

    public long generation() {
        return playbackGeneration;
    }

    public void idleConfigChanged() {
        if (queue.peek() == null) {
            stopCurrent();
            playNext();
        }
    }

    private void sendStopIfLoaded() {
        serverExecutor.execute(() -> {
            if (!lifecycleCurrent()) return;
            if (screen.area.hasPlayer()) {
                broadcaster.send(VideoPackets.skip(screen));
                broadcaster.syncPlaylist();
            }
        });
    }

    private void schedulePlayNext() {
        delayedExecutor.execute(this::playNext, 2);
    }

    private boolean lifecycleCurrent() {
        return lifecycleToken == null || screen.isLifecycleCurrent(lifecycleToken);
    }

    private static Executor stateExecutor(VideoScreen screen) {
        long epoch = screen.serverPluginEpoch();
        return command -> DataHolder.executeState(epoch, command);
    }

    private static DelayedExecutor delayedExecutor(VideoScreen screen) {
        long epoch = screen.serverPluginEpoch();
        return (command, delay) -> VideoPlayerMain.scheduler.schedule(
                () -> DataHolder.executeState(epoch, command), delay, TimeUnit.SECONDS
        );
    }

    private record PlaybackRequest(VideoInfo info, String idleUrl, boolean idle) {
    }

    private record ResolvedPlayback(VideoInfo info, IVideoListener listener) {
    }

    @FunctionalInterface
    interface DelayedExecutor {
        void execute(Runnable command, long delaySeconds);
    }
}
