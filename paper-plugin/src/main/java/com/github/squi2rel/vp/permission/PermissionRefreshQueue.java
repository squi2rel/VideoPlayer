package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.FoliaScheduler;
import com.github.squi2rel.vp.VideoPlayerMain;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class PermissionRefreshQueue implements AutoCloseable {
    private static final int MAX_CONTEXTS_PER_TICK = 4;
    private static final long BUDGET_NANOS = 2_000_000L;
    private final Object lock = new Object();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final EntityTaskScheduler scheduler;
    private final Function<Player, Snapshot> snapshots;
    private final Function<Player, String> world;
    private final BiConsumer<Player, VideoPermissionContext> sender;
    private final LongSupplier clock;
    private boolean closed;

    public PermissionRefreshQueue(EntityTaskScheduler scheduler, Function<Player, Snapshot> snapshots,
                                  Function<Player, String> world, BiConsumer<Player, VideoPermissionContext> sender,
                                  LongSupplier clock) {
        this.scheduler = scheduler;
        this.snapshots = snapshots;
        this.world = world;
        this.sender = sender;
        this.clock = clock;
    }

    public void request(UUID uuid, Player player) {
        Pending created;
        synchronized (lock) {
            if (closed) return;
            Pending existing = pending.get(uuid);
            if (existing != null) {
                existing.dirty = true;
                return;
            }
            created = new Pending();
            pending.put(uuid, created);
        }
        schedule(uuid, player, created);
    }

    public void cancel(UUID uuid) {
        Pending removed;
        synchronized (lock) {
            removed = pending.remove(uuid);
        }
        if (removed != null) removed.task.cancel();
    }

    @Override
    public void close() {
        List<Pending> removed;
        synchronized (lock) {
            closed = true;
            removed = List.copyOf(pending.values());
            pending.clear();
        }
        for (Pending entry : removed) entry.task.cancel();
    }

    private void schedule(UUID uuid, Player player, Pending entry) {
        long ticket;
        synchronized (lock) {
            if (!current(uuid, entry)) return;
            ticket = ++entry.ticket;
        }
        try {
            FoliaScheduler.TaskHandle task = scheduler.schedule(player, () -> run(uuid, player, entry), () -> remove(uuid, entry));
            boolean stale;
            synchronized (lock) {
                stale = !current(uuid, entry) || entry.ticket != ticket;
                if (!stale) entry.task = task;
            }
            if (stale) task.cancel();
            if (task == FoliaScheduler.TaskHandle.NONE) remove(uuid, entry);
        } catch (RuntimeException | Error error) {
            remove(uuid, entry);
            VideoPlayerMain.LOGGER.warn("Failed to schedule VideoPlayer permission refresh", error);
            if (error instanceof Error fatal) throw fatal;
        }
    }

    private void run(UUID uuid, Player player, Pending entry) {
        try {
            synchronized (lock) {
                if (!current(uuid, entry)) return;
                entry.task = FoliaScheduler.TaskHandle.NONE;
            }
            if (!player.isOnline()) {
                remove(uuid, entry);
                return;
            }
            boolean restart;
            synchronized (lock) {
                restart = entry.dirty;
                entry.dirty = false;
            }
            if (restart || entry.snapshot == null || !entry.snapshot.world().equals(world.apply(player))) {
                entry.snapshot = snapshots.apply(player);
                entry.index = 0;
            }
            long started = clock.getAsLong();
            int processed = 0;
            while (entry.index < entry.snapshot.contexts().size() && processed < MAX_CONTEXTS_PER_TICK) {
                synchronized (lock) {
                    if (!current(uuid, entry)) return;
                    if (entry.dirty) break;
                }
                sender.accept(player, entry.snapshot.contexts().get(entry.index++));
                processed++;
                if (clock.getAsLong() - started >= BUDGET_NANOS) break;
            }
            synchronized (lock) {
                if (!current(uuid, entry)) return;
                if (!entry.dirty && entry.index >= entry.snapshot.contexts().size()) {
                    pending.remove(uuid);
                    return;
                }
            }
            schedule(uuid, player, entry);
        } catch (RuntimeException | Error error) {
            remove(uuid, entry);
            VideoPlayerMain.LOGGER.warn("Failed to refresh VideoPlayer permissions", error);
            if (error instanceof Error fatal) throw fatal;
        }
    }

    private boolean current(UUID uuid, Pending entry) {
        return !closed && pending.get(uuid) == entry;
    }

    private void remove(UUID uuid, Pending entry) {
        synchronized (lock) {
            pending.remove(uuid, entry);
        }
    }

    @FunctionalInterface
    public interface EntityTaskScheduler {
        FoliaScheduler.TaskHandle schedule(Player player, Runnable task, Runnable retired);
    }

    public record Snapshot(String world, List<VideoPermissionContext> contexts) {
        public Snapshot {
            contexts = List.copyOf(contexts);
        }
    }

    private static final class Pending {
        private volatile FoliaScheduler.TaskHandle task = FoliaScheduler.TaskHandle.NONE;
        private boolean dirty;
        private long ticket;
        private Snapshot snapshot;
        private int index;
    }
}
