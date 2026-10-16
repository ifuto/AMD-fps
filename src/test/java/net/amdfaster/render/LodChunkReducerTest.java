package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodChunkReducerTest {

    private static final int CELLS = LodChunkReducer.LOD_CELLS * LodChunkReducer.LOD_CELLS;

    private static int[] cells(int value) {
        int[] out = new int[CELLS];
        java.util.Arrays.fill(out, value);
        return out;
    }

    @Test
    void aChunkReducesToItsCells() {
        // A hillside two kilometres away covers a few pixels, and the difference between its real surface
        // and a coarse approximation is smaller than a pixel.
        LodChunkReducer reducer = new LodChunkReducer();

        int produced = reducer.reduce(cells(64), cells(0x7CBD6B));

        assertEquals(CELLS, produced);
        assertEquals(1L, reducer.reduced());
    }

    @Test
    void aCellKeepsTheHighestBlockNotTheAverage() {
        // Taking the average would pull a tall tower down toward the ground around it, and a castle on a
        // hill would read as a slightly raised field. The silhouette is what the eye uses.
        assertEquals(200, LodChunkReducer.cellHeight(new int[] {64, 64, 200, 64}));
        assertEquals(64, LodChunkReducer.cellHeight(new int[] {64, 64, 64, 64}));
    }

    @Test
    void aLodChunkIsNotDrawnWhenTheRealOneIsLoaded() {
        // Drawing both would put two surfaces in the same place and whichever is drawn second would win, so
        // terrain would flicker between detailed and coarse as chunks loaded and unloaded.
        LodChunkReducer reducer = new LodChunkReducer();

        assertFalse(reducer.shouldDrawLod(true));
        assertTrue(reducer.shouldDrawLod(false));

        assertEquals(1L, reducer.skippedForRealChunk());
        assertEquals(1L, reducer.drawnLod());
    }

    @Test
    void theReductionIsAFractionOfTheSection() {
        assertEquals(8, LodChunkReducer.LOD_CELLS);
        assertEquals(2, LodChunkReducer.BLOCKS_PER_CELL);
        assertEquals(192, LodChunkReducer.bytesPerChunk(), "64 cells at 3 bytes");
    }

    @Test
    void aHundredDistantChunksCostAlmostNothingToDraw() {
        LodChunkReducer reducer = new LodChunkReducer();

        for (int i = 0; i < 100; i++) {
            reducer.shouldDrawLod(false);
        }

        assertEquals(1.0, reducer.msDrawingLod(), 1e-9);
        assertEquals(50.0, reducer.msDrawingFullResolution(), 1e-9);
        assertEquals(98.0, reducer.savedPercent(), 1e-9);
    }

    @Test
    void staleLodIsDiscardedRatherThanDrawn() {
        // A player who quarried a hillside will return to find the LOD still showing the hill. A stale LOD
        // is worse than a missing one: a missing one shows the horizon as empty, which reads as distance,
        // while a stale one shows land that is not there.
        LodChunkReducer reducer = new LodChunkReducer();

        reducer.discardStale();

        assertEquals(1L, reducer.discarded());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        LodChunkReducer reducer = new LodChunkReducer();

        assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(new int[10], cells(0)));
        assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(cells(64), new int[10]));
        assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(cells(-1), cells(0)));
        assertThrows(IllegalArgumentException.class,
                () -> LodChunkReducer.cellHeight(new int[0]));
    }

    @Test
    void aFreshReducerReportsZeroNotNaN() {
        // A reducer that has drawn nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        LodChunkReducer reducer = new LodChunkReducer();

        assertEquals(0.0, reducer.savedPercent());
        assertEquals(0.0, reducer.msDrawingLod());
        assertEquals(0L, reducer.reduced());
        assertEquals(0L, reducer.drawnLod());
        assertEquals(0L, reducer.discarded());
    }
}
