package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityFrustumCullerTest {

    /** Half of a seventy degree horizontal field of view. */
    private static final double HALF_FOV = 35.0;

    @Test
    void aChestBehindTheCameraIsLeftOut() {
        // Only about a fifth of the horizontal circle is on screen at once, and a chest behind the player
        // is still assembled and drawn because the section it is in passed the chunk test.
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        assertFalse(culler.shouldDraw(180.0, HALF_FOV));
        assertEquals(1L, culler.culled());
    }

    @Test
    void aChestInTheMiddleOfTheViewIsDrawn() {
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        assertTrue(culler.shouldDraw(0.0, HALF_FOV));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aChestStraddlingTheEdgeIsKept() {
        // Testing the centre point would drop a chest whose left half is on screen, and the player would
        // see it vanish as they turned toward it -- the opposite of what turning toward something should do.
        double nearest = BlockEntityFrustumCuller.nearestAngle(36.0, 2.0);

        assertEquals(34.0, nearest, 1e-9, "the box reaches into the view");
        assertTrue(new BlockEntityFrustumCuller().shouldDraw(nearest, HALF_FOV));
    }

    @Test
    void aBoxEntirelyInsideTheViewHasNearestAngleZero() {
        assertEquals(0.0, BlockEntityFrustumCuller.nearestAngle(10.0, 2.0), 1e-9);
    }

    @Test
    void theEdgeMarginPreventsFlickering() {
        // The projection is steepest at the edge, and an entity sitting exactly on it would flip between
        // drawn and dropped as the camera moved by fractions of a degree.
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        assertTrue(culler.shouldDraw(HALF_FOV + 1.0, HALF_FOV), "just outside, within the margin");
        assertEquals(1L, culler.inMargin());
        assertFalse(culler.shouldDraw(
                HALF_FOV + BlockEntityFrustumCuller.EDGE_MARGIN_DEGREES + 0.1, HALF_FOV));
        assertEquals(1L, culler.culled());
    }

    @Test
    void aStoreroomSpreadThroughASectionIsMostlyOffScreen() {
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        for (int i = 0; i < 100; i++) {
            double angle = i * 3.6;
            culler.shouldDraw(angle, HALF_FOV);
        }

        assertEquals(100L, culler.tested());
        assertEquals(11L, culler.drawn(), "0 to 36 degrees");
        assertEquals(89L, culler.culled());
        assertEquals(89.0, culler.culledPercent(), 0.01);
    }

    @Test
    void theSavingIsNetOfTheTests() {
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        for (int i = 0; i < 100; i++) {
            culler.shouldDraw(180.0, HALF_FOV);
        }

        assertEquals(100 * BlockEntityFrustumCuller.MS_PER_DRAW, culler.msSaved(), 1e-9);
        assertEquals(100 * BlockEntityFrustumCuller.MS_PER_TEST, culler.msTesting(), 1e-9);
        assertEquals(95.0, culler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        assertThrows(IllegalArgumentException.class, () -> culler.shouldDraw(-1.0, HALF_FOV));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldDraw(10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldDraw(10.0, -35.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        BlockEntityFrustumCuller culler = new BlockEntityFrustumCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved());
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.inMargin());
        assertEquals(2.0, BlockEntityFrustumCuller.EDGE_MARGIN_DEGREES, 1e-12);
    }
}
