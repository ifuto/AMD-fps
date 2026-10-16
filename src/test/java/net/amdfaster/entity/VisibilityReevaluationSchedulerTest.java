package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityReevaluationSchedulerTest {

    @Test
    void aStationaryEntityIsTestedOnItsOwnPhaseOfTheInterval() {
        // Counting frames would make the cadence depend on the frame rate, so the same view distance
        // would look different on different hardware. Counting ticks ties it to game time.
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        assertTrue(scheduler.shouldTest(0, 0.0), "phase zero is due on tick zero");
        assertFalse(scheduler.shouldTest(1, 0.0), "phase one is not");
        scheduler.tick();
        assertTrue(scheduler.shouldTest(1, 0.0), "and is due a tick later");
        assertFalse(scheduler.shouldTest(0, 0.0), "phase zero is not");
    }

    @Test
    void theWorkIsSpreadAcrossTheIntervalNotConcentrated() {
        // A quarter of the entities re-tested every fourth tick is only cheap if it is a different
        // quarter each time. If every entity's interval starts at the same moment, all of them are due on
        // one tick and the other three are idle, which shows up as a stutter rather than a saving.
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();
        int[] perTick = new int[4];

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 500; id++) {
                if (scheduler.shouldTest(id, 0.0)) {
                    perTick[tick]++;
                }
            }
            scheduler.tick();
        }

        for (int tick = 0; tick < 4; tick++) {
            assertEquals(125, perTick[tick], "a quarter on every tick");
        }
    }

    @Test
    void theMeasuredLoadIsAQuarterOfTestingEveryTick() {
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 500; id++) {
                scheduler.shouldTest(id, 0.0);
            }
            scheduler.tick();
        }

        assertEquals(500L, scheduler.tests(), "each entity tested once in four ticks");
        assertEquals(125.0, scheduler.testsPerTick(), 1e-9);
        assertEquals(0.5, scheduler.msPerTick(), 1e-9);
        assertEquals(2.0, scheduler.msWithoutScheduling(500), 1e-9);
        assertEquals(0L, scheduler.earlyTests());
    }

    @Test
    void aMovingEntityIsTestedAheadOfItsPhase() {
        // Pop-in is most visible when the view is moving, because that is when an entity crosses from
        // behind cover into sight. Testing early puts the work where it prevents the visible error.
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        assertTrue(scheduler.shouldTest(1, 2.0), "moved, so due now rather than on its phase");
        assertEquals(1L, scheduler.earlyTests());
    }

    @Test
    void aSmallMovementStillWaitsForItsPhase() {
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        assertFalse(scheduler.shouldTest(1, 1.9), "below the threshold and not its phase");
        assertEquals(0L, scheduler.earlyTests());
    }

    @Test
    void negativeArgumentsAreRejected() {
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        assertThrows(IllegalArgumentException.class, () -> scheduler.shouldTest(0, -1.0));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // Before any tick has passed there is no rate, and a rate of zero reads as a result when it is
        // the absence of one.
        VisibilityReevaluationScheduler scheduler = new VisibilityReevaluationScheduler();

        assertEquals(0.0, scheduler.testsPerTick());
        assertEquals(0.0, scheduler.msPerTick());
        assertEquals(0L, scheduler.tests());
        assertEquals(0L, scheduler.ticks());
        assertEquals(4, VisibilityReevaluationScheduler.IDLE_INTERVAL);
        assertEquals(2.0, VisibilityReevaluationScheduler.MOVE_THRESHOLD);
    }
}
