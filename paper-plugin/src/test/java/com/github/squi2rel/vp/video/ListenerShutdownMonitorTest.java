package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenerShutdownMonitorTest {
    @Test
    void returnsImmediatelyAndCompletesAfterListenersExit() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch completed = new CountDownLatch(1);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                ListenerShutdownMonitor.start("test-listener-shutdown", List.of(active),
                        AtomicBoolean::get, 1_000L, completed::countDown, ignored -> {
                        }));

        active.set(false);
        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void reportsTimeoutOnceButStillCompletesWhenListenerEventuallyExits() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicLong timedOut = new AtomicLong();
        CountDownLatch timeoutReported = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        ListenerShutdownMonitor.start("test-listener-timeout", List.of(active),
                AtomicBoolean::get, 25L, completed::countDown, remaining -> {
                    timedOut.incrementAndGet();
                    timeoutReported.countDown();
                });

        assertTrue(timeoutReported.await(1, java.util.concurrent.TimeUnit.SECONDS));
        active.set(false);
        assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1L, timedOut.get());
    }
}
