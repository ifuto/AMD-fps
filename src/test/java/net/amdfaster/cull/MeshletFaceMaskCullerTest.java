package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static net.amdfaster.cull.MeshletFaceMaskCuller.DOWN;
import static net.amdfaster.cull.MeshletFaceMaskCuller.EAST;
import static net.amdfaster.cull.MeshletFaceMaskCuller.NORTH;
import static net.amdfaster.cull.MeshletFaceMaskCuller.SOUTH;
import static net.amdfaster.cull.MeshletFaceMaskCuller.UP;
import static net.amdfaster.cull.MeshletFaceMaskCuller.WEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletFaceMaskCullerTest {

    @Test
    void aCameraLookingNorthCannotSeeNorthFaces() {
        // A camera looking north sees south faces, and so on for each pair.
        int mask = MeshletFaceMaskCuller.visibleMask(NORTH, 0);

        assertEquals(SOUTH | WEST | EAST | UP | DOWN, mask);
        assertEquals(0, mask & NORTH);
    }

    @Test
    void aNorthFacingMeshletIsCulledFromTheNorth() {
        // None of the directions this meshlet can face is one the camera can see, so every triangle in it
        // points away.
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertFalse(culler.shouldDraw(NORTH, MeshletFaceMaskCuller.visibleMask(NORTH, 0)));
        assertEquals(1L, culler.culled());
    }

    @Test
    void aSouthFacingMeshletIsKeptFromTheNorth() {
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertTrue(culler.shouldDraw(SOUTH, MeshletFaceMaskCuller.visibleMask(NORTH, 0)));
        assertEquals(1L, culler.kept());
    }

    @Test
    void aMixedMeshletIsNeverCulled() {
        // A meshlet with both a north and a south face has two bits set, and no view direction makes both
        // point away. That is correct and common, because greedy meshing groups coplanar faces but not always.
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        for (int facing : new int[] {NORTH, SOUTH, WEST, EAST}) {
            for (int vertical : new int[] {-1, 0, 1}) {
                assertTrue(culler.shouldDraw(NORTH | SOUTH, MeshletFaceMaskCuller.visibleMask(facing, vertical)),
                        "facing " + facing + ", vertical " + vertical);
            }
        }
        assertEquals(12L, culler.kept());
        assertEquals(0L, culler.culled());
    }

    @Test
    void lookingUpHidesTheDownFaces() {
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertFalse(culler.shouldDraw(DOWN, MeshletFaceMaskCuller.visibleMask(0, 1)));
        assertTrue(culler.shouldDraw(UP, MeshletFaceMaskCuller.visibleMask(0, 1)));
    }

    @Test
    void aMeshletOfEveryFaceIsNeverCulled() {
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertTrue(culler.shouldDraw(MeshletFaceMaskCuller.ALL_FACES,
                MeshletFaceMaskCuller.visibleMask(NORTH, 0)));
        assertEquals(63, MeshletFaceMaskCuller.ALL_FACES);
    }

    @Test
    void aWallOfUniformMeshletsHalvesTheShading() {
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();
        int camera = MeshletFaceMaskCuller.visibleMask(NORTH, 0);

        for (int i = 0; i < 500; i++) {
            culler.shouldDraw(NORTH, camera);
        }
        for (int i = 0; i < 500; i++) {
            culler.shouldDraw(SOUTH, camera);
        }

        assertEquals(1000L, culler.tested());
        assertEquals(500L, culler.culled());
        assertEquals(50.0, culler.culledPercent(), 1e-9);
        assertEquals(2.0, culler.msSaved(), 1e-9);
        assertEquals(0.01, culler.msTesting(), 1e-9);
        assertEquals(49.75, culler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertThrows(IllegalArgumentException.class, () -> culler.shouldDraw(64, 63));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldDraw(-1, 63));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        MeshletFaceMaskCuller culler = new MeshletFaceMaskCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msTesting(), 1e-12);
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.culled());
    }
}
