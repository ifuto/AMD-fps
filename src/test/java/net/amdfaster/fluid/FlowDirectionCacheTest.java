package net.amdfaster.fluid;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDirectionCacheTest {

    private static final AtomicInteger COMPUTES = new AtomicInteger();

    /** Always flows east, and counts how often it was asked. */
    private static final FlowDirectionCache.Computer EAST = (x, y, z, level) -> {
        COMPUTES.incrementAndGet();
        return FlowDirectionCache.EAST;
    };

    @Test
    void anUnchangedNeighbourhoodIsNotReExamined() {
        // Five hundred flowing blocks is 2,000 neighbour lookups a tick, 40,000 a second, and the answer
        // is the same until something beside the fluid changes.
        FlowDirectionCache cache = new FlowDirectionCache();
        COMPUTES.set(0);

        for (int tick = 0; tick < 10; tick++) {
            assertEquals(FlowDirectionCache.EAST, cache.direction(3, 64, 4, 7, EAST));
        }

        assertEquals(1, COMPUTES.get(), "computed once, served nine times");
        assertEquals(9L, cache.hits());
        assertEquals(9L * FlowDirectionCache.LOOKUPS_PER_TICK, cache.lookupsSaved());
    }

    @Test
    void invalidatingForcesTheNextLookupToRecompute() {
        // The failure this must not have. A stale direction means water stops flowing into a channel the
        // player just dug, or keeps flowing into one they just filled -- a farm that stopped working,
        // with nothing to connect it to a renderer mod.
        FlowDirectionCache cache = new FlowDirectionCache();
        COMPUTES.set(0);
        cache.direction(3, 64, 4, 7, EAST);

        cache.invalidate(3, 64, 4);

        assertFalse(cache.isCached(3, 64, 4, 7));
        cache.direction(3, 64, 4, 7, EAST);
        assertEquals(2, COMPUTES.get(), "recomputed after invalidation");
    }

    @Test
    void aNeighbourChangeInvalidatesTheFluid() {
        FlowDirectionCache cache = new FlowDirectionCache();
        cache.direction(3, 64, 4, 7, EAST);
        assertTrue(cache.isCached(3, 64, 4, 7));

        cache.invalidateNeighbours(4, 64, 4);

        assertFalse(cache.isCached(3, 64, 4, 7), "the block to its west changed");
    }

    @Test
    void onlyTheFourHorizontalNeighboursAreInvalidated() {
        // Fluids flow horizontally, so a diagonal change cannot alter the direction. Including diagonals
        // would throw away entries that are still correct.
        FlowDirectionCache cache = new FlowDirectionCache();
        cache.direction(3, 64, 4, 7, EAST);

        cache.invalidateNeighbours(4, 64, 5);

        assertTrue(cache.isCached(3, 64, 4, 7), "diagonal, so it cannot matter");
    }

    @Test
    void aDifferentLevelIsADifferentEntry() {
        // A block that cached its direction at level 7 flows differently from one at level 1, and
        // returning the cached answer would be the stale-direction bug with no change to trigger an
        // invalidation.
        FlowDirectionCache cache = new FlowDirectionCache();
        COMPUTES.set(0);

        cache.direction(0, 64, 0, 7, EAST);
        cache.direction(0, 64, 0, 1, EAST);

        assertEquals(2, COMPUTES.get(), "each level computed separately");
        assertTrue(cache.isCached(0, 64, 0, 7));
        assertTrue(cache.isCached(0, 64, 0, 1));
    }

    @Test
    void invalidatingDropsEveryLevel() {
        // Leaving another level's entry behind would serve it on the next lookup.
        FlowDirectionCache cache = new FlowDirectionCache();
        cache.direction(0, 64, 0, 7, EAST);
        cache.direction(0, 64, 0, 3, EAST);

        cache.invalidate(0, 64, 0);

        assertFalse(cache.isCached(0, 64, 0, 7));
        assertFalse(cache.isCached(0, 64, 0, 3));
        assertEquals(2L, cache.invalidations());
    }

    @Test
    void differentPositionsAreDifferentEntries() {
        FlowDirectionCache cache = new FlowDirectionCache();
        COMPUTES.set(0);

        cache.direction(0, 64, 0, 7, EAST);
        cache.direction(1, 64, 0, 7, EAST);
        cache.direction(0, 65, 0, 7, EAST);

        assertEquals(3, COMPUTES.get());
    }

    @Test
    void negativeCoordinatesWork() {
        FlowDirectionCache cache = new FlowDirectionCache();
        COMPUTES.set(0);

        assertEquals(FlowDirectionCache.EAST, cache.direction(-5, 12, -900, 4, EAST));
        assertEquals(FlowDirectionCache.EAST, cache.direction(-5, 12, -900, 4, EAST));

        assertEquals(1, COMPUTES.get(), "and the second was a hit");
    }

    @Test
    void entriesSurviveGrowthAndInvalidationStillFindsThem() {
        FlowDirectionCache cache = new FlowDirectionCache(16);

        for (int i = 0; i < 1000; i++) {
            cache.direction(i, 64, 0, 7, EAST);
        }
        assertEquals(1000, cache.size());

        cache.invalidate(500, 64, 0);

        assertFalse(cache.isCached(500, 64, 0, 7), "still found after the table grew");
        assertTrue(cache.isCached(499, 64, 0, 7));
    }

    @Test
    void stillWaterIsCachedToo() {
        FlowDirectionCache cache = new FlowDirectionCache();
        FlowDirectionCache.Computer still = (x, y, z, level) -> FlowDirectionCache.STILL;

        assertEquals(FlowDirectionCache.STILL, cache.direction(0, 64, 0, 0, still));
        assertEquals(FlowDirectionCache.STILL, cache.direction(0, 64, 0, 0, still));
        assertEquals(1L, cache.hits());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FlowDirectionCache(8));
        assertThrows(IllegalArgumentException.class, () -> new FlowDirectionCache(1000));
        FlowDirectionCache cache = new FlowDirectionCache();
        assertThrows(IllegalArgumentException.class, () -> cache.direction(0, 64, 0, -1, EAST));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        FlowDirectionCache cache = new FlowDirectionCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.lookupsSaved());
        assertEquals(500L * 4 * 20, cache.lookupsWithoutCache(500, 20));
    }
}
