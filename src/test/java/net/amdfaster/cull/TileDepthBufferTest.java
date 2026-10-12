package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structure is Intel's: 32 by 8 pixel tiles, 8 by 4 sub-tiles, eight depths per tile so that a whole
 * tile is one 256-bit vector. Every number here is checked against a Python model of the same indexing.
 */
class TileDepthBufferTest {

    /** 64x16 is four tiles and 32 sub-tiles, small enough to reason about exhaustively. */
    private static TileDepthBuffer small() {
        return new TileDepthBuffer(64, 16);
    }

    @Test
    void theTilingArithmeticMatchesThePublishedShape() {
        assertEquals(32, TileDepthBuffer.TILE_W);
        assertEquals(8, TileDepthBuffer.TILE_H);
        assertEquals(256, TileDepthBuffer.PIXELS_PER_TILE, "the width of an AVX2 register in bytes");
        assertEquals(4, TileDepthBuffer.SUBS_X);
        assertEquals(2, TileDepthBuffer.SUBS_Y);
        assertEquals(8, TileDepthBuffer.SUBTILES_PER_TILE);
        assertEquals(32, TileDepthBuffer.SUBTILES_PER_TILE * Float.BYTES,
                "eight depths is exactly one 256-bit vector");
    }

    @Test
    void aFullHdBufferIsThirtyTwoTimesSmallerThanTheRealThing() {
        TileDepthBuffer buffer = new TileDepthBuffer(1920, 1080);

        assertEquals(60, buffer.tilesX());
        assertEquals(135, buffer.tilesY());
        assertEquals(64800, buffer.subtileCount());
        assertEquals(259200, buffer.depthBytes());
        assertEquals(8294400, 1920 * 1080 * 4, "a full-resolution D32 buffer");
    }

    @Test
    void dimensionsRoundUpToWholeTiles() {
        // A partial tile would need a bounds check on every sub-tile access, and the inner loop is
        // exactly where those checks cannot be afforded.
        TileDepthBuffer buffer = new TileDepthBuffer(100, 20);
        assertEquals(4, buffer.tilesX(), "100 pixels needs four 32-pixel tiles");
        assertEquals(3, buffer.tilesY(), "20 pixels needs three 8-pixel tiles");
        assertEquals(100, buffer.width(), "but the reported screen size stays the real one");
        assertEquals(20, buffer.height());
    }

    @Test
    void anEmptyBufferOccludesNothing() {
        TileDepthBuffer buffer = small();

        for (int tile = 0; tile < 4; tile++) {
            for (int sub = 0; sub < 8; sub++) {
                assertEquals(TileDepthBuffer.EMPTY, buffer.subDepthAt(tile, sub), "tile " + tile);
                assertFalse(buffer.isCovered(tile, sub));
            }
        }
        assertFalse(buffer.isOccluded(0, 0, 64, 16, 100.0f),
                "with nothing rasterised, nothing is hidden");
    }

    @Test
    void aFullScreenOccluderCoversEverySubtile() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);

        assertEquals(32, buffer.rasterisedSubtiles());
        for (int tile = 0; tile < 4; tile++) {
            for (int sub = 0; sub < 8; sub++) {
                assertTrue(buffer.isCovered(tile, sub), "tile " + tile + " sub " + sub);
                assertEquals(10.0f, buffer.subDepthAt(tile, sub), 0.0f);
            }
        }
    }

    @Test
    void somethingBehindAnOccluderIsHiddenAndSomethingInFrontIsNot() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);

        assertTrue(buffer.isOccluded(0, 0, 64, 16, 20.0f), "20 is behind 10");
        assertFalse(buffer.isOccluded(0, 0, 64, 16, 5.0f), "5 is in front of 10");
        assertFalse(buffer.isOccluded(0, 0, 64, 16, 10.0f),
                "exactly at the occluder's depth is not behind it, so it is drawn");
    }

    @Test
    void aBoxOnlyPartiallyCoveringASubtileMarksNothing() {
        // Rasterising rounds inward and querying rounds outward, and the asymmetry is the safety
        // property. A box that covers 4 to 28 horizontally spans sub-tile columns 0 through 3 without
        // filling any of them, so it must claim no coverage at all.
        TileDepthBuffer buffer = small();
        buffer.rasterize(4, 4, 28, 6, 10.0f);

        assertEquals(0, buffer.rasterisedSubtiles());
        assertFalse(buffer.isOccluded(4, 4, 28, 6, 20.0f), "a gap remains, so nothing is hidden");
    }

    @Test
    void aBoxSpanningWholeSubtilesMarksExactlyThose() {
        // 8 to 24 horizontally is sub-tile columns 1 and 2; 4 to 12 vertically is sub-tile rows 1 and 2.
        // Four sub-tiles, all inside the first tile.
        TileDepthBuffer buffer = small();
        buffer.rasterize(8, 4, 24, 12, 5.0f);

        assertEquals(4, buffer.rasterisedSubtiles());
        assertFalse(buffer.isCovered(0, 0), "sub-tile 0 is left of the box");
        assertFalse(buffer.isCovered(0, 3), "sub-tile 3 is right of the box");
        for (int sub : new int[] {5, 6}) {
            assertTrue(buffer.isCovered(0, sub), "sub-tile " + sub);
            assertEquals(5.0f, buffer.subDepthAt(0, sub), 0.0f);
        }
    }

    @Test
    void oneUncoveredSubtileInsideAFootprintIsEnoughToKeepABox() {
        // The box spans the whole screen but only four sub-tiles of it are covered. Every covered
        // sub-tile is nearer than the box, so a culler that only looked at covered sub-tiles would
        // reject it -- and it would be wrong, because the box is visible through the gaps.
        TileDepthBuffer buffer = small();
        buffer.rasterize(8, 4, 24, 12, 5.0f);

        assertFalse(buffer.isOccluded(0, 0, 64, 16, 20.0f),
                "the gaps let the box through");
        assertTrue(buffer.isOccluded(8, 4, 24, 12, 20.0f),
                "but restricted to the covered region it is genuinely hidden");
    }

    @Test
    void aNearerOccluderOverwritesAFartherOne() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 30.0f);
        buffer.rasterize(0, 0, 64, 16, 10.0f);

        assertEquals(10.0f, buffer.subDepthAt(0, 0), 0.0f, "the nearer surface wins");
        assertTrue(buffer.isOccluded(0, 0, 64, 16, 20.0f),
                "a box at 20 is behind the stored 10, so it is hidden -- the same verdict the next test "
                        + "reaches from the opposite write order");
    }

    @Test
    void aFartherOccluderDoesNotOverwriteANearerOne() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);
        buffer.rasterize(0, 0, 64, 16, 30.0f);

        assertEquals(10.0f, buffer.subDepthAt(0, 0), 0.0f);
        assertTrue(buffer.isOccluded(0, 0, 64, 16, 20.0f), "still hidden behind the nearer wall");
    }

    @Test
    void anOccluderOffTheScreenContributesNothingAndDoesNotThrow() {
        TileDepthBuffer buffer = small();

        buffer.rasterize(-100, -100, -50, -50, 10.0f);
        buffer.rasterize(1000, 1000, 1100, 1100, 10.0f);
        buffer.rasterize(-32, 0, 0, 16, 10.0f);

        assertEquals(0, buffer.rasterisedSubtiles());
    }

    @Test
    void anOccluderClippedByTheScreenEdgeMarksOnlyWhatIsOnScreen() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(-32, 0, 32, 16, 10.0f);

        // Clipped to x in [0,32) and y in [0,16), which is the whole left half: both rows of sub-tiles
        // in the top-left tile and both in the bottom-left one.
        assertEquals(16, buffer.rasterisedSubtiles());
        for (int sub = 0; sub < 8; sub++) {
            assertTrue(buffer.isCovered(0, sub), "sub " + sub);
        }
        for (int sub = 0; sub < 8; sub++) {
            assertFalse(buffer.isCovered(1, sub), "the next tile is untouched");
        }
    }

    @Test
    void aQueryOutsideTheScreenIsNeverOccluded() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);

        assertFalse(buffer.isOccluded(-100, -100, -50, -50, 20.0f));
        assertFalse(buffer.isOccluded(1000, 1000, 1100, 1100, 20.0f));
        assertFalse(buffer.isOccluded(30, 8, 10, 4, 20.0f), "an inverted box covers nothing");
    }

    @Test
    void clearingRestoresTheEmptyStateAndKeepsTheCounters() {
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);
        assertTrue(buffer.isOccluded(0, 0, 64, 16, 20.0f));

        buffer.clear();

        assertEquals(0, buffer.rasterisedSubtiles());
        assertFalse(buffer.isOccluded(0, 0, 64, 16, 20.0f), "the occluder is gone");
        assertEquals(TileDepthBuffer.EMPTY, buffer.subDepthAt(0, 0), 0.0f);
    }

    @Test
    void theCountersReportWhatTheFrameActuallyDecided() {
        // The cull rate is the number that says whether this is worth its cost, so it has to be
        // readable without instrumenting the caller.
        TileDepthBuffer buffer = small();
        buffer.rasterize(0, 0, 64, 16, 10.0f);

        buffer.isOccluded(0, 0, 64, 16, 20.0f);   // hidden
        buffer.isOccluded(0, 0, 64, 16, 5.0f);    // visible
        buffer.isOccluded(0, 0, 64, 16, 25.0f);   // hidden

        assertEquals(3, buffer.occlusionTests());
        assertEquals(2, buffer.occludedTests());

        buffer.resetCounters();
        assertEquals(0, buffer.occlusionTests());
        assertEquals(0, buffer.occludedTests());
        assertEquals(TileDepthBuffer.EMPTY, buffer.subDepthAt(3, 7), 0.0f,
                "resetting counters does not touch the buffer");
    }

    @Test
    void aDegenerateScreenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TileDepthBuffer(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new TileDepthBuffer(64, -1));
    }
}
