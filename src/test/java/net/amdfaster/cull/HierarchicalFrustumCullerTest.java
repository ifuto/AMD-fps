package net.amdfaster.cull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The property that matters is not the saving, it is that the saving does not change the answer. A
 * culler that dropped one section too many would not log anything -- it would put a hole in the terrain
 * and the player would find it by walking into it. So every test here compares against flat culling of
 * the same range with the same frustum before it looks at any counter.
 */
class HierarchicalFrustumCullerTest {

    private static float[] perspective() {
        float[] m = new float[16];
        float scale = 1.0f / (float) Math.tan(Math.toRadians(35.0));
        m[0] = scale / 1.7777778f;
        m[5] = scale;
        m[11] = -1.0f;
        m[14] = 0.05f;
        return m;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ (long) z;
    }

    /** Every section in the range that flat culling keeps. This is the answer to match. */
    private static Set<Long> flat(Frustum frustum, int minX, int minY, int minZ,
            int sizeX, int sizeY, int sizeZ) {
        Set<Long> kept = new HashSet<>();
        for (int x = minX; x < minX + sizeX; x++) {
            for (int y = minY; y < minY + sizeY; y++) {
                for (int z = minZ; z < minZ + sizeZ; z++) {
                    int bx = x * HierarchicalFrustumCuller.SECTION_SIZE;
                    int by = y * HierarchicalFrustumCuller.SECTION_SIZE;
                    int bz = z * HierarchicalFrustumCuller.SECTION_SIZE;
                    int s = HierarchicalFrustumCuller.SECTION_SIZE;
                    if (frustum.intersectsAabb(bx, by, bz, bx + s, by + s, bz + s)) {
                        kept.add(key(x, y, z));
                    }
                }
            }
        }
        return kept;
    }

    private static Set<Long> hierarchical(Frustum frustum, int minX, int minY, int minZ,
            int sizeX, int sizeY, int sizeZ) {
        HierarchicalFrustumCuller culler = new HierarchicalFrustumCuller();
        Set<Long> kept = new HashSet<>();
        culler.cull(frustum, minX, minY, minZ, sizeX, sizeY, sizeZ,
                (x, y, z) -> kept.add(key(x, y, z)));
        return kept;
    }

    @Test
    void theWalkReachesExactlyWhatFlatCullingKeeps() {
        // A twelve chunk render distance, the scene the numbers in the class comment were measured on.
        // Negative indices throughout, because the walk converts blocks back to section indices by
        // division and Java truncates toward zero -- a negative coordinate would land in the wrong
        // section if that were not exact.
        Frustum frustum = new Frustum(perspective());

        Set<Long> expected = flat(frustum, -12, -4, -12, 24, 24, 24);
        Set<Long> actual = hierarchical(frustum, -12, -4, -12, 24, 24, 24);

        assertEquals(expected, actual, "same sections, or the culler is wrong rather than fast");
        assertTrue(expected.size() > 0, "the frustum has to see something for this to test anything");
    }

    @Test
    void itAgreesAcrossFrustumsAndRanges() {
        // One scene is not a proof. Several frustums, including one that sees nothing and one that sees
        // everything, plus ranges that are not cubes and not powers of two.
        float[][] matrices = {
            perspective(),
            Frustum.identity(),
            Frustum.translation(-13.5f, 64f, 27.25f),
            Frustum.translation(4096f, -2048f, 0.5f),
        };
        int[][] ranges = {
            {-12, -4, -12, 24, 24, 24},
            {0, 0, 0, 1, 1, 1},
            {-3, 0, -5, 7, 3, 11},
            {-1, -1, -1, 2, 2, 2},
            {5, -2, -8, 16, 9, 13},
        };
        int compared = 0;
        for (float[] matrix : matrices) {
            Frustum frustum = new Frustum(matrix);
            for (int[] r : ranges) {
                assertEquals(flat(frustum, r[0], r[1], r[2], r[3], r[4], r[5]),
                        hierarchical(frustum, r[0], r[1], r[2], r[3], r[4], r[5]),
                        "range " + java.util.Arrays.toString(r));
                compared++;
            }
        }
        assertEquals(20, compared);
    }

    @Test
    void aFrustumThatSeesNothingReachesNothing() {
        // Everything behind the camera. The walk must still terminate and must not report a section.
        float[] m = perspective();
        // Push the whole range behind the near plane by translating it far along +z, which the
        // perspective matrix puts behind the camera.
        Frustum frustum = new Frustum(m);
        Set<Long> kept = hierarchical(frustum, 200, 200, 200, 4, 4, 4);

        assertEquals(0, kept.size());
    }

    @Test
    void itPerformsFarFewerTestsThanFlatCulling() {
        // The saving, on the scene from the class comment. These three numbers were derived from this
        // frustum and this range by replaying the walk, not carried over from a model of a different
        // frustum -- the first draft did exactly that and every one of them was wrong.
        Frustum frustum = new Frustum(perspective());
        HierarchicalFrustumCuller culler = new HierarchicalFrustumCuller();

        int flatTests = 24 * 24 * 24;
        int reached = culler.cull(frustum, -12, -4, -12, 24, 24, 24, (x, y, z) -> { });

        assertEquals(flat(frustum, -12, -4, -12, 24, 24, 24).size(), reached);
        assertEquals(13824, flatTests);
        assertEquals(2929L, culler.tests(), "box tests the walk performed");
        assertEquals(1910L, culler.leavesReached());
        assertEquals(78.81, culler.savedPercent(flatTests), 0.01);
        assertTrue(culler.sectionsSkipped() > reached,
                "far more discarded whole than reached, which is where the saving is");
    }

    @Test
    void sectionsOutsideTheRangeAreNeverReported() {
        // The tree is a power-of-two cube and the range usually is not, so part of the cube is past the
        // range. Reporting those would hand over coordinates nobody has loaded.
        // A zero matrix makes every plane's normal zero length, and Frustum.set() stores such a plane as
        // "everything is inside". That is the frustum this test needs: it keeps every section, so the only
        // thing that can remove one is the range check under test. An identity matrix looks like the
        // obvious choice and is not -- it culls almost everything, which would make the test pass for the
        // wrong reason.
        Frustum frustum = new Frustum(new float[16]);
        HierarchicalFrustumCuller culler = new HierarchicalFrustumCuller();
        int[] worstX = {Integer.MIN_VALUE};
        int[] worstY = {Integer.MIN_VALUE};
        int[] worstZ = {Integer.MIN_VALUE};
        int[] count = new int[1];

        // A 3 section range inside a 4 section cube, so a quarter of the cube is past it.
        culler.cull(frustum, 0, 0, 0, 3, 3, 3, (x, y, z) -> {
            count[0]++;
            worstX[0] = Math.max(worstX[0], x);
            worstY[0] = Math.max(worstY[0], y);
            worstZ[0] = Math.max(worstZ[0], z);
        });

        assertEquals(27, count[0], "every section in the range, and none outside it");
        assertEquals(2, worstX[0], "nothing at index 3, which is past a size-3 range");
        assertEquals(2, worstY[0]);
        assertEquals(2, worstZ[0]);
    }

    @Test
    void negativeSectionIndicesSurviveTheRoundTrip() {
        // The walk works in blocks and converts back by division. Java truncates toward zero, so this is
        // only exact because every corner is a multiple of the section size -- which is worth pinning,
        // because the failure would be a culler that reports the neighbouring section.
        Frustum frustum = new Frustum(new float[16]);
        Set<Long> kept = hierarchical(frustum, -2, -2, -2, 2, 2, 2);

        assertEquals(8, kept.size());
        assertTrue(kept.contains(key(-2, -2, -2)));
        assertTrue(kept.contains(key(-1, -1, -1)));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        HierarchicalFrustumCuller culler = new HierarchicalFrustumCuller();
        Frustum frustum = new Frustum(perspective());
        HierarchicalFrustumCuller.Sink sink = (x, y, z) -> { };

        assertThrows(IllegalArgumentException.class,
                () -> culler.cull(null, 0, 0, 0, 1, 1, 1, sink));
        assertThrows(IllegalArgumentException.class,
                () -> culler.cull(frustum, 0, 0, 0, 1, 1, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> culler.cull(frustum, 0, 0, 0, 0, 1, 1, sink));
        assertThrows(IllegalArgumentException.class,
                () -> culler.cull(frustum, 0, 0, 0, 1, -1, 1, sink));
        assertThrows(IllegalArgumentException.class,
                () -> culler.cull(frustum, 0, 0, 0, 1, 1, 0, sink));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // An unused culler has technically avoided everything, and reporting that as a saving would
        // describe it as though it had done something.
        HierarchicalFrustumCuller culler = new HierarchicalFrustumCuller();

        assertEquals(0L, culler.tests());
        assertEquals(0L, culler.leavesReached());
        assertEquals(0L, culler.sectionsSkipped());
        assertEquals(0L, culler.walks());
        assertEquals(0.0, culler.savedPercent(13824), 1e-12);
        assertEquals(0.0, culler.savedPercent(0), 1e-12);
    }
}
