package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalConeCullerTest {

    private static final double ROOT_HALF = 0.7071067811865476;

    @Test
    void aFlatMeshletFacingAwayIsCulled() {
        // If every normal in the cluster points away from the camera then every triangle is back-facing, and
        // the whole cluster can be dropped before any of its vertices are shaded.
        NormalConeCuller culler = new NormalConeCuller();

        assertFalse(culler.shouldDraw(0, 0, 1, 1.0, 0, 0, -1));
        assertEquals(1L, culler.culled());
    }

    @Test
    void aFlatMeshletFacingTheCameraIsKept() {
        NormalConeCuller culler = new NormalConeCuller();

        assertTrue(culler.shouldDraw(0, 0, 1, 1.0, 0, 0, 1));
        assertEquals(1L, culler.kept());
    }

    @Test
    void theConeHalfAngleWidensTheTest() {
        // A cone of forty-five degrees is only fully back-facing once the axis is more than a hundred and
        // thirty-five degrees from the camera.
        NormalConeCuller culler = new NormalConeCuller();

        assertFalse(culler.shouldDraw(0, 0, 1, ROOT_HALF, 0, 0, -1), "squarely behind");
        assertFalse(culler.shouldDraw(0, 0, 1, ROOT_HALF, 0, -ROOT_HALF, -ROOT_HALF), "at the edge");
        assertTrue(culler.shouldDraw(0, 0, 1, ROOT_HALF, 0, -1, 0), "but not from the side");
    }

    @Test
    void aMeshletWithNormalsEverywhereIsNeverCulled() {
        // A rock, a tree, a lump of ore. No view direction makes all of them face away, so the answer is
        // always to draw it. That is not a failure of the technique, it is the honest result for content that
        // has no back.
        NormalConeCuller culler = new NormalConeCuller();

        for (int i = 0; i < 6; i++) {
            assertTrue(culler.shouldDraw(0, 0, 1, 0.0, 0, 0, -1));
        }
        assertEquals(6L, culler.tooWide());
        assertEquals(0L, culler.culled());
    }

    @Test
    void theCutoffContainsEveryNormal() {
        // A cone that is too narrow leaves a normal outside it and a meshlet that should have been drawn is
        // dropped, which is a hole in the world that moves with the camera.
        double cutoff = NormalConeCuller.coneCutoff(0, 0, 1, new double[] {
                0, 0, 1,
                ROOT_HALF, 0, ROOT_HALF,
                0, ROOT_HALF, ROOT_HALF,
        });

        assertEquals(ROOT_HALF, cutoff, 1e-9, "the least aligned normal sets it");
    }

    @Test
    void aHalfOfTheSceneFacesAway() {
        // On average about half the meshlets in a scene face away, so this is worth up to a factor of two on
        // geometry-bound passes.
        NormalConeCuller culler = new NormalConeCuller();

        for (int i = 0; i < 500; i++) {
            culler.shouldDraw(0, 0, 1, 1.0, 0, 0, -1);
        }
        for (int i = 0; i < 500; i++) {
            culler.shouldDraw(0, 0, 1, 1.0, 0, 0, 1);
        }

        assertEquals(1000L, culler.tested());
        assertEquals(500L, culler.culled());
        assertEquals(50.0, culler.culledPercent(), 1e-9);
        assertEquals(2.0, culler.msSaved(), 1e-9);
        assertEquals(0.02, culler.msTesting(), 1e-9);
        assertEquals(49.5, culler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        NormalConeCuller culler = new NormalConeCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(0, 0, 1, 1.5, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldDraw(0, 0, 1, -1.5, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> NormalConeCuller.coneCutoff(0, 0, 1, new double[] {0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> NormalConeCuller.coneCutoff(0, 0, 1, new double[0]));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        NormalConeCuller culler = new NormalConeCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msTesting(), 1e-12);
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.tooWide());
    }
}
