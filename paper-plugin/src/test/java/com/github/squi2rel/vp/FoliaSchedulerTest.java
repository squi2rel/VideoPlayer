package com.github.squi2rel.vp;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FoliaSchedulerTest {
    @Test
    void entityAndRegionSchedulingNeverUseZeroTicks() {
        assertEquals(1L, FoliaScheduler.minimumEntityDelay(-4L));
        assertEquals(1L, FoliaScheduler.minimumEntityDelay(0L));
        assertEquals(1L, FoliaScheduler.minimumEntityDelay(1L));
        assertEquals(12L, FoliaScheduler.minimumEntityDelay(12L));
    }

    @Test
    void exposesTaskOwnerOverloadsForDependentPlugins() throws Exception {
        assertNotNull(FoliaScheduler.class.getMethod("runGlobal", Plugin.class, Runnable.class));
        assertNotNull(FoliaScheduler.class.getMethod("runGlobalDelayed", Plugin.class, Runnable.class, long.class));
        assertNotNull(FoliaScheduler.class.getMethod("runGlobalFixedRate", Plugin.class, Runnable.class, long.class, long.class));
        assertNotNull(FoliaScheduler.class.getMethod("runAsync", Plugin.class, Runnable.class));
        assertNotNull(FoliaScheduler.class.getMethod("runAtRegionDelayed", Plugin.class, Location.class, Runnable.class, long.class));
        assertNotNull(FoliaScheduler.class.getMethod("runAtRegionFixedRate", Plugin.class, Location.class, Runnable.class, long.class, long.class));
        assertNotNull(FoliaScheduler.class.getMethod("runAtEntityDelayed", Plugin.class, Entity.class, Runnable.class, Runnable.class, long.class));
        assertNotNull(FoliaScheduler.class.getMethod("runAtEntityFixedRate", Plugin.class, Entity.class, Runnable.class, Runnable.class, long.class, long.class));
    }
}
