package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexAoTest {

    @Test
    void theTruthTableMatchesTheSymmetryArgument() {
        // Four distinct values per vertex, by symmetry. Every combination of two edge neighbours and
        // one corner neighbour, checked exhaustively rather than by example.
        assertEquals(3, VertexAo.compute(false, false, false), "open face");
        assertEquals(2, VertexAo.compute(false, false, true), "corner only");
        assertEquals(2, VertexAo.compute(false, true, false), "one edge");
        assertEquals(1, VertexAo.compute(false, true, true), "one edge plus corner");
        assertEquals(2, VertexAo.compute(true, false, false), "the other edge");
        assertEquals(1, VertexAo.compute(true, false, true));
        assertEquals(0, VertexAo.compute(true, true, false), "both edges");
        assertEquals(0, VertexAo.compute(true, true, true), "and the corner makes no difference");
    }

    @Test
    void anEnclosedVertexIsFullyOccludedEvenWhenItsCornerIsOpen() {
        // The case that is most often got wrong. With both edges solid the corner cell cannot be
        // reached, so it contributes nothing and the vertex is fully dark. Counting it would give 1,
        // and the result is a faint glow in exactly the corners AO exists to darken -- visible on every
        // interior corner in the world, and easy to miss because it is subtle.
        assertEquals(VertexAo.FULL, VertexAo.compute(true, true, false));
        assertEquals(0, VertexAo.compute(true, true, false));
        assertEquals(1, VertexAo.compute(true, false, true),
                "with only one edge solid the corner does count, and gives 1");
    }

    @Test
    void theDiagonalFlipsWhenTheDefaultPairIsTheBrighterOne() {
        // A quad is two triangles, and the diagonal decides how four corner values are interpolated.
        // With a fixed diagonal, alternating bright and dark corners give a different gradient in each
        // triangle, and the seam shows as a diagonal line across a flat face. The fix is to run the
        // diagonal through the darker pair.
        assertTrue(VertexAo.shouldFlipDiagonal(3, 0, 3, 0), "0-2 is bright, 1-3 is dark: flip");
        assertFalse(VertexAo.shouldFlipDiagonal(0, 3, 0, 3), "0-2 is already the dark pair");
    }

    @Test
    void anEvenFaceNeverFlips() {
        // Uniform occlusion has no seam to hide, and a tie must resolve to "do not flip" so that
        // identical faces mesh identically. A tie that flipped on some faces and not others would be
        // worse than either choice, because the inconsistency would itself be visible.
        assertFalse(VertexAo.shouldFlipDiagonal(3, 3, 3, 3), "fully open");
        assertFalse(VertexAo.shouldFlipDiagonal(0, 0, 0, 0), "fully occluded");
        assertFalse(VertexAo.shouldFlipDiagonal(3, 1, 1, 3), "the two pairs sum the same");
    }

    @Test
    void theRuleIsSymmetricUnderTheDiagonalSwap() {
        // Swapping which pair is called the default must invert the answer, or the rule depends on the
        // labelling of corners rather than on the geometry.
        boolean forward = VertexAo.shouldFlipDiagonal(3, 0, 3, 0);
        boolean swapped = VertexAo.shouldFlipDiagonal(0, 3, 0, 3);
        assertTrue(forward != swapped, "the two labelings disagree, as they must");
    }

    @Test
    void theShadeCurveIsMonotonicAndNeverBlack() {
        // A fully enclosed vertex is in shadow, not absent. Going to zero would punch holes that look
        // like missing geometry rather than like shadow.
        assertEquals(1.0f, VertexAo.shade(3), 1e-6f);
        assertEquals(0.8f, VertexAo.shade(2), 1e-6f);
        assertEquals(0.6f, VertexAo.shade(1), 1e-6f);
        assertEquals(0.4f, VertexAo.shade(0), 1e-6f);
        for (int ao = 0; ao < 3; ao++) {
            assertTrue(VertexAo.shade(ao) < VertexAo.shade(ao + 1), "each step of occlusion is visible");
        }
    }

    @Test
    void occlusionSurvivesAPackAndUnpackRoundTrip() {
        // The quad already carries a packed light word per corner and occlusion needs two bits, so they
        // travel together. A section produces thousands of quads; one integer per corner instead of two
        // is the difference between a vertex format that fits and one that does not.
        for (int light = 0; light < 0xFFFF; light += 997) {
            for (int ao = 0; ao <= 3; ao++) {
                int packed = VertexAo.packIntoLight(light, ao);
                assertEquals(ao, VertexAo.aoFromLight(packed), "ao survives at light " + light);
                assertEquals(light & ~0x3, packed & ~0x3, "the light bits are untouched");
            }
        }
    }

    @Test
    void packingOverwritesThePreviousOcclusionRatherThanOringIntoIt() {
        // Repacking a corner with different occlusion must replace the old value. Or-ing would ratchet
        // every corner toward fully occluded as a section is rebuilt, and the world would get darker
        // every time a block was placed.
        int packed = VertexAo.packIntoLight(0xF0, 3);
        assertEquals(3, VertexAo.aoFromLight(packed));
        packed = VertexAo.packIntoLight(packed, 0);
        assertEquals(0, VertexAo.aoFromLight(packed), "replaced, not accumulated");
        assertEquals(0xF0, packed & ~0x3, "and the light is still intact");
    }
}
