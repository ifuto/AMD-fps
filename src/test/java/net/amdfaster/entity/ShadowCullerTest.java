package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowCullerTest {

    private static final double ON_GROUND = 0.0;
    private static final double NEAR = 10.0;

    @Test
    void anOccludedShadowIsNotDrawn() {
        // Two hundred entities in a farm or a village is 3 ms a frame, and most of those shadows are
        // behind a wall or off the edge of the screen.
        ShadowCuller culler = new ShadowCuller();

        assertFalse(culler.shouldDraw(ON_GROUND, NEAR, true, true, 0.0, 0.0));
        assertEquals(1L, culler.culledOccluded());
        assertEquals(0L, culler.drawn());
    }

    @Test
    void anUncertainOcclusionAnswerDraws() {
        // A shadow is how a player judges where something is, and it matters most exactly where it is
        // hardest to tell: a mob in the air, one on scaffolding, one past a fence. Removing one that
        // should have been drawn takes away the cue they were relying on.
        ShadowCuller culler = new ShadowCuller();

        assertTrue(culler.shouldDraw(ON_GROUND, NEAR, true, false, 0.0, 0.0), "occlusion unknown, so draw");
        assertEquals(1L, culler.drawn());
    }

    @Test
    void anEntityWithNoGroundBeneathItHasNoShadow() {
        // Not an occlusion question: it is a property of the entity. Checked first because it is free
        // and it removes a whole class of entities before any view test runs.
        ShadowCuller culler = new ShadowCuller();

        assertFalse(culler.shouldDraw(10.0, NEAR, false, true, 0.0, 0.0), "in the air");
        assertEquals(1L, culler.culledNoGround());
        assertEquals(4.0, ShadowCuller.MAX_SHADOW_HEIGHT, 1e-9);
    }

    @Test
    void anEntityJustAboveTheGroundStillCastsOne() {
        // Scaffolding, a slab, a carpet -- a shadow there is exactly the cue that tells the player how
        // high the entity is.
        ShadowCuller culler = new ShadowCuller();

        assertTrue(culler.shouldDraw(ShadowCuller.MAX_SHADOW_HEIGHT, NEAR, false, true, 0.0, 0.0));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aShadowPastVisibleDistanceIsNotDrawn() {
        // Beyond this the disc is a pixel or less across, so blending it costs the same and conveys
        // nothing.
        ShadowCuller culler = new ShadowCuller();

        assertTrue(ShadowCuller.isDiscVisible(ShadowCuller.VISIBLE_DISTANCE), "at the cutoff it is visible");
        assertFalse(culler.shouldDraw(ON_GROUND, ShadowCuller.VISIBLE_DISTANCE + 0.1, false, true, 0.0, 0.0));
        assertEquals(1L, culler.culledDistance());
        assertEquals(48.0, ShadowCuller.VISIBLE_DISTANCE, 1e-9);
    }

    @Test
    void anOffScreenShadowIsNotDrawn() {
        ShadowCuller culler = new ShadowCuller();

        assertFalse(culler.shouldDraw(ON_GROUND, NEAR, false, true, 0.0, 60.0), "far to the right");
        assertFalse(culler.shouldDraw(ON_GROUND, NEAR, false, true, -50.0, 0.0), "far below");
        assertEquals(2L, culler.culledOffScreen());
    }

    @Test
    void anEdgeOfScreenShadowIsDrawn() {
        ShadowCuller culler = new ShadowCuller();

        assertTrue(culler.shouldDraw(ON_GROUND, NEAR, false, true,
                ShadowCuller.HALF_FOV, ShadowCuller.HALF_FOV));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void theGroundCheckRunsBeforeTheViewChecks() {
        // It is free, and it removes a whole class of entities before any view test runs.
        ShadowCuller culler = new ShadowCuller();

        assertFalse(culler.shouldDraw(20.0, 200.0, true, true, 90.0, 90.0));
        assertEquals(1L, culler.culledNoGround());
        assertEquals(0L, culler.culledDistance(), "never got that far");
        assertEquals(0L, culler.culledOffScreen());
        assertEquals(0L, culler.culledOccluded());
    }

    @Test
    void theSavingIsTheShareNeverDrawn() {
        ShadowCuller culler = new ShadowCuller();

        // The measured scene: 200 entities, a third with a visible shadow.
        for (int i = 0; i < 200; i++) {
            boolean visible = i < 60;
            culler.shouldDraw(ON_GROUND, visible ? NEAR : 80.0, false, true, 0.0, 0.0);
        }

        assertEquals(200L, culler.offered());
        assertEquals(60L, culler.drawn());
        assertEquals(140L, culler.culledDistance());
        assertEquals(70.0, culler.culledPercent(), 0.01);
    }

    @Test
    void theCostIsWhatTheFormulaSays() {
        assertEquals(3.0, ShadowCuller.msPerFrame(200), 1e-9);

        ShadowCuller culler = new ShadowCuller();
        culler.shouldDraw(ON_GROUND, 80.0, false, true, 0.0, 0.0);
        assertEquals(ShadowCuller.MS_PER_SHADOW, culler.msSavedPerFrame(), 1e-9);
        assertEquals(0.015, ShadowCuller.MS_PER_SHADOW, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ShadowCuller culler = new ShadowCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(0.0, -1.0, false, true, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(-1.0, 1.0, false, true, 0.0, 0.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        ShadowCuller culler = new ShadowCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.offered());
        assertEquals(0.0, culler.msSavedPerFrame());
    }
}
