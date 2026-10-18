package net.amdfaster.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Every number here comes from equation 1 evaluated at the default streaming factor of 2, so the
 * assertions describe what the formula produces rather than what seems plausible.
 */
class LodStreamSchedulerTest {

    private static final double SF = LodStreamScheduler.DEFAULT_STREAMING_FACTOR;

    @Test
    void aNodeDoublesInSizePerLevel() {
        // Aokana aggregates eight children (2 x 2 x 2) into one parent, so the edge doubles and the
        // volume goes up eightfold.
        assertEquals(16, LodStreamScheduler.chunkSize(0));
        assertEquals(32, LodStreamScheduler.chunkSize(1));
        assertEquals(64, LodStreamScheduler.chunkSize(2));
        assertEquals(128, LodStreamScheduler.chunkSize(3));
        assertEquals(256, LodStreamScheduler.chunkSize(4));
    }

    @Test
    void oneCoarseNodeRetiresEightToTheLevelSections() {
        // This is the number that makes coarse-first worth doing.
        assertEquals(1, LodStreamScheduler.sectionsReplaced(0));
        assertEquals(8, LodStreamScheduler.sectionsReplaced(1));
        assertEquals(64, LodStreamScheduler.sectionsReplaced(2));
        assertEquals(512, LodStreamScheduler.sectionsReplaced(3));
        assertEquals(4096, LodStreamScheduler.sectionsReplaced(4));
    }

    @Test
    void theErrorIsChunkSizeTimesFactorMinusDistance() {
        LodStreamScheduler scheduler = new LodStreamScheduler();

        assertEquals(2.0, SF, 1e-12);
        assertEquals(1.0, scheduler.error(0, 31.0), 1e-12, "16 * 2 - 31");
        assertEquals(0.0, scheduler.error(0, 32.0), 1e-12, "16 * 2 - 32");
        assertEquals(-1.0, scheduler.error(0, 33.0), 1e-12, "16 * 2 - 33");
        assertEquals(0.0, scheduler.error(4, 512.0), 1e-12, "256 * 2 - 512");
        assertEquals(480.0, scheduler.error(4, 32.0), 1e-12, "256 * 2 - 32");
    }

    @Test
    void zeroErrorCountsAsGoodEnoughRatherThanRefine() {
        // Refining at exactly the threshold would make the level change on the frame the player crosses
        // it. Holding one frame longer costs nothing visible and stops the pop happening on a boundary.
        LodStreamScheduler scheduler = new LodStreamScheduler();

        assertTrue(scheduler.shouldRefine(0, 31.0));
        assertFalse(scheduler.shouldRefine(0, 32.0), "on the threshold, do not refine");
        assertFalse(scheduler.shouldRefine(0, 33.0));
        assertEquals(1L, scheduler.refinements());
        assertEquals(2L, scheduler.loadTargets());
    }

    @Test
    void theLevelRisesAtEachDoublingOfTheNodeSize() {
        LodStreamScheduler scheduler = new LodStreamScheduler();

        // The thresholds are chunkSize(L) * 2, so 32, 64, 128, 256, 512 -- and the boundary belongs to
        // the coarser level, which is the same choice the zero error makes.
        assertEquals(0, scheduler.levelFor(0.0));
        assertEquals(0, scheduler.levelFor(31.0));
        assertEquals(0, scheduler.levelFor(63.0));
        assertEquals(1, scheduler.levelFor(64.0));
        assertEquals(1, scheduler.levelFor(127.0));
        assertEquals(2, scheduler.levelFor(128.0));
        assertEquals(2, scheduler.levelFor(255.0));
        assertEquals(3, scheduler.levelFor(256.0));
        assertEquals(3, scheduler.levelFor(511.0));
        assertEquals(4, scheduler.levelFor(512.0));
        assertEquals(4, scheduler.levelFor(100_000.0), "clamped to the deepest node");
    }

    @Test
    void aLargerStreamingFactorKeepsDetailLonger() {
        // The factor scales every threshold, so a larger one refines further out. At 64 blocks a factor
        // of 1 is already two levels coarse while a factor of 4 is still at the finest -- this is the
        // knob that trades memory for a longer view of fine detail, and the two numbers were read out
        // of the formula rather than guessed, because the first draft of this test had them the wrong
        // way round.
        LodStreamScheduler tight = new LodStreamScheduler(1.0);
        LodStreamScheduler generous = new LodStreamScheduler(4.0);

        // thresholds chunkSize * 1 = 16, 32, 64, 128, 256 -> 64 blocks reaches level 2
        assertEquals(2, tight.levelFor(64.0));
        // thresholds chunkSize * 4 = 64, 128, 256, 512, 1024 -> 64 blocks only reaches level 0
        assertEquals(0, generous.levelFor(64.0), "the same distance, two levels finer");
        // 128 blocks under a factor of 4 reaches level 1
        assertEquals(1, generous.levelFor(128.0));
        assertEquals(64.0, generous.error(0, 0.0), 1e-12, "16 * 4 - 0");
    }

    @Test
    void coarseNodesLoadBeforeNearFineOnes() {
        // The inversion a nearest-first queue gets wrong. A distant coarse node retires far more
        // streaming work and far more memory than a fine node under the player's feet, so it goes first
        // -- which is also what puts something on the horizon instead of leaving it empty.
        long coarseAndFar = LodStreamScheduler.streamPriority(4, 900.0);
        long fineAndNear = LodStreamScheduler.streamPriority(0, 8.0);

        assertEquals(900L, coarseAndFar);
        assertEquals(17_179_869_192L, fineAndNear);
        assertTrue(coarseAndFar < fineAndNear, "coarse dominates distance");
    }

    @Test
    void withinALevelTheNearerNodeLoadsFirst() {
        assertEquals(300L, LodStreamScheduler.streamPriority(4, 300.0));
        assertEquals(900L, LodStreamScheduler.streamPriority(4, 900.0));
        assertTrue(LodStreamScheduler.streamPriority(4, 300.0)
                < LodStreamScheduler.streamPriority(4, 900.0));
    }

    @Test
    void thePriorityOrderIsTotalAcrossEveryLevel() {
        // A priority queue needs a strict order, so no two distinct (level, distance) pairs may tie
        // unless they really are the same request.
        long[] keys = new long[5 * 4];
        int n = 0;
        for (int level = 0; level <= LodStreamScheduler.MAX_LEVEL; level++) {
            for (double d : new double[] {0.0, 50.0, 500.0, 5000.0}) {
                keys[n++] = LodStreamScheduler.streamPriority(level, d);
            }
        }
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        for (long key : keys) {
            distinct.add(key);
        }
        assertEquals(keys.length, distinct.size(), "every request has its own place in the queue");
    }

    @Test
    void loadingRetiresTheSectionsItStandsInFor() {
        LodStreamScheduler scheduler = new LodStreamScheduler();

        assertEquals(8, scheduler.load(1));
        assertEquals(4096, scheduler.load(4));
        assertEquals(4104L, scheduler.sectionsReplaced());
    }

    @Test
    void theSelfNodeIsCountedSoTheWiringCanBeChecked() {
        // The paper is explicit that this case inverts: for the node containing the camera the bounding
        // box intersection is farther than the real depth, so the cheap test culls tiles that are on
        // screen. Counting it is how a caller that forgot gets noticed.
        LodStreamScheduler scheduler = new LodStreamScheduler();

        scheduler.markSelf();

        assertEquals(1L, scheduler.selfNodes());
    }

    @Test
    void nonsenseInputsAreRejectedRatherThanAnswered() {
        LodStreamScheduler scheduler = new LodStreamScheduler();

        assertThrows(IllegalArgumentException.class, () -> new LodStreamScheduler(0.0),
                "a zero factor makes every error negative and nothing ever refines");
        assertThrows(IllegalArgumentException.class, () -> new LodStreamScheduler(-1.0));
        assertThrows(IllegalArgumentException.class, () -> new LodStreamScheduler(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new LodStreamScheduler(Double.POSITIVE_INFINITY));

        assertThrows(IllegalArgumentException.class, () -> scheduler.error(-1, 10.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.error(5, 10.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.error(0, -1.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.error(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> scheduler.levelFor(-1.0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.levelFor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LodStreamScheduler.chunkSize(5));
        assertThrows(IllegalArgumentException.class, () -> LodStreamScheduler.sectionsReplaced(-1));
        assertThrows(IllegalArgumentException.class, () -> LodStreamScheduler.streamPriority(9, 1.0));
        assertThrows(IllegalArgumentException.class, () -> LodStreamScheduler.streamPriority(0, -1.0));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        LodStreamScheduler scheduler = new LodStreamScheduler();

        assertEquals(2.0, scheduler.streamingFactor(), 1e-12);
        assertEquals(0L, scheduler.refinements());
        assertEquals(0L, scheduler.loadTargets());
        assertEquals(0L, scheduler.selfNodes());
        assertEquals(0L, scheduler.sectionsReplaced());
    }
}
