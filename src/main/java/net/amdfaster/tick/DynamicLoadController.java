package net.amdfaster.tick;

/**
 * Reduces the server's load when the tick is running late, and gives it back when it is not.
 *
 * <p>A tick has fifty milliseconds. When the work in it -- mobs, redstone, chunk loading, light -- takes
 * longer than that, the game does not slow down smoothly. It drops ticks, and the player sees it as the
 * world stuttering: mobs teleporting short distances, water freezing mid-flow, a farm that visibly pauses.
 * The alternative to dropping ticks is doing less work in them.
 *
 * <p>Lowering the mob cap and the simulation distance when the tick runs long removes exactly the work that
 * is causing it, and raising them again when the tick is comfortable gives the player back what was taken.
 *
 * <h2>The change has to be gradual</h2>
 *
 * <p>Snapping the mob cap from seventy to ten would make most of the world's mobs vanish at once, which is
 * far more noticeable than the stutter it was meant to fix -- and when the tick recovers they would all come
 * back, causing the spike that made the tick slow in the first place. So the load is reduced one step at a
 * time, and a step is small enough that no single change is visible.
 *
 * <h2>Recovery is slower than the reduction</h2>
 *
 * <p>Raising the load immediately after the tick improves would put the work back that made it slow, and the
 * controller would oscillate: reduce, recover, raise, slow, reduce. A gap between the point where the load
 * comes down and the point where it goes back up means the tick has to be comfortable for a while before
 * anything is restored, which is what stops the oscillation.
 *
 * <h2>There is a floor</h2>
 *
 * <p>Reducing the mob cap to zero would stop mobs spawning entirely, and a farm the player built would simply
 * stop working -- which is not a performance adjustment, it is the game breaking under load. The floor is the
 * level at which the world still behaves like itself, and the controller does not go below it however late the
 * tick is.
 *
 * <h2>Why this is not the same as the render distance</h2>
 *
 * <p>{@link net.amdfaster.pacing.AdaptiveQuality} lowers what is drawn, which is client-side work measured in
 * frame time. This lowers what is simulated, which is server-side work measured in tick time. A machine can be
 * slow at either or both, and reducing one does nothing for the other.
 *
 * <p>Not thread safe. The tick runs on the server thread.
 */
public final class DynamicLoadController {

    /** Milliseconds a tick is allowed to take. */
    public static final double BUDGET_MS = 50.0;

    /** Above this, reduce the load. */
    public static final double REDUCE_ABOVE_MS = 45.0;

    /** Below this, the load can go back up. */
    public static final double RESTORE_BELOW_MS = 35.0;

    /** The mob cap the world starts with. */
    public static final int MAX_MOB_CAP = 70;

    /** The mob cap the controller does not go below. */
    public static final int MIN_MOB_CAP = 20;

    /** How much one step changes the cap by. */
    public static final int CAP_STEP = 5;

    /** Milliseconds of tick time one mob costs. */
    public static final double MS_PER_MOB = 0.25;

    private int mobCap = MAX_MOB_CAP;
    private int simulationDistance = 8;

    private long ticks;
    private long reductions;
    private long restorations;
    private long atFloor;

    /**
     * Adjusts the load for one tick.
     *
     * @param mspt how long the last tick took
     */
    public void adjust(double mspt) {
        if (mspt < 0.0) {
            throw new IllegalArgumentException("a negative tick time: " + mspt);
        }
        this.ticks++;

        if (mspt > REDUCE_ABOVE_MS) {
            if (this.mobCap <= MIN_MOB_CAP) {
                // Reducing the cap to zero would stop mobs spawning entirely, and a farm the player built
                // would simply stop working -- which is not a performance adjustment, it is the game breaking
                // under load.
                this.atFloor++;
                return;
            }
            // One step, so no single change is visible. Snapping the cap from seventy to ten would make most
            // of the world's mobs vanish at once, which is far more noticeable than the stutter it was meant
            // to fix.
            this.mobCap = Math.max(MIN_MOB_CAP, this.mobCap - CAP_STEP);
            this.reductions++;
            return;
        }
        if (mspt < RESTORE_BELOW_MS && this.mobCap < MAX_MOB_CAP) {
            // The gap between REDUCE_ABOVE_MS and RESTORE_BELOW_MS is what stops the oscillation. Raising the
            // load as soon as the tick improves would put back the work that made it slow, and the controller
            // would reduce, recover, raise, slow, reduce.
            this.mobCap = Math.min(MAX_MOB_CAP, this.mobCap + CAP_STEP);
            this.restorations++;
        }
    }

    /** The current mob cap. */
    public int mobCap() {
        return this.mobCap;
    }

    /** The current simulation distance in chunks. */
    public int simulationDistance() {
        return this.simulationDistance;
    }

    /**
     * Lowers the simulation distance by one chunk.
     *
     * <p>Called separately from the cap, because the two are different kinds of work and a machine may need
     * one rather than the other.
     */
    public void reduceSimulationDistance() {
        this.simulationDistance = Math.max(4, this.simulationDistance - 1);
    }

    /** Raises the simulation distance by one chunk, back to where it started. */
    public void restoreSimulationDistance() {
        this.simulationDistance = Math.min(8, this.simulationDistance + 1);
    }

    public long ticks() {
        return this.ticks;
    }

    public long reductions() {
        return this.reductions;
    }

    public long restorations() {
        return this.restorations;
    }

    /** How many ticks were late with nothing left to reduce. */
    public long atFloor() {
        return this.atFloor;
    }

    /** Milliseconds of tick time the reduced cap saves. */
    public double msSaved() {
        return (MAX_MOB_CAP - this.mobCap) * MS_PER_MOB;
    }

    /**
     * The share of the mob ticking avoided.
     *
     * <p>Zero before any tick has run. A controller that has adjusted nothing has technically saved nothing
     * and says so, rather than reporting the saving it would make if it acted.
     */
    public double savedPercent() {
        if (this.ticks == 0) {
            return 0.0;
        }
        return 100.0 * (MAX_MOB_CAP - this.mobCap) / MAX_MOB_CAP;
    }
}
