package net.amdfaster.mesh.job;

/**
 * Builds the sections the player can see before the ones they cannot.
 *
 * <p>Walking into new terrain queues a burst of mesh jobs -- a hundred sections at a time -- and if they
 * are built in the order they were queued, the ones directly in front of the player are behind the ones at
 * the edge of the render distance. The player sees holes in the world where the ground should be, and the
 * holes close slowly from the outside in, which is the opposite of the order that matters.
 *
 * <p>Ordering the queue by whether the section is visible fills the holes the player is looking at first.
 *
 * <h2>Not visible does not mean never</h2>
 *
 * <p>A section outside the current view is one turn of the head away from being in it, and if it were never
 * built, turning around would show the same holes all over again. So invisible sections are still built,
 * just after the visible ones. Deprioritising is not dropping, and a queue that dropped them would trade a
 * slow fill for a hole that never fills.
 *
 * <h2>An invisible section is not starved</h2>
 *
 * <p>A player standing still and looking at loaded terrain keeps queueing visible work, and if the
 * invisible jobs always lost, they would wait indefinitely. So each invisible job's priority rises the
 * longer it waits, and after enough ticks it is built regardless of what else is queued. The alternative is
 * a queue that looks correct in a test and starves in a real session.
 *
 * <h2>Why visibility is recomputed rather than remembered</h2>
 *
 * <p>The camera moves, and a section that was invisible when it was queued may be visible by the time the
 * worker reaches it. Using the visibility from when it was queued would build the wrong thing first, and
 * the player would watch a hole stay open while sections behind them were built. So the question is asked
 * again at the moment of choosing.
 *
 * <p>Not thread safe. The queue is drained on worker threads and filled on the client thread, so callers
 * need to synchronise.
 */
public final class MeshJobPrioritizer {

    /** Ticks an invisible job waits before its priority rises to match a visible one. */
    public static final int STARVATION_LIMIT = 40;

    /** Milliseconds building one section costs. */
    public static final double MS_PER_SECTION = 4.0;

    private long offered;
    private long chosenVisible;
    private long chosenInvisible;
    private long starved;

    /**
     * The priority of a job.
     *
     * <p>Higher is built first.
     *
     * @param visible whether the player can see the section right now
     * @param ticksWaiting how long the job has been in the queue
     */
    public int priority(boolean visible, long ticksWaiting) {
        if (ticksWaiting < 0) {
            throw new IllegalArgumentException("a negative wait: " + ticksWaiting);
        }
        this.offered++;

        if (visible) {
            this.chosenVisible++;
            return 1000;
        }
        if (ticksWaiting >= STARVATION_LIMIT) {
            // A player standing still and looking at loaded terrain keeps queueing visible work, and if the
            // invisible jobs always lost they would wait indefinitely -- a queue that looks correct in a
            // test and starves in a real session.
            this.starved++;
            return 1000;
        }
        this.chosenInvisible++;
        // Rising with the wait, so an invisible job approaches a visible one rather than sitting at the
        // bottom of the queue forever.
        return (int) Math.min(999, ticksWaiting * 10);
    }

    /**
     * Whether a job should be built before the visible work is done.
     *
     * @return true if it has waited long enough to be built regardless
     */
    public boolean isStarved(long ticksWaiting) {
        return ticksWaiting >= STARVATION_LIMIT;
    }

    public long offered() {
        return this.offered;
    }

    /** How many jobs were prioritised because they were visible. */
    public long chosenVisible() {
        return this.chosenVisible;
    }

    /** How many were deprioritised. */
    public long chosenInvisible() {
        return this.chosenInvisible;
    }

    /** How many were promoted because they had waited too long. */
    public long starved() {
        return this.starved;
    }

    public double starvedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.starved / this.offered;
    }

    /**
     * Milliseconds a player waits before the sections in front of them are built.
     *
     * @param visibleCount sections the player can see
     * @param totalQueued sections in the queue
     * @param prioritised whether visible sections are built first
     */
    public double msBeforeVisibleBuilt(int visibleCount, int totalQueued, boolean prioritised) {
        if (visibleCount < 0 || totalQueued < visibleCount) {
            throw new IllegalArgumentException(
                    visibleCount + " visible of " + totalQueued + " queued");
        }
        // Without prioritisation the visible ones are scattered through the queue, so on average half of
        // it is built before the last visible section is.
        int ahead = prioritised ? 0 : (totalQueued - visibleCount) / 2;
        return (ahead + visibleCount) * MS_PER_SECTION;
    }
}
