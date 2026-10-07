package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static net.amdfaster.cull.Frustum.BOTTOM;
import static net.amdfaster.cull.Frustum.FAR;
import static net.amdfaster.cull.Frustum.LEFT;
import static net.amdfaster.cull.Frustum.NEAR;
import static net.amdfaster.cull.Frustum.RIGHT;
import static net.amdfaster.cull.Frustum.TOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An identity view-projection makes clip space coincide with world space, so the visible volume is
 * exactly [-1,1] x [-1,1] x [0,1] -- the last range being the reversed-Z near/far pair. That makes
 * every expectation below something you can read off directly.
 */
class FrustumTest {

    private static final Frustum IDENTITY = new Frustum(Frustum.identity());

    @Test
    void anIdentityMatrixCullsToTheUnitCube() {
        assertTrue(IDENTITY.containsPoint(0f, 0f, 0.5f));
        assertTrue(IDENTITY.containsPoint(-1f, -1f, 0f), "the near plane is inclusive");
        assertTrue(IDENTITY.containsPoint(1f, 1f, 1f), "the far plane is inclusive");
    }

    @Test
    void pointsOutsideAnyPlaneAreRejected() {
        assertFalse(IDENTITY.containsPoint(2f, 0f, 0.5f), "right of the right plane");
        assertFalse(IDENTITY.containsPoint(-2f, 0f, 0.5f), "left of the left plane");
        assertFalse(IDENTITY.containsPoint(0f, 2f, 0.5f), "above the top plane");
        assertFalse(IDENTITY.containsPoint(0f, -2f, 0.5f), "below the bottom plane");
        assertFalse(IDENTITY.containsPoint(0f, 0f, -0.5f), "behind the near plane");
        assertFalse(IDENTITY.containsPoint(0f, 0f, 1.5f), "past the far plane");
    }

    @Test
    void theNearPlaneIsAtZeroBecauseDepthIsReversed() {
        // With reversed-Z in [0,1] the near plane is row2 on its own. A conventional [-1,1]
        // projection would put it at row3 + row2, and then everything near the camera is culled.
        assertEquals(0f, IDENTITY.distanceTo(NEAR, 0f, 0f, 0f), 1e-6f);
        assertTrue(IDENTITY.distanceTo(NEAR, 0f, 0f, 0.25f) > 0f);
        assertTrue(IDENTITY.distanceTo(NEAR, 0f, 0f, -0.25f) < 0f);
    }

    @Test
    void planesComeOutNormalised() {
        Frustum f = new Frustum(Frustum.translation(3f, -7f, 11f));
        for (int p = 0; p < Frustum.PLANE_COUNT; p++) {
            float length = (float) Math.sqrt(f.normalX(p) * f.normalX(p)
                    + f.normalY(p) * f.normalY(p) + f.normalZ(p) * f.normalZ(p));
            assertEquals(1f, length, 1e-5f, "plane " + p + " is not unit length");
        }
    }

    @Test
    void theIdentityPlanesAreTheSixFacesOfTheCube() {
        assertEquals(1f, IDENTITY.normalX(LEFT), 1e-6f);
        assertEquals(1f, IDENTITY.distance(LEFT), 1e-6f);
        assertEquals(-1f, IDENTITY.normalX(RIGHT), 1e-6f);
        assertEquals(1f, IDENTITY.normalY(BOTTOM), 1e-6f);
        assertEquals(-1f, IDENTITY.normalY(TOP), 1e-6f);
        assertEquals(1f, IDENTITY.normalZ(NEAR), 1e-6f);
        assertEquals(-1f, IDENTITY.normalZ(FAR), 1e-6f);
        assertEquals(1f, IDENTITY.distance(FAR), 1e-6f);
    }

    @Test
    void aTranslationMovesTheVisibleVolume() {
        // The matrix maps p to p + t, so translating by (0,0,-5) puts world z=5.5 at clip z=0.5.
        Frustum f = new Frustum(Frustum.translation(0f, 0f, -5f));
        assertTrue(f.containsPoint(0f, 0f, 5.5f));
        assertFalse(f.containsPoint(0f, 0f, 0.5f), "that is clip z = -4.5");
    }

    @Test
    void aBoxInsideTheVolumeIntersects() {
        assertTrue(IDENTITY.intersectsAabb(0.2f, 0.2f, 0.2f, 0.8f, 0.8f, 0.8f));
    }

    @Test
    void aBoxOutsideTheVolumeDoesNot() {
        assertFalse(IDENTITY.intersectsAabb(2f, 2f, 2f, 3f, 3f, 3f));
        assertFalse(IDENTITY.intersectsAabb(-3f, -3f, -3f, -2f, -2f, -1f));
    }

    @Test
    void aBoxStraddlingTheVolumeIntersects() {
        assertTrue(IDENTITY.intersectsAabb(-2f, -2f, 0.2f, 2f, 2f, 0.8f));
    }

    @Test
    void aDegenerateBoxOnTheBoundaryIntersects() {
        assertTrue(IDENTITY.intersectsAabb(1f, 1f, 1f, 1f, 1f, 1f));
        assertFalse(IDENTITY.intersectsAabb(1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f));
    }

    @Test
    void aMatrixOfTheWrongSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Frustum(new float[15]));
        assertThrows(IllegalArgumentException.class, () -> new Frustum(new float[17]));
    }

    @Test
    void theHelpersBuildWhatTheySay() {
        float[] t = Frustum.translation(1f, 2f, 3f);
        assertEquals(1f, t[12], 0f);
        assertEquals(2f, t[13], 0f);
        assertEquals(3f, t[14], 0f);
        assertEquals(1f, t[15], 0f);
        assertEquals(1f, Frustum.identity()[0], 0f);
        assertEquals(0f, Frustum.identity()[12], 0f);
    }
}
