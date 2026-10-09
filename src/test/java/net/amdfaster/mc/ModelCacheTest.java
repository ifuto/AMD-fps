package net.amdfaster.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCacheTest {

    /** Counts how many times each id was actually loaded. */
    private static final class CountingLoader implements ModelCache.Loader<String> {
        final int[] loads = new int[4096];

        @Override
        public String load(int blockId) {
            this.loads[blockId]++;
            return "model-" + blockId;
        }
    }

    @Test
    void aModelIsLoadedOnceAndReusedAfterThat() {
        // The lazy half. A plains biome touches a handful of block kinds; loading the whole registry at
        // startup spends time and memory on blocks this world will never contain.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();

        assertEquals("model-1", cache.acquire(1, loader));
        assertEquals("model-1", cache.acquire(1, loader));
        assertEquals("model-1", cache.acquire(1, loader));

        assertEquals(1, loader.loads[1], "loaded exactly once");
        assertEquals(1, cache.loads());
        assertEquals(2, cache.reused(), "two lookups served from the resident entry");
        assertEquals(1, cache.residentCount());
        assertTrue(cache.isResident(1));
    }

    @Test
    void nothingIsLoadedUntilItIsAskedFor() {
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();

        cache.acquire(5, loader);
        assertEquals(1, cache.residentCount(), "one block asked for, one loaded");
        assertEquals(0, loader.loads[6], "and nothing else was touched");
        assertFalse(cache.isResident(6));
        assertEquals(0, cache.referenceCount(6));
    }

    @Test
    void referencesAccumulateAndRelease() {
        // Three loaded sections using the same block take three references. Releasing one must not drop
        // the model, because two sections are still referring to it.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();

        cache.acquire(7, loader);
        cache.acquire(7, loader);
        cache.acquire(7, loader);
        assertEquals(3, cache.referenceCount(7));

        assertFalse(cache.release(7), "two references left");
        assertEquals(2, cache.referenceCount(7));
        assertFalse(cache.release(7));
        assertTrue(cache.release(7), "now unreferenced");
        assertEquals(0, cache.referenceCount(7));
        assertTrue(cache.isResident(7), "resident until something actually evicts it");
    }

    @Test
    void aReferencedModelSurvivesCapacityPressure() {
        // The reason this cache is reference counted rather than simply bounded. Evicting a model that
        // loaded sections refer to would either null-dereference during meshing or force a rebuild of
        // every section using it, which costs more than the memory the eviction saved.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = new ModelCache<>(4);

        cache.acquire(1, loader);
        assertFalse(cache.release(1), "held by nobody else, so now releasable");
        cache.acquire(1, loader);
        cache.acquire(1, loader);
        cache.release(1);
        assertEquals(1, cache.referenceCount(1), "one section still using it");

        // Fill past capacity with unreferenced entries.
        for (int id = 100; id < 110; id++) {
            cache.acquire(id, loader);
            cache.release(id);
        }

        assertTrue(cache.isResident(1), "the referenced model is still there");
        assertEquals("model-1", cache.acquire(1, loader), "and still the same instance");
        assertEquals(1, loader.loads[1], "it was never reloaded");
        assertTrue(cache.evictionRefusals() > 0, "and the refusals were counted, not silent");
    }

    @Test
    void unreferencedEntriesAreEvictedToMakeRoom() {
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = new ModelCache<>(4);

        for (int id = 0; id < 4; id++) {
            cache.acquire(id, loader);
            cache.release(id);
        }
        assertEquals(4, cache.residentCount(), "at capacity, all releasable");

        cache.acquire(50, loader);
        assertTrue(cache.residentCount() <= 4, "one was dropped to make room");
        assertTrue(cache.evictions() > 0);
        assertTrue(cache.isResident(50), "and the new one is in");
    }

    @Test
    void collidingIdsDoNotDisplaceEachOther() {
        // A direct-mapped table would put 1 and 5 in the same slot and resolving that means throwing
        // away whichever model was there -- which is precisely the eviction of a referenced model this
        // class exists to refuse. Grass drawn with dirt's quads is not an exception, it is just wrong.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = new ModelCache<>(4);

        cache.acquire(1, loader);
        cache.acquire(5, loader);
        cache.acquire(9, loader);
        cache.acquire(13, loader);

        assertEquals("model-1", cache.acquire(1, loader), "1 is still 1");
        assertEquals("model-5", cache.acquire(5, loader), "5 is still 5");
        assertEquals(4, cache.residentCount(), "four distinct entries, none displaced");
        assertEquals(4, loader.loads[1] + loader.loads[5] + loader.loads[9] + loader.loads[13],
                "each loaded exactly once, so nothing was rebuilt after being clobbered");
    }

    @Test
    void releasingSomethingNotResidentIsHarmless() {
        ModelCache<String> cache = ModelCache.withDefaultCapacity();
        assertFalse(cache.release(42), "never loaded");
        assertEquals(0, cache.referenceCount(42));

        CountingLoader loader = new CountingLoader();
        cache.acquire(42, loader);
        assertTrue(cache.release(42));
        assertFalse(cache.release(42), "and releasing twice does not drive the count negative");
        assertEquals(0, cache.referenceCount(42));
    }

    @Test
    void evictUnreferencedKeepsWhatIsInUse() {
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();

        cache.acquire(1, loader);
        cache.acquire(2, loader);
        cache.release(2);

        assertEquals(1, cache.evictUnreferenced(), "only the released one");
        assertTrue(cache.isResident(1), "the referenced one stays");
        assertFalse(cache.isResident(2));
        assertEquals(1, cache.residentCount());
    }

    @Test
    void clearIsNotAWipeBecauseReferencedEntriesBelongToLoadedSections() {
        // A caller that genuinely wants everything gone has to release the references first, which means
        // unloading the sections. Doing it in the other order would strand geometry pointing at models
        // that are no longer there.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();
        cache.acquire(1, loader);
        cache.acquire(2, loader);
        cache.release(2);

        cache.clear();
        assertTrue(cache.isResident(1), "referenced, so kept");
        assertFalse(cache.isResident(2), "unreferenced, so dropped");
        assertEquals(0, cache.loads(), "counters reset");
        assertEquals(0, cache.reused());
        assertTrue(cache.evictionRefusals() > 0, "and the refusal was recorded");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCache<String>(0));
        assertThrows(IllegalArgumentException.class, () -> new ModelCache<String>(-4));
        assertEquals(ModelCache.DEFAULT_CAPACITY, ModelCache.withDefaultCapacity().capacity());
    }

    @Test
    void theSameInstanceIsHandedBackEveryTime() {
        // Not merely an equal model -- the same object. Geometry holds references into it, so a fresh
        // equal instance would leave every built section pointing at the old one anyway.
        CountingLoader loader = new CountingLoader();
        ModelCache<String> cache = ModelCache.withDefaultCapacity();
        String first = cache.acquire(3, loader);
        String second = cache.acquire(3, loader);
        assertSame(first, second);
    }
}
