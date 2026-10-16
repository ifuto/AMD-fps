package net.amdfaster.fluid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FluidSurfaceMergerTest {

    private static final int CELLS = FluidSurfaceMerger.SECTION_BLOCKS * FluidSurfaceMerger.SECTION_BLOCKS;

    private static boolean[] level(int... fluidCells) {
        boolean[] out = new boolean[CELLS];
        for (int cell : fluidCells) {
            out[cell] = true;
        }
        return out;
    }

    @Test
    void aFullLakeBecomesOneQuad() {
        // A lake a hundred blocks across is ten thousand quads of the same flat surface at the same height,
        // all with the same texture -- which is visually one plane.
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        int quads = merger.mergeLevel(FluidSurfaceMerger.full());

        assertEquals(1, quads);
        assertEquals(CELLS, merger.blocks());
        assertEquals(1L, merger.quads());
    }

    @Test
    void aRunOfFluidIsOneQuad() {
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        // Cells 0..3 of the first row.
        int quads = merger.mergeLevel(level(0, 1, 2, 3));

        assertEquals(1, quads);
        assertEquals(4L, merger.blocks());
    }

    @Test
    void aGapMakesTwoQuads() {
        // Merging across a gap would produce a surface over dry land, which does not exist.
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        int quads = merger.mergeLevel(level(0, 1, 3, 4));

        assertEquals(2, quads);
        assertEquals(4L, merger.blocks());
    }

    @Test
    void aRectangleStacksIntoOneQuad() {
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        int[] cells = new int[8];
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 2; x++) {
                cells[z * 2 + x] = z * FluidSurfaceMerger.SECTION_BLOCKS + x;
            }
        }

        assertEquals(1, merger.mergeLevel(level(cells)), "a 2 by 4 pool");
        assertEquals(8L, merger.blocks());
    }

    @Test
    void anEmptyLevelIsNoQuads() {
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        assertEquals(0, merger.mergeLevel(level()));
        assertEquals(0L, merger.blocks());
    }

    @Test
    void aLakeIsAlmostNothingToDraw() {
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        for (int section = 0; section < 39; section++) {
            merger.mergeLevel(FluidSurfaceMerger.full());
        }

        assertEquals(39L * CELLS, merger.blocks());
        assertEquals(39L, merger.quads());
        assertEquals(1.0 / CELLS, merger.quadsPerBlock(), 1e-12);
        assertEquals(39 * CELLS * FluidSurfaceMerger.MS_PER_QUAD, merger.msWithoutMerging(), 1e-9);
        assertEquals(99.39, merger.savedPercent(), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        assertThrows(IllegalArgumentException.class, () -> merger.mergeLevel(new boolean[10]));
    }

    @Test
    void aFreshMergerReportsZeroNotNaN() {
        // A merger that has merged nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        FluidSurfaceMerger merger = new FluidSurfaceMerger();

        assertEquals(0.0, merger.savedPercent());
        assertEquals(0.0, merger.quadsPerBlock());
        assertEquals(0.0, merger.msDrawing(), 1e-12);
        assertEquals(0L, merger.blocks());
        assertEquals(0L, merger.bytes());
        assertEquals(16, FluidSurfaceMerger.SECTION_BLOCKS);
    }
}
