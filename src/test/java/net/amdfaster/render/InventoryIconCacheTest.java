package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryIconCacheTest {

    private static final String COBBLE = "minecraft:cobblestone";
    private static final String SWORD = "minecraft:diamond_sword";

    @Test
    void theFirstFrameBuildsAndTheRestReuse() {
        // An inventory that is being looked at rather than sorted is the common case, and in it every slot
        // is unchanged.
        InventoryIconCache cache = new InventoryIconCache();

        assertTrue(cache.needsRebuild(COBBLE, 0, false));
        cache.store(COBBLE, 0, false);

        assertFalse(cache.needsRebuild(COBBLE, 0, false), "the same item, so the geometry is reused");
        assertEquals(1L, cache.built());
        assertEquals(1L, cache.hits());
    }

    @Test
    void theCountIsNotPartOfTheIcon() {
        // A stack of sixty-four cobblestone and a stack of one use the same icon; the number is drawn as
        // text over it in a separate pass. Rebuilding for a count change would discard work for a change
        // the icon does not show.
        InventoryIconCache cache = new InventoryIconCache();

        cache.store(COBBLE, 0, false);

        assertFalse(cache.needsRebuild(COBBLE, 0, false), "no count in the key, so nothing to invalidate");
        assertEquals(0L, cache.built());
    }

    @Test
    void damageChangesTheIcon() {
        // A damaged tool draws differently. Treating it like the count would leave a sword looking brand new
        // after it had been used, and a player checks that by looking.
        InventoryIconCache cache = new InventoryIconCache();

        cache.store(SWORD, 0, false);

        assertTrue(cache.needsRebuild(SWORD, 1, false));
        assertNotEquals(InventoryIconCache.key(SWORD, 0, false),
                InventoryIconCache.key(SWORD, 1, false));
    }

    @Test
    void enchantmentChangesTheIcon() {
        // An enchanted item has the glint over it, which is part of the icon rather than drawn on top of it.
        InventoryIconCache cache = new InventoryIconCache();

        cache.store(SWORD, 0, false);

        assertTrue(cache.needsRebuild(SWORD, 0, true));
    }

    @Test
    void swappingTheItemRebuilds() {
        // The obvious case, and the one that has to be right or the inventory shows the wrong thing.
        InventoryIconCache cache = new InventoryIconCache();

        cache.store(COBBLE, 0, false);

        assertTrue(cache.needsRebuild(SWORD, 0, false));
    }

    @Test
    void aFullCacheDropsTheOldestIcon() {
        // A creative inventory pages through hundreds of items and a long session would accumulate an icon
        // for every one ever displayed.
        InventoryIconCache cache = new InventoryIconCache();

        for (int i = 0; i < InventoryIconCache.CAPACITY; i++) {
            cache.needsRebuild("item." + i, 0, false);
            cache.store("item." + i, 0, false);
        }
        cache.store("item.overflow", 0, false);

        assertEquals(1L, cache.evicted());
        assertTrue(cache.needsRebuild("item.0", 0, false), "the oldest was dropped");
    }

    @Test
    void anOpenSurvivalInventoryRebuildsAlmostNothing() {
        InventoryIconCache cache = new InventoryIconCache();

        for (int slot = 0; slot < InventoryIconCache.SURVIVAL_SLOTS; slot++) {
            cache.needsRebuild("item." + slot, 0, false);
            cache.store("item." + slot, 0, false);
        }
        for (int frame = 0; frame < 600; frame++) {
            for (int slot = 0; slot < InventoryIconCache.SURVIVAL_SLOTS; slot++) {
                cache.needsRebuild("item." + slot, 0, false);
            }
        }

        assertEquals(46L * 601, cache.requests());
        assertEquals(46L * 600, cache.hits());
        assertEquals(46L, cache.built());
        assertEquals(99.83, cache.savedPercent(), 0.01);
    }

    @Test
    void closingTheScreenDropsTheIcons() {
        InventoryIconCache cache = new InventoryIconCache();
        cache.store(COBBLE, 0, false);

        cache.clear();

        assertTrue(cache.needsRebuild(COBBLE, 0, false));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        InventoryIconCache cache = new InventoryIconCache();

        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild("", 0, false));
        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(COBBLE, -1, false));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache asked nothing has technically avoided everything, but reporting that as a saving would
        // describe an unused one as though it had done something.
        InventoryIconCache cache = new InventoryIconCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSaved());
        assertEquals(0L, cache.requests());
        assertEquals(0L, cache.built());
        assertEquals(46, InventoryIconCache.SURVIVAL_SLOTS);
    }
}
