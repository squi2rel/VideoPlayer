package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.FoliaScheduler;
import com.github.squi2rel.vp.permission.AreaPermissionDecision;
import com.github.squi2rel.vp.permission.VideoPermissionContext;
import com.github.squi2rel.vp.permission.VideoPermissions;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PermissionRefreshSnapshotTest {
    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final String dimension = "minecraft:overworld";
    private final List<VideoPermissionContext> checked = new ArrayList<>();
    private VideoArea area;
    private VideoScreen screen;

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenAnswer(call -> !VideoPermissions.ADMIN.equals(call.getArgument(0)));
        area = VideoArea.from(new Vector3f(), new Vector3f(10), "area", dimension);
        area.initServer();
        area.addPlayer(uuid);
        screen = new VideoScreen(area, "screen", List.of(new Vector3f(1), new Vector3f(2)), "");
        area.addScreen(screen);
        synchronized (DataHolder.LOCK) {
            HashMap<String, VideoArea> areas = new HashMap<>();
            areas.put(area.name, area);
            DataHolder.areas.put(dimension, areas);
        }
        VideoPermissions.setAreaResolver((permissionPlayer, action, context) -> {
            assertFalse(Thread.holdsLock(DataHolder.LOCK));
            checked.add(context);
            return AreaPermissionDecision.ALLOW;
        });
    }

    @AfterEach
    void tearDown() {
        synchronized (DataHolder.LOCK) {
            DataHolder.areas.remove(dimension);
        }
        VideoPermissions.reset();
    }

    @Test
    void requestInsideOuterLockDefersCalculationToEntityCallback() throws Exception {
        var field = ServerPacketHandler.class.getDeclaredField("permissionRefreshQueue");
        field.setAccessible(true);
        Object original = field.get(null);
        AtomicReference<Runnable> callback = new AtomicReference<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runAtEntity(eq(player), any(Runnable.class), any(Runnable.class)))
                    .thenAnswer(call -> {
                        callback.set(call.getArgument(1));
                        return (FoliaScheduler.TaskHandle) () -> {};
                    });
            ServerPacketHandler.initializePermissionRefresh();
            synchronized (DataHolder.LOCK) {
                ServerPacketHandler.refreshPermissions(player);
                assertTrue(checked.isEmpty());
            }
            callback.get().run();
            assertFalse(checked.isEmpty());
        } finally {
            ServerPacketHandler.shutdownPermissionRefresh();
            field.set(null, original);
        }
    }

    @Test
    void snapshotIsImmutableAndPermissionCalculationRunsOutsideStateLock() {
        var snapshot = ServerPacketHandler.permissionSnapshot(player);
        assertEquals(3, snapshot.contexts().size());
        assertTrue(checked.isEmpty());
        for (VideoPermissionContext context : snapshot.contexts()) ServerPacketHandler.sendRefreshedPermissionContext(player, context);
        assertFalse(checked.isEmpty());
        assertEquals(dimension, snapshot.world());
    }

    @Test
    void staleScreenSnapshotIsRevalidatedBeforePermissionCalculation() {
        VideoPermissionContext previous = VideoPermissionContext.screen(screen);
        screen.vertices = new ArrayList<>(List.of(new Vector3f(4), new Vector3f(5)));
        ServerPacketHandler.sendRefreshedPermissionContext(player, previous);
        assertEquals(VideoPermissionContext.screen(screen), checked.getFirst());
        checked.clear();
        area.screens.clear();
        ServerPacketHandler.sendRefreshedPermissionContext(player, previous);
        assertTrue(checked.isEmpty());
    }

    @Test
    void removedAreaAndChangedMembershipSkipOldContexts() {
        VideoPermissionContext context = VideoPermissionContext.area(area);
        area.removePlayer(uuid);
        ServerPacketHandler.sendRefreshedPermissionContext(player, context);
        assertTrue(checked.isEmpty());
        area.addPlayer(uuid);
        synchronized (DataHolder.LOCK) {
            DataHolder.areas.get(dimension).clear();
        }
        ServerPacketHandler.sendRefreshedPermissionContext(player, context);
        assertTrue(checked.isEmpty());
    }

    @Test
    void changedWorldSkipsOldContexts() {
        World other = mock(World.class);
        when(other.getKey()).thenReturn(NamespacedKey.minecraft("the_nether"));
        VideoPermissionContext context = VideoPermissionContext.area(area);
        when(player.getWorld()).thenReturn(other);
        ServerPacketHandler.sendRefreshedPermissionContext(player, context);
        assertTrue(checked.isEmpty());
    }
}
