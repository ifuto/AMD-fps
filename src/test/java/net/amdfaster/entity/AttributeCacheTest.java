package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeCacheTest {

    private static final AtomicInteger COMPUTES = new AtomicInteger();

    private static final double[] NONE = new double[0];

    @Test
    void anUnchangedAttributeIsNotRecomputed() {
        // Three hundred entities at six attributes and four modifiers each is 7,200 modifier walks a
        // tick, and the values change only when a potion is drunk or equipment is put on.
        AttributeCache cache = new AttributeCache();
        COMPUTES.set(0);

        for (int tick = 0; tick < 10; tick++) {
            assertEquals(20.0, cache.value(1, 2, 20.0, NONE, NONE, NONE, () -> {
                COMPUTES.incrementAndGet();
                return 20.0;
            }), 1e-9);
        }

        assertEquals(0, COMPUTES.get(), "the fallback was never reached");
        assertEquals(9L, cache.served());
        assertEquals(1L, cache.computed());
    }

    @Test
    void invalidatingForcesARecompute() {
        // Serving a value computed before a potion was drunk means the potion does nothing. Neither
        // produces an error; the number is simply wrong.
        AttributeCache cache = new AttributeCache();
        cache.value(1, 2, 20.0, NONE, NONE, NONE, () -> 20.0);
        assertTrue(cache.isCached(1, 2));

        cache.invalidate(1, 2);

        assertFalse(cache.isCached(1, 2));
        assertEquals(1L, cache.invalidations());
    }

    @Test
    void invalidatingOneAttributeLeavesTheOthers() {
        AttributeCache cache = new AttributeCache();
        cache.value(1, 2, 20.0, NONE, NONE, NONE, () -> 20.0);
        cache.value(1, 3, 0.1, NONE, NONE, NONE, () -> 0.1);

        cache.invalidate(1, 2);

        assertFalse(cache.isCached(1, 2));
        assertTrue(cache.isCached(1, 3), "a different attribute is untouched");
    }

    @Test
    void invalidatingAnEntityClearsEveryAttribute() {
        // For one that was reloaded or changed dimension.
        AttributeCache cache = new AttributeCache();
        cache.value(7, 1, 20.0, NONE, NONE, NONE, () -> 20.0);
        cache.value(7, 2, 0.1, NONE, NONE, NONE, () -> 0.1);
        cache.value(8, 1, 20.0, NONE, NONE, NONE, () -> 20.0);

        cache.invalidateEntity(7);

        assertFalse(cache.isCached(7, 1));
        assertFalse(cache.isCached(7, 2));
        assertTrue(cache.isCached(8, 1), "another entity is untouched");
        assertEquals(2L, cache.invalidations());
    }

    @Test
    void theOrderOfOperationsIsFixed() {
        // Base, plus every addition, then times every multiplication, then times one plus the sum of the
        // multiply-totals. Any other order gives a different number, and a cache that computed
        // differently from the game would desync from the save data.
        double value = AttributeCache.compute(20.0,
                new double[] {4.0, 6.0},
                new double[] {2.0},
                new double[] {0.5});

        // (20 + 4 + 6) * 2 * (1 + 0.5) = 90
        assertEquals(90.0, value, 1e-9);
    }

    @Test
    void multiplyTotalSumsRatherThanCompounds() {
        // Two 0.5 multiply-totals give 1 + 0.5 + 0.5 = 2.0, not 1.5 * 1.5 = 2.25.
        double value = AttributeCache.compute(10.0, NONE, NONE, new double[] {0.5, 0.5});

        assertEquals(20.0, value, 1e-9);
    }

    @Test
    void multiplicationCompounds() {
        // Ordinary multiplications do compound: 10 * 2 * 3 = 60.
        double value = AttributeCache.compute(10.0, NONE, new double[] {2.0, 3.0}, NONE);

        assertEquals(60.0, value, 1e-9);
    }

    @Test
    void additionsComeBeforeMultiplication() {
        // (10 + 5) * 2 = 30, not 10 + 5 * 2 = 20.
        double value = AttributeCache.compute(10.0, new double[] {5.0}, new double[] {2.0}, NONE);

        assertEquals(30.0, value, 1e-9);
    }

    @Test
    void valuesSurviveGrowth() {
        AttributeCache cache = new AttributeCache(16);

        for (int i = 0; i < 1000; i++) {
            cache.value(i, 0, i, NONE, NONE, NONE, () -> 0.0);
        }
        assertEquals(1000, cache.size());

        long computed = cache.computed();
        for (int i = 0; i < 1000; i++) {
            cache.value(i, 0, i, NONE, NONE, NONE, () -> 0.0);
        }
        assertEquals(computed, cache.computed(), "every one still cached after the table grew");
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        // 300 entities, 6 attributes, 4 modifiers at half a microsecond each.
        assertEquals(3.6, AttributeCache.msPerTick(300, 6, 4), 1e-9);

        AttributeCache cache = new AttributeCache();
        for (int i = 0; i < 100; i++) {
            cache.value(1, 1, 20.0, NONE, NONE, NONE, () -> 20.0);
        }
        assertEquals(99.0, cache.servedPercent(), 0.01);
        assertEquals(99 * 4 * AttributeCache.MS_PER_WALK, cache.msSavedPerTick(4), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AttributeCache(8));
        assertThrows(IllegalArgumentException.class, () -> new AttributeCache(1000));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        AttributeCache cache = new AttributeCache();

        assertEquals(0.0, cache.servedPercent());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.msSavedPerTick(4));
        assertEquals(0.0005, AttributeCache.MS_PER_WALK, 1e-9);
    }
}
