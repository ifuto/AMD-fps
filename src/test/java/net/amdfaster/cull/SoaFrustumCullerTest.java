package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every expectation here is an integer or a power of two, so the float arithmetic is exact in both Java
 * and the Python model these numbers came from. The same algorithm is implemented in AVX-512 in
 * afback_cull.c and its scalar twin there was differentially checked over 1000 random boxes with zero
 * mismatches, so what these tests pin down is that the Java transcription is the same formula again.
 */
class SoaFrustumCullerTest {

    /** The frustum is the axis-aligned box [0,10] on all three axes. */
    private static SoaFrustumCuller.Planes unitFrustum() {
        SoaFrustumCuller.Planes planes = new SoaFrustumCuller.Planes();
        planes.set(0, 1, 0, 0, 0);
        planes.set(1, -1, 0, 0, 10);
        planes.set(2, 0, 1, 0, 0);
        planes.set(3, 0, -1, 0, 10);
        planes.set(4, 0, 0, 1, 0);
        planes.set(5, 0, 0, -1, 10);
        return planes;
    }

    private static SoaFrustumCuller.Boxes sampleBoxes() {
        SoaFrustumCuller.Boxes boxes = new SoaFrustumCuller.Boxes(8);
        boxes.put(0, 1, 1, 1, 2, 2, 2);            // wholly inside
        boxes.put(1, -5, -5, -5, -1, -1, -1);      // wholly outside
        boxes.put(2, 9, 9, 9, 15, 15, 15);         // straddles the far corner
        boxes.put(3, 11, 0, 0, 12, 1, 1);          // just past x=10
        boxes.put(4, -1, 0, 0, 0, 1, 1);           // touches x=0 exactly
        boxes.put(5, 0, -1, 0, 1, 0, 1);           // touches y=0 exactly
        boxes.put(6, 20, 20, 20, 30, 30, 30);      // far outside
        boxes.put(7, -0.5f, 5, 5, 0.5f, 6, 6);     // straddles x=0
        return boxes;
    }

    @Test
    void theCullerKeepsExactlyTheBoxesThatOverlapTheFrustum() {
        boolean[] visible = new boolean[8];
        int kept = SoaFrustumCuller.cull(unitFrustum(), sampleBoxes(), 8, visible);

        assertEquals(5, kept, "five of the eight overlap [0,10]^3");
        assertArrayEquals(
                new boolean[] {true, false, true, false, true, true, false, true},
                visible,
                "0,2,4,5,7 are visible; 1,3,6 are not");
    }

    @Test
    void aBoxRestingExactlyOnAPlaneIsKept() {
        // Box 4 spans x = -1 to 0, so its farthest corner along plane 0's normal sits at x = 0 and the
        // plane equation evaluates to exactly zero. Keeping it is what >= 0 means, and it is the only
        // safe choice: a culler that used > 0 would delete every chunk whose boundary happens to land on
        // the frustum edge, which shows up as a wall of missing geometry at certain camera angles.
        boolean[] visible = new boolean[8];
        SoaFrustumCuller.cull(unitFrustum(), sampleBoxes(), 8, visible);
        assertTrue(visible[4], "touching x=0 is inside");
        assertTrue(visible[5], "touching y=0 is inside");
    }

    @Test
    void aBoxJustPastAPlaneIsCulled() {
        // Box 3 starts at x = 11, past the far plane at 10. Its positive vertex for plane 1 is the
        // smaller x, because that plane's normal points in -x, and -11 + 10 is negative.
        boolean[] visible = new boolean[8];
        SoaFrustumCuller.cull(unitFrustum(), sampleBoxes(), 8, visible);
        assertFalse(visible[3], "x in [11,12] is past the far plane");
        assertFalse(visible[6], "nowhere near the frustum");
        assertFalse(visible[1], "behind and below and in front of nothing");
    }

    @Test
    void thePositiveVertexTestUsesTheCornerFarthestAlongEachNormal() {
        // The test has to pick maxX when the coefficient is positive and minX when it is negative.
        // Getting that backwards keeps boxes that are outside and culls boxes that are inside, so it
        // would fail loudly here rather than subtly in game -- which is the point of pinning it.
        SoaFrustumCuller.Planes planes = new SoaFrustumCuller.Planes();
        planes.set(0, 1, 0, 0, -5);      // x >= 5
        planes.set(1, -1, 0, 0, 100);
        planes.set(2, 0, 1, 0, 0);
        planes.set(3, 0, -1, 0, 100);
        planes.set(4, 0, 0, 1, 0);
        planes.set(5, 0, 0, -1, 100);

        SoaFrustumCuller.Boxes boxes = new SoaFrustumCuller.Boxes(2);
        boxes.put(0, 0, 0, 0, 4, 1, 1);    // maxX is 4, still below 5
        boxes.put(1, 0, 0, 0, 6, 1, 1);    // maxX is 6, reaches past 5

        boolean[] visible = new boolean[2];
        SoaFrustumCuller.cull(planes, boxes, 2, visible);
        assertFalse(visible[0], "entirely below x=5");
        assertTrue(visible[1], "reaches x=6, so it crosses the plane");
    }

    @Test
    void transposedBoundsAreNormalisedRatherThanSilentlyInvertingTheTest() {
        // A box handed over with min and max swapped would make maxX smaller than minX, and the
        // positive-vertex selection would then test the wrong corner. The result looks exactly like
        // chunks disappearing when the camera turns, so the swap is undone on the way in instead of
        // being trusted.
        SoaFrustumCuller.Boxes boxes = new SoaFrustumCuller.Boxes(2);
        boxes.put(0, 2, 2, 2, 1, 1, 1);        // deliberately backwards
        boxes.put(1, 1, 1, 1, 2, 2, 2);        // the same box, the right way round

        assertEquals(boxes.minX[0], boxes.minX[1], "both end up with the same min");
        assertEquals(boxes.maxX[0], boxes.maxX[1], "and the same max");

        boolean[] visible = new boolean[2];
        int kept = SoaFrustumCuller.cull(unitFrustum(), boxes, 2, visible);
        assertEquals(2, kept, "the reversed box culls identically to the correct one");
        assertTrue(visible[0] && visible[1]);
    }

    @Test
    void theMaskFormAgreesWithTheBooleanForm() {
        // The backend returns a mask over the socket, because a thousand booleans is a thousand bytes
        // to scan and a mask is sixteen longs. Both forms have to say the same thing or the two paths
        // disagree about what is on screen.
        boolean[] visible = new boolean[8];
        int keptBooleans = SoaFrustumCuller.cull(unitFrustum(), sampleBoxes(), 8, visible);

        long[] mask = new long[1];
        int keptMask = SoaFrustumCuller.cullToMask(unitFrustum(), sampleBoxes(), 8, mask);

        assertEquals(keptBooleans, keptMask, "both count the same survivors");
        assertEquals(0xb5L, mask[0], "bits 0,2,4,5,7 set");
        for (int i = 0; i < 8; i++) {
            assertEquals(visible[i], SoaFrustumCuller.isVisible(mask, i), "bit " + i);
        }
    }

    @Test
    void countingAMaskIgnoresTheUnusedBitsInTheLastWord() {
        // Bits above the submitted count are padding. Counting them would report boxes that were never
        // submitted as visible, and the frame budget would be spent on geometry that does not exist.
        long[] mask = new long[] {-1L, -1L};
        assertEquals(64, SoaFrustumCuller.countVisible(mask, 64), "a full word is all real");
        assertEquals(65, SoaFrustumCuller.countVisible(mask, 65),
                "word 0 in full plus exactly one bit of word 1");
        assertEquals(100, SoaFrustumCuller.countVisible(mask, 100), "100 submitted, 100 counted");
        assertEquals(1, SoaFrustumCuller.countVisible(mask, 1), "one submitted, one counted");
    }

    @Test
    void theMaskIsClearedBetweenCalls() {
        // Reusing a scratch mask across frames is the normal case. Leftover bits from a frame where more
        // was visible would keep geometry alive after it left the frustum.
        long[] mask = new long[1];
        SoaFrustumCuller.cullToMask(unitFrustum(), sampleBoxes(), 8, mask);
        assertEquals(0xb5L, mask[0]);

        SoaFrustumCuller.Boxes empty = new SoaFrustumCuller.Boxes(8);
        for (int i = 0; i < 8; i++) {
            empty.put(i, 50, 50, 50, 60, 60, 60);
        }
        assertEquals(0, SoaFrustumCuller.cullToMask(unitFrustum(), empty, 8, mask), "nothing visible now");
        assertEquals(0L, mask[0], "and the previous bits are gone");
    }

    @Test
    void submittingMoreBoxesThanTheOutputHoldsIsRejected() {
        // A silent truncation here would cull the tail of the list every frame, and the missing geometry
        // would always be the same geometry -- the last sections in submission order.
        assertThrows(IllegalArgumentException.class,
                () -> SoaFrustumCuller.cull(unitFrustum(), sampleBoxes(), 8, new boolean[4]));
        assertThrows(IllegalArgumentException.class,
                () -> SoaFrustumCuller.cullToMask(unitFrustum(), sampleBoxes(), 8, new long[0]));
    }

    @Test
    void theBlockStrideMatchesTheVectorWidth() {
        // Sixteen floats per AVX-512 register. If this constant drifts from the backend's LANES the two
        // sides stop processing the same boxes per iteration and the differential check stops meaning
        // anything.
        assertEquals(16, SoaFrustumCuller.BLOCK);
        assertEquals(6, SoaFrustumCuller.PLANES);
    }
}
