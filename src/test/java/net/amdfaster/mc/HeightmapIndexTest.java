package net.amdfaster.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightmapIndexTest {

    @Test
    void anEmptyColumnIsSkyVisibleAtEveryHeight() {
        // Correct, and it is what makes an open plain cheap to mesh: thousands of cells, each answered
        // by one comparison against "nothing", with no column walk at all.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        assertEquals(HeightmapIndex.NONE, index.heightAt(0, 0));
        assertTrue(index.isSkyVisible(0, 320, 0));
        assertTrue(index.isSkyVisible(0, -64, 0), "even the world floor, since nothing blocks");
        assertEquals(2, index.skyQueries(), "both questions counted");
    }

    @Test
    void raisingRecordsTheNewTop() {
        HeightmapIndex index = HeightmapIndex.forChunk(1, -1);
        assertEquals(16, index.originX(), "chunk coordinates are in blocks");
        assertEquals(-16, index.originZ());

        assertTrue(index.raise(20, 70, -12), "the first block in the column");
        assertEquals(70, index.heightAt(20, -12));
        assertFalse(index.isSkyVisible(20, 70, -12), "the block itself is not above itself");
        assertTrue(index.isSkyVisible(20, 71, -12));
        assertFalse(index.isSkyVisible(20, 69, -12), "and everything below is shadowed");

        assertFalse(index.raise(20, 60, -12), "a lower block does not move the top");
        assertEquals(70, index.heightAt(20, -12));
        assertTrue(index.raise(20, 90, -12), "a higher one does");
        assertEquals(2, index.raisedInPlace(), "two raises, each a single comparison");
        assertEquals(0, index.loweredByScan(), "and no scans");
    }

    @Test
    void removingTheTopScansTheColumnAndThatIsTheExpensiveDirection() {
        // Placing a block is a comparison. Removing one cannot be, because the new top could be
        // anywhere below. Counting the scanned cells makes the asymmetry measurable rather than
        // assumed -- and it is why breaking a block at the surface is a heavier operation than placing
        // one, which is the opposite of what the block counts suggest.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.raise(4, 80, 4);
        index.raise(4, 74, 4);
        index.raise(4, 61, 4);

        assertTrue(index.lower(4, 80, 4, (x, y, z) -> y == 74 || y == 61));
        assertEquals(74, index.heightAt(4, 4), "the next blocker down becomes the top");
        assertEquals(1, index.loweredByScan());
        assertEquals(6, index.scanCells(), "scanned y=79 down to y=74");

        assertTrue(index.lower(4, 74, 4, (x, y, z) -> y == 61));
        assertEquals(61, index.heightAt(4, 4));
        assertEquals(6 + 13, index.scanCells(), "and a longer scan the second time");
    }

    @Test
    void removingABlockBelowTheTopChangesNothing() {
        // Below the recorded top, so it was already shadowed. This is the common case -- most blocks a
        // player breaks are underground -- and it costs one comparison, not a scan.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.raise(4, 80, 4);

        assertFalse(index.lower(4, 40, 4, (x, y, z) -> true));
        assertEquals(80, index.heightAt(4, 4));
        assertEquals(0, index.loweredByScan(), "no scan was needed to find that out");
        assertEquals(0, index.scanCells());
    }

    @Test
    void removingSomethingAboveTheTopAlsoChangesNothing() {
        // It was never the thing being measured. A leaf falling off a tree does not move the height of
        // the column it was above.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.raise(4, 80, 4);
        assertFalse(index.lower(4, 90, 4, null));
        assertEquals(80, index.heightAt(4, 4));
        assertEquals(0, index.loweredByScan());
    }

    @Test
    void removingTheLastBlockEmptiesTheColumn() {
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.raise(4, 66, 4);
        assertTrue(index.lower(4, 66, 4, null));
        assertEquals(HeightmapIndex.NONE, index.heightAt(4, 4));
        assertTrue(index.isSkyVisible(4, 0, 4), "the column is open again all the way down");
    }

    @Test
    void rebuildStopsAtTheFirstBlockerPerColumn() {
        // The cheap form of a full rebuild, and the reason it is cheap: it examines only the cells above
        // the surface, not the whole column height. Used when a chunk arrives and there is no previous
        // state to update from.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.rebuild((x, y, z) -> y == 70 - (x & 3));

        assertEquals(70, index.heightAt(0, 0), "surface at 70 for x%4==0");
        assertEquals(69, index.heightAt(1, 0));
        assertEquals(68, index.heightAt(2, 0));
        assertEquals(67, index.heightAt(3, 0));
        assertEquals(70, index.heightAt(4, 0), "and the pattern repeats every four blocks");
        assertEquals(70, index.heightAt(0, 15), "across z as well");

        // 256 columns, each scanned from 319 down to its surface: 256 * (320 - height) cells.
        long expected = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                expected += 320 - (70 - (x & 3));
            }
        }
        assertEquals(expected, index.scanCells(), "only the cells above each surface were examined");
    }

    @Test
    void anEmptyChunkRebuildsToNothing() {
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.rebuild((x, y, z) -> false);
        for (int x = 0; x < 16; x++) {
            assertEquals(HeightmapIndex.NONE, index.heightAt(x, 0), "column " + x);
        }
        // 256 columns times the 384 heights from WORLD_MIN_Y to WORLD_MAX_Y inclusive. This is the
        // number that was 549 755 895 296 before the scan was bounded by the world floor.
        assertEquals(256 * (HeightmapIndex.WORLD_MAX_Y - HeightmapIndex.WORLD_MIN_Y + 1), index.scanCells(),
                "every column scanned the full height and found nothing");
    }

    @Test
    void columnsOutsideTheChunkAreRefusedRatherThanWrapping() {
        // The bounds check ORs the two local coordinates and tests the sign, catching a negative on
        // either axis in one comparison. Wrapping instead would put a neighbouring chunk's heights into
        // this one, which would show as shading that changes at chunk borders.
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        assertEquals(HeightmapIndex.NONE, index.heightAt(-1, 0));
        assertEquals(HeightmapIndex.NONE, index.heightAt(16, 0));
        assertEquals(HeightmapIndex.NONE, index.heightAt(0, -1));
        assertFalse(index.raise(-1, 70, 0));
        assertFalse(index.lower(16, 70, 0, null));
        assertTrue(index.isSkyVisible(-1, 0, 0), "outside reads as open, which is the safe answer");
    }

    @Test
    void clearReopensEveryColumnAndResetsTheCounters() {
        HeightmapIndex index = HeightmapIndex.forChunk(0, 0);
        index.raise(3, 70, 3);
        index.isSkyVisible(3, 60, 3);
        index.lower(3, 70, 3, null);
        assertTrue(index.raisedInPlace() > 0 && index.skyQueries() > 0 && index.loweredByScan() > 0);

        index.clear();
        assertEquals(HeightmapIndex.NONE, index.heightAt(3, 3));
        assertEquals(0, index.raisedInPlace());
        assertEquals(0, index.loweredByScan());
        assertEquals(0, index.skyQueries());
        assertEquals(0, index.scanCells());
    }
}
