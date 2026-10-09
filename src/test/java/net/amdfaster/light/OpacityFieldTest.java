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
        assertEquals(OpacityField.CELL_COUNT, field.cellCount(), "L18");
        assertEquals(512, field.bitsetBytes(), "4096 cells at one bit each");
        assertEquals(2048, field.attenuationBytes(), "and the exact costs, two per byte");
        assertEquals(field.bitsetBytes() * 8, field.cellCount(), "L21");
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
        assertEquals(1, field.costOf(0, 0, 0), "L48");
        assertFalse(field.isOpaque(0, 0, 0), "L49");
    }

    @Test
    void setReportsAnyChangeToTheStoredAttenuation() {
        // Not just the crossing into opaque. A block going from clear to filtering changes the step
        // cost, and the light already propagated through that cell was computed with the old one, so
        // the update has to react. Reporting only the opaque crossing would let that go unnoticed --
        // which is what the first version of this did, and it made 0 -> 3 report false.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertFalse(field.set(0, 0, 0, 0), "0 was already the stored value");
        assertTrue(field.set(0, 0, 0, 3), "clear to filtering changes the step cost");
        assertTrue(field.set(0, 0, 0, 5), "and so does filtering more");
        assertTrue(field.set(0, 0, 0, 15), "and crossing into opaque");
        assertFalse(field.set(0, 0, 0, 15), "writing the same value back is not a change");
        assertEquals(OpacityField.OPAQUE, field.costOf(0, 0, 0), "and the cost follows the last write");

        assertTrue(field.set(0, 0, 0, 3), "coming back out of opaque is a change too");
        assertEquals(3, field.costOf(0, 0, 0), "L67");
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
        assertEquals(4 * 256, field.opaqueCells(), "L81");
        assertEquals(0, field.filteringCells(), "a solid floor has no filtering cells");

        field.set(0, 0, 0, 3);
        assertEquals(4 * 256 - 1, field.opaqueCells(), "clearing one cell updates the count");
        assertEquals(1, field.filteringCells(), "L86");
    }

    @Test
    void outsideTheVolumeBlocksLight() {
        // The flood fill walks off the edge of whatever volume it was given, and light must stop there
        // rather than leak. Treating outside as opaque does that with no bounds check at the call site.
        OpacityField field = OpacityField.forSection(0, 0, 0);
        assertTrue(field.isOpaque(-1, 0, 0), "L94");
        assertTrue(field.isOpaque(0, 16, 0), "L95");
        assertTrue(field.isOpaque(0, 0, 16), "L96");
        assertEquals(OpacityField.OPAQUE, field.costOf(-1, 0, 0), "L97");
        assertEquals(OpacityField.OPAQUE, field.costOf(0, 0, 16), "L98");
        assertFalse(field.contains(-1, 0, 0), "L99");
        assertTrue(field.contains(0, 0, 0), "L100");
        assertTrue(field.contains(15, 15, 15), "L101");
    }

    @Test
    void aNegativeOriginWorksLikeAnyOther() {
        OpacityField field = OpacityField.forSection(-3, -1, -2);
        assertEquals(-48, field.originX(), "L107");
        assertEquals(-16, field.originY(), "L108");
        assertEquals(-32, field.originZ(), "L109");
        assertTrue(field.contains(-48, -16, -32), "L110");
        assertTrue(field.set(-48, -16, -32, 15), "L111");
        assertTrue(field.isOpaque(-48, -16, -32), "L112");
        assertTrue(field.isOpaque(-49, -16, -32), "one block west is outside");
    }

    @Test
    void clearEmptiesBothArrays() {
        OpacityField field = OpacityField.forSection(0, 0, 0);
        for (int i = 0; i < 100; i++) {
            field.set(i & 15, (i >> 4) & 15, 0, 15);
        }
        assertTrue(field.opaqueCells() > 0, "L122");

        field.clear();
        assertEquals(0, field.opaqueCells(), "L125");
        assertEquals(0, field.filteringCells(), "L126");
        assertFalse(field.isOpaque(0, 0, 0), "L127");
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
