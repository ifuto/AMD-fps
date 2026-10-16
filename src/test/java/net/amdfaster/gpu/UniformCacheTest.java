package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniformCacheTest {

    private static final AtomicInteger CALLS = new AtomicInteger();

    private static final UniformCache.Setter COUNTING = (program, slot, value) -> CALLS.incrementAndGet();

    @Test
    void anUnchangedValueIsNotUploadedAgain() {
        // Four hundred draws at six uniforms each is 2,400 sets a frame, and at 5 microseconds of fixed
        // cost apiece that is 12 ms -- more than the whole 16.7 ms budget, mostly writing the same
        // numbers again.
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        for (int frame = 0; frame < 10; frame++) {
            assertTrue(cache.set(1, 0, new float[] {1.0f, 2.0f, 3.0f}, COUNTING) == (frame == 0),
                    "frame " + frame);
        }

        assertEquals(1, CALLS.get(), "uploaded once, skipped nine times");
        assertEquals(9L, cache.skipped());
    }

    @Test
    void aChangedValueIsUploaded() {
        // Drawing with the previous frame's view matrix puts the world a frame behind the camera. That
        // is not subtle: everything shifts as the player turns, and motion mismatch makes people feel ill
        // within seconds.
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {1.0f}, COUNTING);
        assertTrue(cache.set(1, 0, new float[] {1.5f}, COUNTING));

        assertEquals(2, CALLS.get());
        assertEquals(0L, cache.skipped());
    }

    @Test
    void aDifferenceInTheLastBitCountsAsChanged() {
        // Two matrices that differ in the last bit are different matrices. Treating them as equal to
        // save a call trades a microsecond for a visible error.
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {1.0f}, COUNTING);
        float next = Math.nextAfter(1.0f, 2.0f);
        assertTrue(cache.set(1, 0, new float[] {next}, COUNTING), "one ulp apart is a new value");

        assertEquals(2, CALLS.get());
    }

    @Test
    void negativeZeroIsDifferentFromZero() {
        // A sign flip in a matrix column reverses a normal or mirrors an axis. Comparing with == treats
        // -0.0 and 0.0 as equal, so a matrix that changed only in sign would be reported unchanged and
        // the geometry would come out inside-out.
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {0.0f}, COUNTING);
        assertTrue(cache.set(1, 0, new float[] {-0.0f}, COUNTING), "-0.0 is not 0.0");

        assertEquals(2, CALLS.get());
        // assertEquals on floats is bit-exact, so it cannot demonstrate this; the language operator can.
        assertTrue(0.0f == -0.0f, "the == operator treats them as the same, which is the trap");
    }

    @Test
    void nanIsEqualToItselfHere() {
        // A NaN that recurs should not force an upload every frame. floatToRawIntBits makes two
        // identical NaNs compare equal, which == would not.
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {Float.NaN}, COUNTING);
        assertFalse(cache.set(1, 0, new float[] {Float.NaN}, COUNTING));

        assertEquals(1, CALLS.get());
    }

    @Test
    void differentSlotsAreSeparate() {
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {1.0f}, COUNTING);
        cache.set(1, 1, new float[] {1.0f}, COUNTING);

        assertEquals(2, CALLS.get(), "same value, different uniform");
    }

    @Test
    void differentProgramsAreSeparate() {
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {1.0f}, COUNTING);
        cache.set(2, 0, new float[] {1.0f}, COUNTING);

        assertEquals(2, CALLS.get());
    }

    @Test
    void aDifferentLengthIsAChange() {
        UniformCache cache = new UniformCache();
        CALLS.set(0);

        cache.set(1, 0, new float[] {1.0f, 2.0f}, COUNTING);
        assertTrue(cache.set(1, 0, new float[] {1.0f, 2.0f, 3.0f}, COUNTING));

        assertEquals(2, CALLS.get());
    }

    @Test
    void invalidatingForcesTheNextSet() {
        UniformCache cache = new UniformCache();
        CALLS.set(0);
        cache.set(1, 0, new float[] {1.0f}, COUNTING);

        cache.invalidate(1, 0);

        assertFalse(cache.isCached(1, 0));
        assertTrue(cache.set(1, 0, new float[] {1.0f}, COUNTING));
        assertEquals(2, CALLS.get());
    }

    @Test
    void clearingForcesEverything() {
        // A context loss invalidates every uniform on the GPU. A cache that kept its values would report
        // "unchanged" for uniforms that no longer exist, and nothing would be drawn.
        UniformCache cache = new UniformCache();
        CALLS.set(0);
        cache.set(1, 0, new float[] {1.0f}, COUNTING);

        cache.clear();

        assertTrue(cache.set(1, 0, new float[] {1.0f}, COUNTING));
        assertEquals(2, CALLS.get());
    }

    @Test
    void valuesSurviveGrowth() {
        UniformCache cache = new UniformCache(16);
        CALLS.set(0);

        for (int i = 0; i < 1000; i++) {
            cache.set(i, 0, new float[] {i}, COUNTING);
        }
        assertEquals(1000, cache.size());

        int before = CALLS.get();
        for (int i = 0; i < 1000; i++) {
            cache.set(i, 0, new float[] {i}, COUNTING);
        }
        assertEquals(before, CALLS.get(), "every one still cached after the table grew");
    }

    @Test
    void theStoredValueIsCopied() {
        // A caller reusing its array would otherwise change what the cache compares against.
        UniformCache cache = new UniformCache();
        CALLS.set(0);
        float[] value = {1.0f};
        cache.set(1, 0, value, COUNTING);

        value[0] = 999.0f;
        assertFalse(cache.set(1, 0, new float[] {1.0f}, COUNTING),
                "the mutation did not change what was stored");
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        // 400 draws at 6 uniforms is 12 ms a frame of fixed driver cost.
        assertEquals(12.0, UniformCache.msPerFrame(400, 6), 1e-9);

        UniformCache cache = new UniformCache();
        cache.set(1, 0, new float[] {1.0f}, COUNTING);
        cache.set(1, 0, new float[] {1.0f}, COUNTING);

        assertEquals(50.0, cache.skippedPercent(), 0.01);
        assertEquals(UniformCache.MS_PER_SET, cache.msSavedPerFrame(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UniformCache(8));
        assertThrows(IllegalArgumentException.class, () -> new UniformCache(1000));

        UniformCache cache = new UniformCache();
        assertThrows(IllegalArgumentException.class, () -> cache.set(1, 0, new float[0], COUNTING));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        UniformCache cache = new UniformCache();

        assertEquals(0.0, cache.skippedPercent());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.msSavedPerFrame());
        assertEquals(0.005, UniformCache.MS_PER_SET, 1e-9);
    }
}
