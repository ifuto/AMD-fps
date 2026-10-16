package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileBinnerTest {

    @Test
    void aBoxInsideOneTileCoversOne() {
        assertEquals(1, TileBinner.tilesCovered(0, 0, 15, 15));
        assertEquals(1, TileBinner.tilesCovered(3, 4, 12, 9));
    }

    @Test
    void aBoxSpanningTilesCoversThemAll() {
        assertEquals(4, TileBinner.tilesCovered(0, 0, 31, 31), "two by two");
        assertEquals(49, TileBinner.tilesCovered(0, 0, 100, 100), "seven by seven");
    }

    @Test
    void aNegativeBoxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TileBinner.tilesCovered(10, 0, 5, 5));
    }

    @Test
    void geometryAcrossManyTilesIsWorthBinning() {
        // A wall of merged quads covers many tiles with few triangles each, and the binning cost is far less
        // than the framebuffer trips it saves.
        TileBinner binner = new TileBinner();

        assertTrue(binner.shouldBin(100, 49));
        assertEquals(1L, binner.binnedDraws());
        assertEquals(0L, binner.unbinnedDraws());
    }

    @Test
    void geometryInOneTileIsNotWorthBinning() {
        // Where the binning work is pure overhead. A single large entity model covers one tile with many
        // triangles, and the choice is per draw rather than a global setting.
        TileBinner binner = new TileBinner();

        assertFalse(binner.shouldBin(100, 1));
        assertEquals(0L, binner.binnedDraws());
        assertEquals(1L, binner.unbinnedDraws());
    }

    @Test
    void theNetSavingCanGoEitherWay() {
        TileBinner binner = new TileBinner();
        binner.shouldBin(100, 49);

        assertEquals(49 * TileBinner.MS_PER_TILE_TRIP - 100 * TileBinner.MS_PER_ASSIGN,
                binner.msNet(), 1e-12);
        assertEquals(0.0188, binner.msNet(), 1e-9);
        assertEquals(95.92, binner.savedPercent(), 0.01);
    }

    @Test
    void aBadChoiceIsReportedAsNegativeNotHidden() {
        // A draw binned over a single tile costs 0.0008 ms of binning to save 0.0004 ms of trips. Hiding that
        // behind a zero would make the measurement useless for choosing.
        TileBinner binner = new TileBinner();

        assertFalse(binner.shouldBin(100, 1));

        assertEquals(-0.0004, binner.msNet(), 1e-12);
        assertEquals(-100.0, binner.savedPercent(), 1e-9);
    }

    @Test
    void aFreshBinnerReportsZeroNotNaN() {
        // A binner that has seen nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        TileBinner binner = new TileBinner();

        assertEquals(0.0, binner.savedPercent());
        assertEquals(0.0, binner.msNet(), 1e-12);
        assertEquals(0.0, binner.trianglesPerTile());
        assertEquals(0L, binner.triangles());
        assertEquals(0L, binner.tilesTouched());
        assertEquals(16, TileBinner.TILE_PIXELS);
        assertEquals(256, TileBinner.TILE_AREA);
    }
}
