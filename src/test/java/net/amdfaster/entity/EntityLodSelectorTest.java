package net.amdfaster.entity;

import net.amdfaster.entity.EntityLodSelector.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityLodSelectorTest {

    @Test
    void aCloseEntityUsesTheFullModel() {
        // An entity the player is looking at close up is the one they notice most, and it is also the one where
        // a reduction would be most visible.
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(Tier.FULL, selector.select(100.0, false));
        assertEquals(1L, selector.full());
    }

    @Test
    void aDistantEntityUsesTheCoarseMesh() {
        // A cow two hundred blocks away is four pixels tall and still pays the full cost of a model built to
        // look right at four blocks.
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(Tier.COARSE, selector.select(8.0, false));
        assertEquals(1L, selector.coarse());
    }

    @Test
    void aTinyEntityIsNotDrawnAtAll() {
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(Tier.CULL, selector.select(1.0, false));
        assertEquals(1L, selector.culled());
    }

    @Test
    void theTransitionHasAGapSoItDoesNotFlicker() {
        // An entity hovering exactly at the threshold would switch tiers every frame as the distance jitters,
        // and the model would visibly change shape back and forth.
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(Tier.COARSE, selector.select(20.0, true),
                "already coarse, so it stays coarse until twenty-four pixels");
        assertEquals(Tier.FULL, selector.select(20.0, false),
                "and a full one only drops at sixteen");
    }

    @Test
    void theGapIsWideEnoughToMatter() {
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(Tier.COARSE, selector.select(16.0, true));
        assertEquals(Tier.FULL, selector.select(24.0, true));
        assertEquals(Tier.COARSE, selector.select(23.9, true));
        assertEquals(16.0, EntityLodSelector.COARSE_BELOW_PIXELS, 1e-12);
        assertEquals(24.0, EntityLodSelector.COARSE_UNTIL_PIXELS, 1e-12);
    }

    @Test
    void aHorizonFullOfEntitiesCostsAFraction() {
        EntityLodSelector selector = new EntityLodSelector();

        for (int i = 0; i < 200; i++) {
            selector.select(60.0, false);
        }
        for (int i = 0; i < 800; i++) {
            selector.select(8.0, false);
        }

        assertEquals(1000L, selector.entities());
        assertEquals(200L, selector.full());
        assertEquals(800L, selector.coarse());
        assertEquals(19.6, selector.msSkinning(), 1e-9);
        assertEquals(50.0, selector.msWithoutLod(), 1e-9);
        assertEquals(60.8, selector.savedPercent(), 0.01);
    }

    @Test
    void culledEntitiesCostNothingEitherWay() {
        EntityLodSelector selector = new EntityLodSelector();

        for (int i = 0; i < 100; i++) {
            selector.select(0.5, false);
        }

        assertEquals(100L, selector.culled());
        assertEquals(0.0, selector.msWithoutLod(), 1e-12, "they are in neither total");
        assertEquals(0.0, selector.savedPercent(), 1e-9);
    }

    @Test
    void theCoarseRigHasFarFewerBones() {
        assertEquals(21, EntityLodSelector.BONES_FULL);
        assertEquals(6, EntityLodSelector.BONES_COARSE);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EntityLodSelector selector = new EntityLodSelector();

        assertThrows(IllegalArgumentException.class, () -> selector.select(-1.0, false));
    }

    @Test
    void aFreshSelectorReportsZeroNotNaN() {
        // A selector that has drawn nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        EntityLodSelector selector = new EntityLodSelector();

        assertEquals(0.0, selector.savedPercent());
        assertEquals(0.0, selector.msSkinning(), 1e-12);
        assertEquals(0L, selector.entities());
        assertEquals(0L, selector.culled());
        assertEquals(2.0, EntityLodSelector.CULL_BELOW_PIXELS, 1e-12);
    }
}
