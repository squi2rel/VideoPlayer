package com.github.squi2rel.vp;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Routes plugin work to the scheduler model exposed by the running server.
 */
public final class FoliaScheduler {
    @FunctionalInterface
    public interface TaskHandle {
        TaskHandle NONE = () -> {
        };

        void cancel();
    }

    private static volatile JavaPlugin owner;
    private static volatile Boolean folia;

    private FoliaScheduler() {
    }

    /**
     * Must be called once at plugin startup before any plugin task is scheduled.
     */
    public static synchronized void initialize(JavaPlugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin is required");
        owner = plugin;
        if (folia == null) {
            folia = detectFolia();
            VideoPlayerMain.LOGGER.info("VideoPlayer scheduler mode: {}", folia ? "Folia" : "Paper");
        }
    }

    public static boolean isFolia() {
        Boolean detected = folia;
        if (detected == null) throw new IllegalStateException("FoliaScheduler has not been initialized");
        return detected;
    }

    public static synchronized void shutdown(JavaPlugin plugin) {
        if (owner != plugin) return;
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
        owner = null;
    }

    public static TaskHandle runGlobal(Runnable runnable) {
        return runGlobal(requireOwner(), runnable);
    }

    public static TaskHandle runGlobal(Plugin taskOwner, Runnable runnable) {
        Plugin plugin = requireTaskOwner(taskOwner);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin, runnable);
        return task::cancel;
    }

    public static TaskHandle runGlobalDelayed(Runnable runnable, long ticks) {
        return runGlobalDelayed(requireOwner(), runnable, ticks);
    }

    public static TaskHandle runGlobalDelayed(Plugin taskOwner, Runnable runnable, long ticks) {
        Plugin plugin = requireTaskOwner(taskOwner);
        long delay = Math.max(1L, ticks);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> runnable.run(), delay);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runGlobalFixedRate(Runnable runnable, long initialDelayTicks, long periodTicks) {
        return runGlobalFixedRate(requireOwner(), runnable, initialDelayTicks, periodTicks);
    }

    public static TaskHandle runGlobalFixedRate(Plugin taskOwner, Runnable runnable, long initialDelayTicks, long periodTicks) {
        Plugin plugin = requireTaskOwner(taskOwner);
        long initialDelay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), initialDelay, period);
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, initialDelay, period);
        return task::cancel;
    }

    public static TaskHandle runAsync(Runnable runnable) {
        return runAsync(requireOwner(), runnable);
    }

    public static TaskHandle runAsync(Plugin taskOwner, Runnable runnable) {
        Plugin plugin = requireTaskOwner(taskOwner);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getAsyncScheduler().runNow(plugin, ignored -> runnable.run());
            return task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        return task::cancel;
    }

    /**
     * Region callbacks are scheduled for a later tick when Folia owns the location.
     */
    public static TaskHandle runAtRegion(Location location, Runnable runnable) {
        return runAtRegionDelayed(location, runnable, 1L);
    }

    public static TaskHandle runAtRegionDelayed(Location location, Runnable runnable, long ticks) {
        return runAtRegionDelayed(requireOwner(), location, runnable, ticks);
    }

    public static TaskHandle runAtRegionDelayed(Plugin taskOwner, Location location, Runnable runnable, long ticks) {
        if (location == null || runnable == null) return TaskHandle.NONE;
        Plugin plugin = requireTaskOwner(taskOwner);
        long delay = minimumEntityDelay(ticks);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(plugin, location, ignored -> runnable.run(), delay);
            return task == null ? TaskHandle.NONE : task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runAtRegionFixedRate(Location location, Runnable runnable, long initialDelayTicks, long periodTicks) {
        return runAtRegionFixedRate(requireOwner(), location, runnable, initialDelayTicks, periodTicks);
    }

    public static TaskHandle runAtRegionFixedRate(Plugin taskOwner, Location location, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (location == null || runnable == null) return TaskHandle.NONE;
        Plugin plugin = requireTaskOwner(taskOwner);
        long initialDelay = minimumEntityDelay(initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(
                    plugin,
                    location,
                    ignored -> runnable.run(),
                    initialDelay,
                    period
            );
            return task == null ? TaskHandle.NONE : task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, initialDelay, period);
        return task::cancel;
    }

    /**
     * Entity callbacks always run on a later tick. This is required for Folia owner handoff.
     */
    public static TaskHandle runAtEntity(Entity entity, Runnable runnable, Runnable retired) {
        return runAtEntityDelayed(entity, runnable, retired, 1L);
    }

    public static TaskHandle runAtEntityDelayed(Entity entity, Runnable runnable, Runnable retired, long ticks) {
        return runAtEntityDelayed(requireOwner(), entity, runnable, retired, ticks);
    }

    public static TaskHandle runAtEntityDelayed(Plugin taskOwner, Entity entity, Runnable runnable, Runnable retired, long ticks) {
        if (entity == null || runnable == null) return TaskHandle.NONE;
        Plugin plugin = requireTaskOwner(taskOwner);
        long delay = minimumEntityDelay(ticks);
        if (isFolia()) {
            ScheduledTask task = entity.getScheduler().runDelayed(plugin, ignored -> runnable.run(), retired, delay);
            return task == null ? TaskHandle.NONE : task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        return task::cancel;
    }

    public static TaskHandle runAtEntityFixedRate(Entity entity, Runnable runnable, Runnable retired, long initialDelayTicks, long periodTicks) {
        return runAtEntityFixedRate(requireOwner(), entity, runnable, retired, initialDelayTicks, periodTicks);
    }

    public static TaskHandle runAtEntityFixedRate(Plugin taskOwner, Entity entity, Runnable runnable, Runnable retired, long initialDelayTicks, long periodTicks) {
        if (entity == null || runnable == null) return TaskHandle.NONE;
        Plugin plugin = requireTaskOwner(taskOwner);
        long initialDelay = minimumEntityDelay(initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, ignored -> runnable.run(), retired, initialDelay, period);
            return task == null ? TaskHandle.NONE : task::cancel;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, initialDelay, period);
        return task::cancel;
    }

    static long minimumEntityDelay(long ticks) {
        return Math.max(1L, ticks);
    }

    private static JavaPlugin requireOwner() {
        JavaPlugin plugin = owner;
        if (plugin == null) throw new IllegalStateException("FoliaScheduler has not been initialized");
        return plugin;
    }

    private static Plugin requireTaskOwner(Plugin taskOwner) {
        if (taskOwner == null) throw new IllegalArgumentException("task owner is required");
        return taskOwner;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, FoliaScheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
