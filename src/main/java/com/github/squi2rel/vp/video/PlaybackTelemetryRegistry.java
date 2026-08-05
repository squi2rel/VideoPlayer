package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.VideoPlayerMain;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class PlaybackTelemetryRegistry {
    public static final int API_VERSION = 1;
    static final int MAX_REQUESTED_SCREENS = 1024;
    private static final Object REFERENCE_LOCK = new Object();
    private static final ConcurrentHashMap<ScreenKey, AtomicInteger> REFERENCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ScreenKey, CopyOnWriteArraySet<Target>> BINDINGS = new ConcurrentHashMap<>();

    private PlaybackTelemetryRegistry() {
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    public static Registration acquire(ScreenKey key) {
        ScreenKey valid = validate(key);
        synchronized (REFERENCE_LOCK) {
            AtomicInteger count = REFERENCES.get(valid);
            if (count == null) {
                if (REFERENCES.size() >= MAX_REQUESTED_SCREENS) {
                    throw new IllegalStateException("playback telemetry screen limit exceeded");
                }
                count = new AtomicInteger();
                REFERENCES.put(valid, count);
            }
            if (count.incrementAndGet() == 1) publish(valid, true);
        }
        return new Registration(valid);
    }

    public static boolean requested(ScreenKey key) {
        AtomicInteger count = key == null ? null : REFERENCES.get(key);
        return count != null && count.get() > 0;
    }

    static Binding bind(ScreenKey key, Consumer<Boolean> consumer) {
        ScreenKey valid = validate(key);
        Target target = new Target(Objects.requireNonNull(consumer, "consumer"));
        BINDINGS.computeIfAbsent(valid, ignored -> new CopyOnWriteArraySet<>()).add(target);
        target.publish(requested(valid));
        return new Binding(valid, target);
    }

    private static ScreenKey validate(ScreenKey key) {
        if (key == null || key.dimension() == null || key.dimension().isBlank()
                || key.areaName() == null || key.areaName().isBlank()
                || key.screenName() == null || key.screenName().isBlank()) {
            throw new IllegalArgumentException("complete screen key is required");
        }
        return key;
    }

    private static void publish(ScreenKey key, boolean requested) {
        CopyOnWriteArraySet<Target> targets = BINDINGS.get(key);
        if (targets == null) return;
        for (Target target : targets) target.publish(requested);
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
            synchronized (REFERENCE_LOCK) {
                AtomicInteger count = REFERENCES.get(key);
                if (count != null && count.decrementAndGet() <= 0) {
                    REFERENCES.remove(key, count);
                    publish(key, false);
                }
            }
        }
    }

    static final class Binding implements AutoCloseable {
        private final ScreenKey key;
        private final Target target;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Binding(ScreenKey key, Target target) {
            this.key = key;
            this.target = target;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            BINDINGS.computeIfPresent(key, (ignored, targets) -> {
                targets.remove(target);
                return targets.isEmpty() ? null : targets;
            });
        }
    }

    private static final class Target {
        private final Consumer<Boolean> consumer;
        private final AtomicReference<Boolean> last = new AtomicReference<>();

        private Target(Consumer<Boolean> consumer) {
            this.consumer = consumer;
        }

        private void publish(boolean state) {
            Boolean previous = last.getAndSet(state);
            if (previous != null && previous == state) return;
            try {
                consumer.accept(state);
            } catch (RuntimeException error) {
                VideoPlayerMain.LOGGER.warn("Playback telemetry binding failed for state {}", state, error);
            }
        }
    }
}
