package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeshSweepBoundsTest {

    private static final int CELLS = MeshSweepBounds.SECTION_CELLS;

    @Test
    void terrainInTheBottomQuarterSweepsFiveLevels() {
        // In terrain that occupies the bottom quarter of a section, three quarters of the cell visits look at
        // air, find nothing, and cost the loop that looked.
        MeshSweepBounds bounds = new MeshSweepBounds();

        int swept = bounds.sweep(0, 3);

        // Levels -1 to 4, clamped to 0 to 4, is five levels.
        assertEquals(5 * 256, swept);
        assertEquals(CELLS - 5 * 256, bounds.cellsSkipped());
        assertEquals(68.75, bounds.skippedPercent(), 1e-9);
    }

    @Test
    void theBoundsIncludeOneLevelPastEachEnd() {
        // The topmost block has an exposed face above it, and a sweep that stopped at the topmost occupied
        // level would not visit the cell that face is checked from -- so the face would be missing and the
        // terrain would have no top.
        MeshSweepBounds bounds = new MeshSweepBounds();

        assertEquals(3 * 256, bounds.sweep(7, 7), "levels 6, 7 and 8 for one occupied level");
    }

    @Test
    void theBoundsAreClampedAtTheSectionEdges() {
        MeshSweepBounds bounds = new MeshSweepBounds();

        assertEquals(16 * 256, bounds.sweep(0, 15), "a full section sweeps every level");
        assertEquals(2 * 256, bounds.sweep(0, 0), "level -1 is outside the section");
        assertEquals(2 * 256, bounds.sweep(15, 15), "and so is level 16");
    }

    @Test
    void anEmptySectionSweepsNothing() {
        // A section with nothing in it has no occupied levels, so there is nothing to sweep and no mesh to
        // build. That is not a special case bolted on, it is what an empty range means.
        MeshSweepBounds bounds = new MeshSweepBounds();

        assertEquals(0, bounds.sweep(-1, -1));
        assertEquals(1L, bounds.emptySections());
        assertEquals(CELLS, bounds.cellsSkipped());
        assertEquals(0L, bounds.cellsSwept());
    }

    @Test
    void aColumnOfSectionsIsMostlyAir() {
        MeshSweepBounds bounds = new MeshSweepBounds();

        bounds.sweep(0, 3);
        bounds.sweep(4, 7);
        bounds.sweep(8, 11);
        bounds.sweep(-1, -1);
        bounds.sweep(-1, -1);

        assertEquals(5L, bounds.sections());
        assertEquals(2L, bounds.emptySections());
        // The first section is clamped at level zero and sweeps five levels; the two above it are interior, so
        // the one-level margin on each side is not clamped and they sweep six.
        assertEquals(1280 + 1536 + 1536L, bounds.cellsSwept());
        assertEquals(5L * CELLS - 4352, bounds.cellsSkipped());
        assertEquals(78.75, bounds.skippedPercent(), 1e-9);
    }

    @Test
    void theSavingIsTheSweepingNotDone() {
        MeshSweepBounds bounds = new MeshSweepBounds();
        bounds.sweep(0, 3);

        assertEquals(5 * 256 * MeshSweepBounds.MS_PER_CELL, bounds.msSwept(), 1e-12);
        assertEquals(CELLS * MeshSweepBounds.MS_PER_CELL, bounds.msWithoutBounds(), 1e-12);
        assertEquals(68.75, bounds.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshSweepBounds bounds = new MeshSweepBounds();

        assertThrows(IllegalArgumentException.class, () -> bounds.sweep(-2, 3));
        assertThrows(IllegalArgumentException.class, () -> bounds.sweep(0, 16));
        assertThrows(IllegalArgumentException.class, () -> bounds.sweep(5, 3));
    }

    @Test
    void aFreshMesherReportsZeroNotNaN() {
        // A mesher that has swept nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        MeshSweepBounds bounds = new MeshSweepBounds();

        assertEquals(0.0, bounds.skippedPercent());
        assertEquals(0.0, bounds.savedPercent());
        assertEquals(0.0, bounds.msSwept(), 1e-12);
        assertEquals(0L, bounds.sections());
        assertEquals(0L, bounds.cellsSkipped());
        assertEquals(4096, MeshSweepBounds.SECTION_CELLS);
        assertEquals(6, MeshSweepBounds.SWEEP_DIRECTIONS);
    }
}
