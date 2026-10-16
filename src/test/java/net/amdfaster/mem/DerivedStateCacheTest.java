package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DerivedStateCacheTest {

    /** A derived set: shape, collision shape, light opacity, friction, packed by the caller. */
    private static long[] derived(int shape, int collision, int opacity, int friction) {
        return new long[] {shape, collision, opacity, friction};
    }

    @Test
    void statesWithIdenticalDerivedValuesShareOneEntry() {
        // The saving. Two hundred stone-family states all have the same shape, opacity and friction, and
        // each currently carries its own copy of them.
        DerivedStateCache cache = new DerivedStateCache();
        long[] first = cache.intern(0, derived(1, 1, 15, 100));

        for (int state = 1; state < 200; state++) {
            assertSame(first, cache.intern(state, derived(1, 1, 15, 100)), "state " + state);
        }

        assertEquals(1, cache.entryCount());
        assertEquals(200L, cache.lookups());
        assertEquals(199L, cache.duplicates());
        assertEquals(99.5, cache.savedPercent(), 0.01);
    }

    @Test
    void aDifferentCollisionShapeIsADifferentEntry() {
        // The bug this prevents is physical rather than visual: merging two sets that differ only in
        // collision shape gives one block another's, and the player walks through a floor or stands in
        // the air where a slab should be.
        DerivedStateCache cache = new DerivedStateCache();

        long[] full = cache.intern(0, derived(1, 1, 15, 100));
        long[] slab = cache.intern(1, derived(1, 2, 15, 100));

        assertNotSame(full, slab);
        assertEquals(2, cache.entryCount());
    }

    @Test
    void everyFieldIsCompared() {
        DerivedStateCache cache = new DerivedStateCache();
        long[] base = cache.intern(0, derived(1, 1, 15, 100));

        assertNotSame(base, cache.intern(1, derived(2, 1, 15, 100)), "shape");
        assertNotSame(base, cache.intern(2, derived(1, 1, 14, 100)), "opacity");
        assertNotSame(base, cache.intern(3, derived(1, 1, 15, 101)), "friction");
        assertEquals(4, cache.entryCount());
    }

    @Test
    void stairsCollapseToFourEntries() {
        // 120 states, four distinct combinations, which is the shape of the real data.
        DerivedStateCache cache = new DerivedStateCache(256, 256);
        int state = 0;

        for (int facing = 0; facing < 6; facing++) {
            for (int half = 0; half < 2; half++) {
                for (int shape = 0; shape < 5; shape++) {
                    for (int waterlogged = 0; waterlogged < 2; waterlogged++) {
                        // The derived values depend on the shape and whether it is waterlogged, not on
                        // the facing, so 5 shapes times 2 is 10 -- but the four here are what the test
                        // pins: two shapes times two waterlogged.
                        int shapeClass = shape < 3 ? 0 : 1;
                        cache.intern(state++, derived(shapeClass, shapeClass, 15, waterlogged));
                    }
                }
            }
        }

        assertEquals(120, state);
        assertEquals(4, cache.entryCount(), "2 shape classes times 2 waterlogged");
        assertEquals(120L, cache.lookups());
    }

    @Test
    void entriesSurviveGrowthAndStatesStillResolve() {
        DerivedStateCache cache = new DerivedStateCache(16, 16);
        Set<long[]> seen = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            seen.add(cache.intern(i, derived(i, i, 15, 100)));
        }

        assertEquals(500, cache.entryCount(), "nothing lost while growing");
        assertEquals(500, seen.size(), "and every one distinct");

        // The state-to-entry mapping has to survive too, since growing moves every entry to a new slot.
        for (int i = 0; i < 500; i++) {
            assertEquals(1, cache.sharingWith(i), "state " + i + " still resolves");
        }
    }

    @Test
    void sharingIsCountedCorrectly() {
        DerivedStateCache cache = new DerivedStateCache();

        for (int i = 0; i < 10; i++) {
            cache.intern(i, derived(1, 1, 15, 100));
        }
        cache.intern(10, derived(2, 2, 0, 50));

        assertEquals(10, cache.sharingWith(0));
        assertEquals(10, cache.sharingWith(9));
        assertEquals(1, cache.sharingWith(10), "the odd one out");
        assertEquals(0, cache.sharingWith(11), "never interned");
    }

    @Test
    void theStateTableGrowsOnDemand() {
        DerivedStateCache cache = new DerivedStateCache(256, 4);

        cache.intern(0, derived(1, 1, 15, 100));
        cache.intern(1000, derived(2, 2, 15, 100));

        assertEquals(2, cache.entryCount());
        assertEquals(1, cache.sharingWith(0), "and the early state was not lost by the growth");
        assertEquals(1, cache.sharingWith(1000));
    }

    @Test
    void clearDropsEverything() {
        // For a resource pack reload. Dropping one entry is not offered because a state holding a stale
        // index would keep reading it, and tracking which states point where costs more than the
        // entries save.
        DerivedStateCache cache = new DerivedStateCache();
        cache.intern(0, derived(1, 1, 15, 100));

        cache.clear();

        assertEquals(0, cache.entryCount());
        assertEquals(0, cache.sharingWith(0));
    }

    @Test
    void theSavingIsReportedInBytes() {
        DerivedStateCache cache = new DerivedStateCache();
        for (int i = 0; i < 10_000; i++) {
            cache.intern(i, derived(i % 400, i % 400, 15, 100));
        }

        assertEquals(400, cache.entryCount());
        assertEquals(400L * DerivedStateCache.ENTRY_BYTES, cache.bytes());
        assertEquals(10_000L * DerivedStateCache.ENTRY_BYTES, cache.bytesWithoutDedup());
        assertEquals(96.0, cache.savedPercent(), 0.01);
        assertEquals(200, DerivedStateCache.ENTRY_BYTES);
    }

    @Test
    void theStoredContentIsCopied() {
        // A caller reusing its array would otherwise change an entry every state shares.
        DerivedStateCache cache = new DerivedStateCache();
        long[] content = derived(1, 1, 15, 100);
        long[] interned = cache.intern(0, content);
        long[] before = interned.clone();

        content[0] = 999;

        assertEquals(before[0], interned[0]);
        assertSame(interned, cache.intern(1, before));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        DerivedStateCache cache = new DerivedStateCache();

        assertThrows(IllegalArgumentException.class, () -> new DerivedStateCache(8, 16));
        assertThrows(IllegalArgumentException.class, () -> new DerivedStateCache(256, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.intern(0, new long[0]));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.sharingWith(-1));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        DerivedStateCache cache = new DerivedStateCache();
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0, cache.entryCount());
        assertEquals(0L, cache.lookups());
    }
}
