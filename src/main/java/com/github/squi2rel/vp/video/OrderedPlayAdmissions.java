package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.provider.VideoInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;

public final class OrderedPlayAdmissions {
    public static final long TIMEOUT_SECONDS = 45;

    private final VideoScreen screen;
    private final UnaryOperator<CompletableFuture<VideoInfo>> timeout;
    private final BiConsumer<Long, Runnable> stateExecutor;
    private final Predicate<ScreenLifecycleToken> lifecycleCurrent;
    private final LinkedHashMap<Long, PendingAdmission> pending = new LinkedHashMap<>();
    private long nextSequence;
    private long nextCommitSequence;
    private boolean closed;

    public OrderedPlayAdmissions(VideoScreen screen) {
        this(
                screen,
                future -> future.copy().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                DataHolder::executeState,
                token -> screen.isLifecycleCurrent(token) && DataHolder.findScreen(token.key()) == screen
        );
    }

    OrderedPlayAdmissions(VideoScreen screen,
                          UnaryOperator<CompletableFuture<VideoInfo>> timeout,
                          BiConsumer<Long, Runnable> stateExecutor,
                          Predicate<ScreenLifecycleToken> lifecycleCurrent) {
        this.screen = screen;
        this.timeout = timeout;
        this.stateExecutor = stateExecutor;
        this.lifecycleCurrent = lifecycleCurrent;
    }

    public Reservation reserve(Consumer<Result> callback) {
        if (closed || !screen.serverActive() || screen.queueSize() + pending.size() >= PlaybackQueue.MAX_ITEMS) return null;
        long sequence = nextSequence++;
        ScreenLifecycleToken token = screen.captureLifecycleToken();
        pending.put(sequence, new PendingAdmission(sequence, token, callback));
        return new Reservation(sequence, token);
    }

    public void attach(Reservation reservation, CompletableFuture<VideoInfo> future) {
        PendingAdmission admission = pending.get(reservation == null ? -1 : reservation.sequence());
        if (admission == null || !admission.token.equals(reservation.token())) {
            if (future != null) future.cancel(true);
            return;
        }
        if (future == null) {
            complete(admission, null, new IllegalStateException("Unable to resolve video source"));
            return;
        }
        admission.future = timeout.apply(future);
        admission.sourceFuture = future;
        admission.future.whenComplete((info, error) -> {
            if (isTimeout(error)) future.cancel(true);
            stateExecutor.accept(
                    admission.token.pluginEpoch(),
                    () -> complete(admission, info, error)
            );
        });
    }

    public void fail(Reservation reservation, Throwable error) {
        PendingAdmission admission = pending.get(reservation == null ? -1 : reservation.sequence());
        if (admission != null) complete(admission, null, error);
    }

    public void close() {
        if (closed) return;
        closed = true;
        CancellationException error = new CancellationException("Video screen is no longer active");
        ArrayList<PendingAdmission> cancelled = new ArrayList<>(pending.values());
        pending.clear();
        for (PendingAdmission admission : cancelled) {
            if (admission.future != null) admission.future.cancel(true);
            if (admission.sourceFuture != null) admission.sourceFuture.cancel(true);
            notifyResult(admission, new Result(null, error));
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void complete(PendingAdmission admission, VideoInfo info, Throwable error) {
        if (closed || admission.completed || pending.get(admission.sequence) != admission) return;
        if (!lifecycleCurrent.test(admission.token)) {
            pending.remove(admission.sequence);
            admission.completed = true;
            if (admission.sourceFuture != null) admission.sourceFuture.cancel(true);
            notifyResult(admission, new Result(null, new CancellationException("Video screen changed while resolving")));
            advanceCommitCursor();
            drain();
            return;
        }
        admission.info = info;
        admission.error = error;
        admission.completed = true;
        drain();
    }

    private void drain() {
        ArrayList<PendingAdmission> drained = new ArrayList<>();
        ArrayList<VideoInfo> accepted = new ArrayList<>();
        while (true) {
            advanceCommitCursor();
            PendingAdmission admission = pending.get(nextCommitSequence);
            if (admission == null || !admission.completed) break;
            pending.remove(nextCommitSequence++);
            drained.add(admission);
            if (admission.error == null && admission.info != null) accepted.add(admission.info);
        }
        if (!accepted.isEmpty()) screen.addResolvedInfos(accepted);
        for (PendingAdmission admission : drained) {
            if (admission.error == null && admission.info != null) {
                notifyResult(admission, new Result(admission.info, null));
            } else {
                Throwable error = admission.error == null
                        ? new IllegalStateException("Unable to resolve video source")
                        : admission.error;
                notifyResult(admission, new Result(null, error));
            }
        }
    }

    private void advanceCommitCursor() {
        while (nextCommitSequence < nextSequence && !pending.containsKey(nextCommitSequence)) {
            nextCommitSequence++;
        }
    }

    private static void notifyResult(PendingAdmission admission, Result result) {
        if (admission.callback == null || admission.notified) return;
        admission.notified = true;
        try {
            admission.callback.accept(result);
        } catch (RuntimeException error) {
            LOGGER.warn("Failed to complete ordered play admission", error);
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            current = current.getCause();
        }
        return current instanceof TimeoutException;
    }

    public record Reservation(long sequence, ScreenLifecycleToken token) {
    }

    public record Result(VideoInfo info, Throwable error) {
        public boolean success() {
            return info != null && error == null;
        }
    }

    private static final class PendingAdmission {
        private final long sequence;
        private final ScreenLifecycleToken token;
        private final Consumer<Result> callback;
        private CompletableFuture<VideoInfo> future;
        private CompletableFuture<VideoInfo> sourceFuture;
        private VideoInfo info;
        private Throwable error;
        private boolean completed;
        private boolean notified;

        private PendingAdmission(long sequence, ScreenLifecycleToken token, Consumer<Result> callback) {
            this.sequence = sequence;
            this.token = token;
            this.callback = callback;
        }
    }
}
