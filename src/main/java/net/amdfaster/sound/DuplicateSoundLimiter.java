package net.amdfaster.sound;

import java.util.HashMap;
import java.util.Map;

/**
 * Limits how many copies of the same sound can play from the same block at once.
 *
 * <p>Several things in the world make the same sound from the same place at the same moment: a stack of sand
 * collapsing, a row of pistons firing together, a hundred items landing on one hopper. Each one starts its
 * own channel, and identical sounds at full volume add together, so five copies of a click are not five
 * clicks -- they are one click at five times the volume, which is loud in a way no single source in the game
 * ever is.
 *
 * <p>Capping the copies at one position keeps the sound at a volume that matches what the player sees.
 *
 * <h2>The position is a block, not a point</h2>
 *
 * <p>Two sounds a tenth of a block apart are the same event to the ear, and treating them as different
 * positions would let a collapsing stack of sand through one copy per grain. Rounding to the block means the
 * limit is about where in the world the sound is, at the resolution the player can actually locate it.
 *
 * <h2>Different sounds at the same position still stack</h2>
 *
 * <p>A piston firing and an item landing on it are two events, and the player should hear both. The limit is
 * per sound at a position, not per position, so a busy machine with three different noises is not reduced to
 * one of them.
 *
 * <h2>The excess is not played, rather than played quietly</h2>
 *
 * <p>Quieting the extras would still cost a channel each and still add to the mix, so it would fix the volume
 * and leave the performance problem. Not starting them removes both. The player hears the sound, at the
 * volume one source makes, which is what the scene looks like.
 *
 * <h2>Why this is not the same as the other two</h2>
 *
 * <p>{@link SourceFairnessAllocator} caps what one source can hold across the whole pool, and
 * {@link CategoryBudgetAllocator} decides which kinds of sound give way. This is about the same sound
 * repeated at the same spot, which neither of those sees: a collapsing sand stack is one source making one
 * kind of sound, so it is within its fairness cap and within its category's share, and still five times too
 * loud.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Audio Engine Tweaks limits identical sounds from the same coordinate. This is an independent
 * implementation of the same idea.
 */
public final class DuplicateSoundLimiter {

    /** The most copies of one sound allowed from one block at once. */
    public static final int MAX_DUPLICATES = 5;

    /** Milliseconds starting one audio channel costs. */
    public static final double MS_PER_CHANNEL = 0.05;

    private final Map<Long, Integer> playing = new HashMap<>();

    private long requests;
    private long played;
    private long dropped;

    /**
     * The key for a position and a sound.
     *
     * <p>Rounded to the block, because two sounds a tenth of a block apart are the same event to the ear and
     * treating them as different positions would let a collapsing stack of sand through one copy per grain.
     *
     * @param sound an identifier for the sound itself
     */
    public static long key(double x, double y, double z, String sound) {
        long bx = (long) Math.floor(x);
        long by = (long) Math.floor(y);
        long bz = (long) Math.floor(z);
        long position = (bx & 0x3FFFFFL) | ((by & 0xFFFL) << 22) | ((bz & 0x3FFFFFL) << 34);
        return position * 31 + sound.hashCode();
    }

    /**
     * Whether a sound may start at a position.
     *
     * @param sound an identifier for the sound itself
     * @return true if it should play
     */
    public boolean allow(double x, double y, double z, String sound) {
        if (sound == null || sound.isEmpty()) {
            throw new IllegalArgumentException("a sound is required");
        }
        long key = key(x, y, z, sound);
        this.requests++;

        int already = this.playing.getOrDefault(key, 0);
        if (already >= MAX_DUPLICATES) {
            // Quieting the extras would still cost a channel each and still add to the mix, so it would fix
            // the volume and leave the performance problem. Not starting them removes both.
            this.dropped++;
            return false;
        }
        this.playing.put(key, already + 1);
        this.played++;
        return true;
    }

    /** Clears the counts at the end of a tick. */
    public void endTick() {
        this.playing.clear();
    }

    /** How many distinct position-and-sound combinations are playing this tick. */
    public int distinct() {
        return this.playing.size();
    }

    public long requests() {
        return this.requests;
    }

    public long played() {
        return this.played;
    }

    /** How many were not started because the same sound was already playing there. */
    public long dropped() {
        return this.dropped;
    }

    public double droppedPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.dropped / this.requests;
    }

    /** Milliseconds of channel starts avoided. */
    public double msSaved() {
        return this.dropped * MS_PER_CHANNEL;
    }

    /**
     * The share of the channel work avoided.
     *
     * <p>Zero before any sound has been asked about. A limiter that has been asked nothing has technically
     * dropped nothing, and reporting that as a rate would describe an unused one as though it had a result.
     */
    public double savedPercent() {
        if (this.requests == 0) {
            return 0.0;
        }
        return 100.0 * this.dropped / this.requests;
    }
}
