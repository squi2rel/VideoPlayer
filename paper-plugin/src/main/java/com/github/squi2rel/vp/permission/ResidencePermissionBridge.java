package com.github.squi2rel.vp.permission;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.github.squi2rel.vp.network.ServerPacketHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

import java.util.List;

public final class ResidencePermissionBridge {
    public enum State {
        ABSENT_ALLOW,
        ACTIVE,
        FAILED_DENY
    }

    private static final Delegate ABSENT = new Delegate() {
        @Override
        public boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
            return true;
        }

        @Override
        public boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
            return true;
        }
    };
    private static final Delegate FAILED = new Delegate() {
        @Override
        public boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
            return onlineAdministrator(player);
        }

        @Override
        public boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
            return onlineAdministrator(player);
        }
    };

    private static volatile Binding binding = new Binding(State.ABSENT_ALLOW, ABSENT);
    private static Plugin owner;
    private static boolean lifecycleListenerRegistered;
    private static boolean lifecycleListenerFailed;

    private ResidencePermissionBridge() {
    }

    public static synchronized void initialize() {
        registerLifecycleListener();
        refresh();
    }

    public static State state() {
        return binding.state();
    }

    public static boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context) {
        Binding current = binding;
        try {
            return current.delegate().allowed(player, action, context);
        } catch (Throwable error) {
            fail(current, error);
            return FAILED.allowed(player, action, context);
        }
    }

    public static boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second) {
        Binding current = binding;
        try {
            return current.delegate().allowedBounds(player, action, first, second);
        } catch (Throwable error) {
            fail(current, error);
            return FAILED.allowedBounds(player, action, first, second);
        }
    }

    private static synchronized void refresh() {
        Plugin residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence == null) {
            update(new Binding(State.ABSENT_ALLOW, ABSENT));
            return;
        }
        if (!residence.isEnabled() || lifecycleListenerFailed) {
            update(new Binding(State.FAILED_DENY, FAILED));
            return;
        }
        try {
            Delegate delegate = (Delegate) Class.forName(
                    "com.github.squi2rel.vp.permission.ResidencePermissionHook",
                    true,
                    ResidencePermissionBridge.class.getClassLoader()
            ).getDeclaredConstructor().newInstance();
            update(new Binding(State.ACTIVE, delegate));
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to initialize Residence integration; protected area actions will be denied", error);
            update(new Binding(State.FAILED_DENY, FAILED));
        }
    }

    private static synchronized void fail(Binding failedBinding, Throwable error) {
        if (binding != failedBinding) return;
        VideoPlayerMain.LOGGER.warn("Residence permission check failed; protected area actions will be denied", error);
        update(new Binding(State.FAILED_DENY, FAILED));
    }

    private static synchronized void update(Binding next) {
        Binding previous = binding;
        binding = next;
        if (previous.state() != next.state()) refreshPermissionCaches();
    }

    private static void registerLifecycleListener() {
        if (lifecycleListenerRegistered || lifecycleListenerFailed) return;
        try {
            owner = JavaPlugin.getProvidingPlugin(ResidencePermissionBridge.class);
            Bukkit.getPluginManager().registerEvents(new ResidenceLifecycleListener(), owner);
            lifecycleListenerRegistered = true;
        } catch (Throwable error) {
            lifecycleListenerFailed = true;
            VideoPlayerMain.LOGGER.warn("Failed to register Residence lifecycle listener; protected area actions will be denied while Residence is present", error);
        }
    }

    private static void refreshPermissionCaches() {
        Plugin plugin = owner;
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                try {
                    player.getScheduler().run(plugin, task -> ServerPacketHandler.refreshPermissions(player), null);
                } catch (Throwable error) {
                    VideoPlayerMain.LOGGER.warn("Failed to refresh Residence permission cache for {}", player.getName(), error);
                }
            }
        } catch (Throwable error) {
            VideoPlayerMain.LOGGER.warn("Failed to enumerate players for Residence permission refresh", error);
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

    interface Delegate {
        boolean allowed(VideoPermissionPlayer player, VideoPermissionAction action, VideoPermissionContext context);

        boolean allowedBounds(Player player, VideoPermissionAction action, Vector3f first, Vector3f second);
    }

    private record Binding(State state, Delegate delegate) {
    }

    private static final class ResidenceLifecycleListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            if (isResidence(event.getPlugin())) refresh();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(PluginDisableEvent event) {
            if (isResidence(event.getPlugin())) update(new Binding(State.FAILED_DENY, FAILED));
        }

        private static boolean isResidence(Plugin plugin) {
            return plugin != null && "Residence".equalsIgnoreCase(plugin.getName());
        }
    }
}
