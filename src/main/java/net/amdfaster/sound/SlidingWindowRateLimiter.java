package net.amdfaster.sound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits how often one sound may play, over a window that moves with the clock.
 *
 * <p>A piston clock, a hopper chain and a farm with a hundred animals all make the same sound over and over,
 * and each occurrence costs a channel and a mix. Capping how often one sound may play removes most of that
 * while leaving it clearly audible -- a machine that clicks three times a second reads the same as one that
 * clicks thirty, and the second is what costs the frames.
 *
 * <h2>The window slides</h2>
 *
 * <p>A fixed window -- count from the top of the second, reset at the bottom -- lets a burst through twice at
 * the boundary: ten sounds in the last tick of one window and ten in the first tick of the next are twenty in
 * two ticks, which is exactly the spike the limit exists to prevent. A window that moves with the clock
 * counts the last N ticks whenever it is asked, so the rate it enforces is the rate the player actually
 * hears.
 *
 * <h2>Each sound is counted on its own</h2>
 *
 * <p>A piston and a hopper are different sounds and the player should hear both. Counting them together would
 * let a noisy machine silence the hopper next to it, which is the failure the per-region limit was there to
 * prevent, arrived at by a different route.
 *
 * <h2>The window is per sound</h2>
 *
 * <p>A footstep repeating once a second is normal and one repeating thirty times a second is a machine, but a
 * piece of music repeating once a second would be broken. So the length is a property of the sound, not a
 * global setting, and a mod that adds a sound can say what rate is reasonable for it.
 *
 * <h2>Why this is not the same as the duplicate limiter</h2>
 *
 * <p>{@link DuplicateSoundLimiter} caps how many copies play from one block at one moment, which is about
 * volume and simultaneity. This caps how often a sound plays at all over time, which is about repetition. A
 * piston firing once a tick from one block is within the duplicate limit and can still be thirty times a
 * second.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Omega Mute allows a sound to play once per configured interval, and Sound Culling 2.0
 * evaluates over a time window. This is an independent implementation of the same idea.
 */
public final class SlidingWindowRateLimiter {

    /** The default window, in ticks. Twenty ticks is a second. */
    public static final int DEFAULT_WINDOW = 20;

    /** How many times a sound may play in its window by default. */
    public static final int DEFAULT_LIMIT = 6;

    /** Milliseconds starting one audio channel costs. */
    public static final double MS_PER_CHANNEL = 0.05;

    private final Map<String, Deque<Integer>> recent = new HashMap<>();
    private final Map<String, Integer> windows = new HashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();

    private long offered;
    private long played;
    private long refused;

    /**
     * Sets a sound's own window and limit.
     *
     * @param windowTicks how far back to look
     * @param limit how many times it may play in that window
     */
    public void configure(String sound, int windowTicks, int limit) {
        if (sound == null || sound.isEmpty()) {
            throw new IllegalArgumentException("a sound is required");
        }
        if (windowTicks < 1) {
            throw new IllegalArgumentException("a window of at least one tick: " + windowTicks);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("a limit of at least one: " + limit);
        }
        this.windows.put(sound, windowTicks);
        this.limits.put(sound, limit);
    }

    /**
     * Whether a sound may play this tick.
     *
     * @param sound the sound identifier
     * @param tick the current tick, which the window is measured back from
     * @return true if it should play
     */
    public boolean allow(String sound, int tick) {
        if (sound == null || sound.isEmpty()) {
            throw new IllegalArgumentException("a sound is required");
        }
        this.offered++;

        int window = this.windows.getOrDefault(sound, DEFAULT_WINDOW);
        int limit = this.limits.getOrDefault(sound, DEFAULT_LIMIT);

        Deque<Integer> times = this.recent.computeIfAbsent(sound, key -> new ArrayDeque<>());
        // A window that moves with the clock counts the last N ticks whenever it is asked. A fixed window
        // would let a burst through twice at the boundary -- ten sounds in the last tick of one window and
        // ten in the first of the next are twenty in two ticks, which is exactly the spike the limit exists
        // to prevent.
        while (!times.isEmpty() && times.peekFirst() <= tick - window) {
            times.pollFirst();
        }

        if (times.size() >= limit) {
            this.refused++;
            return false;
        }
        times.addLast(tick);
        this.played++;
        return true;
    }

    /** How many sounds are being tracked. */
    public int tracked() {
        return this.recent.size();
    }

    public long offered() {
        return this.offered;
    }

    public long played() {
        return this.played;
    }

    public long refused() {
        return this.refused;
    }

    public double refusedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.refused / this.offered;
    }

    /** Milliseconds of channel starts avoided. */
    public double msSaved() {
        return this.refused * MS_PER_CHANNEL;
    }

    /**
     * The share of the offers refused.
     *
     * <p>Zero before any sound has been offered. A limiter that has been offered nothing has technically
     * refused nothing, and reporting that as a rate would describe an unused one as though it had a result.
     */
    public double savedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.refused / this.offered;
    }
}
