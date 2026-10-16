package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionBoundarySmoothingTest {

    @Test
    void aCellCountsItsEightNeighbours() {
        // A cell at the edge inherits part of what is happening next to it, so the total moves by one at a
        // time as the player walks rather than jumping at a boundary they cannot see.
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        smoother.add(4.0, 4.0);
        smoother.add(12.0, 4.0);
        smoother.add(4.0, 12.0);

        int centre = RegionBoundarySmoothing.cellOf(4.0, 4.0);
        assertEquals(1, smoother.count(centre));
        assertEquals(3, smoother.neighbourhoodCount(centre), "itself and the two beside it");
        assertEquals(9, RegionBoundarySmoothing.NEIGHBOURHOOD);
    }

    @Test
    void aCellFarAwayIsNotCounted() {
        // A cell's loudness is affected by what is immediately beside it, not by a region three cells away.
        // Using a wider neighbourhood would make the limit effectively global, which is the thing the
        // per-region limit was there to prevent.
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        smoother.add(4.0, 4.0);
        smoother.add(100.0, 100.0);

        assertEquals(1, smoother.neighbourhoodCount(RegionBoundarySmoothing.cellOf(4.0, 4.0)));
    }

    @Test
    void theNeighbourhoodWrapsAtTheEdgeOfTheArray() {
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        smoother.add(0.5, 0.5);
        int corner = RegionBoundarySmoothing.cellOf(0.5, 0.5);

        assertEquals(1, smoother.neighbourhoodCount(corner), "no exception, and itself counted once");
    }

    @Test
    void walkingAcrossABoundaryDoesNotFlipTheAnswer() {
        // A region is an arbitrary box, and a source just inside one edge is counted against that region
        // while a source a block further out is counted against the next one over.
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        for (int i = 0; i < 6; i++) {
            smoother.add(7.0 + i, 4.0);
        }

        for (int i = 0; i < 6; i++) {
            assertTrue(smoother.crossesBoundaryCleanly(6.0 + i, 4.0),
                    "one block at a time across the edge at eight");
        }
        assertEquals(6L, smoother.flipsAvoided());
    }

    @Test
    void negativeCoordinatesFallInTheRightCell() {
        // Floored rather than truncated, so a position just below zero is the cell below and not the one at
        // zero -- otherwise the boundary would be in the wrong place for every negative coordinate.
        assertTrue(RegionBoundarySmoothing.cellOf(-0.5, 4.0)
                != RegionBoundarySmoothing.cellOf(0.5, 4.0));
        assertEquals(RegionBoundarySmoothing.cellOf(-8.0, 4.0),
                RegionBoundarySmoothing.cellOf(-1.0, 4.0), "both in the cell below zero");
    }

    @Test
    void aNoisyNeighbourhoodRefusesTheExcess() {
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (smoother.allow(4.0, 4.0)) {
                allowed++;
            }
        }

        assertEquals(RegionBoundarySmoothing.LIMIT, allowed);
        assertEquals(30L, smoother.offered());
        assertEquals(30 - RegionBoundarySmoothing.LIMIT, smoother.refused());
        assertEquals(60.0, smoother.refusedPercent(), 1e-9);
    }

    @Test
    void theCountsResetEveryTick() {
        // A neighbour count that is a tick old is a count of where the noise was, and a moving source would
        // put the boundary in the wrong place.
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        for (int i = 0; i < 20; i++) {
            smoother.allow(4.0, 4.0);
        }
        smoother.endTick();

        assertEquals(0, smoother.neighbourhoodCount(RegionBoundarySmoothing.cellOf(4.0, 4.0)));
        assertTrue(smoother.allow(4.0, 4.0), "a machine that stopped stops affecting its neighbours");
    }

    @Test
    void aFreshSmootherReportsZeroNotNaN() {
        // A smoother that has been offered nothing has technically refused nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        RegionBoundarySmoothing smoother = new RegionBoundarySmoothing();

        assertEquals(0.0, smoother.refusedPercent());
        assertEquals(0L, smoother.offered());
        assertEquals(0L, smoother.allowed());
        assertEquals(0L, smoother.flipsAvoided());
        assertEquals(8, RegionBoundarySmoothing.CELL_BLOCKS);
        assertEquals(12, RegionBoundarySmoothing.LIMIT);
    }
}
