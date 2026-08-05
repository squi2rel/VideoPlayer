package com.github.squi2rel.vp.video;

import java.util.concurrent.atomic.AtomicLong;

final class MpvPendingSeek {
    private final AtomicLong progress = new AtomicLong(-1L);

    void request(long progressMs) {
        progress.set(Math.max(0L, progressMs));
    }

    long peek() {
        return progress.get();
    }

    long consume() {
        return progress.getAndSet(-1L);
    }

    void clearIf(long progressMs) {
        progress.compareAndSet(progressMs, -1L);
    }
}
