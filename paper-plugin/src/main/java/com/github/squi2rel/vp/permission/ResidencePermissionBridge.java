package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.FoliaScheduler;
import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.network.ServerPacketHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ResidencePermissionBridge {
    private static final long RETRY_DELAY_MILLIS = 30_000L;
    private static final Object LOCK = new Object();
    private static final AreaPermissionResolver RESOLVER = new AreaPermissionResolver() {
        @Override
        public AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
            return ResidencePermissionBridge.resolve(player, action, context);
        }

        @Override
        public AreaPermissionResolver prepare(VideoPermissionPlayer player, VideoPermissionContext context) {
            Binding current = currentBinding();
            try {
                AreaPermissionResolver prepared = current.delegate().prepare(player, context);
                return (checkedPlayer, action, checkedContext) -> {
                    if (binding != current) return ResidencePermissionBridge.resolve(checkedPlayer, action, checkedContext);
                    try {
                        return prepared.resolve(checkedPlayer, action, checkedContext);
                    } catch (Throwable error) {
                        fail(current, error);
                        return FAILED.resolve(checkedPlayer, action, checkedContext);
                    }
                };
            } catch (Throwable error) {
                fail(current, error);
                return FAILED;
            }
        }
    };

    public enum State {
        ABSENT_ALLOW,
        ACTIVE,
        FAILED_DENY
    }

    enum Coverage {
        UNKNOWN,
        WILDERNESS,
        PROTECTED
    }

    private static final Delegate ABSENT = new Delegate() {
        @Override
        public AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
            return AreaPermissionDecision.NOT_APPLICABLE;
        }

        @Override
        public AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
            return AreaPermissionDecision.NOT_APPLICABLE;
        }
    };
    private static final Delegate FAILED = new Delegate() {
        @Override
        public AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
            if (onlineAdministrator(player)) return AreaPermissionDecision.ALLOW;
            return failedDecision(classify(lastKnownDelegate, player, context));
        }

        @Override
        public AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
            if (onlineAdministrator(player)) return AreaPermissionDecision.ALLOW;
            return failedDecision(classifyBounds(lastKnownDelegate, player, first, second));
        }
    };

    private static volatile Binding binding = new Binding(State.ABSENT_ALLOW, ABSENT);
    private static volatile Delegate lastKnownDelegate = ABSENT;
    private static long nextRetryAt;
    private static boolean retryScheduled;
    private static boolean permissionCacheRefreshScheduled;
    private static long lifecycleEpoch;
    private static JavaPlugin owner;
    private static ResidenceLifecycleListener lifecycleListener;
    private static ResidenceFlagListener residenceFlagListener;
    private static Plugin residenceFlagListenerPlugin;
    private static boolean lifecycleListenerRegistered;
    private static boolean lifecycleListenerFailed;
    private static FoliaScheduler.TaskHandle retryTask = FoliaScheduler.TaskHandle.NONE;
    private static FoliaScheduler.TaskHandle permissionCacheRefreshTask = FoliaScheduler.TaskHandle.NONE;

    private ResidencePermissionBridge() {
    }

    public static void initialize(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");

        JavaPlugin previous;
        synchronized (LOCK) {
            previous = owner;
        }
        if (previous != null && previous != plugin) {
            VideoPlayerMain.LOGGER.warn("Replacing an unclosed Residence permission bridge instance");
            shutdown();
        }

        long expectedEpoch;
        synchronized (LOCK) {
            if (owner == null) {
                owner = plugin;
                lifecycleEpoch++;
            }
            expectedEpoch = lifecycleEpoch;
        }
        registerLifecycleListener(expectedEpoch);
        refresh(expectedEpoch);
    }

    public static void shutdown() {
        FoliaScheduler.TaskHandle scheduledRetry;
        FoliaScheduler.TaskHandle scheduledCacheRefresh;
        ResidenceLifecycleListener registeredLifecycleListener;
        ResidenceFlagListener registeredFlagListener;
        synchronized (LOCK) {
            lifecycleEpoch++;
            scheduledRetry = retryTask;
            scheduledCacheRefresh = permissionCacheRefreshTask;
            registeredLifecycleListener = lifecycleListener;
            registeredFlagListener = residenceFlagListener;

            retryTask = FoliaScheduler.TaskHandle.NONE;
            permissionCacheRefreshTask = FoliaScheduler.TaskHandle.NONE;
            retryScheduled = false;
            permissionCacheRefreshScheduled = false;
            nextRetryAt = 0L;
            binding = new Binding(State.ABSENT_ALLOW, ABSENT);
            lastKnownDelegate = ABSENT;
            owner = null;
            lifecycleListener = null;
            residenceFlagListener = null;
            residenceFlagListenerPlugin = null;
            lifecycleListenerRegistered = false;
            lifecycleListenerFailed = false;
        }
        cancel(scheduledRetry);
        cancel(scheduledCacheRefresh);
        unregister(registeredLifecycleListener);
        unregister(registeredFlagListener);
    }

    public static State state() {
        return binding.state();
    }

    public static AreaPermissionResolver resolver() {
        return RESOLVER;
    }

    public static AreaPermissionDecision resolve(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
        Binding current = currentBinding();
        try {
            return current.delegate().resolve(player, action, context);
        } catch (Throwable error) {
            fail(current, error);
            return FAILED.resolve(player, action, context);
        }
    }

    public static AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        Binding current = currentBinding();
        try {
            return current.delegate().resolveBounds(player, action, first, second);
        } catch (Throwable error) {
            fail(current, error);
            return FAILED.resolveBounds(player, action, first, second);
        }
    }

    public static boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
        return resolve(player, action, context) != AreaPermissionDecision.DENY;
    }

    public static boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        return resolveBounds(player, action, first, second) != AreaPermissionDecision.DENY;
    }

    private static void refresh(long expectedEpoch) {
        boolean listenerFailed;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch)) return;
            listenerFailed = lifecycleListenerFailed;
        }

        Plugin residence;
        try {
            residence = Bukkit.getPluginManager().getPlugin("Residence");
        } catch (Throwable error) {
            refreshFailed(expectedEpoch, error);
            return;
        }
        if (residence == null) {
            unregisterResidenceFlagListener(expectedEpoch);
            update(expectedEpoch, new Binding(State.ABSENT_ALLOW, ABSENT));
            return;
        }
        if (!residence.isEnabled() || listenerFailed) {
            unregisterResidenceFlagListener(expectedEpoch);
            update(expectedEpoch, new Binding(State.FAILED_DENY, FAILED));
            return;
        }
        try {
            Delegate delegate = (Delegate) Class.forName(
                    "com.github.squi2rel.vp.permission.ResidencePermissionHook",
                    true,
                    ResidencePermissionBridge.class.getClassLoader()
            ).getDeclaredConstructor().newInstance();
            registerResidenceFlagListener(residence, expectedEpoch);
            if (!residence.isEnabled()) {
                unregisterResidenceFlagListener(expectedEpoch);
                update(expectedEpoch, new Binding(State.FAILED_DENY, FAILED));
                return;
            }
            update(expectedEpoch, new Binding(State.ACTIVE, delegate));
        } catch (Throwable error) {
            refreshFailed(expectedEpoch, error);
        }
    }

    private static void refreshFailed(long expectedEpoch, Throwable error) {
        if (!active(expectedEpoch)) return;
        VideoPlayerMain.LOGGER.warn("Failed to initialize Residence integration; protected area actions will be denied", error);
        unregisterResidenceFlagListener(expectedEpoch);
        update(expectedEpoch, new Binding(State.FAILED_DENY, FAILED));
    }

    private static void fail(Binding failedBinding, Throwable error) {
        long expectedEpoch;
        synchronized (LOCK) {
            if (binding != failedBinding || owner == null) return;
            expectedEpoch = lifecycleEpoch;
        }
        VideoPlayerMain.LOGGER.warn("Residence permission check failed; protected area actions will be denied", error);
        update(expectedEpoch, new Binding(State.FAILED_DENY, FAILED));
    }

    private static void update(long expectedEpoch, Binding next) {
        FoliaScheduler.TaskHandle scheduledRetry = FoliaScheduler.TaskHandle.NONE;
        State previousState;
        boolean stateChanged;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch)) return;
            previousState = binding.state();
            binding = next;
            if (next.state() == State.ACTIVE) {
                lastKnownDelegate = next.delegate();
            } else if (next.state() == State.ABSENT_ALLOW) {
                lastKnownDelegate = ABSENT;
            }
            if (next.state() == State.FAILED_DENY) {
                if (nextRetryAt == 0L) nextRetryAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
            } else {
                nextRetryAt = 0L;
                retryScheduled = false;
                scheduledRetry = retryTask;
                retryTask = FoliaScheduler.TaskHandle.NONE;
            }
            stateChanged = previousState != next.state();
        }
        cancel(scheduledRetry);
        if (stateChanged) {
            VideoPlayerMain.LOGGER.info("Residence permission integration state: {}", next.state());
            schedulePermissionCacheRefresh(expectedEpoch);
        }
    }

    private static Binding currentBinding() {
        Binding current = binding;
        if (current.state() == State.FAILED_DENY) scheduleRetry(System.currentTimeMillis());
        return current;
    }

    private static void scheduleRetry(long now) {
        long expectedEpoch;
        synchronized (LOCK) {
            if (owner == null || binding.state() != State.FAILED_DENY || retryScheduled || now < nextRetryAt) return;
            if (!owner.isEnabled()) return;
            nextRetryAt = now + RETRY_DELAY_MILLIS;
            retryScheduled = true;
            expectedEpoch = lifecycleEpoch;
        }
        try {
            FoliaScheduler.TaskHandle task = FoliaScheduler.runGlobal(() -> retryFailed(expectedEpoch));
            boolean stale;
            synchronized (LOCK) {
                stale = !activeLocked(expectedEpoch) || !retryScheduled;
                if (!stale) retryTask = task;
            }
            if (stale) cancel(task);
        } catch (Throwable error) {
            boolean report;
            synchronized (LOCK) {
                report = activeLocked(expectedEpoch);
                if (report) {
                    retryScheduled = false;
                    retryTask = FoliaScheduler.TaskHandle.NONE;
                }
            }
            if (report) {
                VideoPlayerMain.LOGGER.warn("Failed to schedule Residence permission integration retry", error);
            }
        }
    }

    private static void retryFailed(long expectedEpoch) {
        boolean refresh;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch)) return;
            retryScheduled = false;
            retryTask = FoliaScheduler.TaskHandle.NONE;
            refresh = binding.state() == State.FAILED_DENY;
        }
        if (refresh) refresh(expectedEpoch);
    }

    private static void registerLifecycleListener(long expectedEpoch) {
        JavaPlugin plugin;
        ResidenceLifecycleListener listener;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch) || lifecycleListener != null || lifecycleListenerRegistered || lifecycleListenerFailed) return;
            plugin = owner;
            listener = new ResidenceLifecycleListener();
            lifecycleListener = listener;
        }
        try {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            boolean stale;
            synchronized (LOCK) {
                stale = !activeLocked(expectedEpoch) || lifecycleListener != listener;
                if (!stale) lifecycleListenerRegistered = true;
            }
            if (stale) unregister(listener);
        } catch (Throwable error) {
            boolean report;
            synchronized (LOCK) {
                report = activeLocked(expectedEpoch) && lifecycleListener == listener;
                if (report) {
                    lifecycleListener = null;
                    lifecycleListenerRegistered = false;
                    lifecycleListenerFailed = true;
                }
            }
            unregister(listener);
            if (report) {
                VideoPlayerMain.LOGGER.warn("Failed to register Residence lifecycle listener; protected area actions will be denied while Residence is present", error);
            }
        }
    }

    private static void registerResidenceFlagListener(Plugin residence, long expectedEpoch) {
        JavaPlugin plugin;
        ResidenceFlagListener listener;
        ResidenceFlagListener previous;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch) || residence == null) return;
            if (residence == residenceFlagListenerPlugin && residenceFlagListener != null) return;
            plugin = owner;
            previous = residenceFlagListener;
            listener = new ResidenceFlagListener();
            residenceFlagListener = listener;
            residenceFlagListenerPlugin = residence;
        }
        unregister(previous);
        try {
            Class<?> loaded = Class.forName(
                    "com.bekvon.bukkit.residence.event.ResidenceFlagChangeEvent",
                    true,
                    residence.getClass().getClassLoader()
            );
            Class<? extends Event> eventType = loaded.asSubclass(Event.class);
            ResidenceFlagRefreshFilter filter = new ResidenceFlagRefreshFilter(eventType);
            Bukkit.getPluginManager().registerEvent(
                    eventType,
                    listener,
                    EventPriority.MONITOR,
                    (ignoredListener, event) -> {
                        if (filter.shouldRefresh(event)) schedulePermissionCacheRefresh();
                    },
                    plugin,
                    true
            );
            boolean stale;
            synchronized (LOCK) {
                stale = !activeLocked(expectedEpoch)
                        || residenceFlagListener != listener
                        || residenceFlagListenerPlugin != residence
                        || !residence.isEnabled();
                if (stale
                        && activeLocked(expectedEpoch)
                        && residenceFlagListener == listener
                        && residenceFlagListenerPlugin == residence
                        && !residence.isEnabled()) {
                    residenceFlagListener = null;
                    residenceFlagListenerPlugin = null;
                }
            }
            if (stale) unregister(listener);
        } catch (Throwable error) {
            boolean report;
            synchronized (LOCK) {
                report = activeLocked(expectedEpoch)
                        && residenceFlagListener == listener
                        && residenceFlagListenerPlugin == residence;
                if (report) {
                    residenceFlagListener = null;
                    residenceFlagListenerPlugin = null;
                }
            }
            unregister(listener);
            if (report) {
                VideoPlayerMain.LOGGER.warn("Failed to register Residence flag change listener; permissions will refresh when the menu is reopened", error);
            }
        }
    }

    private static void unregisterResidenceFlagListener(long expectedEpoch) {
        ResidenceFlagListener listener;
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch)) return;
            listener = residenceFlagListener;
            residenceFlagListener = null;
            residenceFlagListenerPlugin = null;
        }
        unregister(listener);
    }

    private static void schedulePermissionCacheRefresh() {
        long expectedEpoch;
        synchronized (LOCK) {
            if (owner == null) return;
            expectedEpoch = lifecycleEpoch;
        }
        schedulePermissionCacheRefresh(expectedEpoch);
    }

    private static void schedulePermissionCacheRefresh(long expectedEpoch) {
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch) || permissionCacheRefreshScheduled || !owner.isEnabled()) return;
            permissionCacheRefreshScheduled = true;
        }
        try {
            FoliaScheduler.TaskHandle task = FoliaScheduler.runGlobalDelayed(() -> refreshPermissionCaches(expectedEpoch), 1L);
            boolean stale;
            synchronized (LOCK) {
                stale = !activeLocked(expectedEpoch) || !permissionCacheRefreshScheduled;
                if (!stale) permissionCacheRefreshTask = task;
            }
            if (stale) cancel(task);
        } catch (Throwable error) {
            boolean report;
            synchronized (LOCK) {
                report = activeLocked(expectedEpoch);
                if (report) {
                    permissionCacheRefreshScheduled = false;
                    permissionCacheRefreshTask = FoliaScheduler.TaskHandle.NONE;
                }
            }
            if (report) {
                VideoPlayerMain.LOGGER.warn("Failed to schedule Residence permission cache refresh", error);
            }
        }
    }

    private static void refreshPermissionCaches(long expectedEpoch) {
        synchronized (LOCK) {
            if (!activeLocked(expectedEpoch)) return;
            permissionCacheRefreshScheduled = false;
            permissionCacheRefreshTask = FoliaScheduler.TaskHandle.NONE;
        }
        try {
            List<UUID> players;
            synchronized (DataHolder.LOCK) {
                players = List.copyOf(DataHolder.allPlayers);
            }
            for (UUID uuid : players) {
                try {
                    if (!active(expectedEpoch)) return;
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) ServerPacketHandler.refreshPermissions(uuid, player);
                } catch (Throwable error) {
                    VideoPlayerMain.LOGGER.warn("Failed to schedule Residence permission cache refresh for an online player", error);
                }
            }
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to enumerate players for Residence permission refresh", error);
        }
    }

    private static boolean active(long expectedEpoch) {
        synchronized (LOCK) {
            return activeLocked(expectedEpoch);
        }
    }

    private static boolean activeLocked(long expectedEpoch) {
        return owner != null && lifecycleEpoch == expectedEpoch;
    }

    private static void cancel(FoliaScheduler.TaskHandle task) {
        if (task == null || task == FoliaScheduler.TaskHandle.NONE) return;
        try {
            task.cancel();
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to cancel Residence permission bridge task", error);
        }
    }

    private static void unregister(Listener listener) {
        if (listener == null) return;
        try {
            HandlerList.unregisterAll(listener);
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to unregister Residence permission bridge listener", error);
        }
    }

    private static boolean onlineAdministrator(VideoPermissionPlayer permissionPlayer) {
        try {
            if (permissionPlayer == null || permissionPlayer.uuid() == null) return false;
            return onlineAdministrator(Bukkit.getPlayer(permissionPlayer.uuid()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean onlineAdministrator(Player player) {
        try {
            return player != null && player.isOnline() && (player.isOp() || player.hasPermission(VideoPermissions.ADMIN));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AreaPermissionDecision failedDecision(Coverage coverage) {
        return coverage == Coverage.PROTECTED
                ? AreaPermissionDecision.DENY
                : AreaPermissionDecision.NOT_APPLICABLE;
    }

    private static Coverage classify(Delegate delegate, VideoPermissionPlayer player, VideoPermissionContext context) {
        if (delegate == null || delegate == FAILED) return Coverage.UNKNOWN;
        try {
            return delegate.coverage(player, context);
        } catch (Throwable ignored) {
            return Coverage.UNKNOWN;
        }
    }

    private static Coverage classifyBounds(Delegate delegate, Player player, Vector3f first, Vector3f second) {
        if (delegate == null || delegate == FAILED) return Coverage.UNKNOWN;
        try {
            return delegate.coverageBounds(player, first, second);
        } catch (Throwable ignored) {
            return Coverage.UNKNOWN;
        }
    }

    interface Delegate extends AreaPermissionResolver {
        AreaPermissionDecision resolveBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second);

        default Coverage coverage(VideoPermissionPlayer player, VideoPermissionContext context) {
            return Coverage.UNKNOWN;
        }

        default Coverage coverageBounds(Player player, Vector3f first, Vector3f second) {
            return Coverage.UNKNOWN;
        }
    }

    private record Binding(State state, Delegate delegate) {
    }

    private static final class ResidenceLifecycleListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            if (!isResidence(event.getPlugin())) return;
            long expectedEpoch;
            synchronized (LOCK) {
                if (owner == null) return;
                expectedEpoch = lifecycleEpoch;
            }
            refresh(expectedEpoch);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(PluginDisableEvent event) {
            if (!isResidence(event.getPlugin())) return;
            long expectedEpoch;
            synchronized (LOCK) {
                if (owner == null) return;
                expectedEpoch = lifecycleEpoch;
            }
            unregisterResidenceFlagListener(expectedEpoch);
            update(expectedEpoch, new Binding(State.FAILED_DENY, FAILED));
        }

        private static boolean isResidence(Plugin plugin) {
            return plugin != null && "Residence".equalsIgnoreCase(plugin.getName());
        }
    }

    private static final class ResidenceFlagListener implements Listener {
    }
}
