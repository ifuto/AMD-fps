package net.amdfaster.sound;

/**
 * Measures how hard the game is being asked to make sound, and tightens the limits as it rises.
 *
 * <p>A fixed sound limit is wrong in both directions. Set it high enough for a farm with a hundred animals
 * and a quiet walk through a forest plays every bird and every footstep, costing channels for nothing. Set it
 * low enough to survive the farm and the forest goes silent, which the player notices as the game having lost
 * its audio.
 *
 * <p>Measuring the rate at which sounds are being requested and tightening the limits as that rate rises
 * means each scene gets the treatment it needs: the forest is left alone, and the farm is trimmed only while
 * it is actually flooding the engine.
 *
 * <h2>Pressure is a rate, not a count</h2>
 *
 * <p>What makes a scene hard is how many sounds per second it asks for, not how many it has asked for since
 * the world loaded. A count would grow forever and never relax, so a player who walked past one noisy machine
 * five minutes ago would still be paying for it.
 *
 * <h2>It relaxes when the scene quiets down</h2>
 *
 * <p>Walking out of the farm should restore the full limits within a second or two, not leave the world
 * muffled because of where the player has been. So pressure decays each tick towards zero, and a quiet scene
 * reaches the relaxed end quickly.
 *
 * <h2>It moves smoothly</h2>
 *
 * <p>A pressure that jumped from zero to maximum on one loud tick would tighten the limits instantly and then
 * release them just as fast, and the player would hear the world's audio pumping with whatever was happening.
 * Rising by a fraction of the gap each tick means a burst takes a few ticks to register, which is long enough
 * that a single explosion does not change the soundscape and short enough that a sustained flood is caught.
 *
 * <h2>Why this is not the same as the cut-off</h2>
 *
 * <p>{@link AdaptiveSoundCutoff} tightens with how many sounds are playing right now, which is a level. This
 * measures how fast they are arriving, which is a rate, and the two differ: a scene can hold sixty sounds that
 * started a minute ago and be entirely quiet now, or start sixty in one tick and be silent the next. The
 * level and the rate call for different responses.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) applies adaptive audio pressure as the sound event rate
 * rises. This is an independent implementation of the same behaviour.
 */
public final class AdaptiveSoundPressure {

    /** Events per second above which the pressure is at its maximum. */
    public static final int SATURATING_RATE = 100;

    /** How much of the gap to the target is closed each tick. */
    public static final double RISE_FRACTION = 0.25;

    /** How much pressure is shed each tick when nothing arrives. */
    public static final double DECAY_PER_TICK = 0.05;

    private double pressure;

    private long ticks;
    private long events;
    private long saturated;

    /**
     * Reports one tick's worth of sound events.
     *
     * @param eventsThisTick how many sounds were requested
     * @return the pressure after this tick, between 0 and 1
     */
    public double tick(int eventsThisTick) {
        if (eventsThisTick < 0) {
            throw new IllegalArgumentException("a negative count: " + eventsThisTick);
        }
        this.ticks++;
        this.events += eventsThisTick;

        // Twenty ticks to a second, so this tick's events scaled up give the rate.
        double rate = eventsThisTick * 20.0;
        double target = Math.min(1.0, rate / SATURATING_RATE);

        if (target > this.pressure) {
            // Rising by a fraction of the gap means a burst takes a few ticks to register, which is long
            // enough that a single explosion does not change the soundscape and short enough that a
            // sustained flood is caught.
            this.pressure += (target - this.pressure) * RISE_FRACTION;
        } else {
            // Walking out of the farm should restore the full limits within a second or two, not leave the
            // world muffled because of where the player has been.
            this.pressure = Math.max(target, this.pressure - DECAY_PER_TICK);
        }
        if (this.pressure >= 1.0) {
            this.saturated++;
        }
        return this.pressure;
    }

    /** The current pressure, between 0 and 1. */
    public double pressure() {
        return this.pressure;
    }

    /** Whether the pressure is at its maximum. */
    public boolean isSaturated() {
        return this.pressure >= 1.0;
    }

    /**
     * The sound limit to apply at the current pressure.
     *
     * @param relaxed the limit for a quiet scene
     * @param strict the limit at maximum pressure
     */
    public int limit(int relaxed, int strict) {
        if (strict > relaxed) {
            throw new IllegalArgumentException(
                    "the strict limit must not exceed the relaxed one: " + strict + " > " + relaxed);
        }
        if (relaxed < 1 || strict < 1) {
            throw new IllegalArgumentException("a limit of at least one is required");
        }
        return (int) Math.round(relaxed - this.pressure * (relaxed - strict));
    }

    public long ticks() {
        return this.ticks;
    }

    public long events() {
        return this.events;
    }

    /** How many ticks were spent at maximum pressure. */
    public long saturated() {
        return this.saturated;
    }

    /** The average event rate, in events per second. */
    public double averageRate() {
        return this.ticks == 0 ? 0.0 : (double) this.events / this.ticks * 20.0;
    }

    /**
     * The share of the ticks spent at maximum pressure.
     *
     * <p>Zero before any tick has passed. A gauge that has measured nothing has technically spent no time
     * under pressure, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double saturatedPercent() {
        return this.ticks == 0 ? 0.0 : 100.0 * this.saturated / this.ticks;
    }
}
