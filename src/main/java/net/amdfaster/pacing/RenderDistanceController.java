package net.amdfaster.pacing;

/**
 * Chooses a render distance that holds a target frame time, from the frame times it is given.
 *
 * <p>A fixed render distance is a bet that the scene will stay the same size. It does not: walking out
 * of a tunnel into an open plain multiplies the visible chunk count several times over, and a build with
 * a lot of glass does it without any change of scenery at all. The distance that was comfortable a
 * moment ago is not comfortable now.
 *
 * <h2>Why the adjustment is a square root</h2>
 *
 * <p>The number of chunks inside a render distance grows as the square of that distance, and the cost of
 * a frame is close to proportional to the chunks in it. So if a frame is taking twice the budget, the
 * distance that brings it back is the current distance divided by the square root of two -- not halved,
 * which overshoots by a factor of two and makes the distance visibly pump.
 *
 * <h2>Why it does not react every frame</h2>
 *
 * <p>Two guards. A frame time has to differ from the budget by more than a tenth before anything changes,
 * because a controller that corrects noise instead of trend oscillates and the oscillation is visible as
 * chunks appearing and disappearing. And a number of frames has to pass between adjustments, because the
 * effect of a distance change is not measurable until the chunks at the new distance have loaded, which
 * takes longer than one frame.
 *
 * <h2>Why the step is limited</h2>
 *
 * <p>Even when the controller has decided a large change is needed, it takes at most two chunks at a
 * time. A jump from sixteen to eight is a visible hole in the world; sixteen to fourteen is a slightly
 * shorter horizon that the next frame will reassess.
 *
 * <p>Not thread safe.
 */
public final class RenderDistanceController {

    /** Never go below this, whatever the frame time. */
    public static final int MIN_DISTANCE = 4;
    /** Never go above this, even with a great deal of headroom. */
    public static final int MAX_DISTANCE = 32;
    /** The largest change applied in one step. */
    public static final int MAX_STEP = 2;
    /** Frames that must pass between adjustments. */
    public static final int SETTLE_FRAMES = 30;
    /** How far the frame time has to miss the budget before the controller acts. */
    public static final double HYSTERESIS = 0.10;

    private final long targetFrameNanos;
    private final int minDistance;
    private final int maxDistance;

    private int distance;
    private int framesSinceChange;
    private int adjustments;
    private int suppressedByHysteresis;
    private int suppressedBySettling;
    private int clamped;

    /**
     * @param targetFrameNanos the frame budget to hold, for example 16666667 for 60 fps
     * @param initialDistance where to start
     */
    public RenderDistanceController(long targetFrameNanos, int initialDistance) {
        this(targetFrameNanos, initialDistance, MIN_DISTANCE, MAX_DISTANCE);
    }

    public RenderDistanceController(long targetFrameNanos, int initialDistance, int min, int max) {
        if (targetFrameNanos <= 0L) {
            throw new IllegalArgumentException("target frame time must be positive: " + targetFrameNanos);
        }
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("invalid distance range: " + min + " to " + max);
        }
        this.targetFrameNanos = targetFrameNanos;
        this.minDistance = min;
        this.maxDistance = max;
        this.distance = clamp(initialDistance, min, max);
    }

    /** The render distance currently in force. */
    public int distance() {
        return this.distance;
    }

    public long targetFrameNanos() {
        return this.targetFrameNanos;
    }

    /**
     * Feeds one frame and returns the distance that should be in force afterwards.
     *
     * <p>Call this once per frame with the frame's total time. Returning the distance rather than a
     * boolean means the caller cannot forget to read it after an adjustment.
     *
     * @param frameNanos how long the frame took
     * @return the render distance to use
     */
    public int onFrame(long frameNanos) {
        this.framesSinceChange++;

        if (frameNanos <= 0L || frameNanos > 60_000_000_000L) {
            // A negative time means the clock stepped backwards, and a minute-long frame means the window
            // was dragged or the game paused. Neither says anything about the scene's cost, and reacting
            // to either would drop the distance for no reason.
            return this.distance;
        }
        if (this.framesSinceChange < SETTLE_FRAMES) {
            this.suppressedBySettling++;
            return this.distance;
        }

        double ratio = (double) this.targetFrameNanos / (double) frameNanos;
        if (Math.abs(ratio - 1.0) <= HYSTERESIS) {
            this.suppressedByHysteresis++;
            return this.distance;
        }

        // Cost grows as the square of the distance, so the distance scales as the square root of the
        // time ratio. A frame at twice the budget needs one over root two, not one half.
        int proposed = (int) Math.round(this.distance * Math.sqrt(ratio));
        int step = clamp(proposed - this.distance, -MAX_STEP, MAX_STEP);
        int next = clamp(this.distance + step, this.minDistance, this.maxDistance);

        if (next != this.distance) {
            this.distance = next;
            this.framesSinceChange = 0;
            this.adjustments++;
        }
        if (next != proposed) {
            this.clamped++;
        }
        return this.distance;
    }

    /** How many times the distance has actually changed. */
    public int adjustments() {
        return this.adjustments;
    }

    /** Frames where a correction was wanted but was inside the hysteresis band. */
    public int suppressedByHysteresis() {
        return this.suppressedByHysteresis;
    }

    /** Frames where a correction was wanted but the previous one had not settled. */
    public int suppressedBySettling() {
        return this.suppressedBySettling;
    }

    /** Adjustments that hit a distance bound. */
    public int clamped() {
        return this.clamped;
    }

    /** Forces the distance, for example when the player sets it manually. */
    public void setDistance(int distance) {
        this.distance = clamp(distance, this.minDistance, this.maxDistance);
        this.framesSinceChange = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
