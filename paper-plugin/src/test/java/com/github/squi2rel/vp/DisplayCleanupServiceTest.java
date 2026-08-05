package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayCleanupServiceTest {
    @Test
    void tracksUntracksAndDispatchesImmutableRecordsByGroup() {
        ArrayList<DisplayCleanupService.DisplayRecord> dispatched = new ArrayList<>();
        DisplayCleanupService service = new DisplayCleanupService(dispatched::add);
        UUID group = UUID.randomUUID();
        DisplayCleanupService.DisplayRecord first = record(UUID.randomUUID());
        DisplayCleanupService.DisplayRecord second = record(UUID.randomUUID());

        assertTrue(service.trackRecord(group, first));
        assertTrue(service.trackRecord(group, second));
        assertTrue(service.untrackRecord(group, first.entityId()));
        assertEquals(1, service.cleanupGroup(group));
        assertEquals(second, dispatched.getFirst());
        assertEquals(0, service.cleanupGroup(group));
        assertEquals(0, service.trackedRecords());
    }

    @Test
    void duplicateEntityUpdatesSnapshotWithoutGrowingRegistry() {
        ArrayList<DisplayCleanupService.DisplayRecord> dispatched = new ArrayList<>();
        DisplayCleanupService service = new DisplayCleanupService(dispatched::add);
        UUID group = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        DisplayCleanupService.DisplayRecord first = record(entityId);
        DisplayCleanupService.DisplayRecord replacement = new DisplayCleanupService.DisplayRecord(
                entityId, "minecraft:overworld", 4.0, 5.0, 6.0, "vplight:display"
        );

        assertTrue(service.trackRecord(group, first));
        assertFalse(service.trackRecord(group, replacement));
        assertEquals(1, service.trackedRecords());
        assertEquals(1, service.cleanupGroup(group));
        assertEquals(replacement, dispatched.getFirst());
    }

    @Test
    void rejectsIncompleteOrUnboundedRecordData() {
        assertThrows(IllegalArgumentException.class, () -> new DisplayCleanupService.DisplayRecord(
                null, "minecraft:overworld", 0.0, 0.0, 0.0, "vplight:display"
        ));
        assertThrows(IllegalArgumentException.class, () -> new DisplayCleanupService.DisplayRecord(
                UUID.randomUUID(), "", 0.0, 0.0, 0.0, "vplight:display"
        ));
        assertThrows(IllegalArgumentException.class, () -> new DisplayCleanupService.DisplayRecord(
                UUID.randomUUID(), "minecraft:overworld", Double.NaN, 0.0, 0.0, "vplight:display"
        ));
        assertThrows(IllegalArgumentException.class, () -> new DisplayCleanupService.DisplayRecord(
                UUID.randomUUID(), "minecraft:overworld", 0.0, 0.0, 0.0, ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new DisplayCleanupService.DisplayRecord(
                UUID.randomUUID(), "x".repeat(129), 0.0, 0.0, 0.0, "vplight:display"
        ));
    }

    private static DisplayCleanupService.DisplayRecord record(UUID entityId) {
        return new DisplayCleanupService.DisplayRecord(
                entityId, "minecraft:overworld", 1.0, 2.0, 3.0, "vplight:display"
        );
    }
}
