package net.amdfaster.block;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardnessCacheTest {

    private static final AtomicInteger COMPUTES = new AtomicInteger();

    private static java.util.function.DoubleSupplier counting(double value) {
        return () -> {
            COMPUTES.incrementAndGet();
            return value;
        };
    }

    @Test
    void theSameBlockAndToolIsNotRecomputed() {
        // Breaking one block is a hundred ticks of the same calculation, and the answer is the same
        // every time: the block has not changed and neither has the tool.
        HardnessCache cache = new HardnessCache();
        COMPUTES.set(0);

        for (int tick = 0; tick < 100; tick++) {
            assertEquals(1.5, cache.hardness(10, 3, counting(1.5)), 1e-9);
        }

        assertEquals(1, COMPUTES.get(), "computed once, served ninety-nine times");
        assertEquals(99L, cache.served());
    }

    @Test
    void aDifferentToolIsADifferentEntry() {
        // Keying on the block alone would serve the answer computed for a wooden pickaxe to a player
        // holding a diamond one, and the efficiency enchantment would appear to do nothing.
        HardnessCache cache = new HardnessCache();
        COMPUTES.set(0);

        assertEquals(1.5, cache.hardness(10, 1, counting(1.5)), 1e-9);
        assertEquals(0.4, cache.hardness(10, 5, counting(0.4)), 1e-9);

        assertEquals(2, COMPUTES.get(), "each tool computed separately");
        assertTrue(cache.isCached(10, 1));
        assertTrue(cache.isCached(10, 5));
    }

    @Test
    void aDifferentBlockIsADifferentEntry() {
        // If the cached value is for the wrong block, the block breaks at the wrong speed. Nothing looks
        // wrong, but the game feels inconsistent.
        HardnessCache cache = new HardnessCache();
        COMPUTES.set(0);

        cache.hardness(10, 3, counting(1.5));
        cache.hardness(11, 3, counting(0.5));

        assertEquals(2, COMPUTES.get());
    }

    @Test
    void invalidatingForcesARecompute() {
        HardnessCache cache = new HardnessCache();
        COMPUTES.set(0);
        cache.hardness(10, 3, counting(1.5));

        cache.invalidate(10, 3);

        assertFalse(cache.isCached(10, 3));
        cache.hardness(10, 3, counting(2.0));
        assertEquals(2, COMPUTES.get());
    }

    @Test
    void entriesSurviveGrowth() {
        HardnessCache cache = new HardnessCache(16);
        COMPUTES.set(0);

        for (int i = 0; i < 1000; i++) {
            cache.hardness(i, 0, counting(i));
        }
        assertEquals(1000, cache.size());

        int before = COMPUTES.get();
        for (int i = 0; i < 1000; i++) {
            cache.hardness(i, 0, counting(i));
        }
        assertEquals(before, COMPUTES.get(), "every one still cached after the table grew");
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        HardnessCache cache = new HardnessCache();

        // Breaking one block: a hundred ticks, one computation.
        for (int tick = 0; tick < 100; tick++) {
            cache.hardness(10, 3, counting(1.5));
        }

        assertEquals(99.0, cache.servedPercent(), 0.01);
        assertEquals(99 * HardnessCache.MS_PER_COMPUTE, cache.msSavedPerTick(), 1e-9);
        assertEquals(0.001, HardnessCache.MS_PER_COMPUTE, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HardnessCache(8));
        assertThrows(IllegalArgumentException.class, () -> new HardnessCache(1000));

        HardnessCache cache = new HardnessCache();
        assertThrows(IllegalArgumentException.class, () -> cache.hardness(1, 1, null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        HardnessCache cache = new HardnessCache();

        assertEquals(0.0, cache.servedPercent());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.msSavedPerTick());
        assertEquals(0L, cache.reads());
    }
}
