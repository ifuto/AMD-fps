package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpacityFieldTest {

    @Test
    void theBitsetIsOneEighthOfABytePerCell() {
        // The reason the class exists. A light update asks whether light can enter a neighbour six
        // times per cell it visits, so this array is read constantly; making it eight times smaller
        // than a byte-per-cell opacity map is what keeps those reads in cache.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertEquals(OpacityField.CELL_COUNT, field.cellCount());
        assertEquals(512, field.bitsetBytes(), "4096 cells at one bit each");
        assertEquals(2048, field.attenuationBytes(), "and the exact costs, two per byte");
        assertEquals(field.bitsetBytes() * 8, field.cellCount());
    }

    @Test
    void opacityIsRecoveredExactlyForEveryValue() {
        // Two nibbles per byte, so a write to one cell must not disturb its neighbour. The bitset is
        // derived from the same value, and the two have to agree.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        for (int attenuation = 0; attenuation <= 15; attenuation++) {
            int index = attenuation * 3;
            int x = index & 15;
            int y = (index >> 4) & 15;
            int z = index >> 8;
            field.set(x, y, z, attenuation);
            assertEquals(Math.max(1, attenuation), field.costOf(x, y, z),
                    "attenuation " + attenuation + " floored at 1");
            assertEquals(attenuation >= 15, field.isOpaque(x, y, z),
                    "the bitset agrees for attenuation " + attenuation);
        }
    }

    @Test
    void costIsFlooredAtOne() {
        // Air and glass both attenuate by 0, and both cost 1 per step. Costing them less than 1 would
        // make light travel further through glass than through air, which is not what Minecraft does.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        field.set(0, 0, 0, 0);
        assertEquals(1, field.costOf(0, 0, 0));
        assertFalse(field.isOpaque(0, 0, 0));
    }

    @Test
    void setReportsOnlyClassificationChanges() {
        // Going from attenuation 2 to 3 changes the stored cost but not whether light is blocked, so
        // it is not a change a light update has to react to. Only crossing the opaque threshold is.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertFalse(field.set(0, 0, 0, 0), "0 was already the stored value");
        assertTrue(field.set(0, 0, 0, 3), "clear to filtering is a classification change");
        assertFalse(field.set(0, 0, 0, 5), "still filtering, just more");
        assertTrue(field.set(0, 0, 0, 15), "and crossing into opaque is one");
        assertFalse(field.set(0, 0, 0, 15));
        assertEquals(OpacityField.OPAQUE, field.costOf(0, 0, 0), "and the cost follows the last write");

        field.set(0, 0, 0, 3);
        assertEquals(3, field.costOf(0, 0, 0), "as does coming back out of opaque");
        assertEquals(1, field.costOf(0, 1, 0), "without touching the neighbour");
    }

    @Test
    void opaqueCellsAreCounted() {
        OpacityField field = OpacityField.forSection(0, 0, 0);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    field.set(x, y, z, 15);
                }
            }
        }
        assertEquals(4 * 256, field.opaqueCells());
        assertEquals(0, field.filteringCells(), "a solid floor has no filtering cells");

        field.set(0, 0, 0, 3);
        assertEquals(4 * 256 - 1, field.opaqueCells(), "clearing one cell updates the count");
        assertEquals(1, field.filteringCells());
    }

    @Test
    void outsideTheVolumeBlocksLight() {
        // The flood fill walks off the edge of whatever volume it was given, and light must stop there
        // rather than leak. Treating outside as opaque does that with no bounds check at the call site.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertTrue(field.isOpaque(-1, 0, 0));
        assertTrue(field.isOpaque(0, 16, 0));
        assertTrue(field.isOpaque(0, 0, 16));
        assertEquals(OpacityField.OPAQUE, field.costOf(-1, 0, 0));
        assertEquals(OpacityField.OPAQUE, field.costOf(0, 0, 16));
        assertFalse(field.contains(-1, 0, 0));
        assertTrue(field.contains(0, 0, 0));
        assertTrue(field.contains(15, 15, 15));
    }

    @Test
    void aNegativeOriginWorksLikeAnyOther() {
        OpacityField field = OpacityField.forSection(-3, -1, -2);
        assertEquals(-48, field.originX());
        assertEquals(-16, field.originY());
        assertEquals(-32, field.originZ());
        assertTrue(field.contains(-48, -16, -32));
        assertTrue(field.set(-48, -16, -32, 15));
        assertTrue(field.isOpaque(-48, -16, -32));
        assertTrue(field.isOpaque(-49, -16, -32), "one block west is outside");
    }

    @Test
    void clearEmptiesBothArrays() {
        OpacityField field = OpacityField.forSection(0, 0, 0);
        for (int i = 0; i < 100; i++) {
            field.set(i & 15, (i >> 4) & 15, 0, 15);
        }
        assertTrue(field.opaqueCells() > 0);

        field.clear();
        assertEquals(0, field.opaqueCells());
        assertEquals(0, field.filteringCells());
        assertFalse(field.isOpaque(0, 0, 0));
        assertEquals(1, field.costOf(0, 0, 0), "a cleared cell is air");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpacityField(0, 0, 0, 0, 16, 16));
        assertThrows(IllegalArgumentException.class, () -> new OpacityField(0, 0, 0, 16, -1, 16));
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> field.set(0, 0, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> field.set(0, 0, 0, -1));
    }
}
