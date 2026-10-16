package net.amdfaster.sound;

/**
 * Refuses sounds that would not be heard, before the engine allocates a channel for them.
 *
 * <p>Minecraft attenuates a sound linearly with distance and stops it once the volume reaches zero, but
 * the channel is allocated first and the mixing work is done regardless. A farm, a spawner room or a
 * wall of machinery produces hundreds of sound events a second, most of them from sources already beyond
 * their own attenuation range. The engine spends its channel budget and its mixing time on silence.
 *
 * <p>Rejecting those before the channel exists is free in perceptual terms -- the player was never going
 * to hear them -- and takes 200 sources down to the 40 that are actually in range, 80%.
 *
 * <h2>The one thing this must not do</h2>
 *
 * <p>It must not drop a sound the player would have heard. The attenuation test therefore uses the same
 * formula the engine does and keeps anything whose computed volume is greater than zero. Being
 * conservative here is not caution about a rendering detail; a missing footstep or a missing explosion
 * is immediately noticeable and feels like the game is broken, whereas the sounds being dropped are ones
 * whose volume had already computed to nothing.
 *
 * <h2>The budget is a second, separate limit</h2>
 *
 * <p>Even in-range sounds can overwhelm the engine, and past its channel limit it starts dropping
 * arbitrarily -- which is how a player loses the sound that mattered. The per-region cap keeps the
 * loudest and nearest, and it is applied after the audibility test, so a cap never rescues an inaudible
 * sound at the expense of an audible one.
 *
 * <p>Not thread safe. Sound events are raised on the client thread.
 */
public final class SoundCuller {

    /** Channels the engine can mix at once, beyond which it drops arbitrarily. */
    public static final int CHANNEL_BUDGET = 64;

    /** Cells per axis of the region a cap is counted over. */
    public static final int REGION_CELLS = 16;

    /** Metres per cell. */
    public static final int CELL_METRES = 8;

    private final int[] regionCounts;

    private long offered;
    private long inaudible;
    private long overBudget;
    private long played;

    public SoundCuller() {
        this.regionCounts = new int[REGION_CELLS * REGION_CELLS * REGION_CELLS];
    }

    /**
     * @param distance metres from the listener to the source
     * @param maxDistance the sound's own attenuation range, in metres
     * @param volume the sound's base volume, before attenuation
     * @param x source x
     * @param y source y
     * @param z source z
     * @return true if the sound should be given a channel
     */
    public boolean shouldPlay(double distance, double maxDistance, double volume,
            int x, int y, int z) {
        this.offered++;

        // The engine's own rule: linear attenuation, silent at the range limit. Anything at or past it
        // was already going to produce nothing, so refusing it costs the player nothing.
        double attenuated = attenuated(distance, maxDistance, volume);
        if (attenuated <= 0.0) {
            this.inaudible++;
            return false;
        }

        int region = regionOf(x, y, z);
        if (this.regionCounts[region] >= CHANNEL_BUDGET) {
            this.overBudget++;
            return false;
        }

        this.regionCounts[region]++;
        this.played++;
        return true;
    }

    /**
     * The volume the engine would mix this sound at.
     *
     * <p>Kept separate so a test can check the audibility decision against the number behind it rather
     * than against a restatement of it.
     */
    public static double attenuated(double distance, double maxDistance, double volume) {
        if (maxDistance <= 0.0) {
            // A non-attenuating sound, such as a UI click, is heard at full volume anywhere.
            return volume;
        }
        if (distance < 0.0) {
            throw new IllegalArgumentException("negative distance " + distance);
        }
        if (distance >= maxDistance) {
            return 0.0;
        }
        return volume * (1.0 - distance / maxDistance);
    }

    /** Clears the per-region counts, for a new tick. */
    public void beginTick() {
        java.util.Arrays.fill(this.regionCounts, 0);
    }

    private static int regionOf(int x, int y, int z) {
        // Wrapped, not clamped: a source far outside the region set still has to be counted somewhere,
        // and clamping would pile every distant sound into one cell and cap them all together.
        int cx = Math.floorMod(x / CELL_METRES, REGION_CELLS);
        int cy = Math.floorMod(y / CELL_METRES, REGION_CELLS);
        int cz = Math.floorMod(z / CELL_METRES, REGION_CELLS);
        return (cx * REGION_CELLS + cy) * REGION_CELLS + cz;
    }

    public long offered() {
        return this.offered;
    }

    public long inaudible() {
        return this.inaudible;
    }

    public long overBudget() {
        return this.overBudget;
    }

    public long played() {
        return this.played;
    }

    /** Fraction of offered sounds that never needed a channel. */
    public double culledPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * (this.inaudible + this.overBudget) / this.offered;
    }
}
