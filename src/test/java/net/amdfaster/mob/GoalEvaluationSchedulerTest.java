package net.amdfaster.mob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalEvaluationSchedulerTest {

    @Test
    void anIdleMobIsEvaluatedOnItsOwnPhase() {
        // Mob AI runs on the server tick, so tying the cadence to it means the behaviour is the same at any
        // frame rate. Counting frames would make mobs on a 30 fps machine think half as often.
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();

        assertTrue(scheduler.shouldEvaluate(0, false, false), "phase zero is due on tick zero");
        assertFalse(scheduler.shouldEvaluate(1, false, false), "phase one is not");
        scheduler.tick();
        assertTrue(scheduler.shouldEvaluate(1, false, false), "and is due a tick later");
    }

    @Test
    void aMobThatIsAlreadyDoingSomethingIsNotReEvaluated() {
        // A mob mid-stride along a path does not need to decide again what it is doing. Re-evaluating would
        // either confirm what it is already doing, which is wasted work, or interrupt it, which would make
        // the mob stutter rather than walk.
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();

        assertFalse(scheduler.shouldEvaluate(0, true, false), "even on its own phase");
        assertEquals(1L, scheduler.skippedRunning());
        assertEquals(0L, scheduler.evaluations());
    }

    @Test
    void somethingHappeningForcesAnImmediateEvaluation() {
        // A player appearing, damage taken, a door opening. Waiting for the next scheduled evaluation would
        // make the mob ignore the player for a fraction of a second -- long enough to feel unresponsive,
        // and for a panic goal, long enough to be unfair.
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();

        assertTrue(scheduler.shouldEvaluate(1, true, true), "even mid-path and off-phase");
        assertEquals(1L, scheduler.forced());
    }

    @Test
    void theWorkIsSpreadAcrossTheInterval() {
        // A hundred mobs all evaluated on the same tick is still a hundred evaluations at once, and the
        // other three ticks are idle, which shows up as a hitch at that cadence rather than a saving.
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();
        int[] perTick = new int[4];

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 100; id++) {
                if (scheduler.shouldEvaluate(id, false, false)) {
                    perTick[tick]++;
                }
            }
            scheduler.tick();
        }

        for (int tick = 0; tick < 4; tick++) {
            assertEquals(25, perTick[tick], "a quarter on every tick");
        }
    }

    @Test
    void aFarmOfIdleMobsCostsAQuarter() {
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 100; id++) {
                scheduler.shouldEvaluate(id, false, false);
            }
            scheduler.tick();
        }

        assertEquals(100L, scheduler.evaluations(), "each mob once in four ticks");
        assertEquals(25.0, scheduler.evaluationsPerTick(), 1e-9);
        assertEquals(0.5, scheduler.msPerTick(), 1e-9);
        assertEquals(2.0, scheduler.msWithoutScheduling(100), 1e-9);
        assertEquals(75.0, scheduler.savedPercent(100), 1e-9);
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has run no ticks has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        GoalEvaluationScheduler scheduler = new GoalEvaluationScheduler();

        assertEquals(0.0, scheduler.evaluationsPerTick());
        assertEquals(0.0, scheduler.msPerTick());
        assertEquals(0.0, scheduler.savedPercent(100));
        assertEquals(0L, scheduler.ticks());
        assertEquals(0L, scheduler.evaluations());
        assertEquals(4, GoalEvaluationScheduler.IDLE_INTERVAL);
    }
}
