package net.amdfaster.mob;

/**
 * Re-evaluates a mob's AI goals on a staggered schedule instead of every tick.
 *
 * <p>Every mob decides what to do on every tick: it walks its goal list, asks each one whether it can
 * start, and picks the highest priority that can. With a hundred mobs in a farm that is a hundred goal
 * evaluations a tick, twenty times a second, and almost all of them reach the same conclusion they reached
 * last tick -- the cow is still wandering, the zombie is still walking toward the player, the villager is
 * still working.
 *
 * <p>Evaluating a stationary mob every few ticks instead removes most of that, and the mob's behaviour is
 * unchanged because the answer was going to be the same.
 *
 * <h2>A goal that is already running is not re-evaluated</h2>
 *
 * <p>That is the part that makes the saving safe rather than merely cheap. A mob mid-stride along a path
 * does not need to decide again what it is doing; it needs to keep doing it. Re-evaluating it would either
 * confirm what it is already doing, which is wasted work, or interrupt it, which would make the mob stutter
 * -- restarting a path every few ticks is visible as a mob that twitches rather than walks.
 *
 * <h2>Something changing forces an immediate evaluation</h2>
 *
 * <p>A player appearing, the mob taking damage, a door opening. Those change what the right answer is, and
 * waiting for the next scheduled evaluation would make the mob ignore the player for a fraction of a
 * second -- long enough to feel unresponsive, and in the case of a panic goal, long enough to be unfair.
 * So a change is evaluated on the tick it happens.
 *
 * <h2>The schedule is staggered</h2>
 *
 * <p>A hundred mobs all evaluated on the same tick every fourth tick is still a hundred evaluations at
 * once, and the other three ticks are idle, which shows up as a hitch at that cadence rather than a saving.
 * Giving each mob a phase from its id spreads them, so every tick does about a quarter of the work.
 *
 * <h2>Why the interval is ticks, not frames</h2>
 *
 * <p>Mob AI runs on the server tick, and tying the cadence to it means the behaviour is the same at any
 * frame rate. Counting frames would make mobs on a 30 fps machine think half as often as on a 60 fps one,
 * which would be a gameplay difference caused by a rendering setting.
 *
 * <p>Not thread safe. Mob AI runs on the server thread.
 */
public final class GoalEvaluationScheduler {

    /** Ticks between evaluations for a mob nothing has happened to. */
    public static final int IDLE_INTERVAL = 4;

    /** Milliseconds one goal evaluation costs. */
    public static final double MS_PER_EVALUATION = 0.02;

    private long ticks;
    private long evaluations;
    private long forced;
    private long skippedRunning;

    /** Advances to the next tick. */
    public void tick() {
        this.ticks++;
    }

    /**
     * Whether a mob's goals should be re-evaluated this tick.
     *
     * @param mobId the mob's id, used to give it a phase within the interval
     * @param goalRunning whether the mob is already doing something
     * @param somethingChanged whether a player appeared, it took damage, or a door opened
     */
    public boolean shouldEvaluate(int mobId, boolean goalRunning, boolean somethingChanged) {
        if (somethingChanged) {
            // A player appearing, damage taken, a door opening. Those change what the right answer is, and
            // waiting for the next scheduled evaluation would make the mob ignore the player for a fraction
            // of a second -- long enough to feel unresponsive, and for a panic goal, long enough to be
            // unfair.
            this.evaluations++;
            this.forced++;
            return true;
        }
        if (goalRunning) {
            // A mob mid-stride along a path does not need to decide again what it is doing. Re-evaluating
            // would either confirm what it is already doing, which is wasted work, or interrupt it, which
            // would make the mob stutter rather than walk.
            this.skippedRunning++;
            return false;
        }
        // Staggered by id, so a quarter of the mobs is due on each tick rather than all of them on one.
        boolean due = Math.floorMod(mobId, IDLE_INTERVAL)
                == Math.floorMod(this.ticks, IDLE_INTERVAL);
        if (due) {
            this.evaluations++;
        }
        return due;
    }

    public long ticks() {
        return this.ticks;
    }

    public long evaluations() {
        return this.evaluations;
    }

    /** How many were brought forward because something happened. */
    public long forced() {
        return this.forced;
    }

    /** How many were skipped because the mob was already doing something. */
    public long skippedRunning() {
        return this.skippedRunning;
    }

    /** Evaluations per tick on average. */
    public double evaluationsPerTick() {
        return this.ticks == 0 ? 0.0 : (double) this.evaluations / this.ticks;
    }

    /** Milliseconds of goal evaluation per tick. */
    public double msPerTick() {
        return evaluationsPerTick() * MS_PER_EVALUATION;
    }

    /** Milliseconds a tick costs evaluating every mob. */
    public double msWithoutScheduling(int mobCount) {
        return mobCount * MS_PER_EVALUATION;
    }

    /**
     * The share of the goal evaluation avoided.
     *
     * <p>Zero before any tick has passed. A scheduler that has run no ticks has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent(int mobCount) {
        double without = msWithoutScheduling(mobCount);
        if (without == 0.0 || this.ticks == 0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msPerTick()) / without;
    }
}
