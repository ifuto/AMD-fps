package net.amdfaster.runtime;

import net.amdfaster.runtime.CullRuntime.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CullRuntimeTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final float FOV = 70.0f;

    private static Frame frame(CullRuntime runtime, float x, float y, float z) {
        return runtime.frame(x, y, z, 0.0f, 0.0f, FOV, W, H, false);
    }

    @Test
    void theFirstFrameWalksTheSections() {
        CullRuntime runtime = new CullRuntime();

        Frame first = frame(runtime, 0.0f, 64.0f, 0.0f);

        assertFalse(first.reuseLastResult(), "nothing to reuse yet");
        assertEquals(1L, runtime.walks());
        assertEquals(0L, runtime.reuses());
    }

    @Test
    void anUnchangedCameraReusesTheLastResult() {
        // If the camera has not moved and nothing in the world has changed, the culling result is the same as
        // last frame and the whole walk can be skipped.
        CullRuntime runtime = new CullRuntime();

        frame(runtime, 0.0f, 64.0f, 0.0f);
        Frame second = frame(runtime, 0.0f, 64.0f, 0.0f);

        assertTrue(second.reuseLastResult());
        assertEquals(1L, runtime.reuses());
        assertEquals(1L, runtime.walks(), "and the walk was not repeated");
    }

    @Test
    void aMovedCameraWalksAgain() {
        CullRuntime runtime = new CullRuntime();

        frame(runtime, 0.0f, 64.0f, 0.0f);
        Frame moved = frame(runtime, 8.0f, 64.0f, 0.0f);

        assertFalse(moved.reuseLastResult());
        assertEquals(2L, runtime.walks());
    }

    @Test
    void aTerrainChangeForcesAnotherWalk() {
        // "Nothing has changed" has to include every input, and missing one means the culling is reused when
        // it should not be -- the symptom is geometry that appears or disappears for no reason.
        CullRuntime runtime = new CullRuntime();

        frame(runtime, 0.0f, 64.0f, 0.0f);
        frame(runtime, 0.0f, 64.0f, 0.0f);
        runtime.onTerrainChange();
        Frame after = frame(runtime, 0.0f, 64.0f, 0.0f);

        assertFalse(after.reuseLastResult());
    }

    @Test
    void aCameraCutRebuildsTheDepthPyramid() {
        // A pyramid built for the old position would cull everything in the new one, and the player would
        // arrive somewhere and see nothing.
        CullRuntime runtime = new CullRuntime();

        frame(runtime, 0.0f, 64.0f, 0.0f);
        Frame cut = frame(runtime, 5000.0f, 64.0f, 5000.0f);

        assertTrue(cut.rebuildDepthPyramid());
        assertEquals(1L, runtime.cutsDetected());
    }

    @Test
    void aSectionPastTheFogIsCulled() {
        // First because a section past the distance at which the fog is fully opaque contributes no pixels at
        // all, so testing it against the frustum or the depth pyramid is work for nothing.
        CullRuntime runtime = new CullRuntime();

        assertTrue(runtime.cullByFog(20.0, 8.0), "past the opaque distance");
        assertFalse(runtime.cullByFog(4.0, 8.0), "and inside it is not");
    }

    @Test
    void aSectionBehindTheCameraIsCulledButNotDuringAShadowPass() {
        // Shadows are cast by geometry the player is not looking at -- that is what a shadow is.
        CullRuntime runtime = new CullRuntime();

        assertFalse(runtime.cullBehindCamera(-10.0, false), "behind the camera");
        assertTrue(runtime.cullBehindCamera(-10.0, true), "but drawn during a shadow pass");
    }

    @Test
    void anOccluderHidesWhatIsBehindIt() {
        CullRuntime runtime = new CullRuntime();

        runtime.addOccluder(0, 0, 100, 100, 0.1f);
        runtime.rasterizer().complete();

        assertTrue(runtime.isOccluded(10, 10, 20, 20, 0.5f), "behind the occluder");
        assertFalse(runtime.isOccluded(10, 10, 20, 20, 0.05f), "and in front of it is not");
    }

    @Test
    void aWorldChangeClearsEverything() {
        CullRuntime runtime = new CullRuntime();

        frame(runtime, 0.0f, 64.0f, 0.0f);
        frame(runtime, 0.0f, 64.0f, 0.0f);

        runtime.onWorldChange();

        assertEquals(0L, runtime.frames());
        assertEquals(0L, runtime.reuses());
        assertEquals(0L, runtime.walks());
        assertEquals(0L, runtime.cutsDetected());
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has run nothing has technically reused everything, and reporting that as a rate would
        // describe an unused one as though it had measured something.
        CullRuntime runtime = new CullRuntime();

        assertEquals(0.0, runtime.reusePercent());
        assertEquals(0L, runtime.frames());
        assertEquals(0L, runtime.reuses());
        assertEquals(0L, runtime.walks());
        assertEquals(0L, runtime.cutsDetected());
    }
}
