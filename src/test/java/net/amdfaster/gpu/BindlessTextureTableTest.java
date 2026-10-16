package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindlessTextureTableTest {

    @Test
    void aTextureGetsAHandle() {
        BindlessTextureTable table = new BindlessTextureTable();

        int handle = table.add(1234L);

        assertTrue(handle > BindlessTextureTable.MISSING);
        assertEquals(1234L, table.lookup(handle));
        assertEquals(1, table.inUse());
    }

    @Test
    void slotZeroIsTheMissingTexture() {
        // So a slot that was never filled reads as one rather than as whatever the driver object at zero
        // happens to be.
        BindlessTextureTable table = new BindlessTextureTable();

        assertEquals(-1L, table.lookup(BindlessTextureTable.MISSING));
        assertEquals(0, BindlessTextureTable.MISSING);
    }

    @Test
    void anUnloadedTextureLeavesTheMissingTextureNotTheOldOne() {
        // A draw that references a freed texture has to read something that exists. A block drawn with the
        // missing texture is a visible defect the player can report; one drawn from freed memory is neither
        // visible nor reportable.
        BindlessTextureTable table = new BindlessTextureTable();
        int handle = table.add(1234L);

        table.remove(handle, 2);

        assertEquals(-1L, table.lookup(handle));
        assertEquals(1L, table.unloaded());
        assertEquals(0, table.inUse());
    }

    @Test
    void aFreedSlotIsNotReusableWhileAFramesInFlight() {
        // Freeing it immediately would let an in-flight frame draw the new texture where the old one belonged,
        // which is a frame that is wrong in a way that depends on timing and cannot be reproduced.
        BindlessTextureTable table = new BindlessTextureTable();
        int handle = table.add(1234L);

        table.remove(handle, 2);
        assertFalse(table.isReusable(handle), "two frames could still reference it");

        table.endFrame();
        assertFalse(table.isReusable(handle), "one still could");

        table.endFrame();
        assertTrue(table.isReusable(handle), "and now none can");
    }

    @Test
    void aHundredMaterialsDrawWithoutABindEach() {
        // A scene with a hundred materials is a hundred binds a frame, and on integrated graphics the driver
        // work is a measurable part of the frame.
        BindlessTextureTable table = new BindlessTextureTable();

        for (int i = 0; i < 100; i++) {
            table.add(1000L + i);
        }

        assertEquals(100, table.inUse());
        assertEquals(0.398, table.msSaved(), 1e-9);
        assertEquals(99.5, table.savedPercent(), 1e-9);
    }

    @Test
    void theTableHasALimit() {
        BindlessTextureTable table = new BindlessTextureTable();

        for (int i = 1; i < BindlessTextureTable.CAPACITY; i++) {
            assertTrue(table.add(i) > 0);
        }

        assertEquals(-1, table.add(99999L), "full, and says so rather than overflowing");
        assertEquals(BindlessTextureTable.CAPACITY - 1, table.inUse());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BindlessTextureTable table = new BindlessTextureTable();
        int handle = table.add(1L);

        assertThrows(IllegalArgumentException.class, () -> table.remove(0, 1));
        assertThrows(IllegalArgumentException.class, () -> table.remove(handle, -1));
        assertThrows(IllegalArgumentException.class, () -> table.lookup(-1));
        assertThrows(IllegalArgumentException.class,
                () -> table.lookup(BindlessTextureTable.CAPACITY));
        assertThrows(IllegalArgumentException.class, () -> table.isReusable(-5));
    }

    @Test
    void aFreshTableReportsZeroNotNaN() {
        // A table that has nothing in it has technically avoided every bind, but reporting that as a saving
        // would describe an unused one as though it had done something.
        BindlessTextureTable table = new BindlessTextureTable();

        assertEquals(0.0, table.savedPercent());
        assertEquals(0.0, table.msSaved(), 1e-12);
        assertEquals(0L, table.bound());
        assertEquals(0L, table.lookedUp());
        assertEquals(0, table.inUse());
        assertEquals(4096, BindlessTextureTable.CAPACITY);
    }
}
