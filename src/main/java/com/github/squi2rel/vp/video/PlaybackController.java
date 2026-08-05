package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.i18n.VpTranslation;
import com.github.squi2rel.vp.network.ClientPlaybackResolution;
import com.github.squi2rel.vp.network.VideoPackets;
import com.github.squi2rel.vp.provider.NamedProviderSource;
import com.github.squi2rel.vp.provider.MediaAddressPolicy;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.provider.bilibili.BiliQuality;
import com.github.squi2rel.vp.provider.youtube.YouTubeQuality;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public class PlaybackController {
    private static final long MAX_CLIENT_DURATION_MS = TimeUnit.DAYS.toMillis(7);
    static final long RESOLUTION_TIMEOUT_SECONDS = OrderedPlayAdmissions.TIMEOUT_SECONDS;
    static final int MAX_RETRY_ATTEMPTS = 3;
    private static final SecureRandom CLIENT_REPORTER_TOKENS = new SecureRandom();

    private final VideoScreen screen;
    private final PlaybackQueue queue;
    private final ScreenBroadcaster broadcaster;
    private final QueuedResolver queuedResolver;
    private final IdleResolver idleResolver;
    private final Function<VideoInfo, IVideoListener> listenerFactory;
    private final Executor resolutionExecutor;
    private final Executor serverExecutor;
    private final DelayedExecutor delayedExecutor;
    private final ScreenLifecycleToken lifecycleToken;
    private final Predicate<UUID> reporterEligibility;
    private final PlaybackTelemetryRegistry.Binding telemetryBinding;

    private IVideoListener listener;
    private CompletableFuture<VideoInfo> nextTask;
    private volatile Thread resolutionThread;
    private VideoInfo currentInfo;
    private boolean idlePlaying;
    private boolean resolvingIdle;
    private boolean awaitingClientPlaybackResolution;
    private UUID clientPlaybackReporter;
    private long clientPlaybackReporterToken;
    private long playbackGeneration;
    private long retryVersion;
    private int retryAttempt;
    private long nextRetryAtMs;
    private PlaybackFailureReason lastFailureReason = PlaybackFailureReason.NONE;
    private String lastFailureMessage = "";
    private long lastFailureAtMs;
    private PendingFailure pendingFailure;

    public PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster) {
        this(
                screen,
                queue,
                broadcaster,
                PlaybackController::resolveQueuedInfo,
                PlaybackController::resolveIdle,
                info -> VideoListeners.from(screen, info),
                CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS),
                stateExecutor(screen),
                delayedExecutor(screen),
                screen.captureLifecycleToken(),
                DataHolder::supportsClientPlaybackReporting
        );
    }

    PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster,
                       QueuedResolver queuedResolver,
                       IdleResolver idleResolver,
                       Function<VideoInfo, IVideoListener> listenerFactory,
                       Executor resolutionExecutor, Executor serverExecutor, DelayedExecutor delayedExecutor) {
        this(screen, queue, broadcaster, queuedResolver, idleResolver, listenerFactory,
                resolutionExecutor, serverExecutor, delayedExecutor, null, uuid -> true);
    }

    private PlaybackController(VideoScreen screen, PlaybackQueue queue, ScreenBroadcaster broadcaster,
                                QueuedResolver queuedResolver,
                                IdleResolver idleResolver,
                               Function<VideoInfo, IVideoListener> listenerFactory,
                               Executor resolutionExecutor, Executor serverExecutor, DelayedExecutor delayedExecutor,
                               ScreenLifecycleToken lifecycleToken, Predicate<UUID> reporterEligibility) {
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
        this.reporterEligibility = reporterEligibility == null ? uuid -> false : reporterEligibility;
        this.telemetryBinding = lifecycleToken == null ? null : PlaybackTelemetryRegistry.bind(
                ScreenKey.of(screen),
                requested -> serverExecutor.execute(
                        () -> applyTelemetryRequest(requested, StreamListener::telemetryProbe)
                )
        );
    }

    void applyTelemetryRequest(boolean requested, Function<VideoInfo, IVideoListener> telemetryFactory) {
        if (!lifecycleCurrent()) return;
        IVideoListener current = listener;
        if (!(current instanceof TelemetryVideoListener telemetry)) return;
        if (!requested) {
            telemetry.detachTelemetry();
            return;
        }
        VideoInfo info = currentInfo;
        if (info == null || telemetryFactory == null) return;
        IVideoListener probe;
        try {
            probe = telemetryFactory.apply(info);
        } catch (RuntimeException error) {
            LOGGER.warn("Failed to create playback telemetry probe for screen {}", screen.name, error);
            return;
        }
        if (probe != null) telemetry.attachTelemetry(probe);
    }

    public void playNext() {
        if (!lifecycleCurrent()) return;
        if (!screen.area.hasPlayer()) return;
        retryVersion++;
        nextRetryAtMs = 0L;
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
        PlaybackRequest request = nextRequest(playbackSettings());
        if (request == null) {
            sendStopIfLoaded();
            return;
        }
        startRequest(request);
    }

    private void startRequest(PlaybackRequest request) {
        if (!lifecycleCurrent() || request == null || !screen.area.hasPlayer()) return;
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
            failRequest(request, resolutionFailureReason(error));
            return;
        }
        if (resolved == null) {
            failRequest(request, PlaybackFailureReason.RESOLUTION);
            return;
        }
        IVideoListener resolvedListener;
        try {
            resolvedListener = listenerFactory.apply(resolved);
        } catch (Throwable listenerError) {
            LOGGER.warn("Failed to create playback listener for screen {}", screen.name, listenerError);
            failRequest(request, PlaybackFailureReason.LISTENER_START);
            return;
        }
        if (resolvedListener == null) {
            failRequest(request, PlaybackFailureReason.LISTENER_START);
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
        awaitingClientPlaybackResolution = VideoListeners.awaitsClientPlaybackResolution(resolved.info);
        pendingFailure = null;
        try {
            resolved.listener.playing(seekable -> serverExecutor.execute(
                    () -> playbackStarted(request, resolved, generation, seekable)
            ));
            resolved.listener.errored(() -> serverExecutor.execute(
                    () -> playbackFailed(request, resolved, generation, PlaybackFailureReason.PLAYBACK_ERROR)
            ));
            resolved.listener.timeout(() -> serverExecutor.execute(
                    () -> playbackFailed(request, resolved, generation, PlaybackFailureReason.PLAYBACK_TIMEOUT)
            ));
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
                awaitingClientPlaybackResolution = false;
                clearClientPlaybackReporter();
                failRequest(request, PlaybackFailureReason.LISTENER_START);
            }
            return;
        }
        if (generation != playbackGeneration || listener != resolved.listener) return;
        assignClientPlaybackReporter();
        if (screen.area.hasPlayer()) {
            long progress = resolved.info.seekable() ? Math.max(0L, resolved.listener.getProgress()) : -1L;
            broadcaster.send(VideoPackets.request(screen, resolved.info, request.idle, generation, progress));
        }
        broadcaster.syncPlaylist();
    }

    private void playbackStarted(PlaybackRequest request, ResolvedPlayback resolved, long generation, boolean seekable) {
        if (!lifecycleCurrent()) return;
        if (generation != playbackGeneration || listener != resolved.listener) return;
        pendingFailure = null;
        retryAttempt = 0;
        nextRetryAtMs = 0L;
        if (request.idle || !seekable) return;
        long resumeProgress = screen.consumePlaybackResumeProgress();
        if (resumeProgress < 0L) return;
        resolved.listener.setProgress(resumeProgress);
        screen.rememberPlaybackResumeProgress(resumeProgress);
    }

    private void playbackFailed(PlaybackRequest request, ResolvedPlayback resolved, long generation,
                                PlaybackFailureReason reason) {
        if (!lifecycleCurrent()) return;
        if (generation != playbackGeneration || listener != resolved.listener) return;
        pendingFailure = new PendingFailure(request, resolved.listener, generation, reason);
    }

    private void playbackStopped(PlaybackRequest request, ResolvedPlayback resolved, long generation) {
        if (!lifecycleCurrent()) return;
        if (generation != playbackGeneration || listener != resolved.listener) return;
        PendingFailure failure = pendingFailure;
        boolean failed = failure != null && failure.generation == generation && failure.listener == resolved.listener;
        pendingFailure = null;
        listener = null;
        currentInfo = null;
        idlePlaying = false;
        awaitingClientPlaybackResolution = false;
        clearClientPlaybackReporter();
        if (failed) {
            failRequest(request, failure.reason);
            return;
        }
        if (!request.idle) {
            screen.clearPlaybackResumeProgress();
            removeQueuedRequest(request);
        }
        broadcaster.syncPlaylist();
        playNext();
    }

    private void failRequest(PlaybackRequest request, PlaybackFailureReason reason) {
        if (!lifecycleCurrent()) return;
        PlaybackFailureReason failure = reason == null ? PlaybackFailureReason.RESOLUTION : reason;
        rememberFailure(failure);
        if (failure.retryable() && retryAttempt < MAX_RETRY_ATTEMPTS && retryRequestCurrent(request)) {
            retryAttempt++;
            long delaySeconds = retryDelaySeconds(retryAttempt);
            nextRetryAtMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
            broadcaster.playbackNotice(retryNotice(failure), false);
            scheduleRetry(request, delaySeconds);
            return;
        }
        retryAttempt = 0;
        nextRetryAtMs = 0L;
        broadcaster.playbackNotice(failedNotice(failure, request.idle), true);
        if (request.idle) {
            schedulePlayNext();
            return;
        }
        screen.clearPlaybackResumeProgress();
        removeQueuedRequest(request);
        broadcaster.syncPlaylist();
        playNext();
    }

    private PlaybackFailureReason resolutionFailureReason(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResolutionFailure failure) return failure.reason;
            current = current.getCause();
        }
        return PlaybackFailureReason.RESOLUTION;
    }

    private boolean retryRequestCurrent(PlaybackRequest request) {
        if (request == null) return false;
        return request.idle ? queue.peek() == null : Objects.equals(queue.peek(), request.info);
    }

    private void scheduleRetry(PlaybackRequest request, long delaySeconds) {
        long expectedRetryVersion = ++retryVersion;
        delayedExecutor.execute(() -> {
            if (!lifecycleCurrent() || expectedRetryVersion != retryVersion) return;
            if (listener != null || nextTask != null || !retryRequestCurrent(request)) return;
            nextRetryAtMs = 0L;
            startRequest(request);
        }, delaySeconds);
    }

    private static long retryDelaySeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 2L;
            case 2 -> 5L;
            default -> 10L;
        };
    }

    private void rememberFailure(PlaybackFailureReason reason) {
        lastFailureReason = reason == null ? PlaybackFailureReason.RESOLUTION : reason;
        lastFailureMessage = lastFailureReason.fallback();
        lastFailureAtMs = System.currentTimeMillis();
    }

    private VpTranslation retryNotice(PlaybackFailureReason reason) {
        return VpTranslation.of(
                "message.videoplayer.playback_retry",
                "Playback on screen %s failed: %s. Retrying (%s/%s)",
                screen.name,
                reason.fallback(),
                retryAttempt,
                MAX_RETRY_ATTEMPTS
        );
    }

    private VpTranslation failedNotice(PlaybackFailureReason reason, boolean idle) {
        if (idle) {
            return VpTranslation.of(
                    "error.videoplayer.idle_playback_failed",
                    "Idle playback on screen %s failed: %s. Trying the next idle source",
                    screen.name,
                    reason.fallback()
            );
        }
        return VpTranslation.of(
                "error.videoplayer.playback_failed",
                "Playback on screen %s failed: %s. The queue item was skipped",
                screen.name,
                reason.fallback()
        );
    }

    private void removeQueuedRequest(PlaybackRequest request) {
        VideoInfo head = queue.peek();
        if (head == request.info || Objects.equals(head, request.info)) {
            queue.poll();
        }
    }

    private PlaybackRequest nextRequest(PlaybackSettings settings) {
        VideoInfo info = queue.peek();
        if (info != null) return new PlaybackRequest(info, null, false, settings);
        String idleUrl = screen.nextIdlePlayUrl();
        return idleUrl == null ? null : new PlaybackRequest(null, idleUrl, true, settings);
    }

    private VideoInfo resolve(PlaybackRequest request) {
        VideoInfo resolved = request.idle
                ? idleResolver.resolve(request.idleUrl, request.settings)
                : queuedResolver.resolve(request.info, request.settings);
        if (resolved == null) return null;
        String path = resolved.path();
        if (path != null && !path.isBlank() && !MediaAddressPolicy.isAllowed(path)) {
            LOGGER.warn("Rejected resolved playback address {}", VideoProviders.redactedSource(path));
            throw new ResolutionFailure(PlaybackFailureReason.SOURCE_REJECTED);
        }
        if (VideoParams.hasDisallowedMediaUrls(resolved.params())) {
            LOGGER.warn("Rejected resolved playback parameter URL for {}", VideoProviders.redactedSource(resolved.rawPath()));
            throw new ResolutionFailure(PlaybackFailureReason.SOURCE_REJECTED);
        }
        return resolved;
    }

    private static VideoInfo resolveQueuedInfo(VideoInfo info, PlaybackSettings settings) {
        if (info == null) return null;
        boolean expired = expired(info);
        if (hasPath(info) && !expired) return info;
        if (info.rawPath() != null && !info.rawPath().isBlank()) {
            CompletableFuture<VideoInfo> future = null;
            try {
                if (expired) LOGGER.info("expired, {} {}", info.expire(), info.name());
                future = VideoProviders.from(
                        info.rawPath(), providerSource(settings, info.playerName())
                );
                VideoInfo resolved = future == null ? null : future.get(RESOLUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (resolved != null && !expired(resolved)) return resolved;
            } catch (InterruptedException interrupted) {
                if (future != null) future.cancel(true);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception error) {
                if (future != null) future.cancel(true);
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

    private static VideoInfo resolveIdle(String url, PlaybackSettings settings) {
        CompletableFuture<VideoInfo> video = null;
        try {
            video = VideoProviders.from(url, providerSource(settings, "IdlePlay"));
            return video == null ? null : video.get(RESOLUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            if (video != null) video.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if (video != null) video.cancel(true);
            LOGGER.warn("Failed to resolve idleplay item for screen {}: {}", settings.screenName(), VideoProviders.redactedSource(url), e);
            return null;
        }
    }

    private static NamedProviderSource providerSource(PlaybackSettings settings, String name) {
        return new NamedProviderSource(name, settings.bilibiliQualityLimit(), settings.youtubeHeightLimit());
    }

    public void skip() {
        boolean hadPlayback = listener != null || currentInfo != null || idlePlaying;
        boolean skipIdle = idlePlaying || queue.peek() == null;
        boolean hasNext = (!skipIdle && queue.size() > 1) || !screen.idlePlayEntries.isEmpty();
        stopCurrent();
        clearFailureHistory();
        if (hadPlayback && hasNext && screen.area.hasPlayer()) {
            broadcaster.send(VideoPackets.skip(screen));
        }
        if (!skipIdle) {
            screen.clearPlaybackResumeProgress();
            queue.poll();
        }
        playNext();
        broadcaster.syncPlaylist();
    }

    public void stopAndClear() {
        stopAndClear(true);
    }

    public void stopAndClear(boolean syncPlaylist) {
        stopCurrent();
        clearFailureHistory();
        screen.clearPlaybackResumeProgress();
        queue.clear();
        if (syncPlaylist) {
            broadcaster.syncPlaylist();
        }
    }

    void close() {
        if (telemetryBinding != null) telemetryBinding.close();
        stopAndClear(false);
    }

    private void stopCurrent() {
        playbackGeneration++;
        retryVersion++;
        nextRetryAtMs = 0L;
        pendingFailure = null;
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
        awaitingClientPlaybackResolution = false;
        clearClientPlaybackReporter();
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

    boolean acceptClientPlaybackResolution(UUID reporter, long generation, long reporterToken,
                                           ClientPlaybackResolution resolution, long durationMs) {
        if (!lifecycleCurrent() || generation != playbackGeneration || !awaitingClientPlaybackResolution) return false;
        IVideoListener activeListener = listener;
        VideoInfo activeInfo = currentInfo;
        if (activeListener == null || activeInfo == null || resolution == null) return false;
        if (!Objects.equals(clientPlaybackReporter, reporter) || reporterToken == 0L
                || reporterToken != clientPlaybackReporterToken) {
            return false;
        }
        return switch (resolution) {
            case FINITE -> acceptClientFiniteResolution(activeListener, activeInfo, durationMs);
            case LIVE -> acceptClientLiveResolution(activeListener, activeInfo);
            case FAILED -> failClientResolution();
        };
    }

    private boolean acceptClientFiniteResolution(IVideoListener activeListener, VideoInfo activeInfo, long durationMs) {
        if (durationMs <= 0 || durationMs > MAX_CLIENT_DURATION_MS
                || !(activeListener instanceof ClientPlaybackResolutionListener clientListener)) {
            return false;
        }
        currentInfo = withPlaybackProperties(activeInfo, true, durationMs);
        awaitingClientPlaybackResolution = false;
        clearClientPlaybackReporter();
        if (clientListener.resolveFinite(durationMs)) return true;
        currentInfo = activeInfo;
        awaitingClientPlaybackResolution = true;
        assignClientPlaybackReporter();
        return false;
    }

    private boolean acceptClientLiveResolution(IVideoListener activeListener, VideoInfo activeInfo) {
        if (!(activeListener instanceof ClientPlaybackResolutionListener clientListener)) return false;
        currentInfo = withPlaybackProperties(activeInfo, false, 0L);
        awaitingClientPlaybackResolution = false;
        clearClientPlaybackReporter();
        if (clientListener.resolveLive()) return true;
        currentInfo = activeInfo;
        awaitingClientPlaybackResolution = true;
        assignClientPlaybackReporter();
        return false;
    }

    private boolean failClientResolution() {
        boolean wasIdle = idlePlaying;
        stopCurrent();
        rememberFailure(PlaybackFailureReason.CLIENT_RESOLUTION);
        broadcaster.playbackNotice(failedNotice(PlaybackFailureReason.CLIENT_RESOLUTION, wasIdle), true);
        if (!wasIdle) {
            screen.clearPlaybackResumeProgress();
            queue.poll();
        }
        broadcaster.syncPlaylist();
        playNext();
        return true;
    }

    private static VideoInfo withPlaybackProperties(VideoInfo info, boolean seekable, long durationMs) {
        return new VideoInfo(info.playerName(), info.name(), info.path(), info.rawPath(), info.expire(), seekable,
                info.params(), durationMs);
    }

    void clientPlaybackReporterLeft(UUID uuid) {
        if (uuid == null || !uuid.equals(clientPlaybackReporter)) return;
        assignClientPlaybackReporter();
    }

    void clientPlaybackReporterAvailable() {
        if (awaitingClientPlaybackResolution && clientPlaybackReporter == null) {
            assignClientPlaybackReporter();
        }
    }

    UUID clientPlaybackReporter() {
        return clientPlaybackReporter;
    }

    long clientPlaybackReporterToken() {
        return clientPlaybackReporterToken;
    }

    private void assignClientPlaybackReporter() {
        clearClientPlaybackReporter();
        if (!awaitingClientPlaybackResolution || currentInfo == null || !screen.area.hasPlayer()) return;
        UUID reporter = DataHolder.onlinePlayerUuid(currentInfo.playerName());
        if (reporter == null || !screen.area.containsPlayer(reporter) || !reporterEligibility.test(reporter)) {
            for (UUID candidate : screen.area.playerSnapshot()) {
                if (reporterEligibility.test(candidate)) {
                    reporter = candidate;
                    break;
                }
            }
        }
        if (reporter == null || !reporterEligibility.test(reporter)) return;
        clientPlaybackReporter = reporter;
        clientPlaybackReporterToken = nextClientReporterToken();
        broadcaster.sendTo(reporter, VideoPackets.clientPlaybackReporter(
                screen, playbackGeneration, clientPlaybackReporterToken
        ));
    }

    private static long nextClientReporterToken() {
        long token;
        do {
            token = CLIENT_REPORTER_TOKENS.nextLong();
        } while (token == 0L);
        return token;
    }

    private void clearClientPlaybackReporter() {
        clientPlaybackReporter = null;
        clientPlaybackReporterToken = 0L;
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

    public PlaybackDiagnostics diagnostics(String backendState) {
        VideoInfo active = currentInfo;
        VideoInfo queued = queue.peek();
        return new PlaybackDiagnostics(
                active == null ? "" : active.name(),
                queued == null ? "" : queued.name(),
                queue.size(),
                playbackGeneration,
                getProgress(),
                listener != null,
                nextTask != null,
                idlePlaying,
                active != null && active.seekable(),
                retryAttempt,
                nextRetryAtMs,
                lastFailureReason,
                lastFailureMessage,
                lastFailureAtMs,
                awaitingClientPlaybackResolution,
                clientPlaybackReporter != null,
                backendState
        );
    }

    public void idleConfigChanged() {
        if (queue.peek() == null) {
            stopCurrent();
            clearFailureHistory();
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

    private void clearFailureHistory() {
        retryAttempt = 0;
        nextRetryAtMs = 0L;
        lastFailureReason = PlaybackFailureReason.NONE;
        lastFailureMessage = "";
        lastFailureAtMs = 0L;
    }

    private boolean lifecycleCurrent() {
        return lifecycleToken == null || screen.isLifecycleCurrent(lifecycleToken);
    }

    private PlaybackSettings playbackSettings() {
        int bilibiliLimit = screen.metadata == null
                ? BiliQuality.UNLIMITED
                : screen.metadata.getInt(ScreenMetadata.KEY_BILIBILI_QUALITY, BiliQuality.UNLIMITED);
        int youtubeLimit = screen.metadata == null
                ? YouTubeQuality.AUTO
                : screen.metadata.getInt(ScreenMetadata.KEY_YOUTUBE_QUALITY, YouTubeQuality.AUTO);
        return new PlaybackSettings(
                BiliQuality.normalizeScreenLimit(bilibiliLimit),
                YouTubeQuality.normalizeScreenLimit(youtubeLimit),
                screen.name == null ? "unknown" : screen.name
        );
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

    private record PlaybackRequest(VideoInfo info, String idleUrl, boolean idle, PlaybackSettings settings) {
    }

    record PlaybackSettings(int bilibiliQualityLimit, int youtubeHeightLimit, String screenName) {
    }

    private record ResolvedPlayback(VideoInfo info, IVideoListener listener) {
    }

    private record PendingFailure(PlaybackRequest request, IVideoListener listener, long generation,
                                  PlaybackFailureReason reason) {
    }

    private static final class ResolutionFailure extends RuntimeException {
        private final PlaybackFailureReason reason;

        private ResolutionFailure(PlaybackFailureReason reason) {
            this.reason = reason;
        }
    }

    @FunctionalInterface
    interface QueuedResolver {
        VideoInfo resolve(VideoInfo info, PlaybackSettings settings);
    }

    @FunctionalInterface
    interface IdleResolver {
        VideoInfo resolve(String url, PlaybackSettings settings);
    }

    @FunctionalInterface
    interface DelayedExecutor {
        void execute(Runnable command, long delaySeconds);
    }
}
