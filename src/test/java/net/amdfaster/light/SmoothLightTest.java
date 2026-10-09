package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every expectation in here was produced by an independent re-implementation of the algorithm
 * written separately from the Java, and the two were diffed before this file was committed. That
 * step is not ceremony: hand-computed lighting expectations have been wrong often enough in this
 * project that nothing here is asserted from inspection alone.
 */
class SmoothLightTest {

    private static final class MapSampler implements LightSampler {
        private final Map<Long, int[]> cells = new HashMap<>();
        private final int defaultSky;
        private final int defaultBlock;

        MapSampler(int defaultSky, int defaultBlock) {
            this.defaultSky = defaultSky;
            this.defaultBlock = defaultBlock;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 64) << 24) | ((long) (y + 64) << 12) | (long) (z + 64);
        }

        MapSampler put(int x, int y, int z, int block, int sky, boolean occludes) {
            this.cells.put(key(x, y, z), new int[] {block, sky, occludes ? 1 : 0});
            return this;
        }

        private int[] at(int x, int y, int z) {
            return this.cells.getOrDefault(key(x, y, z), new int[] {this.defaultBlock, this.defaultSky, 0});
        }

        @Override
        public int sky(int x, int y, int z) {
            return at(x, y, z)[1];
        }

        @Override
        public int block(int x, int y, int z) {
            return at(x, y, z)[0];
        }

        @Override
        public boolean occludes(int x, int y, int z) {
            return at(x, y, z)[2] != 0;
        }
    }

    /** Full daylight, nothing solid anywhere. */
    private static LightCache open() {
        LightCache cache = new LightCache();
        cache.fill(new MapSampler(15, 0), 0, 0, 0);
        return cache;
    }

    /** A 1x1 upward face on the top of the block at y=15. */
    private static FaceRef topFace() {
        return new FaceRef(1, true, 16, 0, 1, 0, 1);
    }

    @Test
    void anOpenCornerIsFullyLitAndUnoccluded() {
        VertexLight[] corners = SmoothLight.face(open(), topFace());
        for (VertexLight corner : corners) {
            assertEquals(LightValue.pack(0, 15), corner.light(), "L75");
            assertEquals(3, corner.ao(), "L76");
            assertEquals(1.0f, corner.shade(), 1e-6f, "L77");
        }
    }

    @Test
    void oneOccludedEdgeDropsOnlyTheTwoCornersBesideIt() {
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0).put(-1, 16, 0, 0, 15, true), 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, topFace());
        // Order is (minU,minV), (maxU,minV), (maxU,maxV), (minU,maxV).
        assertEquals(2, corners[0].ao(), "(0,0) touches the occluder along U");
        assertEquals(3, corners[1].ao(), "(1,0) is at the other end of the face");
        assertEquals(3, corners[2].ao(), "L90");
        assertEquals(2, corners[3].ao(), "(0,1) still touches it along U");
    }

    @Test
    void twoOccludedEdgesSealTheCornerCompletely() {
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0)
                .put(-1, 16, 0, 0, 15, true)
                .put(0, 16, -1, 0, 15, true), 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, topFace());
        assertEquals(0, corners[0].ao(), "both edges solid, so the corner is shut");
        assertEquals(2, corners[1].ao(), "L103");
        assertEquals(3, corners[2].ao(), "L104");
        assertEquals(2, corners[3].ao(), "L105");
        assertEquals(0.2f, corners[0].shade(), 1e-6f, "L106");
    }

    @Test
    void theDiagonalAloneStillDarkens() {
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0).put(-1, 16, -1, 0, 15, true), 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, topFace());
        assertEquals(2, corners[0].ao(), "diagonal counts as one of the three");
        assertEquals(3, corners[1].ao(), "L116");
        assertEquals(3, corners[2].ao(), "L117");
        assertEquals(3, corners[3].ao(), "L118");
    }

    @Test
    void aSolidNeighbourDoesNotLightTheCornerBesideIt() {
        // A glowing solid block at (-1,16,0) with the rest of the plane dark. Averaging occluded
        // samples in -- which is the usual implementation -- would give the corner a block level of
        // 3 and show up as a bright edge around every emissive block. Skipping them gives 0.
        MapSampler sampler = new MapSampler(0, 0);
        sampler.put(-1, 16, 0, 15, 0, true);
        LightCache cache = new LightCache();
        cache.fill(sampler, 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, topFace());
        assertEquals(0, LightValue.block(corners[0].light()), "the solid block's light must not leak");
        assertEquals(0, LightValue.sky(corners[0].light()), "L133");
        assertEquals(2, corners[0].ao(), "L134");
    }

    @Test
    void occlusionIsPerCornerNotPerFace() {
        // A 2x2 greedy quad with one occluder at the diagonal of exactly one corner. If the
        // implementation computed one AO for the whole face, all four would come out equal and this
        // would pass with the wrong answer -- hence the explicit per-corner assertions.
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0).put(-1, 16, -1, 0, 15, true), 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, new FaceRef(1, true, 16, 0, 2, 0, 2));
        assertEquals(2, corners[0].ao(), "only (0,0) has the occluder as its diagonal");
        assertEquals(3, corners[1].ao(), "L147");
        assertEquals(3, corners[2].ao(), "L148");
        assertEquals(3, corners[3].ao(), "L149");
    }

    @Test
    void aNegativeFaceSamplesTheBlockBelowItsPlane() {
        assertEquals(-1, new FaceRef(1, false, 0, 0, 1, 0, 1).frontBlock(), "L154");
        assertEquals(16, new FaceRef(1, true, 16, 0, 1, 0, 1).frontBlock(), "L155");

        // Everything open, so the only thing this proves is that the samples land inside the cache
        // rather than throwing on an out-of-range coordinate.
        VertexLight[] corners = SmoothLight.face(open(), new FaceRef(1, false, 0, 0, 1, 0, 1));
        for (VertexLight corner : corners) {
            assertEquals(3, corner.ao(), "L161");
        }
    }

    @Test
    void theAxisMappingPutsUAndVOnTheRightAxes() {
        // A face on the X axis varies over (u=Y, v=Z). Occluding (16,0,-1) must hit the corner at
        // v=0, which is along V, and leave the v=1 corners alone.
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0).put(16, 0, -1, 0, 15, true), 0, 0, 0);

        VertexLight[] corners = SmoothLight.face(cache, new FaceRef(0, true, 16, 0, 1, 0, 1));
        assertEquals(2, corners[0].ao(), "L173");
        assertEquals(2, corners[1].ao(), "L174");
        assertEquals(3, corners[2].ao(), "L175");
        assertEquals(3, corners[3].ao(), "L176");
    }

    @Test
    void theFrontBlockIsWhereTheFaceLooks() {
        FaceRef face = new FaceRef(2, true, 5, 1, 3, 2, 4);
        // On the Z axis the normal coordinate is Z, and u and v are X and Y.
        assertEquals(1, face.x(1, 2, 5), "axis 2: x is u");
        assertEquals(2, face.y(1, 2, 5), "axis 2: y is v");
        assertEquals(5, face.z(1, 2, 5), "axis 2: z is the plane");
        assertEquals(5, face.frontBlock(), "L186");

        FaceRef onX = new FaceRef(0, false, 9, 1, 3, 2, 4);
        assertEquals(9, onX.x(1, 2, 9), "axis 0: x is the plane");
        assertEquals(1, onX.y(1, 2, 9), "axis 0: y is u");
        assertEquals(2, onX.z(1, 2, 9), "axis 0: z is v");
        assertEquals(8, onX.frontBlock(), "a negative face looks one block below its plane");

        FaceRef onY = new FaceRef(1, true, 40, 1, 3, 2, 4);
        assertEquals(1, onY.x(1, 2, 40), "axis 1: x is u");
        assertEquals(40, onY.y(1, 2, 40), "axis 1: y is the plane");
        assertEquals(2, onY.z(1, 2, 40), "axis 1: z is v");
    }

    @Test
    void baseAndSideBlockIndicesStraddleTheFootprint() {
        FaceRef face = new FaceRef(1, true, 16, 2, 5, 3, 7);
        assertEquals(2, face.baseU(2), "L203");
        assertEquals(1, face.sideU(2), "L204");
        assertEquals(4, face.baseU(5), "L205");
        assertEquals(5, face.sideU(5), "L206");
        assertEquals(3, face.baseV(3), "L207");
        assertEquals(2, face.sideV(3), "L208");
        assertEquals(6, face.baseV(7), "L209");
        assertEquals(7, face.sideV(7), "L210");
    }

    @Test
    void aFaceMustHaveAreaAndAValidAxis() {
        assertThrows(IllegalArgumentException.class, () -> new FaceRef(3, true, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FaceRef(-1, true, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FaceRef(1, true, 0, 4, 4, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FaceRef(1, true, 0, 0, 1, 5, 2));
    }

    @Test
    void anOcclusionLevelOutsideZeroToThreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VertexLight(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new VertexLight(0, -1));
        assertThrows(IllegalArgumentException.class, () -> VertexLight.shadeFor(4));
    }

    @Test
    void theShadeCurveIsMinecraftsAndIsNotEvenlySpaced() {
        assertEquals(0.2f, VertexLight.shadeFor(0), 1e-6f, "L230");
        assertEquals(0.6f, VertexLight.shadeFor(1), 1e-6f, "L231");
        assertEquals(0.8f, VertexLight.shadeFor(2), 1e-6f, "L232");
        assertEquals(1.0f, VertexLight.shadeFor(3), 1e-6f, "L233");
        // The first step is four times the others on purpose: a sealed corner has to read as dark.
        assertTrue(VertexLight.shadeFor(1) - VertexLight.shadeFor(0)
                > VertexLight.shadeFor(2) - VertexLight.shadeFor(1));
    }

    @Test
    void theLightmapCoordinatesFollowTheLightValue() {
        VertexLight vertex = new VertexLight(LightValue.pack(4, 11), 3);
        assertEquals(LightValue.lightmapCoord(4), vertex.lightmapU(), 1e-6f, "L242");
        assertEquals(LightValue.lightmapCoord(11), vertex.lightmapV(), 1e-6f, "L243");
        assertEquals(LightValue.fullBright(), VertexLight.fullBright().light(), "L244");
        assertEquals(3, VertexLight.fullBright().ao(), "L245");
    }

    @Test
    void theOutParameterOverloadAgreesWithTheAllocatingOne() {
        LightCache cache = open();
        cache.fill(new MapSampler(15, 0).put(-1, 16, 0, 0, 15, true), 0, 0, 0);
        FaceRef face = topFace();

        VertexLight[] allocated = SmoothLight.face(cache, face);
        VertexLight[] reused = new VertexLight[4];
        SmoothLight.face(cache, face, reused);
        for (int i = 0; i < 4; i++) {
            assertEquals(allocated[i], reused[i], "corner " + i);
        }
    }

    @Test
    void theOutParameterOverloadRejectsAShortArray() {
        assertThrows(IllegalArgumentException.class,
                () -> SmoothLight.face(open(), topFace(), new VertexLight[3]));
    }
}
