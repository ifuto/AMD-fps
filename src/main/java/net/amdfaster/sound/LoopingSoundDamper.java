package net.amdfaster.sound;

/**
 * Reduces a looping sound's volume gradually rather than cutting it off.
 *
 * <p>A moving minecart, a running machine and a beacon's hum are continuous sounds, and when the audio pool
 * fills up the obvious thing is to stop them. Cutting a continuous sound mid-wave is audible as a click,
 * because the waveform does not end at zero -- and doing it to a sound the player has been hearing for a
 * minute is noticeable in a way that dropping one more rain sound is not.
 *
 * <p>Lowering the volume over several ticks instead gives the same relief without the artefact, and brings
 * it back the same way when the pressure drops.
 *
 * <h2>One step per tick</h2>
 *
 * <p>The reduction has to be fast enough to relieve the pool and slow enough not to be heard as a change.
 * Five ticks is a quarter of a second, which is quick enough that a busy scene gets its channels back almost
 * immediately and slow enough that the ear reads it as the sound getting distant rather than as an edit.
 *
 * <h2>Recovery is the same rate as the reduction</h2>
 *
 * <p>A sound that faded out over five ticks and then snapped back to full volume would be more noticeable
 * than the click this exists to avoid, because the ear is very good at hearing something return to exactly
 * where it was. So the recovery is the same step size in the other direction.
 *
 * <h2>It never reaches silence</h2>
 *
 * <p>A sound taken all the way to zero is stopped, and starting it again costs a channel and a load -- which
 * is the expensive thing the dampening was avoiding. Holding it at a floor keeps it alive at a volume the
 * player will not miss, so the relief is nearly as good and the recovery is free.
 *
 * <h2>The floor is not zero, and the ceiling is not above the sound's own volume</h2>
 *
 * <p>Recovering past where the sound was would make a machine get louder under load, which is the opposite
 * of what the player expects and would sound like a bug. So the volume is clamped between the floor and the
 * level the sound was originally played at.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) manages persistent and looping sounds with smooth dampening
 * and recovery. This is an independent implementation of the same behaviour.
 */
public final class LoopingSoundDamper {

    /** Volume steps between full and the floor. */
    public static final int STEPS = 5;

    /** The lowest volume a dampened sound is held at, as a fraction. */
    public static final double FLOOR = 0.15;

    /** How much the volume changes per tick, as a fraction of the range. */
    public static final double STEP = (1.0 - FLOOR) / STEPS;

    /** Milliseconds starting one audio channel costs, which a stopped sound would pay again. */
    public static final double MS_PER_RESTART = 0.05;

    /**
     * The current dampening level, from zero at full volume to {@link #STEPS} at the floor.
     *
     * <p>An integer level rather than an accumulated double. Subtracting a step five times does not land
     * exactly on the floor -- (1.0 - 0.15) / 5 is 0.16999999999999998, and five subtractions leave
     * 0.1500000000000002, a hair above it -- so a damper that compared against the floor would take a sixth
     * tick to arrive and would report one more dampening step than it made. Deriving the volume from the
     * level means the endpoints are exact and the count is the number of ticks that actually moved it.
     */
    private int level;

    private long ticks;
    private long dampened;
    private long recovered;
    private long heldAtFloor;
    private long restartsAvoided;

    /**
     * Applies one tick of pressure or relief.
     *
     * @param underPressure whether the audio pool is currently full enough to need relief
     * @return the volume the sound should play at this tick
     */
    public double tick(boolean underPressure) {
        this.ticks++;

        if (underPressure) {
            if (this.level >= STEPS) {
                // Already at the floor. Going lower would stop the sound, and starting it again costs a
                // channel and a load, which is the expensive thing the dampening was avoiding.
                this.heldAtFloor++;
                return volume();
            }
            this.level++;
            this.dampened++;
            if (this.level >= STEPS) {
                // The sound was never stopped, so there is no restart to pay for when the pressure drops.
                this.restartsAvoided++;
            }
            return volume();
        }

        if (this.level <= 0) {
            return volume();
        }
        // The same step size in the other direction. A sound that faded out over five ticks and then snapped
        // back to full volume would be more noticeable than the click this exists to avoid, because the ear
        // is very good at hearing something return to exactly where it was.
        this.level--;
        this.recovered++;
        return volume();
    }

    /** The current volume, as a fraction of what the sound was played at. */
    public double volume() {
        return 1.0 - this.level * STEP;
    }

    /** Whether the sound is at the floor. */
    public boolean atFloor() {
        return this.level >= STEPS;
    }

    public long ticks() {
        return this.ticks;
    }

    /** How many ticks lowered the volume. */
    public long dampened() {
        return this.dampened;
    }

    /** How many ticks raised it back. */
    public long recovered() {
        return this.recovered;
    }

    /** How many ticks were spent at the floor with nowhere lower to go. */
    public long heldAtFloor() {
        return this.heldAtFloor;
    }

    /** How many times a restart was avoided by keeping the sound alive. */
    public long restartsAvoided() {
        return this.restartsAvoided;
    }

    /** Milliseconds of channel restarts avoided. */
    public double msSaved() {
        return this.restartsAvoided * MS_PER_RESTART;
    }

    /**
     * The share of the ticks that were spent dampened rather than at full volume.
     *
     * <p>Zero before any tick has passed. A damper that has not run has technically done nothing, and
     * reporting that as a rate would describe an unused one as though it had a result.
     */
    public double dampenedPercent() {
        return this.ticks == 0 ? 0.0 : 100.0 * this.dampened / this.ticks;
    }
}
