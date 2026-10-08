package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frustum test runs for every meshlet and every entity in the world, so its inner loop is worth
 * pinning twice: once for what it decides, and once for the fact that making it branchless did not
 * change the decision.
 */
class FrustumBranchlessTest {

    /**
     * The corner-selecting form this replaced, kept as the reference. {@code nx >= 0 ? maxX : minX}
     * picks the corner furthest along the normal; {@code max(nx*minX, nx*maxX)} has to pick the same
     * corner and produce the same float.
     */
    private static boolean referenceIntersects(Frustum frustum,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int p = 0; p < Frustum.PLANE_COUNT; p++) {
            float nx = frustum.normalX(p);
            float ny = frustum.normalY(p);
            float nz = frustum.normalZ(p);
            float px = nx >= 0f ? maxX : minX;
            float py = ny >= 0f ? maxY : minY;
            float pz = nz >= 0f ? maxZ : minZ;
            if (nx * px + ny * py + nz * pz + frustum.distance(p) < 0f) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theBranchlessFormAgreesBitForBitWithTheCornerSelectingForm() {
        // 200 000 random boxes against four different frustums. The identity being relied on is
        // nx * maxX >= nx * minX exactly when nx >= 0, which holds for a well-formed box; this is
        // the check that it also holds after float rounding, including on the negative normals where
        // the two forms take opposite branches.
        float[][] matrices = {
                Frustum.identity(),
                Frustum.translation(-13.5f, 64f, 27.25f),
                perspective(),
                Frustum.translation(1024f, -512f, 0.5f),
        };
        Random random = new Random(20261009L);
        int compared = 0;
        int culled = 0;
        for (float[] matrix : matrices) {
            Frustum frustum = new Frustum(matrix);
            for (int i = 0; i < 50000; i++) {
                float minX = random.nextFloat() * 200f - 100f;
                float minY = random.nextFloat() * 200f - 100f;
                float minZ = random.nextFloat() * 200f - 100f;
                // A box, not two arbitrary points: the identity needs max >= min on every axis.
                float maxX = minX + random.nextFloat() * 32f;
                float maxY = minY + random.nextFloat() * 32f;
                float maxZ = minZ + random.nextFloat() * 32f;

                boolean actual = frustum.intersectsAabb(minX, minY, minZ, maxX, maxY, maxZ);
                boolean expected = referenceIntersects(frustum, minX, minY, minZ, maxX, maxY, maxZ);
                assertEquals(expected, actual,
                        "disagreement at " + minX + "," + minY + "," + minZ);
                compared++;
                culled += actual ? 0 : 1;
            }
        }
        assertTrue(culled > 0, "the sample never culled anything, so it proved nothing");
        assertTrue(culled < compared, "the sample culled everything, so it proved nothing");
    }

    @Test
    void degenerateBoxesAndNormalsStillAgree() {
        // Zero-width boxes and the degenerate-plane path, where the normal is zero and the accessor
        // returns "everything is inside". Both forms have to survive those without dividing by zero
        // or picking a corner at random.
        Frustum degenerate = new Frustum(new float[16]);
        for (int p = 0; p < Frustum.PLANE_COUNT; p++) {
            assertEquals(1f, degenerate.distance(p), "a degenerate plane must not cull");
        }
        assertTrue(degenerate.intersectsAabb(-1f, -1f, -1f, 1f, 1f, 1f));

        Frustum frustum = new Frustum(perspective());
        assertTrue(frustum.intersectsAabb(0f, 0f, 0f, 0f, 0f, 0f)
                == referenceIntersects(frustum, 0f, 0f, 0f, 0f, 0f, 0f),
                "a zero-size box at the origin");
        for (float v : new float[] {-1000f, -1f, 0f, 1f, 1000f}) {
            assertEquals(referenceIntersects(frustum, v, v, v, v, v, v),
                    frustum.intersectsAabb(v, v, v, v, v, v), "zero-size box at " + v);
        }
    }

    @Test
    void theIdentityFrustumStillSeesTheDocumentedVolume() {
        // Under an identity matrix clip space is world space, so the visible region is
        // [-1,1] x [-1,1] x [0,1] with the reversed-Z near and far planes. This is the behaviour the
        // existing tests were written against, so it must survive the rewrite.
        Frustum frustum = new Frustum(Frustum.identity());
        assertTrue(frustum.intersectsAabb(-0.5f, -0.5f, 0.25f, 0.5f, 0.5f, 0.75f));
        assertFalse(frustum.intersectsAabb(2f, 0f, 0.5f, 3f, 1f, 0.6f), "outside +X");
        // Entirely past z = 1. A box reaching from 0.5 to 5 straddles the far plane and is correctly
        // reported visible: the AABB test is conservative, and "partly inside" has to count as inside
        // or geometry at the edge of the world would blink.
        assertFalse(frustum.intersectsAabb(0f, 0f, 2f, 1f, 1f, 5f), "entirely beyond the far plane");
        assertTrue(frustum.intersectsAabb(0f, 0f, 0.5f, 1f, 1f, 5f), "straddling it is visible");
        assertFalse(frustum.intersectsAabb(0f, 0f, -5f, 1f, 1f, -1f), "behind the near plane");
    }

    @Test
    void everyPlaneIsStillConsulted() {
        // The loop bound changed from PLANE_COUNT to the length of the backing array. If the two ever
        // stop matching, a plane would be silently skipped and geometry outside the frustum would be
        // drawn. So: put a box outside each of the six planes in turn, one at a time, and require that
        // each one is rejected. A skipped plane shows up as the matching case passing.
        Frustum frustum = new Frustum(Frustum.identity());
        assertFalse(frustum.intersectsAabb(-1e6f, 0f, 0.25f, -1e6f + 1f, 1f, 0.75f), "outside LEFT");
        assertFalse(frustum.intersectsAabb(1e6f, 0f, 0.25f, 1e6f + 1f, 1f, 0.75f), "outside RIGHT");
        assertFalse(frustum.intersectsAabb(0f, -1e6f, 0.25f, 1f, -1e6f + 1f, 0.75f), "outside BOTTOM");
        assertFalse(frustum.intersectsAabb(0f, 1e6f, 0.25f, 1f, 1e6f + 1f, 0.75f), "outside TOP");
        assertFalse(frustum.intersectsAabb(0f, 0f, -1e6f, 1f, 1f, -1e6f + 1f), "outside NEAR");
        assertFalse(frustum.intersectsAabb(0f, 0f, 1e6f, 1f, 1f, 1e6f + 1f), "outside FAR");
        assertEquals(6, Frustum.PLANE_COUNT, "six planes, six cases above");
    }

    /** A reversed-Z perspective projection: near at depth 1, the far plane at 0. */
    private static float[] perspective() {
        float[] m = new float[16];
        float scale = 1.0f / (float) Math.tan(Math.toRadians(35.0));
        m[0] = scale / 1.7777778f;
        m[5] = scale;
        m[11] = -1.0f;
        m[14] = 0.05f;
        return m;
    }
}
