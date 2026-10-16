package net.amdfaster.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBakeCacheTest {

    private static final String VERSION = "1.21.11";
    private static final String PACKS = "vanilla,fabric";
    private static final String MODS = "amdfaster,sodium";

    private static String identity() {
        return ModelBakeCache.identity(PACKS, MODS, VERSION);
    }

    @Test
    void aFreshCacheHasNothingToLoad() {
        ModelBakeCache cache = new ModelBakeCache();

        assertFalse(cache.matches(identity()));
        assertFalse(cache.hasCache());
    }

    @Test
    void theSameLaunchTwiceLoadsFromDisk() {
        // The same pack and the same mods produce the same quads, so the second launch does not need to
        // bake them again.
        ModelBakeCache cache = new ModelBakeCache();

        assertFalse(cache.matches(identity()));
        cache.write(identity(), ModelBakeCache.PACK_MODELS);

        assertTrue(cache.matches(identity()));
        cache.load(ModelBakeCache.PACK_MODELS);

        assertEquals(200_000L, cache.baked());
        assertEquals(200_000L, cache.loaded());
        assertEquals(1L, cache.writes());
        assertEquals(0L, cache.discarded());
    }

    @Test
    void changingAResourcePackDiscardsTheCache() {
        // A pack retextures blocks, and switching packs has to change them back. Serving models that no
        // longer match would show the wrong textures, and the player would have no way to connect it to a
        // cache written three launches ago.
        ModelBakeCache cache = new ModelBakeCache();
        cache.write(identity(), 100);

        assertFalse(cache.matches(ModelBakeCache.identity("vanilla,fabric,faithful", MODS, VERSION)));
        assertEquals(1L, cache.discarded());
        assertFalse(cache.hasCache());
    }

    @Test
    void addingAModDiscardsTheCache() {
        // Removing one must remove its models, or blocks from an uninstalled mod would keep rendering with
        // no mod behind them.
        ModelBakeCache cache = new ModelBakeCache();
        cache.write(identity(), 100);

        assertFalse(cache.matches(ModelBakeCache.identity(PACKS, "amdfaster,sodium,iris", VERSION)));
        assertEquals(1L, cache.discarded());
    }

    @Test
    void updatingTheGameDiscardsTheCache() {
        // The model format changes between versions, and a cache written by an older one may not describe
        // the same geometry.
        ModelBakeCache cache = new ModelBakeCache();
        cache.write(identity(), 100);

        assertFalse(cache.matches(ModelBakeCache.identity(PACKS, MODS, "1.21.12")));
        assertEquals(1L, cache.discarded());
    }

    @Test
    void aDiscardedCacheIsDroppedRatherThanUpdated() {
        // Partial reuse would mean deciding which models a given change affects, and getting that wrong is
        // the same bug with a smaller blast radius.
        ModelBakeCache cache = new ModelBakeCache();
        cache.write(identity(), 100);

        cache.matches(ModelBakeCache.identity(PACKS, MODS, "1.21.12"));

        assertFalse(cache.matches(identity()), "the old identity does not come back either");
    }

    @Test
    void aLaunchFromCacheSavesNinetyFivePercent() {
        ModelBakeCache cache = new ModelBakeCache();
        int models = ModelBakeCache.PACK_MODELS;

        assertEquals(4000.0, cache.msBaking(models), 1e-9);
        assertEquals(200.0, cache.msLoading(models), 1e-9);
        assertEquals(95.0, cache.savedPercent(models), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ModelBakeCache cache = new ModelBakeCache();

        assertThrows(IllegalArgumentException.class, () -> cache.matches(null));
        assertThrows(IllegalArgumentException.class, () -> cache.matches(""));
        assertThrows(IllegalArgumentException.class, () -> cache.write(identity(), -1));
        assertThrows(IllegalArgumentException.class, () -> cache.load(-1));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has served nothing has not yet paid for itself, and reporting that as a rate would
        // describe an unused one as though it were working.
        ModelBakeCache cache = new ModelBakeCache();

        assertEquals(0.0, cache.launchesServed());
        assertEquals(0L, cache.baked());
        assertEquals(0L, cache.loaded());
        assertEquals(0L, cache.writes());
        assertEquals(0.0, cache.savedPercent(0));
    }
}
