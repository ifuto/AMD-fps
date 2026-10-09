package net.amdfaster.light;

import java.util.List;

import net.amdfaster.dirty.SectionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionLightStoreTest {

    @Test
    void readingADarkSectionAllocatesNothing() {
        // The saving this class exists for. Most loaded sections have no light in them -- solid stone
        // below the first cave, empty air above the terrain -- and reading one of those must not cost
        // the 2048 bytes of nibbles that storing 4096 zeros would.
        SectionLightStore store = new SectionLightStore();
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, store.get(i, 64, 0));
        }
        assertEquals(0, store.sectionCount());
        assertEquals(0, store.allocatedBytes());
        assertNull(store.fieldOrNull(0, 4, 0));
    }

    @Test
    void writingDarknessToAnAbsentSectionAlsoAllocatesNothing() {
        // The path that would defeat the whole design if it allocated: a light update that clears cells
        // touches far more sections than it lights, and allocating for each of them would use more
        // memory for the sections that end up dark than for the ones that end up lit.
        SectionLightStore store = new SectionLightStore();
        assertFalse(store.set(5, 64, 5, 0));
        assertEquals(0, store.sectionCount());
        assertEquals(0, store.allocatedBytes());
    }

    @Test
    void theFirstRealLevelAllocatesTheSection() {
        SectionLightStore store = new SectionLightStore();
        assertTrue(store.set(5, 64, 5, 12));
        assertEquals(1, store.sectionCount());
        assertEquals(LightField.SECTION_BYTES, store.allocatedBytes());
        assertNotNull(store.fieldOrNull(0, 4, 0));
        assertEquals(12, store.get(5, 64, 5));
        assertEquals(0, store.get(6, 64, 5), "and its neighbours are still dark");

        assertFalse(store.set(5, 64, 5, 12), "writing the same level again is not a change");
        assertTrue(store.set(5, 64, 5, 0), "but clearing it is");
        assertEquals(1, store.sectionCount(), "and the section stays allocated");
    }

    @Test
    void twoSectionsInTheSameColumnAreHeldSeparately() {
        SectionLightStore store = new SectionLightStore();
        store.set(0, 64, 0, 9);
        store.set(0, 80, 0, 9);
        assertEquals(2, store.sectionCount());
        assertEquals(9, store.get(0, 64, 0));
        assertEquals(9, store.get(0, 80, 0));
        assertNull(store.fieldOrNull(0, 5, 0));
    }

    @Test
    void lazyAllocationIsFarCheaperThanEagerForARealisticRange() {
        // The comparison the design rests on. A 33x33 block of columns sixteen sections tall is
        // roughly what render distance 16 loads; lighting a handful of sections in it should not cost
        // the arrays for all of them.
        SectionLightStore store = new SectionLightStore();
        for (int i = 0; i < 24; i++) {
            store.set(i, 64, 0, 15);
        }
        long lazy = store.allocatedBytes();
        long eager = store.bytesIfEager(33, 16);

        assertEquals(24 * LightField.SECTION_BYTES, lazy);
        assertEquals(33L * 33 * 16 * LightField.SECTION_BYTES, eager);
        assertTrue(lazy * 100 < eager,
                "lit " + lazy + " bytes against " + eager + " for eager allocation");
        assertEquals(24, store.peakSections());
    }

    @Test
    void replacingAColumnInvalidatesTheEightAroundIt() {
        // Light does not respect chunk boundaries: a torch at the edge of a column lights the next one
        // over, and it reaches fifteen blocks, which is further than a column is wide. Marking only the
        // changed column is what leaves dark seams at borders that persist until the player leaves.
        SectionLightStore store = new SectionLightStore();
        assertEquals(9, store.markColumnAffected(4, 4));
        assertEquals(9, store.pendingColumnCount());

        assertEquals(0, store.markColumnAffected(4, 4), "already pending");
        assertEquals(9, store.pendingColumnCount());

        // A neighbour column overlaps, so only the new part is added.
        assertEquals(6, store.markColumnAffected(5, 4));
        assertEquals(15, store.pendingColumnCount());
    }

    @Test
    void drainingReturnsThePendingColumnsAndEmptiesTheQueue() {
        SectionLightStore store = new SectionLightStore();
        store.markColumnAffected(0, 0);
        List<long[]> drained = store.drainAffectedColumns();

        assertEquals(9, drained.size());
        assertEquals(0, store.pendingColumnCount());
        assertTrue(store.drainAffectedColumns().isEmpty());

        boolean foundCentre = false;
        boolean foundCorner = false;
        for (long[] entry : drained) {
            if (SectionCoord.x(entry[0]) == 0 && SectionCoord.z(entry[0]) == 0) {
                foundCentre = true;
            }
            if (SectionCoord.x(entry[0]) == -1 && SectionCoord.z(entry[0]) == 1) {
                foundCorner = true;
            }
        }
        assertTrue(foundCentre, "the changed column itself");
        assertTrue(foundCorner, "and a diagonal neighbour");
    }

    @Test
    void aNeighbourArrivingLateForcesARelight() {
        // A chunk lit before its neighbour loaded cannot have received light from that neighbour, so
        // without relighting, the border facing the late arrival stays dark permanently.
        SectionLightStore store = new SectionLightStore();
        store.markRelitForLateNeighbour(2, 2);
        assertEquals(9, store.relightsCausedByLateNeighbour());
        assertEquals(9, store.pendingColumnCount());

        store.markRelitForLateNeighbour(2, 2);
        assertEquals(9, store.relightsCausedByLateNeighbour(), "the same arrival twice counts once");
    }

    @Test
    void negativeCoordinatesLandInTheRightSection() {
        // Arithmetic shift is what makes this work: -1 >> 4 is -1, so the section holding block -1 is
        // section -1 and not section 0. Getting this wrong would put the light of the whole negative
        // octant into one section.
        SectionLightStore store = new SectionLightStore();
        store.set(-1, -1, -1, 7);
        assertEquals(7, store.get(-1, -1, -1));
        assertEquals(0, store.get(-16, -1, -1), "the next section west is untouched");
        assertNotNull(store.fieldOrNull(-1, -1, -1));
        assertNull(store.fieldOrNull(0, 0, 0));
        assertEquals(1, store.sectionCount());
    }

    @Test
    void heightmapsAreCreatedPerColumnAndReused() {
        SectionLightStore store = new SectionLightStore();
        SkyHeightmap first = store.heightmap(1, 1);
        first.raise(3, 70, 3);

        assertSame(first, store.heightmap(1, 1), "the same column gets the same map");
        assertEquals(70, store.heightmap(1, 1).heightAt(3, 3));
        assertEquals(SkyHeightmap.EMPTY, store.heightmap(1, 2).heightAt(3, 3), "a different column does not");
    }

    @Test
    void removeSectionDropsItsArray() {
        SectionLightStore store = new SectionLightStore();
        store.set(0, 64, 0, 9);
        assertEquals(1, store.sectionCount());

        store.removeSection(0, 4, 0);
        assertEquals(0, store.sectionCount());
        assertEquals(0, store.get(0, 64, 0));
        assertEquals(0, store.allocatedBytes());
    }

    @Test
    void clearDropsEverythingIncludingPendingWork() {
        SectionLightStore store = new SectionLightStore();
        store.set(0, 64, 0, 9);
        store.markColumnAffected(0, 0);
        store.heightmap(0, 0).raise(0, 70, 0);

        store.clear();
        assertEquals(0, store.sectionCount());
        assertEquals(0, store.pendingColumnCount());
        assertEquals(SkyHeightmap.EMPTY, store.heightmap(0, 0).heightAt(0, 0));
    }

    private static void assertSame(Object expected, Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual, message);
    }
}
