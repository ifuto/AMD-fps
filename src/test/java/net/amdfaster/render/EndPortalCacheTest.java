package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndPortalCacheTest {

    @Test
    void theSameLayerCountIsNotRebuilt() {
        // Six portals at sixteen layers each is ninety-six layers regenerated every frame, about 1 ms --
        // for geometry where every layer is the same plane.
        EndPortalCache cache = new EndPortalCache();

        assertTrue(cache.needsRebuild(EndPortalCache.LAYERS), "the first frame builds it");
        for (int i = 0; i < 99; i++) {
            assertFalse(cache.needsRebuild(EndPortalCache.LAYERS), "frame " + i);
        }

        assertEquals(1L, cache.rebuilt());
        assertEquals(99L, cache.reused());
        assertEquals(99.0, cache.reusedPercent(), 0.01);
    }

    @Test
    void aDifferentLayerCountRebuilds() {
        EndPortalCache cache = new EndPortalCache();
        cache.needsRebuild(16);

        assertTrue(cache.needsRebuild(8));
        assertEquals(8, cache.cachedLayerCount());
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void eachLayerScrollsAtItsOwnRate() {
        // Which is what makes the portal look like it has depth rather than being a single moving
        // picture.
        double a = EndPortalCache.scrollAt(0, 10L, 1.0);
        double b = EndPortalCache.scrollAt(1, 10L, 1.0);
        double c = EndPortalCache.scrollAt(2, 10L, 1.0);

        assertEquals(10.0, a, 1e-9);
        assertEquals(20.0, b, 1e-9);
        assertEquals(30.0, c, 1e-9);
    }

    @Test
    void thePortalKeepsScrolling() {
        // A frozen portal looks like a painted texture. That is the same class of wrongness as frozen
        // rain and a frozen beacon beam: the scene feels dead and the player cannot say why.
        double a = EndPortalCache.scrollAt(3, 100L, 0.5);
        double b = EndPortalCache.scrollAt(3, 101L, 0.5);

        assertTrue(b > a, "the portal is still scrolling");
    }

    @Test
    void theLayersFadeWithDepth() {
        assertEquals(1.0, EndPortalCache.opacityAt(0, 16), 1e-9);
        assertEquals(0.5, EndPortalCache.opacityAt(8, 16), 1e-9);
        assertEquals(0.0625, EndPortalCache.opacityAt(15, 16), 1e-9);
        assertTrue(EndPortalCache.opacityAt(0, 16) > EndPortalCache.opacityAt(15, 16));
    }

    @Test
    void theLayerOrderIsPreserved() {
        // Blending is order-dependent, so reordering the layers changes the colour of the portal, which
        // is visible on the one block in the game that every player looks at closely.
        assertTrue(EndPortalCache.preservesOrder(new int[] {0, 1, 2, 3}));
        assertFalse(EndPortalCache.preservesOrder(new int[] {0, 2, 1, 3}), "swapped");
        assertFalse(EndPortalCache.preservesOrder(new int[] {0, 1, 1, 3}), "duplicated");
        assertTrue(EndPortalCache.preservesOrder(new int[] {}), "nothing to reorder");
    }

    @Test
    void theGeometryDoesNotChangeWhileThePortalAnimates() {
        // Both things that change often -- the offsets and the opacities -- are uniforms. The expensive
        // thing, the geometry, does not change at all.
        EndPortalCache cache = new EndPortalCache();
        cache.needsRebuild(16);

        for (long tick = 0; tick < 100; tick++) {
            assertFalse(cache.needsRebuild(16));
            assertTrue(EndPortalCache.scrollAt(0, tick, 0.5) != EndPortalCache.scrollAt(0, tick + 1, 0.5));
        }
    }

    @Test
    void negativeWorldTimeIsHandled() {
        assertTrue(EndPortalCache.scrollAt(0, -5L, 0.5) < 0.0);
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        EndPortalCache cache = new EndPortalCache();
        cache.needsRebuild(16);

        cache.invalidate();

        assertEquals(0, cache.cachedLayerCount());
        assertTrue(cache.needsRebuild(16));
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        EndPortalCache cache = new EndPortalCache();
        for (int i = 0; i < 100; i++) {
            cache.needsRebuild(16);
        }

        // 16 layers at 10 microseconds each, plus the uniforms.
        assertEquals(0.161, EndPortalCache.msPerFrameWithoutCache(1), 1e-9);
        assertTrue(cache.savedPercent() > 90.0, "was " + cache.savedPercent());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EndPortalCache cache = new EndPortalCache();

        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(0));
        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(-1));
        assertThrows(IllegalArgumentException.class, () -> EndPortalCache.opacityAt(0, 0));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused cache reads as a result when it is the absence of one.
        EndPortalCache cache = new EndPortalCache();

        assertEquals(0.0, cache.savedPercent(), "no frames drawn, so nothing saved");
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0.0, cache.reusedPercent());
        assertEquals(0L, cache.frames());
        assertEquals(16, EndPortalCache.LAYERS);
    }
}
