package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakingOverlayCacheTest {

    @Test
    void theSameStageIsNotRebuilt() {
        // Six quads generated and uploaded again every frame for a shape that is always the same cube,
        // for as long as the player holds the button down.
        BreakingOverlayCache cache = new BreakingOverlayCache();

        assertTrue(cache.needsRebuild(0, 64, 0, 3), "the first frame builds it");
        for (int i = 0; i < 99; i++) {
            assertFalse(cache.needsRebuild(0, 64, 0, 3), "frame " + i);
        }

        assertEquals(1L, cache.rebuilt());
        assertEquals(99L, cache.reused());
        assertEquals(99.0, cache.reusedPercent(), 0.01);
    }

    @Test
    void aStageChangeRebuilds() {
        // The ten crack stages are how the player knows the block is breaking and roughly how much is
        // left. An overlay stuck on one stage would look like the block had stopped responding, and the
        // player would let go and start again.
        BreakingOverlayCache cache = new BreakingOverlayCache();
        cache.needsRebuild(0, 64, 0, 3);

        assertTrue(cache.needsRebuild(0, 64, 0, 4));
        assertEquals(4, cache.stage());
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void movingToAnotherBlockDoesNotRebuild() {
        // The cube is the same shape wherever it is drawn, and the position is a uniform. Baking it into
        // the mesh would mean rebuilding to move, and leaving it behind would show cracks on a block the
        // player is no longer touching.
        BreakingOverlayCache cache = new BreakingOverlayCache();
        cache.needsRebuild(0, 64, 0, 3);
        long first = cache.position();

        assertFalse(cache.needsRebuild(10, 70, -5, 3));
        assertTrue(cache.position() != first, "the overlay followed the player");
        assertEquals(1L, cache.rebuilt(), "and no rebuild was needed");
    }

    @Test
    void positionsAreDistinctInTheKey() {
        assertTrue(BreakingOverlayCache.positionKey(0, 0, 0)
                != BreakingOverlayCache.positionKey(1, 0, 0));
        assertTrue(BreakingOverlayCache.positionKey(0, 0, 0)
                != BreakingOverlayCache.positionKey(0, 1, 0));
        assertTrue(BreakingOverlayCache.positionKey(0, 0, 0)
                != BreakingOverlayCache.positionKey(0, 0, 1));
    }

    @Test
    void negativeCoordinatesWork() {
        BreakingOverlayCache cache = new BreakingOverlayCache();

        assertTrue(cache.needsRebuild(-100, 12, -900, 0));
        assertFalse(cache.needsRebuild(-100, 12, -900, 0));
        assertEquals(BreakingOverlayCache.positionKey(-100, 12, -900), cache.position());
    }

    @Test
    void everyStageIsAccepted() {
        BreakingOverlayCache cache = new BreakingOverlayCache();

        for (int stage = 0; stage < BreakingOverlayCache.STAGES; stage++) {
            cache.needsRebuild(0, 64, 0, stage);
            assertEquals(stage, cache.stage());
        }
        assertEquals(BreakingOverlayCache.STAGES, cache.rebuilt(), "each stage rebuilt once");
        assertEquals(10, BreakingOverlayCache.STAGES);
    }

    @Test
    void theCubeDoesNotChangeWhileThePlayerHolds() {
        // Both things that change often -- the position every frame and the stage as progress advances --
        // are uniforms. The expensive thing, the cube, does not change at all.
        BreakingOverlayCache cache = new BreakingOverlayCache();
        cache.needsRebuild(0, 64, 0, 5);

        for (int i = 0; i < 100; i++) {
            assertFalse(cache.needsRebuild(i, 64, 0, 5));
            assertTrue(cache.position() == BreakingOverlayCache.positionKey(i, 64, 0));
        }
        assertEquals(1L, cache.rebuilt());
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        BreakingOverlayCache cache = new BreakingOverlayCache();
        cache.needsRebuild(0, 64, 0, 3);

        cache.invalidate();

        assertEquals(-1, cache.stage());
        assertTrue(cache.needsRebuild(0, 64, 0, 3), "breaking again starts over");
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        BreakingOverlayCache cache = new BreakingOverlayCache();
        for (int i = 0; i < 100; i++) {
            cache.needsRebuild(0, 64, 0, 3);
        }

        // 6 faces at 5 microseconds each, plus the uniforms.
        assertEquals(0.0305, BreakingOverlayCache.msPerFrameWithoutCache(), 1e-9);
        assertTrue(cache.savedPercent() > 90.0, "was " + cache.savedPercent());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BreakingOverlayCache cache = new BreakingOverlayCache();

        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(0, 64, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> cache.needsRebuild(0, 64, 0, BreakingOverlayCache.STAGES));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused cache reads as a result when it is the absence of one.
        BreakingOverlayCache cache = new BreakingOverlayCache();

        assertEquals(0.0, cache.savedPercent(), "no frames drawn, so nothing saved");
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0.0, cache.reusedPercent());
        assertEquals(0L, cache.frames());
        assertEquals(-1, cache.stage());
        assertEquals(6, BreakingOverlayCache.FACES);
    }
}
