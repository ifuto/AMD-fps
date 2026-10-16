package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehindCameraCullerTest {

    @Test
    void somethingEntirelyBehindTheCameraIsSkipped() {
        // About half of everything in a loaded world is behind the player at any moment, and unlike culling
        // by distance it costs nothing visible -- the player cannot see what is behind them.
        BehindCameraCuller culler = new BehindCameraCuller();

        assertFalse(culler.shouldDraw(-5.0, false));
        assertEquals(1L, culler.culled());
    }

    @Test
    void somethingInFrontIsDrawn() {
        BehindCameraCuller culler = new BehindCameraCuller();

        assertTrue(culler.shouldDraw(5.0, false));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aLongMachineIsTestedByItsWholeBox() {
        // Something long can have its origin behind the camera and still extend into view. Testing the
        // origin would drop it and the player would see a machine disappear as they turned.
        BehindCameraCuller culler = new BehindCameraCuller();

        double nearest = BehindCameraCuller.nearestDepth(-10.0, 30.0);

        assertEquals(-40.0, nearest, 1e-9);
        assertTrue(culler.shouldDraw(nearest, false),
                "the box reaches twenty blocks in front of the camera");
    }

    @Test
    void aLongMachineWhollyBehindIsStillCulled() {
        BehindCameraCuller culler = new BehindCameraCuller();

        double nearest = BehindCameraCuller.nearestDepth(-40.0, 30.0);

        assertEquals(-70.0, nearest, 1e-9);
        assertFalse(culler.shouldDraw(nearest, false));
    }

    @Test
    void aBoxStraddlingThePlaneIsDrawn() {
        // Dropping it would put a hard edge across the screen where the box stopped.
        BehindCameraCuller culler = new BehindCameraCuller();

        assertEquals(0.0, BehindCameraCuller.nearestDepth(5.0, 5.0), 1e-12);
        assertTrue(culler.shouldDraw(0.0, false), "touching the plane counts as in front");
    }

    @Test
    void everythingIsDrawnDuringAShadowPass() {
        // Shadows are cast by geometry the player is not looking at -- that is what a shadow is. Culling
        // behind the camera while the shadow map is drawn would remove the shadows of everything behind the
        // player, and they would turn around to find their own base unlit.
        BehindCameraCuller culler = new BehindCameraCuller();

        assertTrue(culler.shouldDraw(-50.0, true));
        assertEquals(1L, culler.shadowPasses());
        assertEquals(1L, culler.drawn());
        assertEquals(0L, culler.culled());
    }

    @Test
    void halfAWorldIsBehindTheCamera() {
        BehindCameraCuller culler = new BehindCameraCuller();

        for (int i = 0; i < 1000; i++) {
            culler.shouldDraw(i - 500.0, false);
        }

        assertEquals(1000L, culler.tested());
        assertEquals(500L, culler.culled());
        assertEquals(50.0, culler.culledPercent(), 1e-9);
    }

    @Test
    void theSavingIsNetOfTheTests() {
        // Comparing a depth is not free, and a culler whose test cost as much as the draw would save
        // nothing and should report that.
        BehindCameraCuller culler = new BehindCameraCuller();

        for (int i = 0; i < 1000; i++) {
            culler.shouldDraw(-1.0, false);
        }

        assertEquals(1000 * BehindCameraCuller.MS_PER_DRAW, culler.msSaved(), 1e-9);
        assertEquals(1000 * BehindCameraCuller.MS_PER_TEST, culler.msTesting(), 1e-9);
        assertEquals(20.0, culler.msSaved(), 1e-9);
        assertEquals(0.5, culler.msTesting(), 1e-9);
        assertEquals(97.5, culler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BehindCameraCuller.nearestDepth(0.0, -1.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        BehindCameraCuller culler = new BehindCameraCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved(), 1e-12);
        assertEquals(0.0, culler.msTesting(), 1e-12);
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.shadowPasses());
    }
}
