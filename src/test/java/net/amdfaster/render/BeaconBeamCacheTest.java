package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeaconBeamCacheTest {

    private static final long WHITE = BeaconBeamCache.colourKey(255, 255, 255);
    private static final long RED = BeaconBeamCache.colourKey(255, 0, 0);

    @Test
    void theSameColourIsNotRebuilt() {
        // Twelve beacons at sixty-four segments each is 768 segments regenerated every frame, about
        // 0.4 ms -- for a shape that never changes.
        BeaconBeamCache cache = new BeaconBeamCache();

        assertTrue(cache.needsRebuild(WHITE), "the first frame builds it");
        for (int i = 0; i < 99; i++) {
            assertFalse(cache.needsRebuild(WHITE), "frame " + i);
        }

        assertEquals(1L, cache.rebuilt());
        assertEquals(99L, cache.reused());
        assertEquals(99.0, cache.reusedPercent(), 0.01);
    }

    @Test
    void aColourChangeRebuilds() {
        // A player who swaps the glass above a beacon expects the beam to change colour, and it is a
        // visible, deliberate act: they placed the glass to get that colour. A cached mesh carrying the
        // old colour would ignore it.
        BeaconBeamCache cache = new BeaconBeamCache();
        cache.needsRebuild(WHITE);

        assertTrue(cache.needsRebuild(RED));
        assertEquals(RED, cache.cachedColour());
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void coloursAreDistinctInTheKey() {
        assertTrue(BeaconBeamCache.colourKey(255, 0, 0) != BeaconBeamCache.colourKey(0, 255, 0));
        assertTrue(BeaconBeamCache.colourKey(0, 0, 255) != BeaconBeamCache.colourKey(0, 255, 0));
        assertEquals(0x010203L, BeaconBeamCache.colourKey(1, 2, 3));
    }

    @Test
    void theBeamKeepsScrolling() {
        // A frozen beam looks like a painted pole. That is the same class of wrongness as frozen rain:
        // the scene feels dead and the player cannot say why.
        double a = BeaconBeamCache.scrollAt(100L, 0.5);
        double b = BeaconBeamCache.scrollAt(101L, 0.5);

        assertTrue(b > a, "the beam is still scrolling");
        assertEquals(0.5, b - a, 1e-9);
    }

    @Test
    void theBeamKeepsRotating() {
        double a = BeaconBeamCache.rotationAt(100L, 2.0);
        double b = BeaconBeamCache.rotationAt(101L, 2.0);

        assertTrue(b > a);
        assertEquals(2.0, b - a, 1e-9);
    }

    @Test
    void theMeshDoesNotChangeWhileTheBeamAnimates() {
        // Both things that change often -- position and animation -- are uniforms. The expensive thing,
        // the shape, does not change at all unless the colour does.
        BeaconBeamCache cache = new BeaconBeamCache();
        cache.needsRebuild(WHITE);

        for (long tick = 0; tick < 100; tick++) {
            assertFalse(cache.needsRebuild(WHITE));
            assertTrue(BeaconBeamCache.scrollAt(tick, 0.5) != BeaconBeamCache.scrollAt(tick + 1, 0.5));
            assertTrue(BeaconBeamCache.rotationAt(tick, 2.0) != BeaconBeamCache.rotationAt(tick + 1, 2.0));
        }
    }

    @Test
    void negativeWorldTimeIsHandled() {
        assertTrue(BeaconBeamCache.scrollAt(-5L, 0.5) < 0.0);
        assertTrue(BeaconBeamCache.rotationAt(-5L, 2.0) < 0.0);
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        BeaconBeamCache cache = new BeaconBeamCache();
        cache.needsRebuild(WHITE);

        cache.invalidate();

        assertFalse(cache.hasCache());
        assertTrue(cache.needsRebuild(WHITE));
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        BeaconBeamCache cache = new BeaconBeamCache();
        for (int i = 0; i < 100; i++) {
            cache.needsRebuild(WHITE);
        }

        // 64 segments at half a microsecond each, plus the uniforms.
        assertEquals(0.033, BeaconBeamCache.msPerFrameWithoutCache(), 1e-9);
        assertTrue(cache.savedPercent() > 90.0, "was " + cache.savedPercent());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused cache reads as a result when it is the absence of one.
        BeaconBeamCache cache = new BeaconBeamCache();

        assertEquals(0.0, cache.savedPercent(), "no frames drawn, so nothing saved");
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0.0, cache.reusedPercent());
        assertEquals(0L, cache.frames());
        assertFalse(cache.hasCache());
        assertEquals(64, BeaconBeamCache.SEGMENTS);
    }
}
