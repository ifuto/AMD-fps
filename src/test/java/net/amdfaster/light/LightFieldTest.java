package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightFieldTest {

    @Test
    void twoLevelsShareAByte() {
        LightField field = new LightField(0, 0, 0, 16, 16, 16);
        assertEquals(4096, field.cellCount(), "L15");
        assertEquals(2048, field.storageBytes(), "half the cells, rounded up");

        LightField odd = new LightField(0, 0, 0, 3, 1, 1);
        assertEquals(3, odd.cellCount(), "L19");
        assertEquals(2, odd.storageBytes(), "an odd cell count rounds up, not down");
    }

    @Test
    void everyLevelRoundTripsInBothNibbles() {
        // Even indices go in the low nibble and odd in the high, so a level has to survive in both
        // positions. Writing one and clobbering the other is the failure this catches.
        // Thirty-two cells, not sixteen: the odd index below is level + 16, which runs to 31 and is
        // out of range in a sixteen-wide field. Writes there are ignored and reads return zero, so
        // every odd-index assertion would fail for a reason that has nothing to do with nibble packing.
        LightField field = new LightField(0, 0, 0, 32, 1, 1);
        for (int level = 1; level <= 15; level++) {
            // Starting at 1: writing 0 into a fresh field is not a change, so set correctly reports
            // false there, and asserting true would be testing the wrong thing.
            assertTrue(field.set(level, 0, 0, level), "index " + level + " is even");
            assertEquals(level, field.get(level, 0, 0), "even index " + level);

            int oddIndex = level + 16;
            field.set(oddIndex, 0, 0, 15 - level);
            assertEquals(15 - level, field.get(oddIndex, 0, 0), "odd index " + oddIndex);
            assertEquals(level, field.get(level, 0, 0),
                    "writing the odd neighbour did not clobber the even one");
        }
    }

    @Test
    void setReportsWhetherTheValueActuallyChanged() {
        // The signal propagation uses to stop early. A write that changes nothing cannot affect any
        // neighbour, so it does not need enqueuing -- and skipping those is most of what makes a light
        // update cheap.
        LightField field = new LightField(0, 0, 0, 4, 1, 1);
        assertTrue(field.set(0, 0, 0, 5), "0 -> 5 is a change");
        assertFalse(field.set(0, 0, 0, 5), "5 -> 5 is not");
        assertTrue(field.set(0, 0, 0, 6), "5 -> 6 is");
        assertTrue(field.set(0, 0, 0, 0), "and so is going back to dark");
        assertFalse(field.set(0, 0, 0, 0), "L52");
    }

    @Test
    void fillSetsEveryCellIncludingTheLastNibbleOfAnOddField() {
        LightField even = new LightField(0, 0, 0, 4, 1, 1);
        even.fill(7);
        assertEquals(4, even.litCellCount(), "L59");
        for (int x = 0; x < 4; x++) {
            assertEquals(7, even.get(x, 0, 0), "L61");
        }

        LightField odd = new LightField(0, 0, 0, 3, 1, 1);
        odd.fill(9);
        assertEquals(3, odd.litCellCount(), "the third cell lives in the high nibble of byte 1");
        assertEquals(9, odd.get(2, 0, 0), "L67");

        odd.fill(0);
        assertEquals(0, odd.litCellCount(), "L70");
    }

    @Test
    void coordinatesAreRelativeToTheOrigin() {
        LightField field = LightField.forSection(-2, 3, -1);
        assertEquals(16, field.sizeX(), "L76");
        assertTrue(field.contains(-32, 48, -16), "the section origin itself");
        assertTrue(field.contains(-17, 63, -1), "the far corner");
        assertFalse(field.contains(-33, 48, -16), "one block west of it");
        assertFalse(field.contains(-16, 64, -1), "one block above it");

        field.set(-32, 48, -16, 11);
        assertEquals(11, field.get(-32, 48, -16), "L83");
        assertEquals(0, field.get(0, 0, 0), "world origin is outside this section");
    }

    @Test
    void outOfBoundsReadsAsZeroAndWritesAreIgnored() {
        LightField field = new LightField(0, 0, 0, 2, 2, 2);
        assertEquals(0, field.get(-1, 0, 0), "L90");
        assertEquals(0, field.get(0, -1, 0), "L91");
        assertEquals(0, field.get(0, 0, -1), "L92");
        assertEquals(0, field.get(2, 0, 0), "L93");
        assertEquals(0, field.get(0, 2, 0), "L94");
        assertEquals(0, field.get(0, 0, 2), "L95");
        assertFalse(field.set(-1, 0, 0, 15), "L96");
        assertFalse(field.set(0, 0, 2, 15), "L97");
        assertEquals(0, field.litCellCount(), "nothing was written");
    }

    @Test
    void aNegativeOriginDoesNotConfuseTheBoundsCheck() {
        // The bounds test ORs the three local coordinates and checks the sign, which catches a
        // negative on any axis in one comparison. It is worth pinning because a field at a negative
        // origin is the common case, not the unusual one.
        LightField field = new LightField(-100, -100, -100, 8, 8, 8);
        assertTrue(field.contains(-100, -100, -100), "L107");
        assertTrue(field.contains(-93, -93, -93), "L108");
        assertFalse(field.contains(-101, -100, -100), "L109");
        assertFalse(field.contains(-100, -101, -100), "L110");
        assertFalse(field.contains(-100, -100, -101), "L111");
        assertFalse(field.contains(-92, -100, -100), "L112");
        assertTrue(field.set(-100, -100, -100, 15), "L113");
        assertEquals(15, field.get(-100, -100, -100), "L114");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LightField(0, 0, 0, 0, 16, 16));
        assertThrows(IllegalArgumentException.class, () -> new LightField(0, 0, 0, 16, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new LightField(0, 0, 0, 16, 16, -1));

        LightField field = new LightField(0, 0, 0, 4, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> field.set(0, 0, 0, 16),
                "a level above 15 does not fit in a nibble and would corrupt its neighbour");
        assertThrows(IllegalArgumentException.class, () -> field.set(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> field.fill(16));
    }
}
