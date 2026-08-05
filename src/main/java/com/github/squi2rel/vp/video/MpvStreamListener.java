package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.provider.MediaAddressPolicy;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.MpvLibrary.LibMpv;
import com.github.squi2rel.vp.video.MpvLibrary.MpvEvent;
import com.github.squi2rel.vp.video.MpvLibrary.MpvEventEndFile;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.video.MpvLibrary.*;

final class MpvStreamListener implements IVideoListener {
    private static final long TIMEOUT_MS = 30_000;
    private static final long PROPERTY_POLL_INTERVAL_MS = 100;
    private static final String AUDIO_METER_LABEL = "videoplayer_audio_meter";
    private static final String AUDIO_METER_FILTER = "@" + AUDIO_METER_LABEL + ":lavfi=[astats=metadata=1:reset=1]";
    private static final String COLOR_METER_LABEL = "videoplayer_color_meter";
    private static final String COLOR_METER_FILTER = "@" + COLOR_METER_LABEL
            + ":lavfi=[fps=10,scale=32:18:flags=area,format=pix_fmts=yuv444p,signalstats]";
    private static final Set<MpvStreamListener> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final MpvTelemetryPermitPool TELEMETRY_PERMITS = new MpvTelemetryPermitPool(32);
    private static final long SHUTDOWN_MONITOR_MS = 5_000L;

    private final LibMpv lib;
    private final VideoInfo info;
    private final boolean telemetry;
    private final boolean audioAvailable;
    private final boolean videoAvailable;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean telemetryPermit = new AtomicBoolean(false);
    private final MpvProgressClock progressClock = new MpvProgressClock();
    private final MpvPendingSeek pendingSeek = new MpvPendingSeek();
    private final Object commandLock = new Object();

    private Thread thread;
    private volatile Pointer handle;
    private volatile AudioLevelSnapshot audioLevel = AudioLevelSnapshot.unsupported();
    private volatile VideoColorSnapshot videoColor = VideoColorSnapshot.unsupported();
    private Consumer<Boolean> playing = seekable -> {};
    private Runnable stopped = () -> {};
    private Runnable errored = () -> {};
    private Runnable timeout = () -> {};

    MpvStreamListener(VideoInfo info, boolean telemetry) {
        this.lib = MpvLibrary.get();
        this.info = info;
        this.telemetry = telemetry;
        boolean audioOnly = info != null && (VideoParams.isAudioOnly(info.params())
                || VideoParams.looksAudioOnlyPath(info.path()) || VideoParams.looksAudioOnlyPath(info.rawPath()));
        boolean videoOnly = info != null && VideoParams.isVideoOnly(info.params());
        this.audioAvailable = !videoOnly;
        this.videoAvailable = !audioOnly;
    }

    static void verifyAvailable() {
        LibMpv lib = MpvLibrary.get();
        Pointer ctx = null;
        try {
            ctx = lib.mpv_create();
            if (ctx == null) throw new IllegalStateException("mpv_create returned null");
            setOptionString(lib, ctx, "config", "no");
            setOptionString(lib, ctx, "terminal", "no");
            setOptionString(lib, ctx, "vid", "no");
            setOptionString(lib, ctx, "ao", "null");
            setOptionString(lib, ctx, "mute", "yes");
            int result = lib.mpv_initialize(ctx);
            if (result < 0) {
                throw new IllegalStateException("mpv_initialize failed: " + lib.mpv_error_string(result));
            }
        } finally {
            if (ctx != null) {
                lib.mpv_terminate_destroy(ctx);
            }
        }
    }

    static void shutdown() {
        List<MpvStreamListener> listeners = List.copyOf(ACTIVE);
        for (MpvStreamListener listener : listeners) listener.cancel();
        ListenerShutdownMonitor.start(
                "VideoPlayer-MPV-shutdown-monitor",
                listeners,
                ACTIVE::contains,
                SHUTDOWN_MONITOR_MS,
                () -> {
                },
                remaining -> VideoPlayerMain.LOGGER.warn(
                        "{} MPV stream listener(s) did not exit within {} ms",
                        remaining, SHUTDOWN_MONITOR_MS
                )
        );
    }

    private static void setOptionString(LibMpv lib, Pointer ctx, String name, String value) {
        int result = lib.mpv_set_option_string(ctx, name, value);
        if (result < 0) {
            throw new IllegalStateException("mpv_set_option_string " + name + " failed: " + lib.mpv_error_string(result));
        }
    }

    @Override
    public long getProgress() {
        if (!isPlaying()) return -1;
        return progressClock.currentProgress();
    }

    @Override
    public void setProgress(long progress) {
        long target = Math.max(0, progress);
        pendingSeek.request(target);
        progressClock.seekTo(target);
        Pointer ctx = handle;
        if (ctx == null || finished.get()) return;
        synchronized (commandLock) {
            ctx = handle;
            if (ctx == null || finished.get()) return;
            try {
                command(ctx, "seek", String.format(Locale.ROOT, "%.3f", target / 1000.0), "absolute", "exact");
                pendingSeek.clearIf(target);
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
    public AudioLevelSnapshot audioLevel() {
        return audioLevel;
    }

    @Override
    public VideoColorSnapshot videoColor() {
        return videoColor;
    }

    @Override
    public synchronized void listen() {
        if (telemetry) {
            if (!telemetryPermit.compareAndSet(false, true)) {
                throw new IllegalStateException("MPV telemetry listener is already active");
            }
            if (!TELEMETRY_PERMITS.acquire()) {
                telemetryPermit.set(false);
                throw new IllegalStateException("MPV telemetry listener limit exceeded");
            }
        }
        try {
            released.set(false);
            finished.set(false);
            started.set(false);
            progressClock.reset(false);
            resetTelemetry();
            ACTIVE.add(this);
            thread = new Thread(this::run, "VideoPlayer-MPV-stream");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error error) {
            ACTIVE.remove(this);
            releaseTelemetryPermit();
            throw error;
        }
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
            if (info == null || info.path().isBlank() || !MediaAddressPolicy.isAllowed(info.path())
                    || VideoParams.hasDisallowedMediaUrls(info.params())) {
                throw new IllegalArgumentException("Media address is not allowed");
            }
            setOptionString(ctx, "config", "no");
            setOptionString(ctx, "terminal", "no");
            if (telemetry && videoAvailable) {
                setOptionString(ctx, "vo", "null");
                setOptionString(ctx, "vf", COLOR_METER_FILTER);
            } else {
                setOptionString(ctx, "vid", "no");
            }
            setOptionString(ctx, "ao", "null");
            setOptionString(ctx, "mute", "yes");
            if (telemetry && audioAvailable) setOptionString(ctx, "af", AUDIO_METER_FILTER);
            setOptionString(ctx, "network-timeout", "30");
            check(ctx, lib.mpv_initialize(ctx), "mpv_initialize");
            loadFile(ctx, VideoParams.normalizeStreamPath(info.path()),
                    VideoParams.mpvLoadOptionsForPath(info.path(), info.params(), StreamListener.configuredProxy(),
                            StreamListener.configuredYtdlPath()));

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
                    refreshProperties(ctx);
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
            ACTIVE.remove(this);
            releaseTelemetryPermit();
        }
    }

    private void releaseTelemetryPermit() {
        if (telemetryPermit.compareAndSet(true, false)) TELEMETRY_PERMITS.release();
    }

    private void handleEvent(Pointer ctx, MpvEvent event) {
        switch (event.event_id) {
            case MPV_EVENT_NONE -> {
            }
            case MPV_EVENT_FILE_LOADED -> {
                started.set(true);
                long target = pendingSeek.consume();
                if (target >= 0L) {
                    command(ctx, "seek", String.format(Locale.ROOT, "%.3f", target / 1000.0), "absolute", "exact");
                    progressClock.seekTo(target);
                }
                refreshProperties(ctx);
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

    private void refreshProperties(Pointer ctx) {
        refreshProgress(ctx);
        if (!telemetry) return;
        long now = System.currentTimeMillis();
        if (!audioAvailable) {
            audioLevel = AudioLevelSnapshot.noAudio();
        } else {
            String metadata = getString(ctx, "af-metadata/" + AUDIO_METER_LABEL);
            audioLevel = updateAudioSnapshot(audioLevel, metadata, now);
        }
        if (!videoAvailable) {
            videoColor = VideoColorSnapshot.noVideo();
        } else {
            String metadata = getString(ctx, "vf-metadata/" + COLOR_METER_LABEL);
            videoColor = updateColorSnapshot(
                    videoColor,
                    metadata,
                    getString(ctx, "video-params/colormatrix"),
                    getString(ctx, "video-params/colorlevels"),
                    now
            );
        }
    }

    static AudioLevelSnapshot updateAudioSnapshot(AudioLevelSnapshot current, String metadata, long sampledAtMs) {
        return metadata == null ? current : MpvAudioLevelParser.parse(metadata, sampledAtMs);
    }

    static VideoColorSnapshot updateColorSnapshot(VideoColorSnapshot current, String metadata,
                                                   String colorMatrix, String colorLevels, long sampledAtMs) {
        return metadata == null ? current : MpvFrameColorParser.parse(
                metadata, colorMatrix, colorLevels, sampledAtMs
        );
    }

    private void resetTelemetry() {
        audioLevel = telemetry ? audioAvailable ? AudioLevelSnapshot.waiting() : AudioLevelSnapshot.noAudio()
                : AudioLevelSnapshot.unsupported();
        videoColor = telemetry ? videoAvailable ? VideoColorSnapshot.waiting() : VideoColorSnapshot.noVideo()
                : VideoColorSnapshot.unsupported();
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

    private String getString(Pointer ctx, String name) {
        PointerByReference reference = new PointerByReference();
        int result = lib.mpv_get_property(ctx, name, MPV_FORMAT_STRING, reference.getPointer());
        if (result < 0) return null;
        Pointer value = reference.getValue();
        if (value == null) return null;
        try {
            return value.getString(0, StandardCharsets.UTF_8.name());
        } finally {
            lib.mpv_free(value);
        }
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
