package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlintCullerTest {

    private static final double NEAR = 5.0;

    @Test
    void anOccludedGlintIsNotDrawn() {
        // An enchanted item is drawn twice, and the second pass costs as much as the first. Eighty of
        // them is 1.6 ms a frame, on a shimmer that is mostly off screen or behind a wall.
        GlintCuller culler = new GlintCuller();

        assertFalse(culler.shouldDraw(NEAR, true, true, 0.0, 0.0));
        assertEquals(1L, culler.culledOccluded());
        assertEquals(0L, culler.drawn());
    }

    @Test
    void anUncertainOcclusionAnswerDraws() {
        // Whether an item is enchanted is something a player reads at a glance, and it matters: an
        // enchanted tool is worth more than an ordinary one, and dropping the wrong one is a loss they
        // cannot undo.
        GlintCuller culler = new GlintCuller();

        assertTrue(culler.shouldDraw(NEAR, true, false, 0.0, 0.0), "occlusion unknown, so draw");
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aGlintPastRecognisableDistanceIsNotDrawn() {
        // Past this it is a few flickering pixels that read as noise rather than as an enchantment, so
        // culling there is not a visible loss.
        GlintCuller culler = new GlintCuller();

        assertTrue(GlintCuller.isRecognisable(GlintCuller.GLINT_DISTANCE), "at the cutoff it is recognisable");
        assertFalse(culler.shouldDraw(GlintCuller.GLINT_DISTANCE + 0.1, false, true, 0.0, 0.0));
        assertEquals(1L, culler.culledDistance());
        assertEquals(24.0, GlintCuller.GLINT_DISTANCE, 1e-9);
    }

    @Test
    void theGlintCutoffIsCloserThanTheNameplateCutoff() {
        // A nameplate is a fixed pixel height and stays legible far out. A glint is a moving highlight
        // over the item's own texture and stops reading as an enchantment much sooner.
        assertTrue(GlintCuller.GLINT_DISTANCE < GlintCuller.NAMEPLATE_DISTANCE);
        assertEquals(48.0, GlintCuller.NAMEPLATE_DISTANCE, 1e-9);
        assertFalse(GlintCuller.isRecognisable(40.0), "a nameplate would still be legible here");
    }

    @Test
    void anOffScreenGlintIsNotDrawn() {
        GlintCuller culler = new GlintCuller();

        assertFalse(culler.shouldDraw(NEAR, false, true, 0.0, 60.0), "far to the right");
        assertFalse(culler.shouldDraw(NEAR, false, true, -50.0, 0.0), "far below");
        assertEquals(2L, culler.culledOffScreen());
    }

    @Test
    void anEdgeOfScreenGlintIsDrawn() {
        GlintCuller culler = new GlintCuller();

        assertTrue(culler.shouldDraw(NEAR, false, true,
                GlintCuller.HALF_FOV, GlintCuller.HALF_FOV));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aVisibleGlintIsDrawn() {
        GlintCuller culler = new GlintCuller();

        assertTrue(culler.shouldDraw(NEAR, false, true, 5.0, -5.0));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void distanceIsCheckedBeforeOcclusion() {
        // An item too far to read never pays for an occlusion test.
        GlintCuller culler = new GlintCuller();

        assertFalse(culler.shouldDraw(100.0, true, true, 0.0, 0.0));
        assertEquals(1L, culler.culledDistance());
        assertEquals(0L, culler.culledOccluded());
    }

    @Test
    void theSavingIsTheShareNeverDrawn() {
        GlintCuller culler = new GlintCuller();

        // The measured scene: 80 enchanted items, a fifth with a visible glint.
        for (int i = 0; i < 80; i++) {
            boolean visible = i < 16;
            culler.shouldDraw(visible ? NEAR : 40.0, false, true, 0.0, 0.0);
        }

        assertEquals(80L, culler.offered());
        assertEquals(16L, culler.drawn());
        assertEquals(64L, culler.culledDistance());
        assertEquals(80.0, culler.culledPercent(), 0.01);
    }

    @Test
    void theCostIsWhatTheFormulaSays() {
        assertEquals(1.6, GlintCuller.msPerFrame(80), 1e-9);

        GlintCuller culler = new GlintCuller();
        culler.shouldDraw(40.0, false, true, 0.0, 0.0);
        assertEquals(GlintCuller.MS_PER_GLINT, culler.msSavedPerFrame(), 1e-9);
        assertEquals(0.02, GlintCuller.MS_PER_GLINT, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        GlintCuller culler = new GlintCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(-1.0, false, true, 0.0, 0.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        GlintCuller culler = new GlintCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.offered());
        assertEquals(0.0, culler.msSavedPerFrame());
    }
}
