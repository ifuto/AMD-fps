package net.amdfaster.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslucentSortCacheTest {

    private static final double GAP = 1.0;

    @Test
    void theFirstFrameAlwaysSorts() {
        TranslucentSortCache cache = new TranslucentSortCache();

        assertTrue(cache.needsSort(0.0, 64.0, 0.0, GAP));
        assertEquals(1L, cache.sorted());
        assertEquals(0L, cache.skipped());
    }

    @Test
    void aSmallMovementReusesTheOrder() {
        // Three thousand translucent quads at 0.3 microseconds each is 0.9 ms a frame, and for most
        // frames the answer is the same list in the same order because the camera moved a few
        // centimetres and no quad swapped places.
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);

        assertFalse(cache.needsSort(0.1, 64.0, 0.0, GAP), "a tenth of a block is inside the bound");
        assertEquals(1L, cache.skipped());
        assertEquals(1L, cache.sorted());
    }

    @Test
    void aMovementPastHalfTheGapSorts() {
        // Two quads swap when the camera crosses the plane halfway between them, so a movement smaller
        // than half the smallest gap cannot have crossed any of them -- and a larger one might have.
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);

        assertFalse(cache.needsSort(0.4, 64.0, 0.0, GAP), "0.4 is inside half of 1.0");
        assertTrue(cache.needsSort(0.6, 64.0, 0.0, GAP), "0.6 from the last sort is past it");
    }

    @Test
    void theBoundIsMeasuredFromTheLastSortNotTheLastFrame() {
        // Otherwise a camera creeping forward in small steps would never re-sort, and the order would
        // drift out of date by however far it had travelled in total.
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);

        for (int i = 1; i <= 10; i++) {
            cache.needsSort(i * 0.1, 64.0, 0.0, GAP);
        }

        assertTrue(cache.sorted() >= 2L,
                "creeping forward must eventually re-sort; sorted " + cache.sorted());
    }

    @Test
    void aDenserSectionHasATighterBound() {
        // A section full of tightly spaced glass panes has a much smaller bound than one with a single
        // lake, and a global constant would either re-sort constantly or miss changes in the dense case.
        TranslucentSortCache tight = new TranslucentSortCache();
        tight.needsSort(0.0, 64.0, 0.0, 0.1);

        TranslucentSortCache sparse = new TranslucentSortCache();
        sparse.needsSort(0.0, 64.0, 0.0, 4.0);

        assertFalse(sparse.needsSort(0.5, 64.0, 0.0, 4.0), "half of 4.0 is 2.0, so 0.5 is safe");
        assertTrue(tight.needsSort(0.5, 64.0, 0.0, 0.1), "half of 0.1 is 0.05, so 0.5 is not");
    }

    @Test
    void aTeleportForcesTheNextSort() {
        // A jump can be further than any bound, and waiting for the distance check would reuse an order
        // built for a completely different viewpoint.
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);
        cache.needsSort(0.1, 64.0, 0.0, GAP);

        cache.invalidate();

        assertTrue(cache.needsSort(500.0, 64.0, 500.0, GAP));
    }

    @Test
    void movementInAnyAxisCounts() {
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);

        assertFalse(cache.needsSort(0.0, 64.4, 0.0, GAP), "vertical, inside the bound");
        assertTrue(cache.needsSort(0.0, 65.0, 0.0, GAP), "vertical, a whole block from the last sort");
    }

    @Test
    void returningToTheLastSortPositionNeedsNoSort() {
        // The order depends on where the camera is now, not on the path it took. Going up and back to the
        // position the order was built for cannot have reordered anything, however far it travelled in
        // between.
        TranslucentSortCache cache = new TranslucentSortCache();
        cache.needsSort(0.0, 64.0, 0.0, GAP);

        assertFalse(cache.needsSort(0.0, 64.4, 0.0, GAP), "up, inside the bound");
        assertFalse(cache.needsSort(0.0, 64.0, 0.0, GAP), "and back to exactly where it was sorted");
        assertEquals(1L, cache.sorted());
        assertEquals(2L, cache.skipped());
    }

    @Test
    void aStillCameraNeverSortsAgain() {
        TranslucentSortCache cache = new TranslucentSortCache();

        for (int frame = 0; frame < 100; frame++) {
            cache.needsSort(10.0, 64.0, 10.0, GAP);
        }

        assertEquals(1L, cache.sorted());
        assertEquals(99L, cache.skipped());
        assertEquals(99.0, cache.skippedPercent(), 0.01);
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        // 3,000 translucent quads at 0.3 microseconds each.
        assertEquals(0.9, TranslucentSortCache.msPerFrame(3000), 1e-9);

        TranslucentSortCache cache = new TranslucentSortCache();
        for (int frame = 0; frame < 100; frame++) {
            cache.needsSort(0.0, 64.0, 0.0, GAP);
        }

        assertEquals(99.0, cache.skippedPercent(), 0.01);
        assertEquals(0.99 * 0.9, cache.msSavedPerFrame(3000), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        TranslucentSortCache cache = new TranslucentSortCache();

        assertThrows(IllegalArgumentException.class, () -> cache.needsSort(0, 0, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> cache.needsSort(0, 0, 0, -1.0));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        TranslucentSortCache cache = new TranslucentSortCache();

        assertEquals(0.0, cache.skippedPercent());
        assertEquals(0.0, cache.msSavedPerFrame(3000));
        assertEquals(0L, cache.frames());
        assertEquals(0.0003, TranslucentSortCache.MS_PER_QUAD, 1e-9);
    }
}
