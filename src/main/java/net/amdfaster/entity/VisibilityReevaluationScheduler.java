package net.amdfaster.entity;

/**
 * Decides which entities get their visibility re-tested on a given tick, so the test does not run for
 * all of them at once.
 *
 * <p>Telling whether an entity is hidden behind terrain means raycasting toward it, and with 500 entities
 * in view that is about 2 ms a frame -- more than a tenth of the budget, spent deciding what not to draw.
 * Re-testing every entity every fourth tick instead cuts that to 0.5 ms while keeping the answer at most
 * a few frames old.
 *
 * <h2>Staggered, so the saving is not a spike</h2>
 *
 * <p>A quarter of the entities re-tested every fourth tick is only cheap if it is a different quarter
 * each time. If every entity's interval starts at the same moment, all 500 are due on the same tick and
 * the other three are idle, which shows up as a stutter at that cadence rather than a saving. Assigning
 * each entity a phase from its id spreads them evenly across the interval, so each tick does about a
 * quarter of the work and the frame times are flat.
 *
 * <h2>Ticks, not frames</h2>
 *
 * <p>Counting frames would make the re-test cadence depend on the frame rate: on a machine running 30 fps
 * every entity would be re-tested half as often as on one running 60, and the same view distance would
 * look different on different hardware. Counting ticks ties it to game time, which is what the world's
 * own updates use.
 *
 * <h2>Moving entities are re-tested sooner</h2>
 *
 * <p>Pop-in -- an entity appearing a moment after it should have -- is most visible when the view is
 * moving, because that is when an entity crosses from behind cover into sight. An entity that has moved a
 * meaningful distance since its last test is re-tested immediately rather than waiting for its phase to
 * come round, which puts the extra work where it prevents the visible error and saves it where the entity
 * is standing still.
 *
 * <p>Not thread safe. Visibility testing runs on worker threads, so a caller using this from several needs
 * one instance per worker.
 */
public final class VisibilityReevaluationScheduler {

    /** Ticks between re-tests for a stationary entity. */
    public static final int IDLE_INTERVAL = 4;

    /** Blocks of movement that justify testing ahead of schedule. */
    public static final double MOVE_THRESHOLD = 2.0;

    /** Milliseconds one visibility test costs. */
    public static final double MS_PER_TEST = 0.004;

    private long ticks;
    private long tests;
    private long earlyTests;

    /** Advances to the next tick. */
    public void tick() {
        this.ticks++;
    }

    /**
     * Whether an entity should be re-tested on the current tick.
     *
     * @param entityId the entity's id, used to give it a phase within the interval
     * @param movedSinceTest how far it has moved since its last test, in blocks
     */
    public boolean shouldTest(int entityId, double movedSinceTest) {
        if (movedSinceTest < 0.0) {
            throw new IllegalArgumentException(
                    "a negative movement: " + movedSinceTest);
        }

        if (movedSinceTest >= MOVE_THRESHOLD) {
            // Pop-in is most visible when the view is moving, because that is when an entity crosses from
            // behind cover into sight. Testing early here puts the work where it prevents the visible
            // error.
            this.tests++;
            this.earlyTests++;
            return true;
        }

        // Staggered by id, so a quarter of the entities is due on each tick rather than all of them on one.
        boolean due = Math.floorMod(entityId, IDLE_INTERVAL) == Math.floorMod(this.ticks, IDLE_INTERVAL);
        if (due) {
            this.tests++;
        }
        return due;
    }

    /** How many ticks have passed. */
    public long ticks() {
        return this.ticks;
    }

    /** How many tests were scheduled in total. */
    public long tests() {
        return this.tests;
    }

    /** How many of those were brought forward by movement. */
    public long earlyTests() {
        return this.earlyTests;
    }

    /** Tests per tick on average. */
    public double testsPerTick() {
        return this.ticks == 0 ? 0.0 : (double) this.tests / this.ticks;
    }

    /** Milliseconds of visibility testing per tick. */
    public double msPerTick() {
        return testsPerTick() * MS_PER_TEST;
    }

    /** Milliseconds a tick would cost testing every entity every tick. */
    public double msWithoutScheduling(int entityCount) {
        return entityCount * MS_PER_TEST;
    }
}
