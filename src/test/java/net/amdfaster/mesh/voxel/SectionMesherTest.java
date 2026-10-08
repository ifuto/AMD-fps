package net.amdfaster.mesh.voxel;

import net.amdfaster.light.LightSampler;
import net.amdfaster.light.LightValue;
import net.amdfaster.light.VertexLight;
import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corner-order mapping between a quad's winding and {@code SmoothLight.face}'s order is the
 * most fragile thing in the smooth lighting path: getting it wrong renders as a seam down one
 * diagonal of every face, which is easy to miss in a screenshot and impossible to miss in play.
 *
 * <p>The expectations below were produced by a separate implementation of the lighting and diffed
 * before committing. The mapping test asserts the invariant -- that the index chosen is the one
 * whose (u,v) end matches the corner -- rather than the table's contents, so the table can be
 * rewritten without the test turning into a copy of it.
 */
class SectionMesherTest {

    /** Sky light 15 everywhere, block light equal to the x coordinate, nothing occluding. */
    private static final class GradientSampler implements LightSampler {
        private final Set<Long> occluders = new HashSet<>();

        private static long key(int x, int y, int z) {
            return ((long) (x + 64) << 24) | ((long) (y + 64) << 12) | (long) (z + 64);
        }

        GradientSampler occlude(int x, int y, int z) {
            this.occluders.add(key(x, y, z));
            return this;
        }

        @Override
        public int sky(int x, int y, int z) {
            return 15;
        }

        @Override
        public int block(int x, int y, int z) {
            return x >= 0 && x <= 15 ? x : 0;
        }

        @Override
        public boolean occludes(int x, int y, int z) {
            return this.occluders.contains(key(x, y, z));
        }
    }

    private static ArrayVoxelView singleBlock() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        return view;
    }

    private static Meshlet onlyMeshlet(SectionMesh mesh, Orientation orientation) {
        Meshlet[] meshlets = mesh.meshlets(orientation);
        assertEquals(1, meshlets.length, "one block, one quad, one meshlet");
        return meshlets[0];
    }

    @Test
    void theCornerMappingPicksTheEndThatMatchesTheCorner() {
        // For every orientation and every corner, the smooth-lighting index chosen must be the one
        // whose (u,v) position is the same end of the face as the corner's.
        for (Orientation o : Orientation.values()) {
            for (int i = 0; i < 4; i++) {
                int index = SectionMesher.smoothCornerIndex(o, i);
                assertTrue(index >= 0 && index < 4, o + " corner " + i + " -> " + index);
                // SmoothLight.face orders: 0 (minU,minV), 1 (maxU,minV), 2 (maxU,maxV), 3 (minU,maxV).
                boolean highU = index == 1 || index == 2;
                boolean highV = index == 2 || index == 3;
                assertEquals(o.cornerA(i) == 1, highU,
                        o + " corner " + i + " is on the wrong end of U");
                assertEquals(o.cornerB(i) == 1, highV,
                        o + " corner " + i + " is on the wrong end of V");
            }
        }
    }

    @Test
    void theCornerMappingUsesEachSmoothIndexExactlyOnce() {
        // A mapping that sent two corners to the same smooth index would leave one corner's lighting
        // unassigned and silently duplicate another's.
        for (Orientation o : Orientation.values()) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                assertTrue(seen.add(SectionMesher.smoothCornerIndex(o, i)),
                        o + " reuses a smooth index at corner " + i);
            }
            assertEquals(4, seen.size());
        }
    }

    @Test
    void smoothMeshingGivesEachCornerTheLightOfItsOwnPosition() {
        // Block light follows x, so the two corners at x=4 and the two at x=5 must differ. The
        // expected values are what the independent model produced: the face's four samples average
        // to block 3 at x=4 and 4 at x=5.
        SectionMesh mesh = SectionMesher.meshSmooth(singleBlock(), new GradientSampler(), 0, 0, 0);
        Meshlet m = onlyMeshlet(mesh, Orientation.POS_Y);
        assertEquals(4, m.vertexCount());

        for (int corner = 0; corner < 4; corner++) {
            int vertex = m.vertexIndex(0, corner);
            int packed = m.light(vertex);
            int x = m.positionX(vertex);
            assertEquals(x == 4 ? 3 : 4, LightValue.block(VertexLight.unpackLight(packed)),
                    "corner " + corner + " at x=" + x);
            assertEquals(15, LightValue.sky(VertexLight.unpackLight(packed)));
            assertEquals(VertexLight.AO_MAX, VertexLight.unpackAo(packed),
                    "nothing occludes, so nothing is shaded");
        }
    }

    @Test
    void theCornersOfOneQuadAreNotAllTheSame() {
        // The whole point of the change: a merged face must not carry one flat value.
        SectionMesh mesh = SectionMesher.meshSmooth(singleBlock(), new GradientSampler(), 0, 0, 0);
        Meshlet m = onlyMeshlet(mesh, Orientation.POS_Y);
        Set<Integer> distinct = new HashSet<>();
        for (int corner = 0; corner < 4; corner++) {
            distinct.add(m.light(m.vertexIndex(0, corner)));
        }
        assertEquals(2, distinct.size(), "two corners at x=4, two at x=5");
    }

    @Test
    void anOccludedCornerIsShadedAndNotBrightenedByTheBlockOccludingIt() {
        // (3,5,3) is the diagonal of the corner at (minU,minV), and it is set to glow. Averaging
        // solid samples in -- the common implementation -- would make this corner brighter than its
        // neighbours. Skipping them leaves it at the same level as before and just darker in shade.
        GradientSampler sampler = new GradientSampler().occlude(3, 5, 3);
        SectionMesh mesh = SectionMesher.meshSmooth(singleBlock(), sampler, 0, 0, 0);
        Meshlet m = onlyMeshlet(mesh, Orientation.POS_Y);

        int shaded = -1;
        int unshaded = -1;
        for (int corner = 0; corner < 4; corner++) {
            int vertex = m.vertexIndex(0, corner);
            int packed = m.light(vertex);
            if (m.positionX(vertex) == 4 && m.positionZ(vertex) == 4) {
                shaded = packed;
            } else {
                unshaded = packed;
            }
        }
        assertNotEquals(-1, shaded, "found the corner at (4,4)");
        assertEquals(2, VertexLight.unpackAo(shaded), "one solid diagonal drops it one level");
        assertEquals(VertexLight.AO_MAX, VertexLight.unpackAo(unshaded));
        assertEquals(3, LightValue.block(VertexLight.unpackLight(shaded)),
                "the glowing solid block must not leak into the average");
    }

    @Test
    void flatMeshingLightsEveryCornerIdentically() {
        SectionMesh mesh = SectionMesher.mesh(singleBlock(), 0, 0, 0);
        Meshlet m = onlyMeshlet(mesh, Orientation.POS_Y);
        Set<Integer> distinct = new HashSet<>();
        for (int corner = 0; corner < 4; corner++) {
            int packed = m.light(m.vertexIndex(0, corner));
            distinct.add(packed);
            assertEquals(VertexLight.AO_MAX, VertexLight.unpackAo(packed),
                    "flat means no shading was computed, not that the corner is unoccluded by luck");
        }
        assertEquals(1, distinct.size());
    }

    @Test
    void theTwoEntryPointsAgreeOnGeometryAndDifferOnlyInLight() {
        // Smooth lighting must not change what is drawn, only how it is shaded. If it moved a
        // vertex the bug would be invisible in a still image and obvious in motion.
        SectionMesh flat = SectionMesher.mesh(singleBlock(), 0, 0, 0);
        SectionMesh smooth = SectionMesher.meshSmooth(singleBlock(), new GradientSampler(), 0, 0, 0);

        for (Orientation o : Orientation.values()) {
            Meshlet[] a = flat.meshlets(o);
            Meshlet[] b = smooth.meshlets(o);
            assertEquals(a.length, b.length, o + " meshlet count");
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i].quadCount(), b[i].quadCount(), o + " quad count");
                assertEquals(a[i].vertexCount(), b[i].vertexCount(), o + " vertex count");
                for (int v = 0; v < a[i].vertexCount(); v++) {
                    assertEquals(a[i].positionX(v), b[i].positionX(v), o + " x of vertex " + v);
                    assertEquals(a[i].positionY(v), b[i].positionY(v), o + " y of vertex " + v);
                    assertEquals(a[i].positionZ(v), b[i].positionZ(v), o + " z of vertex " + v);
                    assertEquals(a[i].u(v), b[i].u(v), 0f, o + " u of vertex " + v);
                    assertEquals(a[i].v(v), b[i].v(v), 0f, o + " v of vertex " + v);
                }
            }
        }
        assertEquals(flat.totalPositionBytes(), smooth.totalPositionBytes());
        assertEquals(flat.totalAttributeBytes(), smooth.totalAttributeBytes());
        assertEquals(flat.totalLightBytes(), smooth.totalLightBytes());
        assertEquals(flat.totalIndexBytes(), smooth.totalIndexBytes());
    }

    @Test
    void everyOrientationOfTheBlockIsSmoothlyLit() {
        SectionMesh mesh = SectionMesher.meshSmooth(singleBlock(), new GradientSampler(), 0, 0, 0);
        int faces = 0;
        for (Orientation o : Orientation.values()) {
            Meshlet[] meshlets = mesh.meshlets(o);
            assertEquals(1, meshlets.length, o + " should have the block's one face");
            Meshlet m = meshlets[0];
            assertEquals(4, m.vertexCount());
            for (int corner = 0; corner < 4; corner++) {
                int packed = m.light(m.vertexIndex(0, corner));
                assertEquals(15, LightValue.sky(VertexLight.unpackLight(packed)),
                        o + " corner " + corner);
                assertTrue(VertexLight.unpackAo(packed) >= 0);
            }
            faces++;
        }
        assertEquals(6, faces, "a lone block shows all six faces");
    }
}
