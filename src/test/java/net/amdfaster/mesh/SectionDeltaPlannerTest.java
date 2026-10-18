package net.amdfaster.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.mesh.SectionDeltaPlanner.Region;
import org.junit.jupiter.api.Test;

/**
 * The numbers are the section's, not chosen ones: 4,096 cells, a 3 by 3 by 3 neighbourhood of 27, a full
 * solid section's 1,536 quads in meshlets of 62.
 */
class SectionDeltaPlannerTest {

    @Test
    void theConstantsAreTheSections() {
        assertEquals(16, SectionDeltaPlanner.SECTION_SIZE);
        assertEquals(4096, SectionDeltaPlanner.SECTION_CELLS);
        assertEquals(27, SectionDeltaPlanner.NEIGHBOURHOOD_CELLS);
        assertEquals(62, SectionDeltaPlanner.QUADS_PER_MESHLET);
        assertEquals(256, SectionDeltaPlanner.QUADS_PER_FACE);
        assertEquals(1536, SectionDeltaPlanner.QUADS_PER_SECTION);
        assertEquals(25, SectionDeltaPlanner.meshletsForSection(), "ceil(1536 / 62)");
    }

    @Test
    void anInteriorBlockTouchesItsOwnNeighbourhood() {
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        Region region = planner.affectedRegion(8, 8, 8, false);

        assertEquals(7, region.minX());
        assertEquals(7, region.minY());
        assertEquals(7, region.minZ());
        assertEquals(3, region.sizeX());
        assertEquals(3, region.sizeY());
        assertEquals(3, region.sizeZ());
        assertEquals(27, region.cells());
        assertEquals(99.34, SectionDeltaPlanner.cellsAvoidedPercent(region), 0.01);
    }

    @Test
    void anEdgeBlockHasFewerNeighboursInsideThisSection() {
        // The ones outside belong to the neighbouring section and are handled by that section's own
        // rebuild. Clamping is not a simplification -- reaching outside would plan cells that are not
        // in this section at all.
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        assertEquals(8, planner.affectedRegion(0, 0, 0, false).cells(), "a corner: 2 x 2 x 2");
        assertEquals(18, planner.affectedRegion(0, 8, 8, false).cells(), "an edge: 2 x 3 x 3");
        assertEquals(12, planner.affectedRegion(0, 0, 8, false).cells(), "a face: 2 x 2 x 3");

        Region corner = planner.affectedRegion(0, 0, 0, false);
        assertTrue(corner.contains(0, 0, 0));
        assertTrue(corner.contains(1, 1, 1));
        assertFalse(corner.contains(2, 0, 0), "clamped at the section edge");
        assertEquals(99.80, SectionDeltaPlanner.cellsAvoidedPercent(corner), 0.01);
    }

    @Test
    void greedyMergingExpandsTheRegionToTheWholeRun() {
        // A merged quad can span a whole row of identical faces, and a change inside one splits it, so the
        // run has to be re-derived whole. This is the correctness requirement, not pessimism: rebuilding
        // only the 27 cells would leave a merged quad spanning cells that no longer agree with the blocks,
        // which is a face drawn where there is no surface, and nothing logs it because the mesh builder
        // rebuilt exactly what it was told to.
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        Region region = planner.affectedRegion(8, 8, 8, true);

        assertEquals(4096, region.cells());
        assertEquals(0.0, SectionDeltaPlanner.cellsAvoidedPercent(region), 1e-12,
                "greedy merging leaves nothing to skip, and saying otherwise would be the bug");
    }

    @Test
    void meshletCountsRoundUpAndNeverDown() {
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        assertEquals(0, planner.meshletsFor(0), "nothing to draw is no meshlet");
        assertEquals(1, planner.meshletsFor(1));
        assertEquals(1, planner.meshletsFor(9), "a 3x3 patch on one face");
        assertEquals(1, planner.meshletsFor(62), "exactly one meshlet");
        assertEquals(2, planner.meshletsFor(63), "one quad over and it is a second meshlet");
        assertEquals(25, planner.meshletsFor(1536), "a full solid section");
    }

    @Test
    void theSavingIsReportedNotAssumed() {
        // A 3x3 patch on one face is nine quads against a full section's 1,536: one meshlet instead of
        // twenty-five. The planner reports what it computed rather than promising a fraction, because the
        // honest answer runs from 99.8 per cent of the section skipped to none at all.
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        Region region = planner.affectedRegion(8, 8, 8, false);
        int needed = planner.meshletsFor(9);

        assertEquals(1, needed);
        assertEquals(1L, planner.meshletsPlanned());
        assertEquals(24L, planner.meshletsAvoided(), "25 for the section less the 1 rebuilt");
        assertEquals(27L, planner.cellsInvalidated());
        assertEquals(1L, planner.changes());
        assertTrue(SectionDeltaPlanner.cellsAvoidedPercent(region) > 99.0);
    }

    @Test
    void coordinatesOutsideTheSectionAreRejected() {
        // Section-local coordinates. A caller passing world coordinates would get a region somewhere else
        // entirely, and the section would be rebuilt around the wrong hole.
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.affectedRegion(-1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> planner.affectedRegion(16, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> planner.affectedRegion(0, 16, 0, false));
        assertThrows(IllegalArgumentException.class, () -> planner.affectedRegion(0, 0, -1, false));
        // 15 is the last valid index.
        assertEquals(8, planner.affectedRegion(15, 15, 15, false).cells());
    }

    @Test
    void nonsenseInputsAreRejected() {
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.meshletsFor(-1));
        assertThrows(IllegalArgumentException.class, () -> SectionDeltaPlanner.cellsAvoidedPercent(null));
    }

    @Test
    void aFreshPlannerReportsZeroNotNaN() {
        SectionDeltaPlanner planner = new SectionDeltaPlanner();

        assertEquals(0L, planner.changes());
        assertEquals(0L, planner.cellsInvalidated());
        assertEquals(0L, planner.meshletsPlanned());
        assertEquals(0L, planner.meshletsAvoided());
    }
}
