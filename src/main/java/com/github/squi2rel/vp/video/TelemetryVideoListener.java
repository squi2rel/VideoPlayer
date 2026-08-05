package com.github.squi2rel.vp.video;

import java.util.Objects;
import java.util.function.Consumer;

final class TelemetryVideoListener implements IVideoListener {
    private final IVideoListener playback;
    private final Object lock = new Object();
    private volatile IVideoListener telemetry;
    private boolean listening;

    TelemetryVideoListener(IVideoListener playback, IVideoListener telemetry) {
        this.playback = Objects.requireNonNull(playback, "playback");
        this.telemetry = telemetry;
    }

    @Override
    public long getProgress() {
        return playback.getProgress();
    }

    @Override
    public void setProgress(long progress) {
        playback.setProgress(progress);
        IVideoListener current = telemetry;
        if (current != null) current.setProgress(progress);
    }

    @Override
    public boolean isPlaying() {
        return playback.isPlaying();
    }

    @Override
    public void playing(Consumer<Boolean> playing) {
        playback.playing(playing);
    }

    @Override
    public void stopped(Runnable stopped) {
        playback.stopped(stopped);
    }

    @Override
    public void errored(Runnable errored) {
        playback.errored(errored);
    }

    @Override
    public void timeout(Runnable timeout) {
        playback.timeout(timeout);
    }

    @Override
    public AudioLevelSnapshot audioLevel() {
        IVideoListener current = telemetry;
        return current == null ? AudioLevelSnapshot.unsupported() : current.audioLevel();
    }

    @Override
    public VideoColorSnapshot videoColor() {
        IVideoListener current = telemetry;
        return current == null ? VideoColorSnapshot.unsupported() : current.videoColor();
    }

    @Override
    public void listen() {
        playback.listen();
        IVideoListener current;
        synchronized (lock) {
            listening = true;
            current = telemetry;
        }
        if (current != null) startTelemetry(current);
    }

    @Override
    public void cancel() {
        IVideoListener current;
        synchronized (lock) {
            listening = false;
            current = telemetry;
            telemetry = null;
        }
        try {
            playback.cancel();
        } finally {
            if (current != null) current.cancel();
        }
    }

    boolean attachTelemetry(IVideoListener next) {
        if (next == null) return false;
        IVideoListener previous;
        boolean start;
        synchronized (lock) {
            if (telemetry == next) return false;
            previous = telemetry;
            telemetry = next;
            start = listening;
        }
        if (previous != null) previous.cancel();
        if (start) startTelemetry(next);
        return true;
    }

    boolean detachTelemetry() {
        IVideoListener previous;
        synchronized (lock) {
            previous = telemetry;
            telemetry = null;
        }
        if (previous == null) return false;
        previous.cancel();
        return true;
    }

    private void startTelemetry(IVideoListener target) {
        target.playing(ignored -> {
        });
        target.stopped(() -> {
        });
        target.errored(() -> failTelemetry(target));
        target.timeout(() -> failTelemetry(target));
        long progress = playback.getProgress();
        if (progress >= 0L) target.setProgress(progress);
        try {
            target.listen();
        } catch (Throwable error) {
            failTelemetry(target);
        }
    }

    private void failTelemetry(IVideoListener target) {
        synchronized (lock) {
            if (telemetry != target) return;
            telemetry = null;
        }
        target.cancel();
    }
}
