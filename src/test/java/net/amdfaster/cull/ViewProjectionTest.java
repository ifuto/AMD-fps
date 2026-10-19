package net.amdfaster.cull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the matrix convention the frustum and depth-pyramid passes depend on.
 *
 * <p>Every assertion here is a property that has to hold for the culling to be correct in the game, and
 * none of them is obvious from the code: which way view-space z points, where the near and far planes land
 * in a reversed-Z range, which sign of clip w means "behind the camera", and that a point on the near
 * plane's edge is exactly on the edge of the screen. A sign error in any of them is invisible in a test
 * that uses an identity matrix and mirrors every occlusion lookup in the game.
 *
 * <p>The values were derived and checked numerically before this file existed, so the numbers below are
 * what the arithmetic produces rather than what was hoped for.
 */
class ViewProjectionTest {

    private static final float NEAR = 2.0f;
    private static final float FAR = 200.0f;
    private static final float HALF_WIDTH = 1.0f;
    private static final float HALF_HEIGHT = 0.5625f;

    private static final float[] RIGHT = {1f, 0f, 0f};
    private static final float[] UP = {0f, 1f, 0f};
    private static final float[] FORWARD = {0f, 0f, 1f};

    private static float[] camera() {
        return ViewProjection.build(0f, 0f, 0f,
                RIGHT[0], RIGHT[1], RIGHT[2], UP[0], UP[1], UP[2], FORWARD[0], FORWARD[1], FORWARD[2],
                HALF_WIDTH, HALF_HEIGHT, NEAR, FAR, new float[16]);
    }

    private static float[] clip(float[] m, float x, float y, float z) {
        return new float[] {
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14],
                m[3] * x + m[7] * y + m[11] * z + m[15]};
    }

    @Test
    void aPointStraightAheadLandsAtTheCentreOfTheScreen() {
        float[] c = clip(camera(), 0f, 0f, 10f);
        // w is the view-space depth, positive because view space has +Z forward.
        assertEquals(10f, c[3], 1e-4f);
        assertEquals(0f, c[0] / c[3], 1e-6f);
        assertEquals(0f, c[1] / c[3], 1e-6f);
    }

    @Test
    void aPointBehindTheCameraHasANonPositiveW() {
        // This is the test HiZ.projectToPixels relies on to decide a box straddles the camera. Getting the
        // sign wrong would make it treat the whole world as behind the camera and keep everything.
        assertTrue(clip(camera(), 0f, 0f, -10f)[3] <= 0f);
    }

    @Test
    void theNearPlanesEdgeIsTheEdgeOfTheScreen() {
        assertEquals(1.0f, clip(camera(), HALF_WIDTH, 0f, NEAR)[0]
                / clip(camera(), HALF_WIDTH, 0f, NEAR)[3], 1e-5f);
        assertEquals(1.0f, clip(camera(), 0f, HALF_HEIGHT, NEAR)[1]
                / clip(camera(), 0f, HALF_HEIGHT, NEAR)[3], 1e-5f);
    }

    @Test
    void depthRunsFromOneAtTheNearPlaneToZeroAtTheFarOne() {
        // Reversed-Z on [0, 1], which is what Frustum's near and far planes assume.
        float[] near = clip(camera(), 0f, 0f, NEAR);
        float[] far = clip(camera(), 0f, 0f, FAR);
        assertEquals(1.0f, near[2] / near[3], 1e-5f);
        assertEquals(0.0f, far[2] / far[3], 1e-5f);
    }

    @Test
    void theFrustumKeepsWhatIsInFrontAndDropsWhatIsNot() {
        Frustum frustum = new Frustum(camera());
        assertTrue(frustum.containsPoint(0f, 0f, 10f));
        assertFalse(frustum.containsPoint(0f, 0f, -10f));
        assertFalse(frustum.containsPoint(0f, 0f, FAR * 2.5f), "beyond the far plane");
        assertFalse(frustum.containsPoint(900f, 0f, 10f), "far outside the left and right planes");
        // Just inside the corner rather than exactly on it: the exact corner sits on four planes at once
        // and a rounding can put it a float outside, which is a property of the test and not of the code.
        assertTrue(frustum.containsPoint(HALF_WIDTH * 0.98f, HALF_HEIGHT * 0.98f, NEAR),
                "inside the near plane's corner");
        assertFalse(frustum.containsPoint(HALF_WIDTH * 1.2f, HALF_HEIGHT, NEAR),
                "outside that corner");
    }

    @Test
    void anArbitraryBasisStillCentresWhatIsAheadOfIt() {
        // The axes come from the game's near plane, so they are not world-aligned in general. Diagonal
        // forward and right, and the same two properties: straight ahead is the centre, and a step along
        // the right axis moves right on the screen.
        float d = (float) (Math.sqrt(0.5));
        float[] m = ViewProjection.build(0f, 0f, 0f,
                d, 0f, -d, 0f, 1f, 0f, d, 0f, d,
                HALF_WIDTH, HALF_HEIGHT, NEAR, FAR, new float[16]);
        float[] ahead = clip(m, 10f * d, 0f, 10f * d);
        assertEquals(0f, ahead[0] / ahead[3], 1e-4f);
        assertEquals(0f, ahead[1] / ahead[3], 1e-4f);
        float[] right = clip(m, 10f * d + d, 0f, 10f * d - d);
        assertTrue(right[0] / right[3] > 0f, "a step along the right axis must land right of centre");
        assertTrue(new Frustum(m).containsPoint(10f * d, 0f, 10f * d));
    }

    @Test
    void aTranslatedCameraStillCentresWhatIsInFrontOfIt() {
        float[] m = ViewProjection.build(5f, 64f, -3f,
                RIGHT[0], RIGHT[1], RIGHT[2], UP[0], UP[1], UP[2], FORWARD[0], FORWARD[1], FORWARD[2],
                HALF_WIDTH, HALF_HEIGHT, NEAR, FAR, new float[16]);
        float[] ahead = clip(m, 5f, 64f, 7f);
        assertEquals(0f, ahead[0] / ahead[3], 1e-5f);
        assertEquals(0f, ahead[1] / ahead[3], 1e-5f);
        assertTrue(clip(m, 5f, 64f, -13f)[3] <= 0f, "ten blocks behind it");
    }

    @Test
    void aTurnedCameraMovesTheScreenWithIt() {
        // Facing +X, so screen right is -Z. A point off to -Z of the centre has to land on the right half
        // of the screen, which is the assertion that would fail if the right axis were negated.
        float[] m = ViewProjection.build(0f, 0f, 0f,
                0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f,
                HALF_WIDTH, HALF_HEIGHT, NEAR, FAR, new float[16]);
        float[] centre = clip(m, 10f, 0f, 0f);
        assertEquals(0f, centre[0] / centre[3], 1e-5f);
        float[] off = clip(m, 10f, 0f, -1f);
        assertTrue(off[0] / off[3] > 0f, "a point towards screen right must project right of centre");
    }

    @Test
    void theMatrixIsWrittenInTheLayoutTheReadersExpect() {
        // Column-major: element (row, column) at column * 4 + row. Frustum.translation is the reference,
        // because it puts x, y and z at 12, 13 and 14 -- the translation column.
        float[] t = Frustum.translation(3f, -7f, 11f);
        assertEquals(3f, t[12]);
        assertEquals(-7f, t[13]);
        assertEquals(11f, t[14]);
        float[] m = camera();
        // The bottom row of the projection is (0, 0, 1, 0), so row 3 of the product is the view's forward
        // row and its translation, which is what makes clip w the view-space depth.
        assertEquals(FORWARD[0], m[3], 1e-6f);
        assertEquals(FORWARD[1], m[7], 1e-6f);
        assertEquals(FORWARD[2], m[11], 1e-6f);
        assertEquals(0f, m[15], 1e-6f);
    }

    @Test
    void nonsenseInputsAreRefusedRatherThanProducingNaN() {
        // A NaN matrix compares false against every threshold, which reads as "cull everything". Failing
        // loudly here is the difference between a bug report and a black screen.
        float[] out = new float[16];
        assertThrows(IllegalArgumentException.class, () -> ViewProjection.build(0f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, 100f, out));
        assertThrows(IllegalArgumentException.class, () -> ViewProjection.build(0f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 10f, 10f, out));
        assertThrows(IllegalArgumentException.class, () -> ViewProjection.build(0f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 100f, out));
        assertThrows(IllegalArgumentException.class, () -> ViewProjection.build(0f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 100f, new float[15]));
    }
}
