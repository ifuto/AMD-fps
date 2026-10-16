package net.amdfaster.craft;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSearchCacheTest {

    private static final AtomicInteger WALKS = new AtomicInteger();

    private static java.util.function.IntSupplier counting(int value) {
        return () -> {
            WALKS.incrementAndGet();
            return value;
        };
    }

    /** A pickaxe: three across the top, two sticks down the middle. */
    private static final int[] PICKAXE = {7, 7, 7, 0, 1, 0, 0, 1, 0};

    /** The same materials arranged as a shovel. */
    private static final int[] SHOVEL = {0, 7, 0, 0, 1, 0, 0, 1, 0};

    @Test
    void theSameGridIsNotSearchedAgain() {
        // A wall of furnaces is the same search repeated twenty times a second for an answer that never
        // changes, at three thousand recipes and about 6 ms of comparison each time.
        RecipeSearchCache cache = new RecipeSearchCache();
        WALKS.set(0);
        long key = RecipeSearchCache.shapedKey(PICKAXE);

        for (int tick = 0; tick < 100; tick++) {
            assertEquals(42, cache.result(key, counting(42)));
        }

        assertEquals(1, WALKS.get(), "walked the list once, served ninety-nine times");
        assertEquals(99L, cache.hits());
        assertEquals(99.0, cache.hitPercent(), 0.01);
    }

    @Test
    void aDifferentArrangementIsADifferentRecipe() {
        // A pickaxe and a shovel use the same materials in a different arrangement, and treating a
        // rotation or a mirror as the same grid would return whichever recipe happened to be found
        // first. The player would take out something other than what they crafted.
        RecipeSearchCache cache = new RecipeSearchCache();
        WALKS.set(0);

        assertEquals(42, cache.result(RecipeSearchCache.shapedKey(PICKAXE), counting(42)));
        assertEquals(99, cache.result(RecipeSearchCache.shapedKey(SHOVEL), counting(99)));

        assertEquals(2, WALKS.get(), "two searches, not one");
        assertEquals(2, cache.size());
    }

    @Test
    void oneIngredientDifferenceIsADifferentEntry() {
        // Returning the wrong recipe is not a rendering artefact. A diamond where an iron ingot should
        // be is a diamond gone.
        int[] iron = PICKAXE.clone();
        int[] diamond = PICKAXE.clone();
        diamond[0] = 8;

        assertNotEquals(RecipeSearchCache.shapedKey(iron), RecipeSearchCache.shapedKey(diamond));
    }

    @Test
    void aShapedKeyDependsOnEverySlot() {
        int[] base = new int[RecipeSearchCache.GRID_SLOTS];
        long baseKey = RecipeSearchCache.shapedKey(base);

        for (int slot = 0; slot < RecipeSearchCache.GRID_SLOTS; slot++) {
            int[] changed = base.clone();
            changed[slot] = 1;
            assertNotEquals(baseKey, RecipeSearchCache.shapedKey(changed), "slot " + slot);
        }
    }

    @Test
    void aShapelessKeyIgnoresOrder() {
        // A shapeless recipe's arrangement does not matter, and sorting is what makes "any order" one
        // entry rather than factorial-many.
        int[] a = {5, 0, 0, 0, 0, 0, 0, 0, 0};
        int[] b = {0, 0, 0, 0, 0, 0, 0, 0, 5};

        assertNotEquals(RecipeSearchCache.shapedKey(a), RecipeSearchCache.shapedKey(b),
                "shaped, so the arrangement matters");
        assertEquals(RecipeSearchCache.shapelessKey(a), RecipeSearchCache.shapelessKey(b),
                "shapeless, so it does not");
    }

    @Test
    void aCachedMissIsRemembered() {
        // "Nothing matches" is an answer like any other, and re-walking three thousand recipes to
        // confirm it every tick is the common case for an empty grid.
        RecipeSearchCache cache = new RecipeSearchCache();
        WALKS.set(0);
        long key = RecipeSearchCache.shapedKey(new int[RecipeSearchCache.GRID_SLOTS]);

        for (int i = 0; i < 10; i++) {
            assertEquals(RecipeSearchCache.NO_RECIPE, cache.result(key, counting(RecipeSearchCache.NO_RECIPE)));
        }

        assertEquals(1, WALKS.get());
        assertEquals(9L, cache.hits());
    }

    @Test
    void aNewRecipeClearsTheCache() {
        // A mod loaded after the first search can add a recipe that matches a grid already answered with
        // "nothing". Serving the cached miss would mean the recipe does not work, and the player would
        // report a broken recipe rather than a caching mod.
        RecipeSearchCache cache = new RecipeSearchCache();
        long key = RecipeSearchCache.shapedKey(PICKAXE);
        cache.result(key, counting(RecipeSearchCache.NO_RECIPE));
        assertTrue(cache.isCached(key));

        cache.invalidate();

        assertFalse(cache.isCached(key));
        assertEquals(1L, cache.invalidations());
    }

    @Test
    void entriesSurviveGrowth() {
        RecipeSearchCache cache = new RecipeSearchCache(16);
        WALKS.set(0);

        for (int i = 0; i < 1000; i++) {
            int[] slots = new int[RecipeSearchCache.GRID_SLOTS];
            slots[0] = i;
            cache.result(RecipeSearchCache.shapedKey(slots), counting(i));
        }
        assertEquals(1000, cache.size());

        int before = WALKS.get();
        for (int i = 0; i < 1000; i++) {
            int[] slots = new int[RecipeSearchCache.GRID_SLOTS];
            slots[0] = i;
            cache.result(RecipeSearchCache.shapedKey(slots), counting(i));
        }
        assertEquals(before, WALKS.get(), "every one still cached after the table grew");
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        RecipeSearchCache cache = new RecipeSearchCache();
        long key = RecipeSearchCache.shapedKey(PICKAXE);
        for (int i = 0; i < 100; i++) {
            cache.result(key, counting(42));
        }

        assertEquals(600.0, cache.msWithoutCache(), 1e-9);
        assertEquals(99 * (6.0 - 0.001), cache.msSaved(), 1e-9);
        assertEquals(98.9835, cache.savedPercent(), 0.001);
        assertEquals(6.0, RecipeSearchCache.MS_PER_SEARCH, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecipeSearchCache(8));
        assertThrows(IllegalArgumentException.class, () -> new RecipeSearchCache(1000));
        assertThrows(IllegalArgumentException.class, () -> RecipeSearchCache.shapedKey(new int[4]));

        RecipeSearchCache cache = new RecipeSearchCache();
        assertThrows(IllegalArgumentException.class, () -> cache.result(1L, null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused cache reads as a result when it is the absence of one.
        RecipeSearchCache cache = new RecipeSearchCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.savedPercent(), "no searches, so nothing saved");
        assertEquals(0.0, cache.msSaved());
        assertEquals(0, cache.size());
        assertEquals(9, RecipeSearchCache.GRID_SLOTS);
        assertEquals(-1, RecipeSearchCache.NO_RECIPE);
    }
}
