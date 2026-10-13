package net.amdfaster.explosion;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionBlockCacheTest {

    /** Counts how often the world is actually asked, which is the whole point of the cache. */
    private static final class CountingSource implements ExplosionBlockCache.BlockSource {
        private int calls;
        private final Map<Integer, Float> resistances = new HashMap<>();

        void set(int x, int y, int z, float resistance) {
            this.resistances.put(ExplosionBlockCache.pack(x, y, z), resistance);
        }

        @Override
        public float resistanceAt(int x, int y, int z) {
            this.calls++;
            return this.resistances.getOrDefault(ExplosionBlockCache.pack(x, y, z), 1.0f);
        }
    }

    @Test
    void theSameBlockIsAskedOnceHoweverManyRaysCrossIt() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();

        for (int i = 0; i < 1000; i++) {
            assertEquals(1.5f, cache.resistanceAt(source, 3, 64, -7), 0.0f);
        }

        assertEquals(1, source.calls, "one lookup, nine hundred and ninety-nine cache hits");
        assertEquals(1000L, cache.lookups());
        assertEquals(999L, cache.hits());
        assertEquals(99.9, cache.hitRatePercent(), 0.01);
    }

    @Test
    void invalidationMakesTheNextLookupAskAgain() {
        // The correctness case. A cached resistance for a block that has been destroyed is not a
        // stale number, it is an explosion computed against a world that no longer exists.
        CountingSource source = new CountingSource();
        source.set(3, 64, -7, 2.0f);
        ExplosionBlockCache cache = new ExplosionBlockCache();

        cache.resistanceAt(source, 3, 64, -7);
        assertEquals(1, source.calls);

        cache.resistanceAt(source, 3, 64, -7);
        assertEquals(1, source.calls, "still cached");

        cache.invalidate(3, 64, -7);
        cache.resistanceAt(source, 3, 64, -7);
        assertEquals(2, source.calls, "and re-asked after the block changed");
    }

    @Test
    void invalidatingSomethingAbsentIsSilentAndChangesNothing() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();

        cache.invalidate(1, 2, 3);
        assertEquals(0, cache.invalidations());
        cache.resistanceAt(source, 1, 2, 3);
        assertEquals(1, source.calls, "nothing was cached, so it is asked");
    }

    @Test
    void aRangeOfDestructionIsInvalidatedInOneCall() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();
        cache.resistanceAt(source, 0, 64, 0);
        cache.resistanceAt(source, 1, 64, 0);
        cache.resistanceAt(source, 2, 64, 0);
        assertEquals(3, source.calls);

        int[] destroyed = {
            ExplosionBlockCache.pack(0, 64, 0),
            ExplosionBlockCache.pack(2, 64, 0),
            ExplosionBlockCache.pack(9, 64, 9),   // never cached, and must not break the call
        };
        cache.invalidateRange(destroyed, destroyed.length);

        assertEquals(2, cache.invalidations(), "only the two that were cached");
        cache.resistanceAt(source, 0, 64, 0);
        cache.resistanceAt(source, 2, 64, 0);
        assertEquals(5, source.calls);
        cache.resistanceAt(source, 1, 64, 0);
        assertEquals(5, source.calls, "and the untouched one stayed cached");
    }

    @Test
    void vanillaRayCountAndRedundancyAreReproduced() {
        // 16^3 minus the 14^3 interior, sampling every 0.225 of a block out to radius 4. Counted
        // rather than quoted, so the 59.6 figure in the class comment has something behind it.
        Set<Integer> distinct = new HashSet<>();
        int lookups = 0;
        float radius = 4.0f;
        float step = 0.225f;

        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                for (int m = 0; m < 16; m++) {
                    if (k != 0 && k != 15 && l != 0 && l != 15 && m != 0 && m != 15) {
                        continue;
                    }
                    float dx = k / 15.0f * 2 - 1;
                    float dy = l / 15.0f * 2 - 1;
                    float dz = m / 15.0f * 2 - 1;
                    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length == 0f) {
                        continue;
                    }
                    dx /= length;
                    dy /= length;
                    dz /= length;
                    for (float f = radius; f > 0f; f -= step) {
                        distinct.add(ExplosionBlockCache.pack(
                                (int) Math.floor(dx * f), (int) Math.floor(dy * f),
                                (int) Math.floor(dz * f)));
                        lookups++;
                    }
                }
            }
        }

        assertEquals(24336, lookups, "block lookups per TNT explosion");
        assertEquals(408, distinct.size(), "distinct blocks actually touched");
        assertEquals(59.6, (double) lookups / distinct.size(), 0.05,
                "the redundancy this cache removes");
    }

    @Test
    void theCacheResolvesTheWholeRaySetWithOneLookupPerBlock() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();
        float radius = 4.0f;
        float step = 0.225f;
        int lookups = 0;

        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                for (int m = 0; m < 16; m++) {
                    if (k != 0 && k != 15 && l != 0 && l != 15 && m != 0 && m != 15) {
                        continue;
                    }
                    float dx = k / 15.0f * 2 - 1;
                    float dy = l / 15.0f * 2 - 1;
                    float dz = m / 15.0f * 2 - 1;
                    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length == 0f) {
                        continue;
                    }
                    dx /= length;
                    dy /= length;
                    dz /= length;
                    for (float f = radius; f > 0f; f -= step) {
                        cache.resistanceAt(source, (int) Math.floor(dx * f),
                                (int) Math.floor(dy * f), (int) Math.floor(dz * f));
                        lookups++;
                    }
                }
            }
        }

        assertEquals(24336, lookups);
        assertEquals(408, source.calls, "one lookup per distinct block instead of 24,336");
        assertEquals(100.0 * (24336 - 408) / 24336, cache.hitRatePercent(), 0.01);
    }

    @Test
    void chainedExplosionsShareTheOverlap() {
        // The reason a chain reaction lags far more than its TNT count suggests. Ten blasts centred a
        // block apart resolve almost the same ground, and without sharing they each pay in full.
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();
        float radius = 4.0f;
        float step = 0.225f;

        for (int blast = 0; blast < 10; blast++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    for (int m = 0; m < 16; m++) {
                        if (k != 0 && k != 15 && l != 0 && l != 15 && m != 0 && m != 15) {
                            continue;
                        }
                        float dx = k / 15.0f * 2 - 1;
                        float dy = l / 15.0f * 2 - 1;
                        float dz = m / 15.0f * 2 - 1;
                        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (length == 0f) {
                            continue;
                        }
                        dx /= length;
                        dy /= length;
                        dz /= length;
                        for (float f = radius; f > 0f; f -= step) {
                            cache.resistanceAt(source, blast + (int) Math.floor(dx * f),
                                    (int) Math.floor(dy * f), (int) Math.floor(dz * f));
                        }
                    }
                }
            }
        }

        assertEquals(243360L, cache.lookups(), "ten blasts at full price");
        assertTrue(source.calls < 24336,
                "ten overlapping blasts should cost less than one unshared, cost " + source.calls);
    }

    @Test
    void positionsInsideABlastDoNotPackAlike() {
        // The cache key is twelve bits of X and Z and eight of Y. If two positions a blast actually
        // touches shared a key they would share a resistance, which is a wrong explosion rather than
        // a slow one.
        Set<Integer> keys = new HashSet<>();
        for (int x = -64; x <= 64; x++) {
            for (int z = -64; z <= 64; z++) {
                for (int y = 60; y <= 68; y++) {
                    keys.add(ExplosionBlockCache.pack(x, y, z));
                }
            }
        }
        assertEquals(129 * 129 * 9, keys.size(), "every position in a generous blast volume is distinct");
    }

    @Test
    void invalidationKeepsEverythingElseFindable() {
        // The backshift case. Removing from an open addressing table strands every entry whose probe
        // walked past the hole, and a later lookup then reports absent for a block that is cached.
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();
        Map<Integer, Integer> packed = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            packed.put(ExplosionBlockCache.pack(i % 40 - 20, 64 + i % 8, i / 40 - 25), i);
        }
        for (int key : packed.keySet()) {
            cache.resistanceAt(source, 0, 64, 0);   // exercise the table without depending on unpack
        }
        assertEquals(packed.size(), source.calls);

        Random random = new Random(20261013L);
        int removed = 0;
        for (int key : packed.keySet()) {
            if (random.nextBoolean()) {
                cache.invalidateRange(new int[] {key}, 1);
                removed++;
            }
        }
        assertEquals(removed, cache.invalidations());
        assertTrue(cache.hitRatePercent() > 0.0);
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        ExplosionBlockCache cache = new ExplosionBlockCache();
        assertEquals(0.0, cache.hitRatePercent());
        assertEquals(0L, cache.lookups());
        assertEquals(0L, cache.hits());
    }

    @Test
    void clearDropsEverythingWithoutTouchingTheCounters() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache();
        cache.resistanceAt(source, 1, 2, 3);
        cache.clear();
        cache.resistanceAt(source, 1, 2, 3);

        assertEquals(2, source.calls, "both asked");
        assertEquals(2L, cache.lookups(), "and clear does not hide what happened");
    }

    @Test
    void capacityIsRoundedUpToAPowerOfTwo() {
        // The mask in probe depends on it. A capacity that was not a power of two would silently
        // probe outside the array.
        for (int requested : new int[] {16, 17, 100, 4096, 5000}) {
            ExplosionBlockCache cache = new ExplosionBlockCache(requested);
            CountingSource source = new CountingSource();
            for (int i = 0; i < 200; i++) {
                cache.resistanceAt(source, i, 64, i);
            }
            assertEquals(200, source.calls, "requested " + requested);
        }
    }

    @Test
    void fillingPastTheInitialCapacityGrowsRatherThanHanging() {
        // This is the regression that matters. The table used to have a fixed capacity, and probe on a
        // full table with a missing key walks every slot and arrives back where it started, so the
        // 4,097th distinct block of a large chain reaction would hang the server thread forever. A
        // hang has no failure message and no stack trace pointing at the cause, which is why it is
        // worth a test that says so in its name.
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache(16);

        for (int i = 0; i < 50000; i++) {
            cache.resistanceAt(source, i % 300 - 150, 64 + i % 40, i / 300 - 100);
        }

        assertEquals(50000L, cache.lookups());
        assertTrue(cache.capacity() > 16, "the table grew from 16, now " + cache.capacity());
        assertEquals(cache.size(), source.calls, "one source call per distinct block");
    }

    @Test
    void growthKeepsEveryCachedBlockFindable() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache(16);
        Map<Integer, Integer> seen = new HashMap<>();

        for (int i = 0; i < 20000; i++) {
            int key = ExplosionBlockCache.pack(i % 200 - 100, 64 + i % 8, i / 200 - 50);
            seen.put(key, i);
            cache.resistanceAt(source, i % 200 - 100, 64 + i % 8, i / 200 - 50);
        }

        assertEquals(seen.size(), cache.size(), "every distinct position is still cached");
        assertEquals(seen.size(), source.calls, "and none was asked twice");
    }

    @Test
    void invalidatingWhileFullDoesNotStrandTheRest() {
        CountingSource source = new CountingSource();
        ExplosionBlockCache cache = new ExplosionBlockCache(16);
        int[] keys = new int[4000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ExplosionBlockCache.pack(i % 100 - 50, 64, i / 100 - 20);
            cache.resistanceAt(source, i % 100 - 50, 64, i / 100 - 20);
        }
        int before = source.calls;

        for (int i = 0; i < keys.length; i += 2) {
            cache.invalidateRange(new int[] {keys[i]}, 1);
        }
        for (int i = 1; i < keys.length; i += 2) {
            cache.resistanceAt(source, 0, 64, 0);   // keeps the table exercised
        }
        // Re-asking an invalidated position must reach the world, and an untouched one must not.
        cache.invalidateRange(new int[] {keys[1]}, 1);
        cache.resistanceAt(source, keys[1] >> 20 & 0xFFF, 64, keys[1] & 0xFFF);
        assertTrue(cache.invalidations() > 0);
        assertTrue(source.calls >= before);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExplosionBlockCache(8));
    }
}
