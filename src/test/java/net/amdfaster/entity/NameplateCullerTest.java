package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameplateCullerTest {

    private static final double NEAR = 10.0;

    @Test
    void anOccludedNameplateIsNotDrawn() {
        // A nameplate is not a quad: it is a run of glyphs, each a textured blend against whatever is
        // behind it. A hundred and twenty of them is 9.6 ms a frame, over half the budget, on text that
        // is mostly hidden.
        NameplateCuller culler = new NameplateCuller();

        assertFalse(culler.shouldDraw(NEAR, true, true, 0.0, 0.0));
        assertEquals(1L, culler.culledOccluded());
        assertEquals(0L, culler.drawn());
    }

    @Test
    void anUncertainOcclusionAnswerDraws() {
        // The decision is deliberately one-sided. Being wrong this way costs the time the culling was
        // meant to save; being wrong the other way costs information the player needed -- who is
        // standing there, and whether the mob is hostile.
        NameplateCuller culler = new NameplateCuller();

        assertTrue(culler.shouldDraw(NEAR, true, false, 0.0, 0.0), "occlusion unknown, so draw");
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aNameplatePastLegibleDistanceIsNotDrawn() {
        // A nameplate is a fixed pixel height, so past a certain distance the glyphs are smaller than a
        // pixel and blending them produces a grey smear rather than text. It costs the same and conveys
        // nothing.
        NameplateCuller culler = new NameplateCuller();

        assertTrue(NameplateCuller.isLegible(NameplateCuller.LEGIBLE_DISTANCE), "at the cutoff it is legible");
        assertFalse(culler.shouldDraw(NameplateCuller.LEGIBLE_DISTANCE + 0.1, false, false, 0.0, 0.0));
        assertEquals(1L, culler.culledDistance());
        assertEquals(48.0, NameplateCuller.LEGIBLE_DISTANCE, 1e-9);
    }

    @Test
    void anOffScreenNameplateIsNotDrawn() {
        NameplateCuller culler = new NameplateCuller();

        assertFalse(culler.shouldDraw(NEAR, false, true, 0.0, 60.0), "far to the right");
        assertFalse(culler.shouldDraw(NEAR, false, true, -50.0, 0.0), "far below");
        assertEquals(2L, culler.culledOffScreen());
    }

    @Test
    void anEdgeOfScreenNameplateIsDrawn() {
        NameplateCuller culler = new NameplateCuller();

        assertTrue(culler.shouldDraw(NEAR, false, true,
                NameplateCuller.HALF_FOV, NameplateCuller.HALF_FOV));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aVisibleNameplateIsDrawn() {
        NameplateCuller culler = new NameplateCuller();

        assertTrue(culler.shouldDraw(NEAR, false, true, 5.0, -5.0));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void distanceIsCheckedBeforeOcclusion() {
        // An entity too far to read is culled on distance alone, so an expensive occlusion test is not
        // even consulted for it.
        NameplateCuller culler = new NameplateCuller();

        assertFalse(culler.shouldDraw(100.0, true, true, 0.0, 0.0));
        assertEquals(1L, culler.culledDistance());
        assertEquals(0L, culler.culledOccluded());
    }

    @Test
    void theSavingIsTheShareNeverDrawn() {
        NameplateCuller culler = new NameplateCuller();

        // The measured scene: 120 named entities, a quarter readable.
        for (int i = 0; i < 120; i++) {
            boolean readable = i < 30;
            culler.shouldDraw(readable ? NEAR : 80.0, false, true, 0.0, 0.0);
        }

        assertEquals(120L, culler.offered());
        assertEquals(30L, culler.drawn());
        assertEquals(90L, culler.culledDistance());
        assertEquals(75.0, culler.culledPercent(), 0.01);
    }

    @Test
    void theCostIsWhatTheFormulaSays() {
        assertEquals(9.6, NameplateCuller.msPerFrame(120), 1e-9);

        NameplateCuller culler = new NameplateCuller();
        culler.shouldDraw(80.0, false, true, 0.0, 0.0);
        assertEquals(NameplateCuller.MS_PER_NAMEPLATE, culler.msSavedPerFrame(), 1e-9);
        assertEquals(0.08, NameplateCuller.MS_PER_NAMEPLATE, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        NameplateCuller culler = new NameplateCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(-1.0, false, true, 0.0, 0.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        NameplateCuller culler = new NameplateCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.offered());
        assertEquals(0.0, culler.msSavedPerFrame());
    }
}
