package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CullingExclusionListTest {

    private static final String BEACON = "minecraft:beacon_beam";
    private static final String ZOMBIE = "minecraft:zombie";

    @Test
    void nothingIsExcludedByDefault() {
        // A culling optimisation that starts by assuming some entities are special has to be right about
        // every one of them from the first frame. One that starts excluding nothing is simply culling, and
        // the exclusions are additions someone deliberately made.
        CullingExclusionList list = new CullingExclusionList();

        assertEquals(0, list.size());
        assertFalse(list.isExcluded(BEACON));
    }

    @Test
    void anExcludedEntityIsDrawnEvenWhenOccluded() {
        // A beacon's beam is visible from behind a hill by design. Culling it removes a signal the game
        // intends to give, and the player experiences that as a rendering bug.
        CullingExclusionList list = new CullingExclusionList();
        list.exclude(BEACON);

        assertTrue(list.shouldDraw(BEACON, true), "occluded, but excluded from culling");
        assertEquals(1L, list.bypassed());
    }

    @Test
    void anOrdinaryEntityIsCulledWhenOccluded() {
        CullingExclusionList list = new CullingExclusionList();

        assertFalse(list.shouldDraw(ZOMBIE, true));
        assertEquals(1L, list.culled());
    }

    @Test
    void aVisibleEntityIsAlwaysDrawn() {
        CullingExclusionList list = new CullingExclusionList();

        assertTrue(list.shouldDraw(ZOMBIE, false));
        assertEquals(1L, list.drawn());
    }

    @Test
    void anExcludedEntityThatIsVisibleIsNotCountedAsBypassed() {
        // It was never going to be culled, so recording it as a bypass would claim credit for work the
        // culler did not avoid.
        CullingExclusionList list = new CullingExclusionList();
        list.exclude(BEACON);

        assertTrue(list.shouldDraw(BEACON, false));
        assertEquals(0L, list.bypassed());
        assertEquals(1L, list.drawn());
    }

    @Test
    void removingAnExclusionRestoresCulling() {
        CullingExclusionList list = new CullingExclusionList();

        list.exclude(BEACON);
        list.include(BEACON);

        assertFalse(list.isExcluded(BEACON));
        assertFalse(list.shouldDraw(BEACON, true));
    }

    @Test
    void anEmptyTypeIsRejected() {
        CullingExclusionList list = new CullingExclusionList();

        assertThrows(IllegalArgumentException.class, () -> list.exclude(null));
        assertThrows(IllegalArgumentException.class, () -> list.exclude(""));
    }

    @Test
    void aFreshListReportsZeroNotNaN() {
        CullingExclusionList list = new CullingExclusionList();

        assertEquals(0.0, list.culledPercent());
        assertEquals(0.0, list.msSavedPerFrame());
        assertEquals(0L, list.tests());
        assertEquals(0L, list.bypassed());
        assertEquals(0, list.size());
    }
}
