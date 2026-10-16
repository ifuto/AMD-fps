package net.amdfaster.mem;

/**
 * Watches how much of the heap is in use and says so before it becomes a problem.
 *
 * <p>A game that runs out of memory does not fail politely. It stutters as the collector works harder, then
 * freezes, then crashes with an error that says nothing about what filled the heap. The player loses the
 * session and has no idea whether it was a mod, a world, or the memory setting in the launcher.
 *
 * <p>Watching the heap and reporting it while there is still time to act turns that into something the
 * player can respond to: close a screen, reduce the render distance, or save and restart before the crash
 * rather than after it.
 *
 * <h2>It observes, and does not intervene</h2>
 *
 * <p>Calling {@code System.gc()} from a mod trades a heap that fills gradually for a pause whose timing the
 * mod chose, and the pause is not shorter for having been scheduled -- it is the same work, moved to a
 * moment the mod picked rather than the runtime. A monitor that also collected would be making that trade on
 * the player's behalf without being asked, so this one reports and stops there.
 *
 * <h2>One warning per crossing</h2>
 *
 * <p>A heap sitting near the threshold would warn every tick, and a log that fills with the same line every
 * fiftieth of a second is not readable -- the player would stop looking at it, including at the line that
 * matters. So the warning is issued once when the threshold is crossed, and not again until the heap has
 * come back down.
 *
 * <h2>It can warn again after recovering</h2>
 *
 * <p>A heap that drops below the threshold and then climbs again is a second episode, not a continuation of
 * the first, and suppressing it would hide exactly the pattern -- a slow leak that refills -- that the
 * monitor exists to surface. So the flag resets when the heap recovers.
 *
 * <h2>Why the threshold is a fraction and not a byte count</h2>
 *
 * <p>Players allocate anywhere from two gigabytes to sixteen, and a fixed byte count would be almost nothing
 * on one and almost everything on the other. A fraction of the maximum means the warning comes at the same
 * point in the heap's life whatever was allocated -- and the point is not "you have little left", it is "the
 * collector is about to start working hard".
 *
 * <p>Thread safety: sampling happens on the server tick and the flag is read from the client, so it is
 * volatile.
 */
public final class MemoryPressureMonitor {

    /** Fraction of the maximum heap at which to warn. */
    public static final double WARN_FRACTION = 0.9;

    /** Fraction at which the warning is considered recovered. */
    public static final double RECOVER_FRACTION = 0.8;

    private volatile boolean warning;

    private long samples;
    private long warnings;
    private double peak;

    /**
     * Samples the heap.
     *
     * @param used bytes currently in use
     * @param max bytes the heap can grow to
     * @return true if this sample crossed the threshold and a warning should be shown
     */
    public boolean sample(long used, long max) {
        if (max <= 0) {
            throw new IllegalArgumentException("a positive maximum is required: " + max);
        }
        if (used < 0) {
            throw new IllegalArgumentException("a negative used count: " + used);
        }
        this.samples++;

        double fraction = (double) used / max;
        if (fraction > this.peak) {
            this.peak = fraction;
        }

        if (fraction >= WARN_FRACTION) {
            if (this.warning) {
                // A heap sitting near the threshold would warn every tick, and a log filling with the same
                // line every fiftieth of a second is not readable -- the player would stop looking at it,
                // including at the line that matters.
                return false;
            }
            this.warning = true;
            this.warnings++;
            return true;
        }
        if (fraction < RECOVER_FRACTION) {
            // A heap that drops and then climbs again is a second episode, not a continuation of the first,
            // and suppressing it would hide exactly the pattern -- a slow leak that refills -- that this
            // exists to surface.
            this.warning = false;
        }
        return false;
    }

    /** Whether the heap is currently above the warning threshold. */
    public boolean isWarning() {
        return this.warning;
    }

    public long samples() {
        return this.samples;
    }

    /** How many times the threshold was crossed. */
    public long warnings() {
        return this.warnings;
    }

    /** The highest fraction of the heap seen, for diagnostics. */
    public double peak() {
        return this.peak;
    }

    /**
     * The threshold in bytes, for a given maximum heap.
     *
     * <p>A fraction rather than a fixed count, because players allocate anywhere from two gigabytes to
     * sixteen and a fixed number would be almost nothing on one and almost everything on the other.
     */
    public static long warnBytes(long max) {
        if (max <= 0) {
            throw new IllegalArgumentException("a positive maximum is required: " + max);
        }
        return (long) (max * WARN_FRACTION);
    }
}
