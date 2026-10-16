package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbientOcclusionBakerTest {

    @Test
    void everyCornerCombinationFollowsTheRule() {
        // Both sides solid means the corner is enclosed no matter what the diagonal block does, so level
        // 0. Otherwise the level is three minus how many of the three neighbours are solid. Asserted
        // against the rule rather than against a restatement of the code.
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        assertEquals(3, baker.level(false, false, false), "nothing blocks it");
        assertEquals(2, baker.level(false, false, true), "only the diagonal");
        assertEquals(2, baker.level(false, true, false), "only one side");
        assertEquals(1, baker.level(false, true, true), "one side and the diagonal");
        assertEquals(2, baker.level(true, false, false), "only the other side");
        assertEquals(1, baker.level(true, false, true));
        assertEquals(0, baker.level(true, true, false), "both sides enclose the corner");
        assertEquals(0, baker.level(true, true, true), "and the diagonal cannot brighten it back");
        assertEquals(8L, baker.baked());
    }

    @Test
    void bothSidesSolidIsAlwaysTheDarkest() {
        // The diagonal block cannot brighten an enclosed corner back up.
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        assertEquals(baker.level(true, true, false), baker.level(true, true, true));
        assertEquals(AmbientOcclusionBaker.LEVEL_FULL, baker.level(true, true, true));
    }

    @Test
    void brightnessStepsFromFullToAQuarter() {
        assertEquals(1.0, AmbientOcclusionBaker.brightness(3), 1e-9);
        assertEquals(0.75, AmbientOcclusionBaker.brightness(2), 1e-9);
        assertEquals(0.5, AmbientOcclusionBaker.brightness(1), 1e-9);
        assertEquals(0.25, AmbientOcclusionBaker.brightness(0), 1e-9);
    }

    @Test
    void nonsenseLevelsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AmbientOcclusionBaker.brightness(4));
        assertThrows(IllegalArgumentException.class, () -> AmbientOcclusionBaker.brightness(-1));
    }

    @Test
    void aFaceBakesFourCorners() {
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        // Two sides per corner, then the four diagonals.
        boolean[] sides = {
                false, false,
                true, false,
                true, true,
                false, true};
        boolean[] corners = {false, false, true, false};

        int[] levels = baker.bakeFace(sides, corners);

        // Derived from the rule, corner by corner:
        //   0: no sides, no diagonal          -> 3 - 0 = 3
        //   1: one side, no diagonal          -> 3 - 1 = 2
        //   2: both sides solid               -> 0 regardless of the diagonal
        //   3: one side, no diagonal          -> 3 - 1 = 2
        assertEquals(3, levels[0]);
        assertEquals(2, levels[1]);
        assertEquals(0, levels[2], "both sides solid");
        assertEquals(2, levels[3], "one side only; corners[3] is false so the diagonal does not count");
    }

    @Test
    void theDiagonalFlipsWhenTheOtherPairIsLighter() {
        // A quad is two triangles and the split decides how the corner values interpolate. When the two
        // corners on one diagonal are darker than the two on the other, splitting along the dark pair
        // leaves a visible seam down the middle of the face.
        assertTrue(AmbientOcclusionBaker.flipDiagonal(new int[] {0, 3, 0, 3}),
                "corners 0 and 2 are the dark pair, so flip");
        assertFalse(AmbientOcclusionBaker.flipDiagonal(new int[] {3, 0, 3, 0}),
                "corners 1 and 3 are the dark pair, so keep");
    }

    @Test
    void anEvenFaceDoesNotFlip() {
        assertFalse(AmbientOcclusionBaker.flipDiagonal(new int[] {3, 3, 3, 3}));
        assertFalse(AmbientOcclusionBaker.flipDiagonal(new int[] {1, 1, 1, 1}));
        assertFalse(AmbientOcclusionBaker.flipDiagonal(new int[] {2, 2, 2, 2}));
    }

    @Test
    void thePerFrameCostIsWhatItSays() {
        // Twenty thousand visible quads at four corners each, sixty times a second.
        assertEquals(4.0, AmbientOcclusionBaker.msPerFrame(20_000), 1e-9);
        assertEquals(240.0, AmbientOcclusionBaker.msPerFrame(20_000) * 60, 1e-9);

        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();
        assertEquals(4.0, baker.msSavedPerFrame(20_000), 1e-9);
    }

    @Test
    void avoidedLookupsAreCounted() {
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        baker.avoidPerFrame(20_000);

        assertEquals(80_000L, baker.perFrameAvoided());
    }

    @Test
    void aFaceNeedsEightSidesAndFourCorners() {
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        assertThrows(IllegalArgumentException.class,
                () -> baker.bakeFace(new boolean[6], new boolean[4]));
        assertThrows(IllegalArgumentException.class,
                () -> baker.bakeFace(new boolean[8], new boolean[3]));
        assertThrows(IllegalArgumentException.class,
                () -> AmbientOcclusionBaker.flipDiagonal(new int[3]));
    }

    @Test
    void aFreshBakerReportsZeroNotNaN() {
        AmbientOcclusionBaker baker = new AmbientOcclusionBaker();

        assertEquals(0L, baker.baked());
        assertEquals(0L, baker.perFrameAvoided());
        assertEquals(0.0, baker.msSavedPerFrame(0));
        assertEquals(4, AmbientOcclusionBaker.CORNERS);
        assertEquals(3, AmbientOcclusionBaker.LEVEL_NONE);
        assertEquals(0, AmbientOcclusionBaker.LEVEL_FULL);
    }
}
