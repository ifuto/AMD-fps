package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtStringCacheTest {

    private static final String CHEST = "minecraft:chest";

    @Test
    void equalValuesShareOneObject() {
        // minecraft:chest appears once per chest in the world, and each occurrence is a separate String with
        // its own array of characters.
        NbtStringCache cache = new NbtStringCache();

        String first = cache.intern(CHEST);
        String second = cache.intern(CHEST);

        assertSame(first, second);
        assertEquals(1L, cache.reused());
        assertEquals(1L, cache.cached());
    }

    @Test
    void differentValuesAreNotShared() {
        // Strings are immutable, so sharing is safe -- but only equal ones may share, or two tags would hold
        // the wrong value.
        NbtStringCache cache = new NbtStringCache();

        assertNotSameHelper(cache.intern("minecraft:chest"), cache.intern("minecraft:furnace"));
        assertEquals(2L, cache.cached());
    }

    private static void assertNotSameHelper(String a, String b) {
        assertTrue(a != b, "different values must not share an object");
    }

    @Test
    void aLongStringIsNotCached() {
        // A written book's text appears once, so caching it costs a map entry and saves nothing, and it
        // would evict something that does repeat.
        NbtStringCache cache = new NbtStringCache();
        String book = "x".repeat(NbtStringCache.MAX_LENGTH + 1);

        cache.intern(book);

        assertEquals(1L, cache.skippedTooLong());
        assertEquals(0, cache.size());
    }

    @Test
    void aFullCacheEvictsTheLeastRecentlyUsed() {
        // An unbounded cache would grow for the whole session and become its own memory problem, which is
        // the opposite of the point.
        NbtStringCache cache = new NbtStringCache();

        for (int i = 0; i < NbtStringCache.CAPACITY; i++) {
            cache.intern("key." + i);
        }
        // Touch the first one, so it is the most recently used rather than the oldest.
        cache.intern("key.0");
        cache.intern("overflow");

        assertEquals(1L, cache.evicted());
        assertTrue(cache.intern("key.0") != null, "the touched one survived");
    }

    @Test
    void accessOrderRatherThanInsertionOrder() {
        // LinkedHashMap with the access flag orders by access, which is what "least recently used" means;
        // insertion order would evict the oldest entry even if it is used every tick.
        NbtStringCache cache = new NbtStringCache();

        cache.intern("hot");
        for (int i = 0; i < NbtStringCache.CAPACITY - 1; i++) {
            cache.intern("cold." + i);
        }
        cache.intern("hot");
        cache.intern("new");

        assertEquals(NbtStringCache.CAPACITY, cache.size());
        assertEquals(1L, cache.evicted());
    }

    @Test
    void aModpackRepeatsTheSameFewValues() {
        NbtStringCache cache = new NbtStringCache();

        for (int stack = 0; stack < 10_000; stack++) {
            cache.intern("minecraft:chest");
            cache.intern("id");
            cache.intern("Count");
        }

        assertEquals(30_000L, cache.requests());
        assertEquals(29_997L, cache.reused());
        assertEquals(3L, cache.cached());
        assertEquals(99.99, cache.reusePercent(), 0.01);
    }

    @Test
    void clearingDropsEveryString() {
        NbtStringCache cache = new NbtStringCache();
        cache.intern(CHEST);

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        NbtStringCache cache = new NbtStringCache();

        assertThrows(IllegalArgumentException.class, () -> cache.intern(null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has been asked nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        NbtStringCache cache = new NbtStringCache();

        assertEquals(0.0, cache.reusePercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0L, cache.bytesSaved());
        assertEquals(0L, cache.requests());
        assertEquals(0, cache.size());
        assertEquals(2048, NbtStringCache.CAPACITY);
        assertEquals(64, NbtStringCache.MAX_LENGTH);
    }
}
