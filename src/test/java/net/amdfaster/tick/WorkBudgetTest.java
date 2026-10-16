package net.amdfaster.tick;

import net.amdfaster.tick.WorkBudget.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkBudgetTest {

    private static Task deferrable(double ms) {
        return new Task(WorkBudget.DEFERRABLE, ms, "chunk generation");
    }

    private static Task critical(double ms) {
        return new Task(WorkBudget.CRITICAL, ms, "player physics");
    }

    @Test
    void workThatFitsRunsNow() {
        WorkBudget budget = new WorkBudget();

        assertTrue(budget.offer(deferrable(10.0), 20.0));
        assertEquals(0, budget.backlog());
        assertEquals(0L, budget.deferred());
    }

    @Test
    void workThatOverflowsIsDeferred() {
        // A run of ticks reading 8, 45, 12, 60, 9, 38 has an average of 28.7, inside a 50 ms budget,
        // and a peak of 60 -- which is the one the player feels. Averaging is not what stutters.
        WorkBudget budget = new WorkBudget();

        assertFalse(budget.offer(deferrable(20.0), 45.0));
        assertEquals(1, budget.backlog());
        assertEquals(1L, budget.deferred());
    }

    @Test
    void criticalWorkIsNeverDeferred() {
        // Delaying input response or physics does not reduce the load the player perceives, it moves it
        // into the thing they perceive: a jump that lands a tick late, a step that clips through a block.
        // That reads as the game feeling heavy rather than as a dropped frame.
        WorkBudget budget = new WorkBudget();

        assertTrue(budget.offer(critical(40.0), 45.0), "even past the budget");
        assertEquals(0, budget.backlog());
        assertEquals(1L, budget.criticalRun());
    }

    @Test
    void deferredWorkIsDrainedOldestFirst() {
        // For tasks whose order matters -- a chunk that has to exist before its neighbour is decorated --
        // reordering is a correctness problem, not a performance one.
        WorkBudget budget = new WorkBudget();
        budget.offer(deferrable(5.0), 50.0);
        budget.offer(deferrable(5.0), 50.0);

        List<Task> drained = budget.drain(10.0);

        assertEquals(2, drained.size());
        assertEquals(0, budget.backlog());
        assertEquals(2L, budget.drained());
    }

    @Test
    void drainingStopsAtTheBudget() {
        WorkBudget budget = new WorkBudget();
        for (int i = 0; i < 10; i++) {
            budget.offer(deferrable(10.0), 50.0);
        }

        List<Task> drained = budget.drain(30.0);

        assertEquals(2, drained.size(), "20 ms of the 20 ms left");
        assertEquals(8, budget.backlog(), "and the rest wait");
    }

    @Test
    void aLargeTaskAtTheHeadBlocksTheQueueRatherThanBeingSkipped() {
        // Skipping it would reorder the backlog. Running it late is a performance problem; running the
        // things behind it out of order is a correctness one.
        WorkBudget budget = new WorkBudget();
        budget.offer(deferrable(40.0), 50.0);
        budget.offer(deferrable(1.0), 50.0);

        assertTrue(budget.isBlocked(20.0), "40 ms does not fit in 30");
        assertTrue(budget.drain(20.0).isEmpty(), "and the small one behind it waits too");
        assertEquals(2, budget.backlog());
    }

    @Test
    void aFullBacklogRunsTheWorkInsteadOfQueueingIt() {
        // Running it late is worse than running it now, but running it never is worse than both. Without
        // the bound, a run of spikes turns the deferral into a growing delay.
        WorkBudget budget = new WorkBudget();

        for (int i = 0; i < WorkBudget.BACKLOG_LIMIT; i++) {
            assertFalse(budget.offer(deferrable(1.0), 50.0));
        }
        assertEquals(WorkBudget.BACKLOG_LIMIT, budget.backlog());

        assertTrue(budget.offer(deferrable(1.0), 50.0), "the backlog is full, so it runs now");
        assertEquals(1L, budget.refusedForFullBacklog());
        assertEquals(WorkBudget.BACKLOG_LIMIT, budget.backlog(), "nothing was dropped");
    }

    @Test
    void theBacklogDelayIsReported() {
        WorkBudget budget = new WorkBudget();
        budget.offer(deferrable(12.0), 50.0);
        budget.offer(deferrable(8.0), 50.0);

        assertEquals(20.0, budget.backlogMs(), 1e-9);
    }

    @Test
    void thePeakIsTrackedNotTheAverage() {
        double[] costs = {8, 45, 12, 60, 9, 38};

        assertEquals(60.0, WorkBudget.peakWithoutDeferral(costs), 1e-9);
        assertEquals(28.666666, WorkBudget.average(costs), 1e-6);
        assertTrue(WorkBudget.average(costs) < WorkBudget.BUDGET_MS,
                "the average is fine, which is why the peak is the number that matters");
    }

    @Test
    void deferralBringsThePeakIntoBudget() {
        WorkBudget budget = new WorkBudget();
        double[] costs = {8, 45, 12, 60, 9, 38};

        for (double cost : costs) {
            // Anything past the budget is deferrable work that should have moved.
            if (cost > WorkBudget.BUDGET_MS) {
                budget.offer(deferrable(cost - WorkBudget.BUDGET_MS), WorkBudget.BUDGET_MS);
            } else {
                budget.offer(deferrable(cost), 0.0);
            }
            budget.drain(cost > WorkBudget.BUDGET_MS ? WorkBudget.BUDGET_MS : cost);
        }

        assertTrue(budget.peakMs() <= WorkBudget.BUDGET_MS,
                "peak was " + budget.peakMs());
        assertEquals(60.0, WorkBudget.peakWithoutDeferral(costs), 1e-9);
    }

    @Test
    void drainingAnEmptyBacklogDoesNothing() {
        WorkBudget budget = new WorkBudget();

        assertTrue(budget.drain(0.0).isEmpty());
        assertEquals(0L, budget.drained());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        WorkBudget budget = new WorkBudget();

        assertThrows(IllegalArgumentException.class, () -> budget.offer(deferrable(-1.0), 0.0));
    }

    @Test
    void aFreshBudgetReportsZeroNotNaN() {
        WorkBudget budget = new WorkBudget();

        assertEquals(0, budget.backlog());
        assertEquals(0.0, budget.backlogMs());
        assertEquals(0.0, budget.peakMs());
        assertEquals(0L, budget.deferred());
        assertEquals(0.0, WorkBudget.average(new double[0]));
        assertEquals(50.0, WorkBudget.BUDGET_MS, 1e-9);
        assertEquals(64, WorkBudget.BACKLOG_LIMIT);
    }
}
