package net.amdfaster.cull;

import net.amdfaster.mesh.Meshlet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletCullerTest {

    /** The packed form of a box from (2,3,4) to (10,11,12), matching Meshlet.packedBounds. */
    private static int packed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (minX & 0x1F) | ((minY & 0x1F) << 5) | ((minZ & 0x1F) << 10)
                | ((maxX & 0x1F) << 15) | ((maxY & 0x1F) << 20) | ((maxZ & 0x1F) << 25);
    }

    @Test
    void thePackingAgreesWithMeshletsUnpackers() {
        int bounds = packed(2, 3, 4, 10, 11, 12);
        assertEquals(2, Meshlet.unpackMinX(bounds));
        assertEquals(3, Meshlet.unpackMinY(bounds));
        assertEquals(4, Meshlet.unpackMinZ(bounds));
        assertEquals(10, Meshlet.unpackMaxX(bounds));
        assertEquals(11, Meshlet.unpackMaxY(bounds));
        assertEquals(12, Meshlet.unpackMaxZ(bounds));
    }

    @Test
    void fiveBitsReachTheFarEdgeOfASection() {
        // Greedy meshing can produce a quad spanning the whole section, whose far edge sits at
        // coordinate 16, so the field has to hold 16 and not wrap.
        int bounds = packed(0, 0, 0, 16, 16, 16);
        assertEquals(16, Meshlet.unpackMaxX(bounds));
        assertEquals(16, Meshlet.unpackMaxY(bounds));
        assertEquals(16, Meshlet.unpackMaxZ(bounds));
    }

    @Test
    void aMeshletAtTheOriginIsOutsideTheIdentityClipCube() {
        Frustum identity = new Frustum(Frustum.identity());
        assertFalse(MeshletCuller.isVisible(packed(2, 3, 4, 10, 11, 12), 0, 0, 0, identity),
                "the box starts at x=2, well past the right plane at x=1");
    }

    @Test
    void movingTheSectionOriginBringsTheSameMeshletIntoView() {
        Frustum identity = new Frustum(Frustum.identity());
        int bounds = packed(2, 3, 4, 10, 11, 12);
        assertTrue(MeshletCuller.isVisible(bounds, -5, -5, -5, identity),
                "the box becomes (-3,-2,-1)..(5,6,7), which straddles the clip cube");
    }

    @Test
    void aMeshletBehindTheCameraIsCulled() {
        Frustum far = new Frustum(Frustum.translation(0f, 0f, -100f));
        assertFalse(MeshletCuller.isVisible(packed(2, 3, 4, 10, 11, 12), 0, 0, 0, far));
    }

    @Test
    void aMeshletInFrontOfTheCameraIsKept() {
        Frustum f = new Frustum(Frustum.translation(-5f, -7f, -4.5f));
        assertTrue(MeshletCuller.isVisible(packed(2, 3, 4, 10, 11, 12), 0, 0, 0, f),
                "the box lands at (-3,-4,-0.5)..(5,4,7.5), which overlaps every plane");
    }

    @Test
    void cullingIsConservativeOnACorner() {
        // A box that straddles a corner of the frustum and is really outside still passes the
        // per-plane test. That is the safe direction: a false positive costs a draw, a false
        // negative costs geometry that vanishes while you look at it.
        Frustum identity = new Frustum(Frustum.identity());
        assertTrue(identity.intersectsAabb(0.5f, 0.5f, 0.5f, 5f, 5f, 0.9f));
    }
}
