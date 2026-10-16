package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLayoutCacheTest {

    private static final long SEED = 12345L;
    private static final int VILLAGE = 1;
    private static final int STRONGHOLD = 2;
    private static final int[] HOUSES = {10, 64, 20, 30, 64, 40};

    @Test
    void theFirstLookupSearchesAndTheRestAreCached() {
        // Placing a village means choosing where each house goes, and that search is the most expensive part
        // of generating a chunk that contains one.
        StructureLayoutCache cache = new StructureLayoutCache();
        AtomicInteger searches = new AtomicInteger();

        assertEquals(HOUSES, cache.layout(SEED, 0, 0, VILLAGE,
                () -> { searches.incrementAndGet(); return HOUSES; }));
        assertEquals(HOUSES, cache.layout(SEED, 0, 0, VILLAGE,
                () -> { searches.incrementAndGet(); return HOUSES; }));

        assertEquals(1, searches.get());
        assertEquals(1L, cache.hits());
    }

    @Test
    void aDifferentSeedIsADifferentLayout() {
        // Two worlds with different seeds have different villages in the same chunk, and a key of the
        // position alone would serve one world's layout to the other -- houses floating, roads ending in
        // hills.
        assertNotEquals(StructureLayoutCache.key(SEED, 0, 0, VILLAGE),
                StructureLayoutCache.key(SEED + 1, 0, 0, VILLAGE));
    }

    @Test
    void aDifferentChunkIsADifferentLayout() {
        assertNotEquals(StructureLayoutCache.key(SEED, 0, 0, VILLAGE),
                StructureLayoutCache.key(SEED, 1, 0, VILLAGE));
    }

    @Test
    void aDifferentStructureTypeIsADifferentLayout() {
        // A key without the type would let one be served for the other, which would be a stronghold where
        // the village should be.
        assertNotEquals(StructureLayoutCache.key(SEED, 0, 0, VILLAGE),
                StructureLayoutCache.key(SEED, 0, 0, STRONGHOLD));
    }

    @Test
    void anEmptyChunkIsCachedToo() {
        // Most chunks have no structure, and the search that establishes that is the same work as the one
        // that finds something. Remembering that a chunk has none is the common case, not the exception.
        StructureLayoutCache cache = new StructureLayoutCache();
        AtomicInteger searches = new AtomicInteger();

        assertEquals(0, cache.layout(SEED, 5, 5, VILLAGE,
                () -> { searches.incrementAndGet(); return new int[0]; }).length);
        cache.layout(SEED, 5, 5, VILLAGE, () -> { searches.incrementAndGet(); return HOUSES; });

        assertEquals(1, searches.get(), "the empty result was not re-searched");
        assertEquals(2L, cache.empty(), "both lookups found nothing there");
    }

    @Test
    void aSearchThatProducesNothingIsRejected() {
        StructureLayoutCache cache = new StructureLayoutCache();

        assertThrows(IllegalStateException.class,
                () -> cache.layout(SEED, 0, 0, VILLAGE, () -> null));
    }

    @Test
    void walkingPastManyChunksSearchesEachOnce() {
        StructureLayoutCache cache = new StructureLayoutCache();

        for (int frame = 0; frame < 10; frame++) {
            for (int chunk = 0; chunk < 100; chunk++) {
                for (int type = 0; type < StructureLayoutCache.TYPES_PER_CHUNK; type++) {
                    cache.layout(SEED, chunk, 0, type, () -> new int[0]);
                }
            }
        }

        assertEquals(4000L, cache.lookups());
        assertEquals(400L, cache.searches(), "100 chunks by 4 types");
        assertEquals(3600L, cache.hits());
        assertEquals(90.0, cache.hitPercent(), 1e-9);
    }

    @Test
    void theSavingIsAlmostAllOfTheSearchCost() {
        StructureLayoutCache cache = new StructureLayoutCache();

        for (int i = 0; i < 100; i++) {
            cache.layout(SEED, 0, 0, VILLAGE, () -> HOUSES);
        }

        assertEquals(800.0, cache.msWithoutCache(), 1e-9);
        assertEquals(8.99, cache.msSpent(), 1e-9);
        assertEquals(98.88, cache.savedPercent(), 0.01);
    }

    @Test
    void clearingDropsEveryLayout() {
        StructureLayoutCache cache = new StructureLayoutCache();
        cache.layout(SEED, 0, 0, VILLAGE, () -> HOUSES);

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        StructureLayoutCache cache = new StructureLayoutCache();

        assertThrows(IllegalArgumentException.class,
                () -> cache.layout(SEED, 0, 0, VILLAGE, null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has been asked nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        StructureLayoutCache cache = new StructureLayoutCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSpent());
        assertEquals(0L, cache.lookups());
        assertEquals(0L, cache.empty());
        assertEquals(0, cache.size());
        assertTrue(StructureLayoutCache.TYPES_PER_CHUNK > 0);
    }
}
