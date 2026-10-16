package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenFaceCullerTest {

    @Test
    void aFaceAgainstStoneIsNotBuilt() {
        // A wall of stone has one visible face per block and five that are buried. Building all of them
        // means the mesh holds six times the geometry it needs.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertFalse(culler.shouldBuildFace(true, false));
        assertEquals(1L, culler.culled());
        assertEquals(0L, culler.built());
    }

    @Test
    void aFaceAgainstAirIsBuilt() {
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertTrue(culler.shouldBuildFace(false, false));
        assertEquals(1L, culler.built());
    }

    @Test
    void glassDoesNotHideTheFaceBehindIt() {
        // The player sees the stone through the glass, and removing that face would leave a hole in the
        // wall. The rule is whether the neighbour blocks the view completely, and only a full opaque cube
        // does.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertTrue(culler.shouldBuildFace(false, false), "glass is not opaque");
    }

    @Test
    void aDifferentBlockStillHidesItWhenOpaque() {
        // Stone beside dirt is still a buried face. Two blocks pressed together have no gap between them,
        // and matching by type would keep every face at a material boundary, which is most of the faces in
        // a built structure.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertFalse(culler.shouldBuildFace(true, false));
        assertEquals(1L, culler.culled());
    }

    @Test
    void leavesAreDeferredToTheirOwnRules() {
        // A leaf block's texture has holes, so the face behind it can be seen through them. Whether those
        // faces are culled depends on the graphics setting and on the texture actually having holes, which
        // is a different decision and a different class.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertTrue(culler.shouldBuildFace(false, true));
        assertEquals(1L, culler.deferred());
        assertEquals(0L, culler.culled());
    }

    @Test
    void aSolidChunkCullsAlmostEveryFace() {
        // A chunk of solid rock has almost no visible faces at all.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    boolean interior = x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15;
                    for (int face = 0; face < HiddenFaceCuller.FACES; face++) {
                        boolean exposed = !interior && isOuterFace(x, y, z, face);
                        culler.shouldBuildFace(!exposed, false);
                    }
                }
            }
        }

        assertEquals(4096L * 6, culler.faces());
        assertEquals(6L * 16 * 16, culler.built(), "only the outer shell");
        assertEquals(4096L * 6 - 6L * 16 * 16, culler.culled());
        assertEquals(93.75, culler.culledPercent(), 1e-9);
    }

    /** Whether a face on the given block is on the outside of the chunk. */
    private static boolean isOuterFace(int x, int y, int z, int face) {
        return switch (face) {
            case 0 -> x == 0;
            case 1 -> x == 15;
            case 2 -> y == 0;
            case 3 -> y == 15;
            case 4 -> z == 0;
            default -> z == 15;
        };
    }

    @Test
    void theSavingIsInMemoryAsWellAsTime() {
        HiddenFaceCuller culler = new HiddenFaceCuller();

        for (int i = 0; i < 1000; i++) {
            culler.shouldBuildFace(true, false);
        }

        assertEquals(1000L * HiddenFaceCuller.BYTES_PER_FACE, culler.bytesSaved());
        assertEquals(36_000L, culler.bytesSaved());
        assertEquals(0.05, culler.msSaved(), 1e-9);
        assertEquals(100.0, culler.savedPercent(), 1e-9);
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has been offered nothing has technically avoided everything, but reporting that as
        // a saving would describe an unused one as though it had done something.
        HiddenFaceCuller culler = new HiddenFaceCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0L, culler.bytesSaved());
        assertEquals(0L, culler.faces());
        assertEquals(0L, culler.deferred());
        assertEquals(6, HiddenFaceCuller.FACES);
    }
}
