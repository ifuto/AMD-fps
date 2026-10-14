package net.amdfaster.fluid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidTickFilterTest {

    @Test
    void aBlockNeverSeenNeedsATick() {
        // Assuming stability for something never evaluated is how water gets stuck. The default has
        // to be "evaluate it", not "leave it alone".
        FluidTickFilter filter = new FluidTickFilter();
        assertTrue(filter.needsTick(10, 64, 10));
        assertEquals(0, filter.size(), "and asking did not add it");
    }

    @Test
    void settledFluidStopsBeingEvaluated() {
        FluidTickFilter filter = new FluidTickFilter();
        assertTrue(filter.needsTick(0, 64, 0), "untracked, so it is evaluated");
        assertEquals(0L, filter.skipped());

        filter.markStable(0, 64, 0);

        // Marking it stable is what the evaluation that found it settled does, so from here on the
        // answer is no. An earlier version of this test expected one more evaluation afterwards.
        for (int i = 0; i < 200; i++) {
            assertFalse(filter.needsTick(0, 64, 0), "evaluation " + i + " skipped");
        }
        assertEquals(1, filter.size());
        assertEquals(201L, filter.evaluations(), "the untracked one plus two hundred");
        assertEquals(200L, filter.skipped());
    }

    @Test
    void unstableFluidKeepsItsSchedule() {
        FluidTickFilter filter = new FluidTickFilter();
        filter.markUnstable(1, 64, 1);

        for (int i = 0; i < 50; i++) {
            assertTrue(filter.needsTick(1, 64, 1), "evaluation " + i + " runs");
        }
        assertEquals(1, filter.size());
        assertEquals(0L, filter.skipped());
    }

    @Test
    void diggingUnderWaterWakesIt() {
        // The correctness case. Water sitting on a block that is no longer there has to fall, and a
        // filter that leaves it marked stable leaves it hanging in the air.
        FluidTickFilter filter = new FluidTickFilter();
        filter.markStable(0, 65, 0);
        assertFalse(filter.needsTick(0, 65, 0));

        filter.invalidateNeighbours(0, 64, 0);

        assertTrue(filter.needsTick(0, 65, 0), "the block above the change woke");
        assertEquals(1L, filter.wakes());
    }

    @Test
    void aChangeWakesExactlyTheSixNeighbours() {
        FluidTickFilter filter = new FluidTickFilter();
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            filter.markStable(d[0], 64 + d[1], d[2]);
        }
        // Diagonals are not affected by a block change, and must not be woken.
        filter.markStable(1, 64, 1);
        filter.markStable(-1, 64, -1);

        filter.invalidateNeighbours(0, 64, 0);

        assertEquals(6L, filter.wakes(), "six face neighbours, not eight");
        for (int[] d : around) {
            assertTrue(filter.needsTick(d[0], 64 + d[1], d[2]), "neighbour woke");
        }
        assertFalse(filter.needsTick(1, 64, 1), "a diagonal is untouched by the change");
        assertFalse(filter.needsTick(-1, 64, -1));
    }

    @Test
    void wakingDoesNotAddUntrackedBlocksToTheTable() {
        // Marking an untracked neighbour unstable would grow the table with blocks that were never
        // evaluated. needsTick already treats untracked as needing a tick, so there is nothing to
        // record and a table that grows on every block change would never stop.
        FluidTickFilter filter = new FluidTickFilter();

        filter.invalidateNeighbours(5, 64, 5);

        assertEquals(0, filter.size(), "nothing tracked");
        assertEquals(0L, filter.wakes(), "nothing was stable to wake");
        assertTrue(filter.needsTick(6, 64, 5));
    }

    @Test
    void removingAFluidBlockDropsIt() {
        FluidTickFilter filter = new FluidTickFilter();
        filter.markStable(0, 64, 0);
        filter.remove(0, 64, 0);

        assertEquals(0, filter.size());
        assertTrue(filter.needsTick(0, 64, 0), "untracked again, so it is evaluated");
        assertEquals(1L, filter.invalidations());
    }

    @Test
    void removalKeepsTheRestOfThePondFindable() {
        // An open-addressed table with an empty slot in the middle stops probing at it. Entries past
        // the hole have to move into it or they are lost while still counted as present.
        FluidTickFilter filter = new FluidTickFilter();
        int[][] positions = new int[600][3];
        for (int i = 0; i < positions.length; i++) {
            positions[i][0] = i % 30 - 15;
            positions[i][1] = 64;
            positions[i][2] = i / 30 - 10;
            filter.markStable(positions[i][0], 64, positions[i][2]);
        }
        assertEquals(positions.length, filter.size());

        for (int i = 0; i < positions.length; i += 3) {
            filter.remove(positions[i][0], 64, positions[i][2]);
        }
        int stillTracked = 0;
        for (int i = 0; i < positions.length; i++) {
            boolean expected = i % 3 != 0;
            assertEquals(expected, !filter.needsTick(positions[i][0], 64, positions[i][2]),
                    "position " + i + " tracked state");
            if (expected) {
                stillTracked++;
            }
        }
        assertEquals(400, stillTracked);
        assertEquals(positions.length - 400, filter.size());
    }

    @Test
    void aStillPondCostsAlmostNothingAfterTheFirstTick() {
        // The shape of the saving. Water that settled stays settled, and the block changes that would
        // wake it are rare next to the five-tick schedule it would otherwise run.
        int blocks = 500;
        int minutes = 1;
        int ticks = 20 * 60 * minutes;
        int interval = 5;

        FluidTickFilter filter = new FluidTickFilter(1024);
        for (int i = 0; i < blocks; i++) {
            filter.markStable(i % 25, 64, i / 25);
        }

        int wouldHaveRun = 0;
        for (int t = 0; t < ticks; t += interval) {
            for (int i = 0; i < blocks; i++) {
                wouldHaveRun++;
                filter.needsTick(i % 25, 64, i / 25);
            }
        }
        // 1200 ticks a minute over a five tick interval is 240 rounds, times 500 blocks.
        assertEquals(120_000L, wouldHaveRun, "evaluations a minute");
        assertEquals(wouldHaveRun, filter.skipped(), "all of them skipped, because nothing changed");
        // Each evaluation reads six positions.
        assertEquals(720_000L, wouldHaveRun * 6L, "block-state reads avoided in a minute");
    }

    @Test
    void clearDropsEverything() {
        FluidTickFilter filter = new FluidTickFilter();
        filter.markStable(0, 64, 0);
        filter.markUnstable(1, 64, 1);
        filter.clear();

        assertEquals(0, filter.size());
        assertTrue(filter.needsTick(0, 64, 0));
        assertTrue(filter.needsTick(1, 64, 1));
    }

    @Test
    void positionsInABodyOfWaterDoNotPackAlike() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (int x = -100; x < 100; x++) {
            for (int z = -100; z < 100; z++) {
                for (int y = 60; y < 68; y++) {
                    keys.add(FluidTickFilter.pack(x, y, z));
                }
            }
        }
        assertEquals(200 * 200 * 8, keys.size(), "every position in a generous volume is distinct");
    }

    @Test
    void fillingPastTheCapacityGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and comes back to its start.
        // Another class here did that and hung the thread; CI reported it as a timeout.
        FluidTickFilter filter = new FluidTickFilter(16);

        for (int i = 0; i < 20000; i++) {
            filter.markStable(i % 200 - 100, 64, i / 200 - 50);
        }

        assertTrue(filter.size() > 0);
        for (int i = 0; i < 20000; i++) {
            assertFalse(filter.needsTick(i % 200 - 100, 64, i / 200 - 50), "position " + i);
        }
    }

    @Test
    void aFreshFilterReportsZeroNotNaN() {
        FluidTickFilter filter = new FluidTickFilter();
        assertEquals(0.0, filter.skipRatePercent());
        assertEquals(0L, filter.evaluations());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FluidTickFilter(8));
    }
}
