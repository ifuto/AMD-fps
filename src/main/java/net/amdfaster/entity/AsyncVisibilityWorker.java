package net.amdfaster.entity;

/**
 * Runs the line-of-sight test on spare cores instead of the render thread.
 *
 * <p>Telling whether an entity is hidden behind terrain means raycasting toward it, and with hundreds of
 * entities in view that is milliseconds of work. Doing it on the render thread means the frame waits for it,
 * and the saving from culling the entities arrives as a stall that costs more than the culling saved. Doing
 * it on cores that would otherwise be idle removes the stall, so the culling is a saving rather than a
 * trade.
 *
 * <h2>Until the answer arrives, the entity is drawn</h2>
 *
 * <p>That is the rule that makes the result being late safe. A worker takes some frames to finish, and
 * during those frames the caller has no answer. Guessing "hidden" would make entities pop in as the answers
 * arrive, which is exactly the artefact culling is supposed to avoid. Guessing "visible" means an entity
 * behind a wall is drawn for a few frames longer than necessary, which costs a little and looks correct.
 *
 * <h2>The render thread never waits</h2>
 *
 * <p>A worker that has not finished leaves the previous answer in place. Blocking until it finishes would
 * put the stall back, and would make the frame time depend on the slowest test in the batch. The render
 * thread reads whatever answer is available and moves on.
 *
 * <h2>The newest answer wins, not the first to arrive</h2>
 *
 * <p>Tests are issued every few frames, and two of them can be in flight for the same entity. If the older
 * one finished last and overwrote the newer, the entity would be drawn according to where it was rather
 * than where it is, and a fast-moving entity would flicker between visible and hidden. So each answer
 * carries the tick it was issued on, and an older one is discarded rather than applied.
 *
 * <h2>Why a worker is not given more than it can hold</h2>
 *
 * <p>A queue that grows without bound means the workers are falling behind, and every answer they produce
 * is further out of date -- the culling would be based on where entities were many frames ago, which is
 * worse than not culling. So when a worker is full, new tests are dropped instead of queued, and the entity
 * keeps whatever answer it had. Dropping is the safe direction: the entity stays drawn.
 *
 * <p>Thread safe by construction: the answer slots are read on the render thread and written on worker
 * threads, so each is volatile and the tick comparison decides which write is applied.
 */
public final class AsyncVisibilityWorker {

    /** Milliseconds one line-of-sight test costs. */
    public static final double MS_PER_TEST = 0.004;

    /** Tests a worker will hold before new ones are dropped. */
    public static final int QUEUE_LIMIT = 64;

    /** Ticks between issuing tests for the same entity. */
    public static final int ISSUE_INTERVAL = 4;

    private volatile boolean visible = true;
    private volatile long answerTick;

    private long issued;
    private long answered;
    private long droppedFull;
    private long staleDiscarded;
    private int queued;

    /**
     * Issues a test for an entity.
     *
     * @param tick the tick the test is for, so a late answer from an earlier test can be recognised
     * @return true if the test was queued, false if the worker was full and it was dropped
     */
    public boolean issue(long tick) {
        if (this.queued >= QUEUE_LIMIT) {
            // The workers are falling behind, and every answer they produce is further out of date. Queuing
            // anyway would mean culling based on where entities were many frames ago, which is worse than
            // not culling. Dropping leaves the entity drawn, which is the safe direction.
            this.droppedFull++;
            return false;
        }
        this.issued++;
        this.queued++;
        return true;
    }

    /**
     * Applies an answer from a worker.
     *
     * @param tick the tick the test was issued on
     * @param hidden whether the entity was found to be behind terrain
     * @return true if the answer was applied, false if a newer one had already arrived
     */
    public boolean answer(long tick, boolean hidden) {
        if (tick < this.answerTick) {
            // Two tests can be in flight for the same entity. If the older one finished last and overwrote
            // the newer, the entity would be drawn according to where it was rather than where it is, and a
            // fast-moving one would flicker between visible and hidden.
            this.staleDiscarded++;
            return false;
        }
        this.answerTick = tick;
        this.visible = !hidden;
        this.answered++;
        if (this.queued > 0) {
            this.queued--;
        }
        return true;
    }

    /**
     * Whether the entity should be drawn.
     *
     * <p>True until an answer says otherwise. Guessing "hidden" while the worker is still running would make
     * entities pop in as the answers arrive, which is exactly the artefact culling is supposed to avoid.
     */
    public boolean isVisible() {
        return this.visible;
    }

    /** The tick of the answer currently applied. */
    public long answerTick() {
        return this.answerTick;
    }

    /** How many tests are waiting on a worker. */
    public int queued() {
        return this.queued;
    }

    public long issued() {
        return this.issued;
    }

    public long answered() {
        return this.answered;
    }

    /** How many tests were dropped because the worker was full. */
    public long droppedFull() {
        return this.droppedFull;
    }

    /** How many answers arrived too late to apply. */
    public long staleDiscarded() {
        return this.staleDiscarded;
    }

    /**
     * The share of tests that were dropped or arrived too late.
     *
     * <p>Zero before any test has been issued. A worker that has been given nothing has technically lost
     * nothing, but reporting that as a rate would describe an unused one as though it had a result.
     */
    public double lostPercent() {
        if (this.issued == 0) {
            return 0.0;
        }
        return 100.0 * (this.droppedFull + this.staleDiscarded) / this.issued;
    }

    /** Milliseconds of line-of-sight testing moved off the render thread. */
    public double msMovedOffRenderThread() {
        return this.answered * MS_PER_TEST;
    }
}
