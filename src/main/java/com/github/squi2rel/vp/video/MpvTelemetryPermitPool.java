package com.github.squi2rel.vp.video;

import java.util.concurrent.atomic.AtomicInteger;

final class MpvTelemetryPermitPool {
    private final int capacity;
    private final AtomicInteger used = new AtomicInteger();

    MpvTelemetryPermitPool(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    boolean acquire() {
        while (true) {
            int current = used.get();
            if (current >= capacity) return false;
            if (used.compareAndSet(current, current + 1)) return true;
        }
    }

    void release() {
        while (true) {
            int current = used.get();
            if (current == 0 || used.compareAndSet(current, current - 1)) return;
        }
    }

    int available() {
        return capacity - used.get();
    }
}
