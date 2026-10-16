package net.amdfaster.pathfinding;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedPathCacheTest {

    private static final int[] PATH = {10, 64, 20, 11, 64, 20, 12, 64, 20};
    private static final int COW = 1;
    private static final int SPIDER = 2;

    @Test
    void everyMobToTheSameTargetSharesOneSearch() {
        // Fifty zombies chasing one player is fifty searches, each walking the same blocks and reaching very
        // nearly the same route, in the same tick, on the server thread.
        SharedPathCache cache = new SharedPathCache();
        AtomicInteger searches = new AtomicInteger();

        for (int mob = 0; mob < 50; mob++) {
            cache.path(12, 64, 20, COW, () -> { searches.incrementAndGet(); return PATH; });
        }

        assertEquals(1, searches.get());
        assertEquals(49L, cache.hits());
        assertEquals(98.0, cache.hitPercent(), 1e-9);
    }

    @Test
    void aTerrainChangeInvalidatesThePath() {
        // A door opening changes which route is walkable, and a cached path from before the change would send
        // mobs into a wall, where they would stand pushing against it -- both wrong and visible.
        SharedPathCache cache = new SharedPathCache();
        AtomicInteger searches = new AtomicInteger();

        cache.path(12, 64, 20, COW, () -> { searches.incrementAndGet(); return PATH; });
        cache.bumpTerrain();
        cache.path(12, 64, 20, COW, () -> { searches.incrementAndGet(); return PATH; });

        assertEquals(2, searches.get(), "searched again after the change");
        assertEquals(1L, cache.terrainVersion());
    }

    @Test
    void differentSizeClassesGetDifferentPaths() {
        // A spider fits through a one-block gap a cow does not, and a path that works for one is a path into a
        // wall for the other. Sharing by target alone would give the cow the spider's route.
        SharedPathCache cache = new SharedPathCache();
        AtomicInteger searches = new AtomicInteger();

        cache.path(12, 64, 20, COW, () -> { searches.incrementAndGet(); return PATH; });
        cache.path(12, 64, 20, SPIDER, () -> { searches.incrementAndGet(); return PATH; });

        assertEquals(2, searches.get());
        assertNotEquals(SharedPathCache.key(12, 64, 20, COW, 0),
                SharedPathCache.key(12, 64, 20, SPIDER, 0));
    }

    @Test
    void aDifferentTargetIsADifferentPath() {
        assertNotEquals(SharedPathCache.key(12, 64, 20, COW, 0),
                SharedPathCache.key(13, 64, 20, COW, 0));
    }

    @Test
    void aCrowdedSceneSavesAlmostAllOfTheSearching() {
        SharedPathCache cache = new SharedPathCache();

        for (int mob = 0; mob < 50; mob++) {
            cache.path(12, 64, 20, COW, () -> PATH);
        }

        assertEquals(0.498, cache.msSpent(), 1e-9);
        assertEquals(20.0, cache.msWithoutSharing(), 1e-9);
        assertEquals(97.51, cache.savedPercent(), 0.01);
    }

    @Test
    void aFullCacheEvictsToMakeRoom() {
        SharedPathCache cache = new SharedPathCache();

        for (int i = 0; i < SharedPathCache.CAPACITY + 10; i++) {
            cache.path(i, 64, 0, COW, () -> PATH);
        }

        assertEquals(SharedPathCache.CAPACITY, cache.size());
        assertEquals(10L, cache.evicted());
    }

    @Test
    void aSearchThatProducesNothingIsRejected() {
        SharedPathCache cache = new SharedPathCache();

        assertThrows(IllegalStateException.class,
                () -> cache.path(12, 64, 20, COW, () -> null));
        assertThrows(IllegalArgumentException.class,
                () -> cache.path(12, 64, 20, COW, null));
    }

    @Test
    void clearingDropsEveryPath() {
        SharedPathCache cache = new SharedPathCache();
        cache.path(12, 64, 20, COW, () -> PATH);

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has been asked nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        SharedPathCache cache = new SharedPathCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSpent(), 1e-12);
        assertEquals(0L, cache.requests());
        assertEquals(0L, cache.terrainVersion());
        assertEquals(0, cache.size());
        assertEquals(256, SharedPathCache.CAPACITY);
    }
}
