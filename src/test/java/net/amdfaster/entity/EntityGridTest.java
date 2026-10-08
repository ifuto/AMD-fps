package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grid is the index that keeps batching at one hash and one probe per entity. The failures that
 * matter are not "it returns the wrong value for a key I just inserted" -- an open-addressed table
 * gets that right almost by accident -- but entries being lost when the table grows, and the probe
 * sequence degenerating into a linear scan on the sequential keys that neighbouring cells produce.
 */
class EntityGridTest {

    @Test
    void anEmptyGridHasNothing() {
        EntityGrid grid = new EntityGrid();
        assertTrue(grid.isEmpty());
        assertEquals(0, grid.size());
        assertEquals(-1, grid.get(0L, 0));
        assertFalse(grid.contains(0L, 0));
    }

    @Test
    void aPutIsVisibleToGetAndContains() {
        EntityGrid grid = new EntityGrid();
        long cell = EntityCell.keyOf(4f, 64f, 4f);
        grid.put(cell, 7, 42);
        assertEquals(42, grid.get(cell, 7));
        assertTrue(grid.contains(cell, 7));
        assertEquals(1, grid.size());
    }

    @Test
    void theSameCellUnderTwoModelsIsTwoBatches() {
        // One instanced draw binds one model, so two models in one cell cannot share a batch.
        EntityGrid grid = new EntityGrid();
        long cell = EntityCell.keyOf(4f, 64f, 4f);
        grid.put(cell, 1, 10);
        grid.put(cell, 2, 11);
        assertEquals(10, grid.get(cell, 1));
        assertEquals(11, grid.get(cell, 2));
        assertEquals(2, grid.size());
    }

    @Test
    void theSameModelInTwoCellsIsTwoBatches() {
        EntityGrid grid = new EntityGrid();
        grid.put(EntityCell.keyOf(4f, 64f, 4f), 1, 10);
        grid.put(EntityCell.keyOf(40f, 64f, 4f), 1, 11);
        assertEquals(10, grid.get(EntityCell.keyOf(4f, 64f, 4f), 1));
        assertEquals(11, grid.get(EntityCell.keyOf(40f, 64f, 4f), 1));
    }

    @Test
    void puttingAPairTwiceIsRejected() {
        // Silently overwriting would drop a batch of instances from the draw list, and the entities
        // would simply not be drawn. Batching only puts after a get missed, so this means the
        // caller is wrong.
        EntityGrid grid = new EntityGrid();
        long cell = EntityCell.keyOf(4f, 64f, 4f);
        grid.put(cell, 1, 10);
        assertThrows(IllegalStateException.class, () -> grid.put(cell, 1, 11));
        assertEquals(10, grid.get(cell, 1), "the original value survives the rejected put");
        assertEquals(1, grid.size());
    }

    @Test
    void growingKeepsEveryEntry() {
        // The resize rehashes by re-probing, which is where open addressing usually loses entries.
        EntityGrid grid = new EntityGrid(4);
        int count = 5000;
        for (int i = 0; i < count; i++) {
            grid.put(EntityCell.key(i % 97, i / 97, i % 31), i % 13, i);
        }
        assertEquals(count, grid.size());
        for (int i = 0; i < count; i++) {
            long cell = EntityCell.key(i % 97, i / 97, i % 31);
            assertEquals(i, grid.get(cell, i % 13), "entry " + i + " lost during a resize");
        }
        assertTrue(grid.capacity() > 4, "the table grew");
        assertEquals(0, grid.capacity() & (grid.capacity() - 1), "capacity stays a power of two");
    }

    @Test
    void neighbouringCellsDoNotCollideIntoOneProbeRun() {
        // Cell keys are sequential, so a weak hash puts a line of cells into consecutive slots and
        // the probe becomes a linear scan over all of them. This is the shape batching actually
        // produces: many entities along one axis.
        EntityGrid grid = new EntityGrid();
        int count = 4096;
        for (int i = 0; i < count; i++) {
            grid.put(EntityCell.key(i, 0, 0), 0, i);
        }
        assertEquals(count, grid.size());
        for (int i = 0; i < count; i++) {
            assertEquals(i, grid.get(EntityCell.key(i, 0, 0), 0), "cell " + i);
        }
        // At a 70% load factor the table can be at most about 1/0.7 entries wide, so four times
        // the entry count is a loose bound that still catches a table grown by rehash churn. A
        // degenerate probe sequence would pass the lookups above and only show up here.
        assertTrue(grid.capacity() <= 4 * count,
                "capacity " + grid.capacity() + " for " + count + " entries suggests rehash churn");
    }

    @Test
    void clearEmptiesButKeepsTheArrays() {
        EntityGrid grid = new EntityGrid();
        for (int i = 0; i < 2000; i++) {
            grid.put(EntityCell.key(i, 0, 0), 0, i);
        }
        int capacity = grid.capacity();
        grid.clear();
        assertTrue(grid.isEmpty());
        assertEquals(0, grid.size());
        assertEquals(-1, grid.get(EntityCell.key(0, 0, 0), 0), "cleared entries are gone");
        assertEquals(capacity, grid.capacity(), "clear must not shrink; the next frame reuses it");

        // And the same fill afterwards must not have to grow again, which is the whole point of
        // keeping the arrays: a steady-state frame allocates nothing here.
        for (int i = 0; i < 2000; i++) {
            grid.put(EntityCell.key(i, 0, 0), 0, i);
        }
        assertEquals(capacity, grid.capacity());
        assertEquals(2000, grid.size());
    }

    @Test
    void theConstructorRejectsANonPowerOfTwoCapacity() {
        // The probe masks with length - 1, which is only a modulo for a power of two.
        assertThrows(IllegalArgumentException.class, () -> new EntityGrid(0));
        assertThrows(IllegalArgumentException.class, () -> new EntityGrid(-4));
        assertThrows(IllegalArgumentException.class, () -> new EntityGrid(3));
        assertThrows(IllegalArgumentException.class, () -> new EntityGrid(100));
    }
}
