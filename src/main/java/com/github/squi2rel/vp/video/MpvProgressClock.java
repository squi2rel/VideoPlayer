package com.github.squi2rel.vp.video;

final class MpvProgressClock {
    private static final long RAW_JITTER_TOLERANCE_MS = 250;
    private static final long SEEK_SAMPLE_GRACE_MS = 500;

    private long progressMs;
    private long progressUpdateMs;
    private long durationMs;
    private long ignoreRawSamplesUntilMs;
    private float rate = 1f;
    private boolean paused = true;

    synchronized void reset(boolean paused) {
        progressMs = 0;
        progressUpdateMs = System.currentTimeMillis();
        durationMs = 0;
        ignoreRawSamplesUntilMs = 0;
        rate = 1f;
        this.paused = paused;
    }

    synchronized void setPaused(boolean paused) {
        if (this.paused == paused) return;
        long now = System.currentTimeMillis();
        progressMs = currentProgressAt(now);
        progressUpdateMs = now;
        this.paused = paused;
    }

    synchronized void setRate(float rate) {
        if (rate <= 0 || !Float.isFinite(rate) || Math.abs(this.rate - rate) < 0.0001f) return;
        long now = System.currentTimeMillis();
        progressMs = currentProgressAt(now);
        progressUpdateMs = now;
        this.rate = rate;
    }

    synchronized float rate() {
        return rate;
    }

    synchronized void seekTo(long progress) {
        progressMs = Math.max(0, progress);
        progressUpdateMs = System.currentTimeMillis();
        ignoreRawSamplesUntilMs = progressUpdateMs + SEEK_SAMPLE_GRACE_MS;
    }

    synchronized void setDurationMs(long duration) {
        durationMs = Math.max(0, duration);
    }

    synchronized long durationMs() {
        return durationMs;
    }

    synchronized long currentProgress() {
        return currentProgressAt(System.currentTimeMillis());
    }

    synchronized void updateFromTimePos(double timePosSeconds) {
        if (!Double.isFinite(timePosSeconds) || timePosSeconds < 0) return;

        long rawMs = Math.max(0, Math.round(timePosSeconds * 1000));
        long now = System.currentTimeMillis();
        long predicted = currentProgressAt(now);
        if (now < ignoreRawSamplesUntilMs && Math.abs(rawMs - predicted) > RAW_JITTER_TOLERANCE_MS) {
            return;
        }
        if (paused || progressMs <= 0 || Math.abs(rawMs - predicted) > RAW_JITTER_TOLERANCE_MS) {
            progressMs = rawMs;
            progressUpdateMs = now;
        }
    }

    private long currentProgressAt(long now) {
        long base = progressMs;
        if (paused || base <= 0) return base;
        long elapsed = Math.max(0, now - progressUpdateMs);
        long current = base + Math.round(elapsed * rate);
        return durationMs > 0 ? Math.min(current, durationMs) : current;
    }
}
