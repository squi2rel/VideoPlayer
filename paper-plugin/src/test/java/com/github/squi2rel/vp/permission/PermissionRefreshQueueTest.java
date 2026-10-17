package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.FoliaScheduler;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionRefreshQueueTest {
    @Test
    void delayedHandleRegistrationCannotReplaceContinuationHandle() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        List<Scheduled> tasks = new ArrayList<>();
        PermissionRefreshQueue queue = new PermissionRefreshQueue((entity, task, retired) -> {
            Scheduled scheduled = new Scheduled(task, retired);
            tasks.add(scheduled);
            if (tasks.size() == 1) task.run();
            return scheduled;
        }, entity -> new PermissionRefreshQueue.Snapshot("world", java.util.Collections.nCopies(8, VideoPermissionContext.global("world"))),
                entity -> "world", (entity, context) -> {}, () -> 0L);
        queue.request(UUID.randomUUID(), player);
        assertEquals(2, tasks.size());
        assertTrue(tasks.get(0).cancelled);
        queue.close();
        assertTrue(tasks.get(1).cancelled);
    }

    @Test
    void failedRefreshReleasesEntryForNextRequest() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        List<Runnable> tasks = new ArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        PermissionRefreshQueue queue = new PermissionRefreshQueue((entity, task, retired) -> {
            tasks.add(task);
            return () -> {};
        }, entity -> {
            snapshots.incrementAndGet();
            throw new IllegalStateException("fixture");
        }, entity -> "world", (entity, context) -> {}, () -> 0L);
        UUID uuid = UUID.randomUUID();
        queue.request(uuid, player);
        tasks.get(0).run();
        queue.request(uuid, player);
        tasks.get(1).run();
        assertEquals(2, snapshots.get());
        queue.close();
    }

    @Test
    void burstsCoalesceAndEachTickHandlesAtMostFourContexts() {
        Fixture fixture = new Fixture(11);
        for (int i = 0; i < 100; i++) fixture.request();
        assertEquals(1, fixture.tasks.size());
        fixture.tick();
        assertEquals(4, fixture.sent.size());
        fixture.tick();
        assertEquals(8, fixture.sent.size());
        fixture.tick();
        assertEquals(11, fixture.sent.size());
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(1, fixture.captures.get());
        fixture.request();
        assertEquals(1, fixture.tasks.size());
        fixture.close();
    }

    @Test
    void budgetYieldsAfterOneExpensiveContext() {
        Fixture fixture = new Fixture(8);
        fixture.clockStep = 3_000_000L;
        fixture.request();
        fixture.tick();
        assertEquals(1, fixture.sent.size());
        assertEquals(1, fixture.tasks.size());
        fixture.close();
    }

    @Test
    void changesDuringRefreshRestartFromFreshSnapshot() {
        Fixture fixture = new Fixture(9);
        fixture.request();
        fixture.tick();
        fixture.world.set("changed");
        fixture.request();
        fixture.tick();
        assertEquals(2, fixture.captures.get());
        assertEquals("changed", fixture.sent.get(4).dimension());
        assertEquals("context0", fixture.sent.get(4).areaName());
        fixture.close();
    }

    @Test
    void worldChangeRecapturesEvenWithoutAnotherRequest() {
        Fixture fixture = new Fixture(8);
        fixture.request();
        fixture.tick();
        fixture.world.set("other-world");
        fixture.tick();
        assertEquals(2, fixture.captures.get());
        assertEquals("other-world", fixture.sent.get(4).dimension());
        fixture.close();
    }

    @Test
    void retirementAndLateCallbacksCannotRemoveReplacementTask() {
        Fixture fixture = new Fixture(2);
        fixture.request();
        Scheduled old = fixture.tasks.remove();
        old.retired.run();
        fixture.request();
        old.retired.run();
        old.task.run();
        assertTrue(fixture.sent.isEmpty());
        fixture.tick();
        assertEquals(2, fixture.sent.size());
        fixture.close();
    }

    @Test
    void quitAndDisableCancelTasksAndRejectLateCallbacks() {
        Fixture fixture = new Fixture(8);
        fixture.request();
        Scheduled quit = fixture.tasks.remove();
        fixture.queue.cancel(fixture.uuid);
        assertTrue(quit.cancelled);
        quit.task.run();
        fixture.request();
        Scheduled disabled = fixture.tasks.remove();
        fixture.close();
        assertTrue(disabled.cancelled);
        disabled.task.run();
        fixture.request();
        assertTrue(fixture.tasks.isEmpty());
        assertTrue(fixture.sent.isEmpty());
    }

    @Test
    void offlinePlayerCleansUpAndCanBeRequestedAgain() {
        Fixture fixture = new Fixture(2);
        when(fixture.player.isOnline()).thenReturn(false);
        fixture.request();
        fixture.tick();
        assertEquals(0, fixture.captures.get());
        when(fixture.player.isOnline()).thenReturn(true);
        fixture.request();
        fixture.tick();
        assertEquals(2, fixture.sent.size());
        fixture.close();
    }

    @Test
    void unscheduledEntityDoesNotLeavePendingEntry() {
        AtomicInteger attempts = new AtomicInteger();
        PermissionRefreshQueue queue = new PermissionRefreshQueue((player, task, retired) -> {
            attempts.incrementAndGet();
            return FoliaScheduler.TaskHandle.NONE;
        }, player -> new PermissionRefreshQueue.Snapshot("world", List.of()), player -> "world", (player, context) -> {}, () -> 0L);
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        queue.request(uuid, player);
        queue.request(uuid, player);
        assertEquals(2, attempts.get());
        queue.close();
    }

    private static final class Fixture implements AutoCloseable {
        private final UUID uuid = UUID.randomUUID();
        private final Player player = mock(Player.class);
        private final ArrayDeque<Scheduled> tasks = new ArrayDeque<>();
        private final List<VideoPermissionContext> sent = new ArrayList<>();
        private final AtomicReference<String> world = new AtomicReference<>("world");
        private final AtomicInteger captures = new AtomicInteger();
        private final AtomicLong clock = new AtomicLong();
        private long clockStep;
        private final PermissionRefreshQueue queue;

        private Fixture(int count) {
            when(player.isOnline()).thenReturn(true);
            queue = new PermissionRefreshQueue((entity, task, retired) -> {
                Scheduled scheduled = new Scheduled(task, retired);
                tasks.add(scheduled);
                return scheduled;
            }, entity -> {
                captures.incrementAndGet();
                List<VideoPermissionContext> contexts = new ArrayList<>();
                for (int i = 0; i < count; i++) contexts.add(new VideoPermissionContext(world.get(), "context" + i, null, null, null, null));
                return new PermissionRefreshQueue.Snapshot(world.get(), contexts);
            }, entity -> world.get(), (entity, context) -> sent.add(context), () -> clock.getAndAdd(clockStep));
        }

        private void request() {
            queue.request(uuid, player);
        }

        private void tick() {
            Scheduled task = tasks.remove();
            if (!task.cancelled) task.task.run();
        }

        @Override
        public void close() {
            queue.close();
        }
    }

    private static final class Scheduled implements FoliaScheduler.TaskHandle {
        private final Runnable task;
        private final Runnable retired;
        private boolean cancelled;

        private Scheduled(Runnable task, Runnable retired) {
            this.task = task;
            this.retired = retired;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
