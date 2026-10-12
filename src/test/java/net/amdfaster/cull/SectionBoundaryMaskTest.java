package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionBoundaryMaskTest {

    /** A section that is solid everywhere. */
    private static final SectionBoundaryMask.OpacitySource SOLID = (x, y, z) -> true;

    /** A section that is empty everywhere. */
    private static final SectionBoundaryMask.OpacitySource EMPTY = (x, y, z) -> false;

    /** Solid except one block, which is the shape a window or a doorway makes. */
    private static SectionBoundaryMask.OpacitySource solidExcept(int hx, int hy, int hz) {
        return (x, y, z) -> !(x == hx && y == hy && z == hz);
    }

    @Test
    void aSolidSectionSealsEveryFace() {
        SectionBoundaryMask mask = SectionBoundaryMask.scan(SOLID);

        assertEquals(SectionBoundaryMask.SEALED, mask.mask());
        assertEquals(6, mask.sealedFaceCount());
        for (int face = 0; face < 6; face++) {
            assertTrue(mask.isSealed(face), "face " + face);
            assertTrue(mask.hidesNeighbour(face));
        }
    }

    @Test
    void anEmptySectionSealsNothing() {
        SectionBoundaryMask mask = SectionBoundaryMask.scan(EMPTY);

        assertEquals(SectionBoundaryMask.OPEN, mask.mask());
        assertEquals(0, mask.sealedFaceCount());
    }

    @Test
    void oneHoleOpensExactlyTheFacesItTouches() {
        // A block at a corner is on three faces at once, and all three must open. Reporting only one
        // would leave the other two claiming a wall where there is now a hole.
        SectionBoundaryMask corner = SectionBoundaryMask.scan(solidExcept(0, 0, 0));

        assertEquals(3, corner.sealedFaceCount());
        assertTrue(corner.isSealed(SectionBoundaryMask.FACE_X_POS));
        assertTrue(corner.isSealed(SectionBoundaryMask.FACE_Y_POS));
        assertTrue(corner.isSealed(SectionBoundaryMask.FACE_Z_POS));
        assertFalse(corner.isSealed(SectionBoundaryMask.FACE_X_NEG));
        assertFalse(corner.isSealed(SectionBoundaryMask.FACE_Y_NEG));
        assertFalse(corner.isSealed(SectionBoundaryMask.FACE_Z_NEG));
    }

    @Test
    void anInteriorBlockOpensNothing() {
        // A block in the middle of the section is on no face. Removing it changes the geometry and must
        // not change what the boundary mask claims.
        SectionBoundaryMask mask = SectionBoundaryMask.scan(solidExcept(8, 8, 8));

        assertEquals(SectionBoundaryMask.SEALED, mask.mask());
    }

    @Test
    void aHoleInOneFaceOnlyOpensThatFace() {
        SectionBoundaryMask mask = SectionBoundaryMask.scan(solidExcept(8, 8, 0));

        assertFalse(mask.isSealed(SectionBoundaryMask.FACE_Z_NEG), "the hole is on -Z");
        assertEquals(5, mask.sealedFaceCount());
    }

    @Test
    void theNeighbourOffsetsMapToTheSixFaces() {
        assertEquals(SectionBoundaryMask.FACE_X_NEG, SectionBoundaryMask.faceForNeighbour(-1, 0, 0));
        assertEquals(SectionBoundaryMask.FACE_X_POS, SectionBoundaryMask.faceForNeighbour(1, 0, 0));
        assertEquals(SectionBoundaryMask.FACE_Y_NEG, SectionBoundaryMask.faceForNeighbour(0, -1, 0));
        assertEquals(SectionBoundaryMask.FACE_Y_POS, SectionBoundaryMask.faceForNeighbour(0, 1, 0));
        assertEquals(SectionBoundaryMask.FACE_Z_NEG, SectionBoundaryMask.faceForNeighbour(0, 0, -1));
        assertEquals(SectionBoundaryMask.FACE_Z_POS, SectionBoundaryMask.faceForNeighbour(0, 0, 1));
    }

    @Test
    void diagonalAndSelfOffsetsGetNoFace() {
        // Diagonal neighbours share an edge or a corner, and a sealed face says nothing about them.
        // Reporting a face would cull a section that is visible around the corner, which is a hole in
        // the world rather than a missed optimisation.
        assertEquals(-1, SectionBoundaryMask.faceForNeighbour(0, 0, 0), "itself");
        assertEquals(-1, SectionBoundaryMask.faceForNeighbour(1, 1, 0), "an edge neighbour");
        assertEquals(-1, SectionBoundaryMask.faceForNeighbour(1, 1, 1), "a corner neighbour");
        assertEquals(-1, SectionBoundaryMask.faceForNeighbour(-1, 0, -1));
    }

    @Test
    void oppositeFacesPairUpByXorWithOne() {
        // If B is reached from A through A's +X, then A is reached from B through B's -X. The pairing has
        // to be an involution or walking the graph in both directions disagrees.
        for (int face = 0; face < 6; face++) {
            assertEquals(face, SectionBoundaryMask.oppositeFace(SectionBoundaryMask.oppositeFace(face)),
                    "face " + face + " round-trips");
            assertFalse(face == SectionBoundaryMask.oppositeFace(face), "and is never its own opposite");
        }
        assertEquals(SectionBoundaryMask.FACE_X_POS,
                SectionBoundaryMask.oppositeFace(SectionBoundaryMask.FACE_X_NEG));
        assertEquals(SectionBoundaryMask.FACE_Y_NEG,
                SectionBoundaryMask.oppositeFace(SectionBoundaryMask.FACE_Y_POS));
        assertEquals(SectionBoundaryMask.FACE_Z_POS,
                SectionBoundaryMask.oppositeFace(SectionBoundaryMask.FACE_Z_NEG));
    }

    @Test
    void aMaskWithBitsOutsideTheSixFacesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SectionBoundaryMask(1 << 6));
        assertThrows(IllegalArgumentException.class, () -> new SectionBoundaryMask(-1));
        assertEquals(SectionBoundaryMask.SEALED, new SectionBoundaryMask(63).mask());
    }

    @Test
    void aSealedFaceHidesTheNeighbourAndAnOpenOneDoesNot() {
        SectionBoundaryMask mask = SectionBoundaryMask.scan(solidExcept(8, 8, 0));

        assertTrue(mask.hidesNeighbour(SectionBoundaryMask.FACE_X_NEG));
        assertFalse(mask.hidesNeighbour(SectionBoundaryMask.FACE_Z_NEG));
    }
}
