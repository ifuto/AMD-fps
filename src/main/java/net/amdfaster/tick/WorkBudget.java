package net.amdfaster.tick;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Moves non-urgent tick work out of a spike and into the quiet ticks around it.
 *
 * <p>A server tick's workload is not even. One tick generates a chunk, the next runs a wave of mob AI,
 * and the ones between are nearly idle. A run of ticks reading 8, 45, 12, 60, 9, 38 milliseconds has an
 * average of 28.7 -- comfortably inside a 50 ms budget -- and a peak of 60, which is the one the player
 * feels. Averaging is not what stutters; peaks are.
 *
 * <p>Deferring the work that can wait takes the peak into budget without changing how much work is done,
 * and without touching the ticks that were already fine.
 *
 * <h2>Some work must not be deferred</h2>
 *
 * <p>Responding to input, resolving collisions and stepping physics have to happen in the tick they were
 * raised in. Delaying them does not reduce the load the player perceives, it moves it into the thing
 * they perceive: a jump that lands a tick late, a step that clips through a block. That reads as lag
 * rather than as a stutter, and players describe it as the game feeling heavy rather than as a dropped
 * frame. So work is classified when it is offered, and critical work is never queued.
 *
 * <h2>Deferred work must not be lost</h2>
 *
 * <p>A deferred chunk that never generates is terrain that never loads, and a deferred AI step that
 * never runs is a mob that stops moving. So the backlog is a queue that is always drained, and the
 * budget is what limits how much is taken on, not what is dropped. Nothing is discarded for being old.
 *
 * <h2>The backlog is bounded</h2>
 *
 * <p>If spikes keep coming and the quiet ticks are not quiet enough, the backlog grows without limit and
 * the deferral stops being a smoothing mechanism and becomes a growing delay. So the queue has a
 * capacity, and when it is full new deferrable work runs immediately instead of being queued. Running it
 * late is worse than running it now, but running it never is worse than both.
 *
 * <p>Not thread safe. Ticking happens on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class WorkBudget {

    /** Milliseconds a tick should take. */
    public static final double BUDGET_MS = 50.0;

    /** How much deferrable work may be waiting. */
    public static final int BACKLOG_LIMIT = 64;

    /** Work that cannot wait. */
    public static final int CRITICAL = 0;

    /** Work that can be moved to a quieter tick. */
    public static final int DEFERRABLE = 1;

    private final Deque<Task> backlog = new ArrayDeque<>();

    private long criticalRun;
    private long deferred;
    private long drained;
    private long refusedForFullBacklog;
    private double peakMs;

    /** A unit of tick work. */
    public record Task(int kind, double costMs, String what) {}

    /**
     * Offers work to this tick.
     *
     * @param spentMs how much of the budget this tick has already used
     * @return true if the work should run now, false if it was queued for a later tick
     */
    public boolean offer(Task task, double spentMs) {
        if (task.costMs() < 0.0) {
            throw new IllegalArgumentException("work cannot cost " + task.costMs() + " ms");
        }
        if (task.kind() == CRITICAL) {
            this.criticalRun++;
            trackPeak(spentMs + task.costMs());
            return true;
        }

        if (spentMs + task.costMs() <= BUDGET_MS) {
            trackPeak(spentMs + task.costMs());
            return true;
        }
        if (this.backlog.size() >= BACKLOG_LIMIT) {
            // Running it late is worse than running it now, but running it never is worse than both.
            this.refusedForFullBacklog++;
            trackPeak(spentMs + task.costMs());
            return true;
        }

        this.backlog.addLast(task);
        this.deferred++;
        return false;
    }

    /**
     * Runs the deferred work this tick can afford.
     *
     * @param spentMs how much of the budget has already been used
     * @return the tasks to run now, oldest first
     */
    public java.util.List<Task> drain(double spentMs) {
        java.util.List<Task> out = new java.util.ArrayList<>();
        double remaining = BUDGET_MS - spentMs;
        while (!this.backlog.isEmpty() && this.backlog.peekFirst().costMs() <= remaining) {
            Task task = this.backlog.removeFirst();
            out.add(task);
            remaining -= task.costMs();
            this.drained++;
        }
        if (!out.isEmpty()) {
            trackPeak(BUDGET_MS - remaining);
        }
        return out;
    }

    /**
     * Whether a task that does not fit can still be taken.
     *
     * <p>Head-of-line blocking: a large deferred task at the front stops everything behind it, even
     * small ones that would fit. Skipping it would reorder the backlog, and for tasks whose order
     * matters -- a chunk that has to exist before its neighbour is decorated -- that is a correctness
     * problem, not a performance one. So the queue stops rather than skips.
     */
    public boolean isBlocked(double spentMs) {
        Task head = this.backlog.peekFirst();
        return head != null && head.costMs() > BUDGET_MS - spentMs;
    }

    private void trackPeak(double ms) {
        this.peakMs = Math.max(this.peakMs, ms);
    }

    /** How much deferred work is waiting. */
    public int backlog() {
        return this.backlog.size();
    }

    /** Milliseconds of work waiting, which is the delay the deferral has introduced. */
    public double backlogMs() {
        double total = 0.0;
        for (Task task : this.backlog) {
            total += task.costMs();
        }
        return total;
    }

    public long criticalRun() {
        return this.criticalRun;
    }

    public long deferred() {
        return this.deferred;
    }

    public long drained() {
        return this.drained;
    }

    /** How many deferrable tasks ran immediately because the backlog was full. */
    public long refusedForFullBacklog() {
        return this.refusedForFullBacklog;
    }

    /** The longest tick seen, which is what the player actually feels. */
    public double peakMs() {
        return this.peakMs;
    }

    public void clear() {
        this.backlog.clear();
    }

    /**
     * The peak a run of ticks would have without deferral.
     *
     * <p>Compared against {@link #peakMs} to show what the smoothing did.
     */
    public static double peakWithoutDeferral(double[] tickCosts) {
        double peak = 0.0;
        for (double cost : tickCosts) {
            peak = Math.max(peak, cost);
        }
        return peak;
    }

    public static double average(double[] tickCosts) {
        if (tickCosts.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (double cost : tickCosts) {
            total += cost;
        }
        return total / tickCosts.length;
    }
}
