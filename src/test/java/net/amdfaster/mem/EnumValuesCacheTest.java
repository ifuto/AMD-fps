package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumValuesCacheTest {

    private enum Colour { RED, GREEN, BLUE }

    private enum Empty { }

    @Test
    void theFirstRequestBuildsTheArray() {
        // values() clones the internal array every call, which costs an allocation each time -- and that
        // matters when the call is inside a loop that runs per block state, per entity, per frame.
        EnumValuesCache cache = new EnumValuesCache();

        Colour[] values = cache.values(Colour.class);

        assertArrayEquals(Colour.values(), values);
        assertEquals(1L, cache.cached());
        assertEquals(1, cache.size());
    }

    @Test
    void everyCallerGetsItsOwnCopy() {
        // Returning the cached array would hand out something mutable that every other caller shares, and
        // one of them writing to it would corrupt the enum for the rest of the game.
        EnumValuesCache cache = new EnumValuesCache();

        Colour[] first = cache.values(Colour.class);
        Colour[] second = cache.values(Colour.class);

        assertNotSame(first, second, "a fresh array each time, as values() gives");
        first[0] = null;
        assertEquals(Colour.RED, second[0], "and writing to one does not affect the other");
    }

    @Test
    void theArrayIsBuiltOnceNotPerRequest() {
        EnumValuesCache cache = new EnumValuesCache();

        for (int i = 0; i < 10_000; i++) {
            cache.values(Colour.class);
        }

        assertEquals(10_000L, cache.requests());
        assertEquals(1L, cache.cached(), "built on the first request only");
        assertEquals(99.99, cache.savedPercent(), 0.01);
    }

    @Test
    void anEnumWithNoConstantsIsHandled() {
        EnumValuesCache cache = new EnumValuesCache();

        Empty[] values = cache.values(Empty.class);

        assertEquals(0, values.length);
        assertEquals(1L, cache.emptyRequests());
    }

    @Test
    void theEmptyArrayIsShared() {
        // An empty array is the same empty array every time, and allocating one per call would be the same
        // waste in a smaller package.
        assertSame(EnumValuesCache.sharedEmpty(), EnumValuesCache.sharedEmpty());
        assertEquals(0, EnumValuesCache.sharedEmpty().length);
    }

    @Test
    void aNonEnumIsRejected() {
        EnumValuesCache cache = new EnumValuesCache();

        assertThrows(IllegalArgumentException.class, () -> cache.values((Class) String.class));
        assertThrows(IllegalArgumentException.class, () -> cache.values(null));
    }

    @Test
    void theLengthOfACachedEnumIsAvailableWithoutCopying() {
        EnumValuesCache cache = new EnumValuesCache();

        cache.values(Colour.class);

        assertEquals(3, cache.length(Colour.class));
    }

    @Test
    void clearingDropsEveryCachedArray() {
        EnumValuesCache cache = new EnumValuesCache();
        cache.values(Colour.class);

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has been asked nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        EnumValuesCache cache = new EnumValuesCache();

        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSaved(), 1e-12);
        assertEquals(0L, cache.requests());
        assertEquals(0L, cache.cached());
        assertEquals(0, cache.size());
    }
}
