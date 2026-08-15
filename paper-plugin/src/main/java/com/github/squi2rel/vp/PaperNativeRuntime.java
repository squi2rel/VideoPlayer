package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.StreamListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class PaperNativeRuntime {
    public enum State {
        INITIALIZING,
        INSTALLING,
        LOADING,
        READY,
        UNAVAILABLE,
        STOPPED
    }

    private static final AtomicLong EPOCHS = new AtomicLong();
    private static final AtomicReference<PaperNativeRuntime> CURRENT = new AtomicReference<>();

    private final long epoch = EPOCHS.incrementAndGet();
    private final Installer installer;
    private final Installer fallbackInstaller;
    private final BooleanSupplier loader;
    private final TaskLauncher launcher;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZING);
    private volatile Cancellable task = () -> {};
    private volatile Throwable error;

    private PaperNativeRuntime(Installer installer, Installer fallbackInstaller, BooleanSupplier loader, TaskLauncher launcher) {
        this.installer = installer;
        this.fallbackInstaller = fallbackInstaller;
        this.loader = loader;
        this.launcher = launcher;
    }

    public static PaperNativeRuntime start(VideoPlayerPaperPlugin plugin, PaperNativeConfig config) {
        return start(
                config::downloadIfMissing,
                config::downloadVlcFallbackIfMissing,
                StreamListener::load,
                runnable -> {
                    FoliaScheduler.TaskHandle scheduled = FoliaScheduler.runAsync(runnable);
                    return scheduled::cancel;
                }
        );
    }

    static PaperNativeRuntime start(Installer installer, BooleanSupplier loader, TaskLauncher launcher) {
        return start(installer, active -> {}, loader, launcher);
    }

    static PaperNativeRuntime start(Installer installer, Installer fallbackInstaller, BooleanSupplier loader, TaskLauncher launcher) {
        PaperNativeRuntime runtime = new PaperNativeRuntime(installer, fallbackInstaller, loader, launcher);
        PaperNativeRuntime previous = CURRENT.getAndSet(runtime);
        if (previous != null) previous.stop();
        runtime.launch();
        return runtime;
    }

    public static PaperNativeRuntime current() {
        return CURRENT.get();
    }

    public static State currentState() {
        PaperNativeRuntime runtime = CURRENT.get();
        return runtime == null ? State.STOPPED : runtime.state();
    }

    public static boolean isReady() {
        return currentState() == State.READY;
    }

    public static Throwable currentError() {
        PaperNativeRuntime runtime = CURRENT.get();
        return runtime == null ? null : runtime.error();
    }

    public long epoch() {
        return epoch;
    }

    public State state() {
        return state.get();
    }

    public Throwable error() {
        return error;
    }

    public boolean active() {
        return isCurrent();
    }

    public void stop() {
        active.set(false);
        state.set(State.STOPPED);
        task.cancel();
        NativePackageManager.cancelActiveDownloads();
        StreamListener.shutdown();
        CURRENT.compareAndSet(this, null);
    }

    private void launch() {
        Cancellable launched;
        try {
            launched = launcher.launch(this::initialize);
        } catch (Throwable launchError) {
            publishUnavailable(launchError);
            return;
        }
        task = launched == null ? () -> {} : launched;
        if (!isCurrent()) task.cancel();
    }

    private void initialize() {
        if (!publishState(State.INSTALLING)) return;
        try {
            installer.install(this::isCurrent);
        } catch (Throwable installError) {
            if (!isCurrent()) return;
            VideoPlayerMain.LOGGER.warn("Failed to prepare VideoPlayer native package; trying system libraries", installError);
        }
        if (!isCurrent()) return;
        StreamListener.resetLoadState();
        if (!publishState(State.LOADING)) return;
        LoadAttempt attempt = loadAttempt();
        if (!attempt.loaded() && isCurrent()) {
            try {
                fallbackInstaller.install(this::isCurrent);
            } catch (Throwable fallbackError) {
                VideoPlayerMain.LOGGER.warn("Failed to prepare VLC fallback runtime after the primary backend could not load", fallbackError);
            }
            if (!isCurrent()) return;
            StreamListener.resetLoadState();
            if (!publishState(State.LOADING)) return;
            LoadAttempt retried = loadAttempt();
            if (!retried.loaded() && attempt.error() != null && retried.error() != null
                    && retried.error() != attempt.error()) {
                retried.error().addSuppressed(attempt.error());
            }
            attempt = retried;
        }
        if (!isCurrent()) return;
        if (attempt.loaded()) {
            error = null;
            VideoPlayerMain.error = null;
            state.set(State.READY);
            VideoPlayerMain.LOGGER.info("VideoPlayer stream listener backend is ready");
        } else {
            publishUnavailable(attempt.error() == null
                    ? new IllegalStateException("Cannot load stream listener backend")
                    : attempt.error());
        }
    }

    private LoadAttempt loadAttempt() {
        try {
            boolean loaded = loader.getAsBoolean();
            return new LoadAttempt(loaded, loaded ? null : StreamListener.loadError());
        } catch (Throwable failure) {
            return new LoadAttempt(false, failure);
        }
    }

    private boolean publishState(State next) {
        if (!isCurrent()) return false;
        state.set(next);
        return true;
    }

    private void publishUnavailable(Throwable failure) {
        if (!isCurrent()) return;
        Throwable resolved = failure == null
                ? new IllegalStateException("Cannot load stream listener backend")
                : failure;
        error = resolved;
        VideoPlayerMain.error = resolved;
        state.set(State.UNAVAILABLE);
        VideoPlayerMain.LOGGER.error("Cannot load stream listener backend; plugin channel will remain available for clients", resolved);
    }

    private boolean isCurrent() {
        return active.get() && CURRENT.get() == this;
    }

    @FunctionalInterface
    interface Installer {
        void install(BooleanSupplier active);
    }

    @FunctionalInterface
    interface TaskLauncher {
        Cancellable launch(Runnable runnable);
    }

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }

    private record LoadAttempt(boolean loaded, Throwable error) {
    }
}
