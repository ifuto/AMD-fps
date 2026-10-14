package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodMergerTest {

    private static final int SNOW = 0xFFFFFF;
    private static final int ROCK = 0x808080;

    /** A flat face at one height. */
    private static int[] flat(int height) {
        int[] heights = new int[LodMerger.CELLS];
        java.util.Arrays.fill(heights, height);
        return heights;
    }

    private static int[] colours(int colour) {
        int[] colours = new int[LodMerger.CELLS];
        java.util.Arrays.fill(colours, colour);
        return colours;
    }

    @Test
    void aMergedQuadSitsAtThePeakNotTheAverage() {
        // The reason the class exists. Averaging a peak with the valley beside it puts the quad in
        // between and the mountain sinks, which is a change in the skyline -- the most visible thing
        // about distant terrain.
        int[] heights = flat(64);
        heights[0] = 90;
        LodMerger merger = new LodMerger();
        LodMerger.Merged[] out = new LodMerger.Merged[16];

        merger.merge(heights, colours(ROCK), 2, out);

        assertEquals(90, out[0].height, "the merged quad covers the peak, so it sits at the peak");
        assertEquals(64, out[1].height, "and the one next to it stays at the valley floor");
        assertEquals(4, out[0].span);
        assertEquals(0, out[0].cellX);
        assertEquals(0, out[0].cellZ);
    }

    @Test
    void aSnowCapKeepsItsOwnColour() {
        // One cell of snow over fifteen of rock. The most common colour would erase the cap, which is
        // again the skyline. The highest cell is the one visible from a distance.
        int[] heights = flat(64);
        int[] colours = colours(ROCK);
        heights[5] = 100;
        colours[5] = SNOW;

        LodMerger merger = new LodMerger();
        LodMerger.Merged[] out = new LodMerger.Merged[16];
        merger.merge(heights, colours, 2, out);

        assertEquals(SNOW, out[0].colour, "cell 5 is inside the first 4 by 4 group");
        assertEquals(100, out[0].height);
        assertEquals(ROCK, out[1].colour, "and the groups without the peak keep the rock");
    }

    @Test
    void aFlatAreaPicksTheFirstCellSoItIsRepeatable() {
        // Taking the last equal cell would make the colour depend on iteration order, which flickers
        // between frames when the mesh is rebuilt on another thread.
        int[] heights = flat(70);
        int[] colours = colours(ROCK);
        colours[0] = SNOW;
        colours[3] = 0xFF0000;

        LodMerger merger = new LodMerger();
        LodMerger.Merged[] first = new LodMerger.Merged[16];
        LodMerger.Merged[] second = new LodMerger.Merged[16];
        merger.merge(heights, colours, 2, first);
        merger.merge(heights, colours, 2, second);

        assertEquals(SNOW, first[0].colour, "cell 0 wins the tie");
        assertEquals(first[0].colour, second[0].colour, "and it is the same every time");
    }

    @Test
    void levelZeroMergesNothing() {
        int[] heights = flat(64);
        LodMerger merger = new LodMerger();
        LodMerger.Merged[] out = new LodMerger.Merged[LodMerger.CELLS];

        int n = merger.merge(heights, colours(ROCK), 0, out);

        assertEquals(LodMerger.CELLS, n, "one quad per cell");
        assertEquals(1, out[0].span);
        assertEquals(0.0, merger.reductionPercent());
        assertEquals(15, out[255].cellX);
        assertEquals(15, out[255].cellZ);
    }

    @Test
    void eachLevelProducesTheQuadCountItClaims() {
        assertEquals(256, LodMerger.quadsAt(0));
        assertEquals(64, LodMerger.quadsAt(1));
        assertEquals(16, LodMerger.quadsAt(2));
        assertEquals(4, LodMerger.quadsAt(3));
        assertEquals(1, LodMerger.quadsAt(4));

        assertEquals(1, LodMerger.spanAt(0));
        assertEquals(2, LodMerger.spanAt(1));
        assertEquals(4, LodMerger.spanAt(2));
        assertEquals(8, LodMerger.spanAt(3));
        assertEquals(16, LodMerger.spanAt(4));
    }

    @Test
    void mergingRemovesTheQuadsItClaims() {
        LodMerger merger = new LodMerger();
        int[] heights = flat(64);
        int[] colours = colours(ROCK);

        merger.merge(heights, colours, 2, new LodMerger.Merged[16]);
        assertEquals(93.75, merger.reductionPercent(), 0.001, "16 of 256 survive");

        merger.merge(heights, colours, 3, new LodMerger.Merged[4]);
        // Counters accumulate: 512 in and 20 out across the two merges, so (512 - 20) / 512.
        assertEquals(512L, merger.quadsIn());
        assertEquals(96.1, merger.reductionPercent(), 0.05, "counted across both merges");

        merger.merge(heights, colours, 4, new LodMerger.Merged[1]);
        assertEquals(3L, merger.merges());
        assertEquals(21L, merger.quadsOut(), "16 + 4 + 1");
        assertEquals(768L, merger.quadsIn());
        assertEquals(97.3, merger.reductionPercent(), 0.05);
    }

    @Test
    void theCoarsestLevelIsOneQuadForTheWholeFace() {
        int[] heights = flat(64);
        heights[LodMerger.CELLS - 1] = 120;

        LodMerger merger = new LodMerger();
        LodMerger.Merged[] out = new LodMerger.Merged[1];
        int n = merger.merge(heights, colours(ROCK), 4, out);

        assertEquals(1, n);
        assertEquals(16, out[0].span, "the whole face");
        assertEquals(120, out[0].height, "and it still sits at the highest block in it");
        assertEquals(99.6, merger.reductionPercent(), 0.05);
    }

    @Test
    void distancesMapToLevelsAtTheStatedBoundaries() {
        assertEquals(0, LodMerger.levelForDistance(0.0));
        assertEquals(0, LodMerger.levelForDistance(95.9));
        assertEquals(1, LodMerger.levelForDistance(96.0), "the boundary belongs to the coarser level");
        assertEquals(1, LodMerger.levelForDistance(191.9));
        assertEquals(2, LodMerger.levelForDistance(192.0));
        assertEquals(2, LodMerger.levelForDistance(319.9));
        assertEquals(3, LodMerger.levelForDistance(320.0));
        assertEquals(3, LodMerger.levelForDistance(511.9));
        assertEquals(4, LodMerger.levelForDistance(512.0));
        assertEquals(4, LodMerger.levelForDistance(10_000.0));
    }

    @Test
    void negativeHeightsWork() {
        // The world floor is at -64, so heights are routinely negative and a merge that assumed
        // otherwise would put distant ground at the wrong place.
        int[] heights = flat(-64);
        heights[7] = -40;

        LodMerger merger = new LodMerger();
        LodMerger.Merged[] out = new LodMerger.Merged[16];
        merger.merge(heights, colours(ROCK), 2, out);

        assertEquals(-40, out[0].height, "the highest of a set of negatives");
        assertEquals(-64, out[1].height);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        LodMerger merger = new LodMerger();
        int[] heights = flat(64);
        int[] colours = colours(ROCK);

        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(new int[10], colours, 0, new LodMerger.Merged[256]));
        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(heights, new int[10], 0, new LodMerger.Merged[256]));
        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(heights, colours, 5, new LodMerger.Merged[1]));
        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(heights, colours, -1, new LodMerger.Merged[256]));
        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(heights, colours, 2, new LodMerger.Merged[15]),
                "too small for the level");
        assertThrows(IllegalArgumentException.class, () -> LodMerger.levelForDistance(-1.0));
        assertThrows(IllegalArgumentException.class,
                () -> LodMerger.levelForDistance(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LodMerger.quadsAt(5));
        assertThrows(IllegalArgumentException.class, () -> LodMerger.spanAt(5));
    }

    @Test
    void aFreshMergerReportsZeroNotNaN() {
        LodMerger merger = new LodMerger();
        assertEquals(0.0, merger.reductionPercent());
        assertEquals(0L, merger.merges());
        assertEquals(16, LodMerger.SIZE);
        assertEquals(256, LodMerger.CELLS);
        assertTrue(LodMerger.MAX_LEVEL == 4);
    }
}
