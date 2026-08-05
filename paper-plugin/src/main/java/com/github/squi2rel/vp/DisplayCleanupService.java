package com.github.squi2rel.vp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class DisplayCleanupService {
    public static final int API_VERSION = 1;
    private static final int MAX_GROUPS = 32;
    private static final int MAX_RECORDS_PER_GROUP = 4096;
    private static volatile DisplayCleanupService current;

    private final CleanupExecutor executor;
    private final ReentrantLock lock = new ReentrantLock();
    private final HashMap<UUID, HashMap<UUID, DisplayRecord>> groups = new HashMap<>();

    DisplayCleanupService(CleanupExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    public static UUID openGroup() {
        return UUID.randomUUID();
    }

    public static boolean available() {
        return current != null;
    }

    public static boolean track(UUID groupId, DisplayRecord record) {
        DisplayCleanupService service = current;
        return service != null && service.trackRecord(groupId, record);
    }

    public static boolean untrack(UUID groupId, UUID entityId) {
        DisplayCleanupService service = current;
        return service != null && service.untrackRecord(groupId, entityId);
    }

    public static int cleanup(UUID groupId) {
        DisplayCleanupService service = current;
        return service == null ? 0 : service.cleanupGroup(groupId);
    }

    static synchronized void initialize(CleanupExecutor executor) {
        DisplayCleanupService previous = current;
        current = new DisplayCleanupService(executor);
        if (previous != null) previous.close();
    }

    static synchronized void initialize(Plugin plugin) {
        initialize(new BukkitCleanupExecutor(plugin));
    }

    static synchronized void shutdown() {
        DisplayCleanupService previous = current;
        current = null;
        if (previous != null) previous.close();
    }

    boolean trackRecord(UUID groupId, DisplayRecord record) {
        if (groupId == null) throw new IllegalArgumentException("group id is required");
        Objects.requireNonNull(record, "record");
        lock.lock();
        try {
            HashMap<UUID, DisplayRecord> records = groups.get(groupId);
            if (records == null) {
                if (groups.size() >= MAX_GROUPS) throw new IllegalStateException("display cleanup group limit exceeded");
                records = new HashMap<>();
                groups.put(groupId, records);
            }
            if (!records.containsKey(record.entityId()) && records.size() >= MAX_RECORDS_PER_GROUP) {
                throw new IllegalStateException("display cleanup record limit exceeded");
            }
            return records.put(record.entityId(), record) == null;
        } finally {
            lock.unlock();
        }
    }

    boolean untrackRecord(UUID groupId, UUID entityId) {
        if (groupId == null || entityId == null) return false;
        lock.lock();
        try {
            HashMap<UUID, DisplayRecord> records = groups.get(groupId);
            if (records == null || records.remove(entityId) == null) return false;
            if (records.isEmpty()) groups.remove(groupId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    int cleanupGroup(UUID groupId) {
        if (groupId == null) return 0;
        List<DisplayRecord> records;
        lock.lock();
        try {
            HashMap<UUID, DisplayRecord> removed = groups.remove(groupId);
            if (removed == null || removed.isEmpty()) return 0;
            records = List.copyOf(removed.values());
        } finally {
            lock.unlock();
        }
        for (DisplayRecord record : records) {
            try {
                executor.dispatch(record);
            } catch (RuntimeException error) {
                VideoPlayerMain.LOGGER.warn("Failed to dispatch display cleanup for {}", record.entityId(), error);
            }
        }
        return records.size();
    }

    int trackedRecords() {
        lock.lock();
        try {
            int total = 0;
            for (HashMap<UUID, DisplayRecord> records : groups.values()) total += records.size();
            return total;
        } finally {
            lock.unlock();
        }
    }

    private void clear() {
        lock.lock();
        try {
            groups.clear();
        } finally {
            lock.unlock();
        }
    }

    private void close() {
        clear();
        executor.close();
    }

    @FunctionalInterface
    interface CleanupExecutor extends AutoCloseable {
        void dispatch(DisplayRecord record);

        @Override
        default void close() {
        }
    }

    public record DisplayRecord(UUID entityId, String worldKey, double x, double y, double z, String markerKey) {
        public DisplayRecord {
            if (entityId == null) throw new IllegalArgumentException("entity id is required");
            worldKey = validateText(worldKey, "world key");
            markerKey = validateText(markerKey, "marker key");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("display coordinates must be finite");
            }
        }

        private static String validateText(String value, String name) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return value;
        }
    }

    private static final class BukkitCleanupExecutor implements CleanupExecutor {
        private final Plugin plugin;
        private final Set<FoliaScheduler.TaskHandle> tasks = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        private BukkitCleanupExecutor(Plugin plugin) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
        }

        @Override
        public void dispatch(DisplayRecord record) {
            if (closed.get() || !plugin.isEnabled()) return;
            AtomicReference<FoliaScheduler.TaskHandle> reference = new AtomicReference<>(FoliaScheduler.TaskHandle.NONE);
            FoliaScheduler.TaskHandle task = FoliaScheduler.runGlobal(plugin, () -> {
                tasks.remove(reference.get());
                scheduleRegion(record);
            });
            reference.set(task);
            tasks.add(task);
        }

        private void scheduleRegion(DisplayRecord record) {
            if (closed.get() || !plugin.isEnabled()) return;
            NamespacedKey worldKey = NamespacedKey.fromString(record.worldKey());
            NamespacedKey markerKey = NamespacedKey.fromString(record.markerKey());
            World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
            if (world == null || markerKey == null) return;
            Location location = new Location(world, record.x(), record.y(), record.z());
            AtomicReference<FoliaScheduler.TaskHandle> reference = new AtomicReference<>(FoliaScheduler.TaskHandle.NONE);
            FoliaScheduler.TaskHandle task = FoliaScheduler.runAtRegionDelayed(plugin, location, () -> {
                tasks.remove(reference.get());
                cleanupAtRegion(record, location, markerKey);
            }, 1L);
            reference.set(task);
            if (task != FoliaScheduler.TaskHandle.NONE) tasks.add(task);
        }

        private void cleanupAtRegion(DisplayRecord record, Location location, NamespacedKey markerKey) {
            if (closed.get() || !location.isChunkLoaded()) return;
            Entity entity = location.getWorld().getEntity(record.entityId());
            if (entity == null) return;
            if (FoliaScheduler.isFolia() && !Bukkit.isOwnedByCurrentRegion(entity)) {
                scheduleEntity(record, entity, markerKey);
                return;
            }
            removeMarked(entity, record.entityId(), markerKey);
        }

        private void scheduleEntity(DisplayRecord record, Entity entity, NamespacedKey markerKey) {
            if (closed.get() || !plugin.isEnabled()) return;
            AtomicReference<FoliaScheduler.TaskHandle> reference = new AtomicReference<>(FoliaScheduler.TaskHandle.NONE);
            FoliaScheduler.TaskHandle task = FoliaScheduler.runAtEntityDelayed(
                    plugin,
                    entity,
                    () -> {
                        tasks.remove(reference.get());
                        removeMarked(entity, record.entityId(), markerKey);
                    },
                    () -> tasks.remove(reference.get()),
                    1L
            );
            reference.set(task);
            if (task != FoliaScheduler.TaskHandle.NONE) tasks.add(task);
        }

        private void removeMarked(Entity entity, UUID entityId, NamespacedKey markerKey) {
            if (!entity.getUniqueId().equals(entityId)) return;
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) entity.remove();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            for (FoliaScheduler.TaskHandle task : List.copyOf(tasks)) task.cancel();
            tasks.clear();
        }
    }
}
