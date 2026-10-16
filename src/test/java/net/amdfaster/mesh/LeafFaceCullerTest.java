package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeafFaceCullerTest {

    private static final int[] ALL_LEAVES = {
            LeafFaceCuller.LEAF, LeafFaceCuller.LEAF, LeafFaceCuller.LEAF,
            LeafFaceCuller.LEAF, LeafFaceCuller.LEAF, LeafFaceCuller.LEAF};

    @Test
    void anOpaqueNeighbourIsAlwaysCulled() {
        // It covers the face completely. Vanilla already does this; it is repeated here so the decision
        // has one place to live.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertTrue(culler.shouldCull(LeafFaceCuller.OPAQUE, true, false));
        assertTrue(culler.shouldCull(LeafFaceCuller.OPAQUE, false, false));
        assertEquals(2L, culler.facesCulledOpaque());
    }

    @Test
    void aLeafNeighbourInFancyIsKeptByDefault() {
        // The project's rule is culling that does not change what is on screen. Leaf textures have holes,
        // so strictly a sliver of the inner face could be visible, and the caller has to accept that.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertFalse(culler.shouldCull(LeafFaceCuller.LEAF, true, false));
        assertEquals(1L, culler.facesKept());
        assertEquals(0L, culler.facesCulledLeaf());
    }

    @Test
    void aLeafNeighbourInFancyIsCulledWhenAsked() {
        // The deliberate approximation, which is what More Culling and Cull Less Leaves do.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertTrue(culler.shouldCull(LeafFaceCuller.LEAF, true, true));
        assertEquals(1L, culler.facesCulledLeaf());
    }

    @Test
    void aLeafNeighbourInFastIsAlwaysCulled() {
        // In Fast graphics leaves are opaque blocks, so there are no holes to see through and no
        // approximation is involved.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertTrue(culler.shouldCull(LeafFaceCuller.LEAF, false, false));
        assertTrue(culler.shouldCull(LeafFaceCuller.LEAF, false, true));
        assertEquals(2L, culler.facesCulledLeaf());
    }

    @Test
    void airIsNeverCulledAgainst() {
        // It does not cover the face.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertFalse(culler.shouldCull(LeafFaceCuller.AIR, false, true),
                "not even in Fast graphics with the approximation on");
        assertEquals(1L, culler.facesKept());
    }

    @Test
    void aTranslucentNeighbourIsNeverCulledAgainst() {
        // Water, glass and ice do not cover the face, and culling against one would remove a surface that
        // should be seen through -- the opposite of the saving, and visible.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertFalse(culler.shouldCull(LeafFaceCuller.TRANSLUCENT, false, true));
        assertTrue(culler.auditDecision(LeafFaceCuller.TRANSLUCENT, false));
        assertEquals(0L, culler.facesCulledWrongly());
    }

    @Test
    void aCanopyOfInteriorLeavesCullsEverything() {
        // A single oak's canopy is about three hundred leaf blocks, 62% of them surrounded on all six
        // sides, which is 1,116 faces of geometry that exists only to be hidden.
        LeafFaceCuller culler = new LeafFaceCuller();

        for (int i = 0; i < 186; i++) {
            assertEquals(6, culler.cullBlock(ALL_LEAVES, false, false));
        }

        assertEquals(1116L, culler.facesCulledLeaf());
        assertEquals(100.0, culler.culledPercent(), 0.01);
        assertEquals(2232L, culler.trianglesRemoved());
        assertEquals(40176L, culler.bytesSaved());
        assertEquals(3600, LeafFaceCuller.trianglesPerCanopy(300));
    }

    @Test
    void theMeasuredCanopyIsCulledByAboutEightySevenPercent() {
        // 300 leaves, 62% interior (all six neighbours leaves), the rest on the boundary with two leaf,
        // two opaque and two air neighbours each.
        LeafFaceCuller culler = new LeafFaceCuller();
        int[] boundary = {
                LeafFaceCuller.LEAF, LeafFaceCuller.LEAF,
                LeafFaceCuller.OPAQUE, LeafFaceCuller.OPAQUE,
                LeafFaceCuller.AIR, LeafFaceCuller.AIR};

        for (int i = 0; i < 186; i++) {
            culler.cullBlock(ALL_LEAVES, false, false);
        }
        for (int i = 0; i < 114; i++) {
            assertEquals(4, culler.cullBlock(boundary, false, false));
        }

        assertEquals(1800L, culler.facesOffered());
        assertEquals(1572L, culler.facesCulledLeaf() + culler.facesCulledOpaque());
        assertEquals(87.33, culler.culledPercent(), 0.01);
        assertEquals(3144L, culler.trianglesRemoved());
    }

    @Test
    void theAuditCatchesAFaceCulledAgainstAir() {
        // A face culled against air or a translucent block is a hole in the world, and it is the failure
        // this class exists to avoid.
        LeafFaceCuller culler = new LeafFaceCuller();

        assertFalse(culler.auditDecision(LeafFaceCuller.AIR, true));
        assertFalse(culler.auditDecision(LeafFaceCuller.TRANSLUCENT, true));
        assertTrue(culler.auditDecision(LeafFaceCuller.OPAQUE, true));
        assertTrue(culler.auditDecision(LeafFaceCuller.LEAF, true));
        assertTrue(culler.auditDecision(LeafFaceCuller.AIR, false), "kept is always fine");

        assertEquals(2L, culler.facesCulledWrongly());
    }

    @Test
    void aBlockNeedsSixNeighbours() {
        LeafFaceCuller culler = new LeafFaceCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.cullBlock(new int[] {LeafFaceCuller.AIR}, false, false));
    }

    @Test
    void nonsenseNeighboursAreRejected() {
        LeafFaceCuller culler = new LeafFaceCuller();

        assertThrows(IllegalArgumentException.class, () -> culler.shouldCull(9, false, false));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldCull(-1, false, false));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        LeafFaceCuller culler = new LeafFaceCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.facesOffered());
        assertEquals(0L, culler.trianglesRemoved());
        assertEquals(0L, culler.facesCulledWrongly());
        assertEquals(6, LeafFaceCuller.FACES);
        assertEquals(2, LeafFaceCuller.TRIANGLES_PER_FACE);
    }
}
