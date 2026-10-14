package net.amdfaster.worldgen;

import net.amdfaster.worldgen.ChunkStageScheduler.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStageSchedulerTest {

    /** A 9 by 9 region, fully tracked. */
    private static ChunkStageScheduler region() {
        ChunkStageScheduler scheduler = new ChunkStageScheduler(9, 9);
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                scheduler.track(x, z);
            }
        }
        return scheduler;
    }

    @Test
    void theStageOrderAndRequirementsArePinned() {
        // The requirement table is the whole class. An ordinal out of place would mean a stage waiting
        // on the wrong thing, and the world would be subtly wrong at chunk borders.
        assertEquals(0, Stage.EMPTY.ordinal());
        assertEquals(1, Stage.STRUCTURE_STARTS.ordinal());
        assertEquals(2, Stage.STRUCTURE_REFERENCES.ordinal());
        assertEquals(3, Stage.BIOMES.ordinal());
        assertEquals(4, Stage.NOISE.ordinal());
        assertEquals(5, Stage.SURFACE.ordinal());
        assertEquals(6, Stage.CARVERS.ordinal());
        assertEquals(7, Stage.FEATURES.ordinal());
        assertEquals(8, Stage.LIGHT.ordinal());
        assertEquals(9, Stage.SPAWN.ordinal());
        assertEquals(10, Stage.FULL.ordinal());
        assertEquals(11, Stage.values().length);

        assertEquals(-1, Stage.STRUCTURE_STARTS.requiresNeighboursAt());
        assertEquals(1, Stage.STRUCTURE_REFERENCES.requiresNeighboursAt());
        assertEquals(-1, Stage.BIOMES.requiresNeighboursAt());
        assertEquals(3, Stage.NOISE.requiresNeighboursAt());
        assertEquals(-1, Stage.SURFACE.requiresNeighboursAt());
        assertEquals(-1, Stage.CARVERS.requiresNeighboursAt());
        assertEquals(5, Stage.FEATURES.requiresNeighboursAt());
        assertEquals(-1, Stage.LIGHT.requiresNeighboursAt());
        assertEquals(-1, Stage.SPAWN.requiresNeighboursAt());
        assertEquals(-1, Stage.FULL.requiresNeighboursAt());
    }

    @Test
    void stagesWithoutNeighbourRequirementsRunImmediately() {
        ChunkStageScheduler scheduler = region();

        // EMPTY -> STRUCTURE_STARTS needs nothing.
        assertEquals(Stage.EMPTY, scheduler.stageAt(4, 4));
        assertTrue(scheduler.advance(4, 4));
        assertEquals(Stage.STRUCTURE_STARTS, scheduler.stageAt(4, 4));
    }

    @Test
    void structureReferencesWaitForAllEightNeighbours() {
        // A structure crossing a diagonal border is as real as one crossing a straight one, so the
        // check covers eight neighbours and not four.
        ChunkStageScheduler scheduler = region();
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                scheduler.advance(x, z);
            }
        }
        // Every chunk is now at STRUCTURE_STARTS, so STRUCTURE_REFERENCES is unblocked everywhere
        // except at the region edge, where a neighbour is missing.
        assertTrue(scheduler.canAdvance(4, 4));
        assertFalse(scheduler.canAdvance(0, 0), "the corner has neighbours outside the region");

        // One chunk left behind at EMPTY blocks the reference stage for everything around it. An
        // earlier version of this test cleared the region and re-tracked it, which put every chunk
        // back on EMPTY -- and EMPTY -> STRUCTURE_STARTS needs nothing from a neighbour, so the
        // assertion was checking the wrong transition.
        ChunkStageScheduler partial = new ChunkStageScheduler(9, 9);
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                partial.track(x, z);
                if (x == 4 && z == 3) {
                    continue;
                }
                partial.advance(x, z);
            }
        }
        assertFalse(partial.canAdvance(4, 4), "the neighbour at 4,3 never reached STRUCTURE_STARTS");
        assertFalse(partial.canAdvance(3, 3), "and neither can its other neighbour");
        assertTrue(partial.canAdvance(4, 1), "far enough that 4,3 is not a neighbour");
    }

    @Test
    void aDiagonalNeighbourBlocksJustLikeANorthOne() {
        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 5; x++) {
                scheduler.track(x, z);
            }
        }
        // Everything to STRUCTURE_STARTS except the south-east diagonal.
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 5; x++) {
                if (x == 3 && z == 3) {
                    continue;
                }
                scheduler.advance(x, z);
            }
        }

        assertFalse(scheduler.canAdvance(2, 2), "the diagonal neighbour at 3,3 is still empty");
        assertTrue(scheduler.canAdvance(1, 1), "far enough that 3,3 is not a neighbour");
    }

    @Test
    void deadlockIsReportedOnlyWhenNothingCanMoveAndSomethingIsUnfinished() {
        // A 3 by 3 tracked block inside a larger region: the centre has all eight neighbours tracked,
        // so it can keep going, and the set is not stuck. An earlier version of this test asserted the
        // opposite, having forgotten that the centre of a 3 by 3 block is fully surrounded.
        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        for (int z = 1; z < 4; z++) {
            for (int x = 1; x < 4; x++) {
                scheduler.track(x, z);
            }
        }
        for (int z = 1; z < 4; z++) {
            for (int x = 1; x < 4; x++) {
                scheduler.advance(x, z);
            }
        }
        for (int z = 1; z < 4; z++) {
            for (int x = 1; x < 4; x++) {
                assertEquals(Stage.STRUCTURE_STARTS, scheduler.stageAt(x, z));
            }
        }

        assertTrue(scheduler.canAdvance(2, 2), "the centre has all eight neighbours tracked");
        assertFalse(scheduler.canAdvance(1, 1), "the corner of the block does not");
        assertFalse(scheduler.isDeadlocked(), "and so the set is not stuck");
    }

    @Test
    void aSetWhereNothingCanMoveIsReportedAsDeadlocked() {
        // Two chunks side by side: each is missing a neighbour on the far side, so once both reach
        // STRUCTURE_STARTS neither can go further, and workers would idle with no error anywhere.
        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        scheduler.track(2, 2);
        scheduler.track(3, 2);
        assertTrue(scheduler.advance(2, 2));
        assertTrue(scheduler.advance(3, 2));

        assertFalse(scheduler.canAdvance(2, 2));
        assertFalse(scheduler.canAdvance(3, 2));
        assertTrue(scheduler.isDeadlocked());
        assertEquals(0, scheduler.finishedCount(), "and nothing got to FULL");
    }

    @Test
    void anUntrackedNeighbourBlocksRatherThanBeingAssumedReady() {
        // The safe direction. Treating a missing chunk as ready would advance against a neighbour that
        // does not exist, which is how a border seam appears.
        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        scheduler.track(2, 2);
        scheduler.advance(2, 2);

        assertFalse(scheduler.canAdvance(2, 2));
        assertEquals(1, scheduler.trackedCount());
    }

    @Test
    void generationParallelisesAcrossWorkers() {
        // The figure the requirement table buys. Most stages need nothing from a neighbour, so most
        // chunks are workable at any moment.
        ChunkStageScheduler scheduler = new ChunkStageScheduler(16, 16);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                scheduler.track(x, z);
            }
        }

        double parallelism = scheduler.measuredParallelism(8, 40);

        assertTrue(parallelism > 7.0, "eight workers stay busy, got " + parallelism);
        assertTrue(scheduler.advances() > 280L, "and work happened: " + scheduler.advances());
    }

    @Test
    void collectReadyReturnsOnlyWorkableChunks() {
        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        for (int z = 1; z < 4; z++) {
            for (int x = 1; x < 4; x++) {
                scheduler.track(x, z);
            }
        }
        int[] xs = new int[16];
        int[] zs = new int[16];

        int ready = scheduler.collectReady(xs, zs);
        assertEquals(9, ready, "all nine can move off EMPTY");
        for (int i = 0; i < ready; i++) {
            assertTrue(scheduler.advance(xs[i], zs[i]), "and each of them does");
        }

        ready = scheduler.collectReady(xs, zs);
        // One, not zero. The centre of the 3 by 3 tracked block has all eight neighbours tracked, so
        // it can move to the reference stage; the eight around it cannot, because their neighbours
        // reach outside the tracked block.
        assertEquals(1, ready, "only the fully surrounded centre can go on");
        assertEquals(2, xs[0]);
        assertEquals(2, zs[0]);
        assertTrue(scheduler.advance(xs[0], zs[0]));

        // The centre goes on again, because BIOMES needs nothing from a neighbour either. It stops at
        // NOISE, which does.
        ready = scheduler.collectReady(xs, zs);
        assertEquals(1, ready, "BIOMES has no neighbour requirement");
        assertEquals(Stage.STRUCTURE_REFERENCES, scheduler.stageAt(2, 2));
        assertTrue(scheduler.advance(xs[0], zs[0]));
        assertEquals(Stage.BIOMES, scheduler.stageAt(2, 2));

        ready = scheduler.collectReady(xs, zs);
        assertEquals(0, ready, "and NOISE waits for the neighbours' biomes, which they do not have");
    }

    @Test
    void outOfRegionCoordinatesAreRejected() {
        ChunkStageScheduler scheduler = new ChunkStageScheduler(3, 3);
        scheduler.track(1, 1);

        assertEquals(Stage.EMPTY, scheduler.stageAt(1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.stageAt(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.stageAt(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.advance(1, 3));
    }

    @Test
    void clearDropsAllTracking() {
        ChunkStageScheduler scheduler = region();
        scheduler.advance(4, 4);
        scheduler.clear();

        assertEquals(0, scheduler.trackedCount());
        assertEquals(Stage.EMPTY, scheduler.stageAt(4, 4));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkStageScheduler(2, 9));
        assertThrows(IllegalArgumentException.class, () -> new ChunkStageScheduler(9, 2));

        ChunkStageScheduler scheduler = new ChunkStageScheduler(5, 5);
        int[] xs = new int[4];
        int[] zs = new int[5];
        assertThrows(IllegalArgumentException.class, () -> scheduler.collectReady(xs, zs));
    }
}
