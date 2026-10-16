package net.amdfaster.hopper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccludedPositionSkipperTest {

    @Test
    void aHopperUnderStoneDoesNotSearch() {
        // The space above a hopper in a storage system is usually solid stone, and an item cannot be inside
        // solid stone, so the answer to the search is already known.
        OccludedPositionSkipper skipper = new OccludedPositionSkipper();

        assertTrue(skipper.canSkip(true));
        assertEquals(1L, skipper.skipped());
        assertEquals(0L, skipper.searched());
    }

    @Test
    void aHopperUnderASlabStillSearches() {
        // A slab, a stair, a fence and a snow layer all leave room and an item can sit in the space they
        // occupy. Treating them as occluding would stop a hopper under a slab from ever picking anything up,
        // which is not a saving -- it is the hopper being broken.
        OccludedPositionSkipper skipper = new OccludedPositionSkipper();

        assertFalse(skipper.canSkip(false));
        assertEquals(1L, skipper.searched());
        assertEquals(1L, skipper.partialBlocks());
    }

    @Test
    void onlyAFullCubeThatIsNotAirEncloses() {
        assertTrue(OccludedPositionSkipper.encloses(true, false));
        assertFalse(OccludedPositionSkipper.encloses(false, false), "a slab leaves room");
        assertFalse(OccludedPositionSkipper.encloses(true, true), "air is not a block at all");
        assertFalse(OccludedPositionSkipper.encloses(false, true));
    }

    @Test
    void aStorageRoomSkipsAlmostEverySearch() {
        // Hoppers are the single most common block entity in a built world. The saving is not in one hopper,
        // it is in a thousand of them agreeing that there is nothing above them.
        OccludedPositionSkipper skipper = new OccludedPositionSkipper();

        for (int i = 0; i < 950; i++) {
            skipper.canSkip(true);
        }
        for (int i = 0; i < 50; i++) {
            skipper.canSkip(false);
        }

        assertEquals(1000L, skipper.checks());
        assertEquals(95.0, skipper.skippedPercent(), 1e-9);
        assertEquals(9.0, skipper.msSaved(), 1e-9, "9.5 of searching less 0.5 of block reads");
        assertEquals(10.0, skipper.msWithoutSkipping(), 1e-9);
        assertEquals(90.0, skipper.savedPercent(), 1e-9);
    }

    @Test
    void theSavingIsNetOfTheBlockReads() {
        // Checking costs something, and a skipper whose check cost as much as the search would save nothing
        // and should report that.
        OccludedPositionSkipper skipper = new OccludedPositionSkipper();

        for (int i = 0; i < 20; i++) {
            skipper.canSkip(false);
        }

        assertEquals(0L, skipper.skipped());
        assertEquals(0.0, Math.max(0.0, skipper.msSaved()), 1e-9);
        assertEquals(0.0, skipper.savedPercent(), 1e-9, "nothing saved, and not reported as a loss");
    }

    @Test
    void aFreshSkipperReportsZeroNotNaN() {
        // A skipper that has checked nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        OccludedPositionSkipper skipper = new OccludedPositionSkipper();

        assertEquals(0.0, skipper.skippedPercent());
        assertEquals(0.0, skipper.savedPercent());
        assertEquals(0.0, skipper.msSaved(), 1e-12);
        assertEquals(0L, skipper.checks());
        assertEquals(0L, skipper.partialBlocks());
        assertEquals(0.01, OccludedPositionSkipper.MS_PER_SEARCH, 1e-12);
    }
}
