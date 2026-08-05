package com.github.squi2rel.vp.video;

import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.concurrent.locks.LockSupport;

final class ListenerShutdownMonitor {
    private static final long POLL_NANOS = 50_000_000L;

    private ListenerShutdownMonitor() {
    }

    static <T> void start(String threadName, List<T> listeners, Predicate<? super T> active,
                          long timeoutMs, Runnable completed, LongConsumer timedOut) {
        List<T> snapshot = List.copyOf(listeners);
        Thread monitor = new Thread(
                () -> monitor(snapshot, active, Math.max(1L, timeoutMs), completed, timedOut),
                threadName
        );
        monitor.setDaemon(true);
        monitor.start();
    }

    private static <T> void monitor(List<T> listeners, Predicate<? super T> active,
                                    long timeoutMs, Runnable completed, LongConsumer timedOut) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        boolean reported = false;
        while (true) {
            long remaining = listeners.stream().filter(active).count();
            if (remaining == 0L) {
                completed.run();
                return;
            }
            if (!reported && System.nanoTime() >= deadline) {
                reported = true;
                timedOut.accept(remaining);
            }
            LockSupport.parkNanos(POLL_NANOS);
            if (Thread.currentThread().isInterrupted()) return;
        }
    }
}
