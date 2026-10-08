package net.amdfaster.dirty;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting this set wrong fails in one of two directions, and both are bad in ways that are hard to
 * trace. Too small and a face that should have appeared does not, leaving a see-through hole in the
 * world that only heals when something else dirties the section. Too large and every block change
 * re-meshes eight sections, which is the mistake this class exists to remove.
 *
 * <p>The distribution below was measured over the sample before being written down.
 */
class SectionSetTest {

    @Test
    void aBlockInTheMiddleOfASectionInvalidatesOnlyThatSection() {
        // The common case, and the one that makes the difference: most blocks are not on a boundary.
        SectionSet set = new SectionSet();
        assertEquals(1, set.fillForBlockChange(20, 68, 20));
        assertEquals(1, set.x(0));
        assertEquals(4, set.y(0));
        assertEquals(1, set.z(0));
    }

    @Test
    void aBlockOnOneBoundaryInvalidatesTwoSections() {
        SectionSet set = new SectionSet();
        // x = 31 is local 15, so the +X neighbour shares the face plane at x = 32.
        assertEquals(2, set.fillForBlockChange(31, 68, 20));
        Set<String> sections = collect(set);
        assertTrue(sections.contains("1,4,1"));
        assertTrue(sections.contains("2,4,1"));
    }

    @Test
    void aBlockOnACornerInvalidatesEightSections() {
        SectionSet set = new SectionSet();
        // (16, 64, 16) is local (0,0,0), so all three low neighbours share a face plane with it.
        assertEquals(8, set.fillForBlockChange(16, 64, 16));
        assertEquals(8, SectionSet.MAX);
    }

    @Test
    void negativeCoordinatesUseTheirOwnSectionAndNeighbours() {
        // -1 is local 15 of section -1, not local -1 of section 0. Getting this wrong puts the
        // rebuild in the wrong section and the hole appears on the wrong side of the origin.
        SectionSet set = new SectionSet();
        assertEquals(8, set.fillForBlockChange(-1, -1, -1));
        Set<String> sections = collect(set);
        assertTrue(sections.contains("-1,-1,-1"), "the block's own section");
        assertTrue(sections.contains("0,0,0"), "the +X+Y+Z neighbour");

        SectionSet low = new SectionSet();
        assertEquals(1, low.fillForBlockChange(-20, 68, -20));
        assertEquals(-2, low.x(0));
    }

    @Test
    void theBlocksOwnSectionIsAlwaysIncluded() {
        SectionSet set = new SectionSet();
        for (int x = -40; x <= 40; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = -40; z <= 40; z++) {
                    int count = set.fillForBlockChange(x, y, z);
                    boolean found = false;
                    for (int i = 0; i < count; i++) {
                        if (set.x(i) == (x >> 4) && set.y(i) == (y >> 4) && set.z(i) == (z >> 4)) {
                            found = true;
                        }
                    }
                    assertTrue(found, "block " + x + "," + y + "," + z + " lost its own section");
                }
            }
        }
    }

    @Test
    void noSectionIsListedTwice() {
        SectionSet set = new SectionSet();
        for (int x = -40; x <= 40; x += 3) {
            for (int y = 60; y < 80; y += 5) {
                for (int z = -40; z <= 40; z += 7) {
                    int count = set.fillForBlockChange(x, y, z);
                    assertEquals(count, collect(set).size(),
                            "duplicates for block " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void theCountNeverExceedsTheMaximum() {
        SectionSet set = new SectionSet();
        for (int x = -40; x <= 40; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = -40; z <= 40; z++) {
                    int count = set.fillForBlockChange(x, y, z);
                    assertTrue(count >= 1 && count <= SectionSet.MAX,
                            count + " sections for " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void mostBlocksInvalidateOnlyOneSection() {
        // Measured over this sample: 1 section for 83300 of them, 2 for 38500, 4 for 5900 and 8 for
        // 300. If this shifts toward the higher counts, the boundary logic has been widened and the
        // win has quietly been given away.
        SectionSet set = new SectionSet();
        int[] histogram = new int[SectionSet.MAX + 1];
        for (int x = -40; x < 40; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = -40; z < 40; z++) {
                    histogram[set.fillForBlockChange(x, y, z)]++;
                }
            }
        }
        assertEquals(83300, histogram[1]);
        assertEquals(38500, histogram[2]);
        assertEquals(5900, histogram[4]);
        assertEquals(300, histogram[8]);
        assertEquals(0, histogram[3], "counts are products of 1s and 2s, so 3 is impossible");
    }

    @Test
    void theInstanceIsReusableWithoutAllocating() {
        SectionSet set = new SectionSet();
        assertEquals(8, set.fillForBlockChange(16, 64, 16));
        assertEquals(1, set.fillForBlockChange(20, 68, 20), "the previous fill is fully replaced");
        assertEquals(1, set.size());
    }

    @Test
    void sectionCoordinatesPackAndUnpack() {
        int[][] cases = {
                {0, 0, 0}, {-1, -1, -1}, {SectionCoord.MIN, SectionCoord.MIN, SectionCoord.MIN},
                {SectionCoord.MAX, SectionCoord.MAX, SectionCoord.MAX}, {1048575, -1048576, 7},
        };
        for (int[] c : cases) {
            long key = SectionCoord.key(c[0], c[1], c[2]);
            assertEquals(c[0], SectionCoord.x(key));
            assertEquals(c[1], SectionCoord.y(key));
            assertEquals(c[2], SectionCoord.z(key));
            assertTrue(key != SectionCoord.EMPTY, "a real key collided with the empty marker");
        }
        // The extremes matter: the minimum packs to 0, which a table using 0 as its empty marker
        // would lose, and the maximum must not reach the sign bit.
        assertEquals(0L, SectionCoord.key(SectionCoord.MIN, SectionCoord.MIN, SectionCoord.MIN));
        assertEquals(Long.MAX_VALUE,
                SectionCoord.key(SectionCoord.MAX, SectionCoord.MAX, SectionCoord.MAX));
    }

    @Test
    void outOfRangeSectionCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SectionCoord.key(SectionCoord.MIN - 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SectionCoord.key(0, SectionCoord.MAX + 1, 0));
    }

    private static Set<String> collect(SectionSet set) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < set.size(); i++) {
            out.add(set.x(i) + "," + set.y(i) + "," + set.z(i));
        }
        return out;
    }
}
