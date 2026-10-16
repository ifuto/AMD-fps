package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChangeRegistryTest {

    /** Records how many times it was cleared, so idempotence can be checked. */
    private static final class CountingCache implements WorldChangeRegistry.Clearable {
        private final String name;
        private int cleared;

        CountingCache(String name) {
            this.name = name;
        }

        @Override
        public String cacheName() {
            return this.name;
        }

        @Override
        public void clearForWorldChange() {
            this.cleared++;
        }
    }

    /** Throws, to check that one broken cache does not stop the others. */
    private static final class BrokenCache implements WorldChangeRegistry.Clearable {
        @Override
        public String cacheName() {
            return "broken";
        }

        @Override
        public void clearForWorldChange() {
            throw new IllegalStateException("this cache does not clear");
        }
    }

    @Test
    void everyRegisteredCacheIsCleared() {
        // A leak is not caused by a cache that clears itself badly; it is caused by one that was never
        // told.
        WorldChangeRegistry registry = new WorldChangeRegistry();
        List<CountingCache> caches = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            CountingCache cache = new CountingCache("cache" + i);
            caches.add(cache);
            registry.register(cache);
        }

        assertEquals(12, registry.clearAll());
        assertEquals(12, registry.registered());

        for (CountingCache cache : caches) {
            assertEquals(1, cache.cleared, cache.cacheName() + " was told");
        }
        assertTrue(registry.allCleared());
    }

    @Test
    void clearingTwiceLeavesTheSameStateAsClearingOnce() {
        // A world change can be signalled more than once: disconnecting, then loading a save, then being
        // kicked back to the menu. A cache that throws on the second clear would turn a routine logout
        // into a crash.
        WorldChangeRegistry registry = new WorldChangeRegistry();
        CountingCache cache = new CountingCache("meshes");
        registry.register(cache);

        assertEquals(1, registry.clearAll());
        assertEquals(1, registry.clearAll());

        assertEquals(2, cache.cleared, "cleared again, without complaint");
        assertTrue(registry.allCleared());
    }

    @Test
    void aFailureInOneCacheDoesNotStopTheOthers() {
        // Throwing here would leave every remaining cache holding the old world, so the leak this exists
        // to prevent would happen anyway, along with an exception that hides the real problem.
        WorldChangeRegistry registry = new WorldChangeRegistry();
        CountingCache before = new CountingCache("before");
        CountingCache after = new CountingCache("after");
        registry.register(before);
        registry.register(new BrokenCache());
        registry.register(after);

        assertEquals(2, registry.clearAll(), "the two working caches were cleared");
        assertEquals(1, before.cleared);
        assertEquals(1, after.cleared, "cleared despite the failure before it");

        assertFalse(registry.allCleared());
        assertEquals(1, registry.failures().size());
        assertTrue(registry.failures().get(0).startsWith("broken: "),
                "the failure names the cache that leaked");
    }

    @Test
    void aCacheIsReusedRatherThanRebuilt() {
        // Clearing empties a cache; it does not discard it. The next world uses the same objects, so the
        // allocations made to build them are not repeated.
        WorldChangeRegistry registry = new WorldChangeRegistry();
        CountingCache cache = new CountingCache("sections");
        registry.register(cache);

        registry.clearAll();
        registry.clearAll();
        registry.clearAll();

        assertEquals(1, registry.registered(), "still one cache, cleared three times");
        assertEquals(3, cache.cleared);
    }

    @Test
    void registeringNothingIsRejected() {
        WorldChangeRegistry registry = new WorldChangeRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void aFreshRegistryReportsZeroNotNaN() {
        WorldChangeRegistry registry = new WorldChangeRegistry();

        assertEquals(0, registry.registered());
        assertEquals(0L, registry.clears());
        assertEquals(0, registry.clearAll(), "nothing registered, so nothing cleared");
        assertTrue(registry.allCleared());
        assertEquals(0, registry.failures().size());
    }
}
