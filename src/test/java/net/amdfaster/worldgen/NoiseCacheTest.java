package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoiseCacheTest {

    /** A stand-in evaluator that counts calls and returns a value derived from the inputs. */
    private static final NoiseCache.Evaluator COUNTING = new NoiseCache.Evaluator() {
        final AtomicLong calls = new AtomicLong();

        @Override
        public double evaluate(long seed, int octave, double x, double y, double z) {
            this.calls.incrementAndGet();
            return seed * 0.001 + octave + x * 0.5 + y * 0.25 + z * 0.125;
        }
    };

    private static long calls() {
        return ((AtomicLong) field(COUNTING, "calls")).get();
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theSameCoordinateIsOnlyEvaluatedOnce() {
        // The whole point. The same coordinate is asked for again by later stages of the same chunk and
        // by neighbouring chunks that overlap it, so about 60% of evaluations are repeats.
        NoiseCache cache = new NoiseCache(12345L, 1024);
        long before = calls();

        double first = cache.sample(0, 1.0, 2.0, 3.0, COUNTING);
        double second = cache.sample(0, 1.0, 2.0, 3.0, COUNTING);

        assertEquals(first, second);
        assertEquals(before + 1, calls(), "the second lookup did not evaluate");
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.evaluations());
    }

    @Test
    void differentCoordinatesAreDifferentEntries() {
        NoiseCache cache = new NoiseCache(1L, 1024);

        double a = cache.sample(0, 1.0, 0.0, 0.0, COUNTING);
        double b = cache.sample(0, 2.0, 0.0, 0.0, COUNTING);

        assertNotEquals(a, b);
        assertEquals(0L, cache.hits());
        assertEquals(2L, cache.evaluations());
    }

    @Test
    void differentOctavesAreDifferentEntries() {
        // The same coordinate at a different octave is a different value, and merging them would flatten
        // the terrain detail.
        NoiseCache cache = new NoiseCache(1L, 1024);

        double low = cache.sample(0, 5.0, 5.0, 5.0, COUNTING);
        double high = cache.sample(3, 5.0, 5.0, 5.0, COUNTING);

        assertNotEquals(low, high);
        assertEquals(2L, cache.evaluations());
    }

    @Test
    void coordinatesDifferingInTheLastBitAreDistinct() {
        // The values must be bit-identical. Rounding coordinates to a grid would make nearby ones collide
        // and return the wrong value, which shifts terrain heights and produces a world that no longer
        // matches its own save data.
        NoiseCache cache = new NoiseCache(1L, 1024);

        double a = cache.sample(0, 1.0, 0.0, 0.0, COUNTING);
        double b = cache.sample(0, Math.nextAfter(1.0, 2.0), 0.0, 0.0, COUNTING);

        assertNotEquals(a, b, "one ulp apart is a different coordinate");
        assertEquals(2L, cache.evaluations());
    }

    @Test
    void negativeAndZeroCoordinatesWork() {
        NoiseCache cache = new NoiseCache(1L, 1024);

        double a = cache.sample(0, -0.0, -1.5, -1000.25, COUNTING);
        double b = cache.sample(0, -0.0, -1.5, -1000.25, COUNTING);

        assertEquals(a, b);
        assertEquals(1L, cache.hits());
    }

    @Test
    void changingSeedClearsEverything() {
        // Returning one world's noise for another would mix terrains.
        NoiseCache cache = new NoiseCache(111L, 1024);
        double old = cache.sample(0, 1.0, 1.0, 1.0, COUNTING);

        cache.reseed(222L);

        assertEquals(0, cache.size());
        assertEquals(222L, cache.seed());
        double fresh = cache.sample(0, 1.0, 1.0, 1.0, COUNTING);
        assertNotEquals(old, fresh, "the new seed gives a different world");
        assertEquals(0L, cache.hits(), "and the counters restarted");
    }

    @Test
    void entriesSurviveGrowth() {
        NoiseCache cache = new NoiseCache(7L, 16);

        for (int i = 0; i < 2000; i++) {
            cache.sample(0, i, 0.0, 0.0, COUNTING);
        }
        assertEquals(2000, cache.size(), "nothing lost while growing");

        long before = calls();
        for (int i = 0; i < 2000; i++) {
            cache.sample(0, i, 0.0, 0.0, COUNTING);
        }
        assertEquals(before, calls(), "and every one is still a hit");
    }

    @Test
    void theSavingIsTheHitsTimesTheCostPerEvaluation() {
        NoiseCache cache = new NoiseCache(1L, 1 << 16);
        // A chunk's worth of repeats: the same 1000 coordinates asked three times.
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 1000; i++) {
                cache.sample(i % 4, i, 64.0, 0.0, COUNTING);
            }
        }

        assertEquals(3000L, cache.lookups());
        assertEquals(2000L, cache.hits());
        assertEquals(1000L, cache.evaluations());
        assertEquals(66.67, cache.hitPercent(), 0.01);
        assertEquals(2000 * NoiseCache.MS_PER_EVALUATION, cache.msSaved(), 1e-9);
    }

    @Test
    void aChunkOfNoiseCostsWhatTheFormulaSays() {
        // 98,304 blocks at four octaves is the dominant cost in world generation.
        assertEquals(98304, NoiseCache.CHUNK_BLOCKS);
        assertEquals(98304 * 4 * NoiseCache.MS_PER_EVALUATION, NoiseCache.msPerChunk(4), 1e-9);
        assertEquals(19.66, NoiseCache.msPerChunk(4), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NoiseCache(0L, 8), "below sixteen");
        assertThrows(IllegalArgumentException.class, () -> new NoiseCache(0L, 1000), "not a power of two");
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        NoiseCache cache = new NoiseCache(0L, 1024);

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.msSaved());
        assertEquals(0L, cache.lookups());
    }
}
