package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.video.MpvLibrary.*;

final class MpvStreamListener implements IVideoListener {
    private static final long TIMEOUT_MS = 10_000;
    private static final long PROPERTY_POLL_INTERVAL_MS = 100;

    private final LibMpv lib;
    private final VideoInfo info;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final MpvProgressClock progressClock = new MpvProgressClock();
    private final Object commandLock = new Object();

    private Thread thread;
    private volatile Pointer handle;
    private Consumer<Boolean> playing = seekable -> {};
    private Runnable stopped = () -> {};
    private Runnable errored = () -> {};
    private Runnable timeout = () -> {};

    MpvStreamListener(VideoInfo info) {
        this.lib = MpvLibrary.get();
        this.info = info;
    }

    static void verifyAvailable() {
        LibMpv lib = MpvLibrary.get();
        Pointer ctx = null;
        try {
            ctx = lib.mpv_create();
            if (ctx == null) throw new IllegalStateException("mpv_create returned null");
        } finally {
            if (ctx != null) {
                lib.mpv_terminate_destroy(ctx);
            }
        }
    }

    @Override
    public long getProgress() {
        if (!isPlaying()) return -1;
        return progressClock.currentProgress();
    }

    @Override
    public void setProgress(long progress) {
        Pointer ctx = handle;
        if (ctx == null || finished.get()) return;
        long target = Math.max(0, progress);
        progressClock.seekTo(target);
        synchronized (commandLock) {
            ctx = handle;
            if (ctx == null || finished.get()) return;
            try {
                command(ctx, "seek", String.format(Locale.ROOT, "%.3f", target / 1000.0), "absolute", "exact");
            } catch (RuntimeException e) {
                VideoPlayerMain.LOGGER.warn("Failed to seek MPV stream listener", e);
            }
        }
    }

    @Override
    public boolean isPlaying() {
        return handle != null && !finished.get();
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        this.playing = playing;
    }

    @Override
    public void stopped(Runnable stopped) {
        this.stopped = stopped;
    }

    @Override
    public void errored(Runnable errored) {
        this.errored = errored;
    }

    @Override
    public void timeout(Runnable timeout) {
        this.timeout = timeout;
    }

    @Override
    public void listen() {
        released.set(false);
        finished.set(false);
        started.set(false);
        progressClock.reset(false);
        thread = new Thread(this::run, "VideoPlayer-MPV-stream");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void cancel() {
        released.set(true);
        synchronized (commandLock) {
            Pointer ctx = handle;
            if (ctx != null) lib.mpv_wakeup(ctx);
        }
    }

    private void run() {
        Pointer ctx = null;
        try {
            ctx = lib.mpv_create();
            if (ctx == null) throw new IllegalStateException("mpv_create returned null");
            handle = ctx;
            setOptionString(ctx, "config", "no");
            setOptionString(ctx, "terminal", "no");
            setOptionString(ctx, "vid", "no");
            setOptionString(ctx, "ao", "null");
            setOptionString(ctx, "mute", "yes");
            check(ctx, lib.mpv_initialize(ctx), "mpv_initialize");
            loadFile(ctx, info.path().replace("rtspt://", "rtsp://"),
                    VideoParams.mpvLoadOptions(info.params(), StreamListener.configuredProxy(), StreamListener.configuredYtdlPath()));

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            long lastPoll = 0;
            while (!released.get() && !finished.get()) {
                Pointer eventPointer = lib.mpv_wait_event(ctx, 0.05);
                if (eventPointer != null) {
                    handleEvent(ctx, new MpvEvent(eventPointer));
                }
                long now = System.currentTimeMillis();
                if (now - lastPoll >= PROPERTY_POLL_INTERVAL_MS) {
                    lastPoll = now;
                    refreshProgress(ctx);
                }
                if (!started.get() && now >= deadline) {
                    completeTimeout();
                }
            }
        } catch (Throwable t) {
            if (!released.get()) {
                VideoPlayerMain.LOGGER.warn("MPV stream listener failed", t);
                completeErrored();
            }
        } finally {
            if (ctx != null) {
                synchronized (commandLock) {
                    try {
                        handle = null;
                        lib.mpv_terminate_destroy(ctx);
                    } catch (RuntimeException e) {
                        VideoPlayerMain.LOGGER.warn("Failed to destroy MPV stream listener", e);
                    }
                }
            }
        }
    }

    private void handleEvent(Pointer ctx, MpvEvent event) {
        switch (event.event_id) {
            case MPV_EVENT_NONE -> {
            }
            case MPV_EVENT_FILE_LOADED -> {
                started.set(true);
                refreshProgress(ctx);
                playing.accept(Boolean.TRUE.equals(getFlag(ctx, "seekable")));
            }
            case MPV_EVENT_END_FILE -> {
                if (event.data != null) {
                    MpvEventEndFile end = new MpvEventEndFile(event.data);
                    if (end.reason == MPV_END_FILE_REASON_EOF) {
                        completeStopped();
                    } else {
                        VideoPlayerMain.LOGGER.warn("MPV stream listener ended early: reason={} error={} message={}",
                                end.reason, end.error, lib.mpv_error_string(end.error));
                        completeErrored();
                    }
                } else {
                    completeStopped();
                }
            }
            case MPV_EVENT_SHUTDOWN -> released.set(true);
            default -> {
            }
        }
    }

    private void refreshProgress(Pointer ctx) {
        Double timePos = getDouble(ctx, "time-pos");
        if (timePos != null && timePos >= 0) {
            progressClock.updateFromTimePos(timePos);
        }
    }

    private void completeStopped() {
        if (!finished.compareAndSet(false, true)) return;
        stopped.run();
    }

    private void completeErrored() {
        if (!finished.compareAndSet(false, true)) return;
        errored.run();
        stopped.run();
    }

    private void completeTimeout() {
        if (!finished.compareAndSet(false, true)) return;
        VideoPlayerMain.LOGGER.warn("MPV stream listener timed out before media loaded");
        timeout.run();
        stopped.run();
    }

    private void command(Pointer ctx, String... args) {
        ArrayList<Memory> strings = new ArrayList<>(args.length);
        Memory argv = new Memory((long) (args.length + 1) * Native.POINTER_SIZE);
        for (int i = 0; i < args.length; i++) {
            Memory string = utf8(args[i]);
            strings.add(string);
            argv.setPointer((long) i * Native.POINTER_SIZE, string);
        }
        argv.setPointer((long) args.length * Native.POINTER_SIZE, null);
        check(ctx, lib.mpv_command(ctx, argv), "mpv_command " + args[0]);
    }

    private void loadFile(Pointer ctx, String path, String loadOptions) {
        if (loadOptions == null || loadOptions.isEmpty()) {
            command(ctx, "loadfile", path, "replace");
            return;
        }
        command(ctx, "loadfile", path, "replace", "-1", loadOptions);
    }

    private void setOptionString(Pointer ctx, String name, String value) {
        check(ctx, lib.mpv_set_option_string(ctx, name, value), "mpv_set_option_string " + name);
    }

    private Double getDouble(Pointer ctx, String name) {
        Memory data = new Memory(Double.BYTES);
        int result = lib.mpv_get_property(ctx, name, MPV_FORMAT_DOUBLE, data);
        return result < 0 ? null : data.getDouble(0);
    }

    private Boolean getFlag(Pointer ctx, String name) {
        Memory data = intMemory(0);
        int result = lib.mpv_get_property(ctx, name, MPV_FORMAT_FLAG, data);
        return result < 0 ? null : data.getInt(0) != 0;
    }

    private static Memory intMemory(int value) {
        Memory data = new Memory(Integer.BYTES);
        data.setInt(0, value);
        return data;
    }

    private void check(Pointer ctx, int result, String operation) {
        if (result >= 0) return;
        throw new IllegalStateException(operation + " failed: " + lib.mpv_error_string(result));
    }
}
