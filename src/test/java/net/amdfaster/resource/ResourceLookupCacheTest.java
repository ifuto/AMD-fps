package net.amdfaster.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceLookupCacheTest {

    private static final String STONE = "textures/block/stone.png";

    @Test
    void theFirstLookupSearchesAndTheRestAreCached() {
        // The search happens for every block drawn, every frame, and its answer changes only when a
        // resource pack is reloaded.
        ResourceLookupCache cache = new ResourceLookupCache();
        AtomicInteger searches = new AtomicInteger();

        assertEquals(STONE, cache.resolve("minecraft", "block/stone",
                () -> { searches.incrementAndGet(); return STONE; }));
        assertEquals(STONE, cache.resolve("minecraft", "block/stone",
                () -> { searches.incrementAndGet(); return STONE; }));

        assertEquals(1, searches.get(), "searched once");
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.resolvedCount());
    }

    @Test
    void aReloadInvalidatesEverything() {
        // Reloading is how a player applies a new texture pack, and a cache that survived it would keep
        // drawing the old textures -- the player would have installed a pack and seen no change.
        ResourceLookupCache cache = new ResourceLookupCache();
        AtomicInteger searches = new AtomicInteger();

        cache.resolve("minecraft", "block/stone", () -> { searches.incrementAndGet(); return STONE; });
        cache.invalidate();

        assertEquals(0, cache.size());
        assertEquals(1L, cache.reloads());

        cache.resolve("minecraft", "block/stone",
                () -> { searches.incrementAndGet(); return "textures/block/stone_v2.png"; });

        assertEquals(2, searches.get(), "searched again after the reload");
    }

    @Test
    void aFailedLookupIsCachedToo() {
        // A mod may reference a texture it does not ship. Searching for it every frame would repeat the
        // same failed search thousands of times a second.
        ResourceLookupCache cache = new ResourceLookupCache();
        AtomicInteger searches = new AtomicInteger();

        assertNull(cache.resolve("somemod", "block/missing",
                () -> { searches.incrementAndGet(); return null; }));
        assertNull(cache.resolve("somemod", "block/missing",
                () -> { searches.incrementAndGet(); return null; }));

        assertEquals(1, searches.get(), "the failed search was not repeated");
        assertEquals(2L, cache.missing(),
                "both lookups asked for a resource that does not exist");
        assertEquals(1L, cache.hits(), "the second was served from the cache");
    }

    @Test
    void theNamespaceIsPartOfTheKey() {
        // minecraft:block/stone and somemod:block/stone are different resources, and a key of the path alone
        // would let one resolve to the other -- a mod's blocks drawn with vanilla textures, which looks like
        // the mod failed to load.
        assertNotEquals(ResourceLookupCache.key("minecraft", "block/stone"),
                ResourceLookupCache.key("somemod", "block/stone"));
        assertEquals(ResourceLookupCache.key("minecraft", "block/stone"),
                ResourceLookupCache.key("minecraft", "block/stone"));
    }

    @Test
    void aFrameDrawingManyBlocksResolvesAlmostNothing() {
        ResourceLookupCache cache = new ResourceLookupCache();
        AtomicInteger searches = new AtomicInteger();

        for (int i = 0; i < 200; i++) {
            cache.resolve("minecraft", "block/" + (i % 50),
                    () -> { searches.incrementAndGet(); return STONE; });
        }
        for (int frame = 0; frame < 60; frame++) {
            for (int i = 0; i < 200; i++) {
                cache.resolve("minecraft", "block/" + (i % 50),
                        () -> { searches.incrementAndGet(); return STONE; });
            }
        }

        assertEquals(12_200L, cache.lookups());
        assertEquals(50, searches.get(), "fifty distinct resources, searched once each");
        assertEquals(12_150L, cache.hits());
        assertEquals(99.59, cache.hitPercent(), 0.01);
    }

    @Test
    void theSavingIsAlmostAllOfTheSearchCost() {
        ResourceLookupCache cache = new ResourceLookupCache();

        cache.resolve("minecraft", "block/stone", () -> STONE);
        for (int i = 0; i < 999; i++) {
            cache.resolve("minecraft", "block/stone", () -> STONE);
        }

        assertEquals(1000 * ResourceLookupCache.MS_PER_LOOKUP, cache.msWithoutCache(), 1e-9);
        assertEquals(0.5, cache.msWithoutCache(), 1e-9);
        assertEquals(98.90, cache.savedPercent(), 0.01,
                "0.5 ms of searching reduced to 0.0055 -- the one real search still counts");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ResourceLookupCache cache = new ResourceLookupCache();

        assertThrows(IllegalArgumentException.class,
                () -> ResourceLookupCache.key(null, "path"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceLookupCache.key("", "path"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceLookupCache.key("minecraft", null));
        assertThrows(IllegalArgumentException.class,
                () -> cache.resolve("minecraft", "block/stone", null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache asked nothing has technically avoided everything, but reporting that as a saving would
        // describe an unused one as though it had done something.
        ResourceLookupCache cache = new ResourceLookupCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSpent());
        assertEquals(0L, cache.lookups());
        assertEquals(0L, cache.reloads());
        assertEquals(0, cache.size());
    }
}
