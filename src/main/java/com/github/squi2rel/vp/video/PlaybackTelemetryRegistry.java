package com.github.squi2rel.vp.video;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlaybackTelemetryRegistry {
    public static final int API_VERSION = 1;
    private static final ConcurrentHashMap<ScreenKey, AtomicInteger> REFERENCES = new ConcurrentHashMap<>();

    private PlaybackTelemetryRegistry() {
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    public static Registration acquire(ScreenKey key) {
        ScreenKey valid = validate(key);
        REFERENCES.compute(valid, (ignored, count) -> {
            AtomicInteger next = count == null ? new AtomicInteger() : count;
            next.incrementAndGet();
            return next;
        });
        return new Registration(valid);
    }

    public static boolean requested(ScreenKey key) {
        AtomicInteger count = key == null ? null : REFERENCES.get(key);
        return count != null && count.get() > 0;
    }

    private static ScreenKey validate(ScreenKey key) {
        if (key == null || key.dimension() == null || key.dimension().isBlank()
                || key.areaName() == null || key.areaName().isBlank()
                || key.screenName() == null || key.screenName().isBlank()) {
            throw new IllegalArgumentException("complete screen key is required");
        }
        return key;
    }

    public static final class Registration implements AutoCloseable {
        private final ScreenKey key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(ScreenKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            REFERENCES.computeIfPresent(key, (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
        }
    }
}
