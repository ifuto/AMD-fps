package net.amdfaster.runtime;

/**
 * Decides which parts of the HUD are worth redrawing this frame.
 *
 * <p>The HUD is redrawn whole every frame: at a GUI resolution of 960 by 540 and 60 fps that is 31
 * million blended pixels a second, for a display whose hotbar has not changed since the last time the
 * player picked something up. Two things are going on that this separates.
 *
 * <p>The first is that most layers do not change. A content hash per layer costs a few bytes to compare
 * and replaces thousands of pixels of blending when it matches. That applies to every layer and is
 * never a change anyone can see, because the pixels produced are identical.
 *
 * <p>The second is that some layers change often but are read slowly. An FPS counter updates sixty
 * times a second and is read as a number that happens to be moving; four updates a second is the same
 * experience. A clock updates sixty times a second and is read to the minute. Throwing those updates
 * away is the saving that is actually free.
 *
 * <h2>What must never be throttled</h2>
 *
 * <p>Anything that tracks a position. The hotbar's selection outline follows the mouse and the
 * crosshair follows the view, and delaying either by a hundred milliseconds is immediately obvious --
 * it feels like input lag rather than looking like a slow number. So a layer declares what kind of thing
 * it is, and {@link #shouldRedraw} refuses to throttle a position-tracking layer even when the caller
 * asks it to. The caller cannot make that mistake, which is worth more than the flexibility.
 *
 * <h2>The intervals are chosen from what can be read, not from what is cheap</h2>
 *
 * <p>250 ms for a counter, because a number updating at 4 Hz is still legible as a number and below that
 * it starts to stutter. 1,000 ms for a clock, which is read to the minute. 100 ms for coordinates and
 * the debug overlay, both read to one decimal place. Each of those is a claim about perception and would
 * be wrong in a visible way if it were too aggressive, so they are named constants with the reasoning
 * attached rather than tuned values buried in a call.
 *
 * <p>Not thread safe.
 */
public final class GuiUpdateThrottle {

    /** What a layer is, which decides whether it may be throttled at all. */
    public enum Kind {
        /**
         * Follows a position on screen. Never throttled, because delaying it reads as input lag.
         */
        TRACKS_POSITION,

        /**
         * A number that is read rather than watched. Throttled to its interval.
         */
        NUMERIC_READOUT,

        /**
         * Changes only when its content changes, and is otherwise static. Content detection alone.
         */
        CONTENT_ONLY
    }

    /** Interval for a counter such as FPS: 4 Hz, below which a number starts to stutter. */
    public static final long COUNTER_INTERVAL_MS = 250;

    /** Interval for a clock, which is read to the minute. */
    public static final long CLOCK_INTERVAL_MS = 1000;

    /** Interval for coordinates and the debug overlay, both read to one decimal place. */
    public static final long READOUT_INTERVAL_MS = 100;

    private static final int DEFAULT_LAYERS = 32;

    private final int[] lastHash;
    private final long[] lastDrawnMs;
    private final Kind[] kinds;
    private final long[] intervals;
    private final int layers;

    private long checks;
    private long redraws;
    private long skippedUnchanged;
    private long skippedTooSoon;
    private long throttleRefused;

    public GuiUpdateThrottle() {
        this(DEFAULT_LAYERS);
    }

    public GuiUpdateThrottle(int layers) {
        if (layers < 1) {
            throw new IllegalArgumentException("a HUD has at least one layer: " + layers);
        }
        this.layers = layers;
        this.lastHash = new int[layers];
        this.lastDrawnMs = new long[layers];
        this.kinds = new Kind[layers];
        this.intervals = new long[layers];
        for (int i = 0; i < layers; i++) {
            // Never drawn, so the first check always draws.
            this.lastDrawnMs[i] = Long.MIN_VALUE;
            this.kinds[i] = Kind.CONTENT_ONLY;
        }
    }

    /**
     * Declares what a layer is and how often it may update.
     *
     * @param layer the layer index
     * @param kind what the layer is
     * @param intervalMs the minimum time between redraws, ignored for {@link Kind#TRACKS_POSITION}
     */
    public void configure(int layer, Kind kind, long intervalMs) {
        checkLayer(layer);
        if (intervalMs < 0) {
            throw new IllegalArgumentException("interval must not be negative: " + intervalMs);
        }
        this.kinds[layer] = kind;
        this.intervals[layer] = kind == Kind.TRACKS_POSITION ? 0 : intervalMs;
    }

    /**
     * Whether a layer should be redrawn this frame.
     *
     * @param layer the layer index
     * @param contentHash a hash of everything the layer would draw
     * @param nowMs the current time
     * @return true if the caller should draw it
     */
    public boolean shouldRedraw(int layer, int contentHash, long nowMs) {
        checkLayer(layer);
        this.checks++;

        boolean neverDrawn = this.lastDrawnMs[layer] == Long.MIN_VALUE;
        if (!neverDrawn && this.lastHash[layer] == contentHash) {
            this.skippedUnchanged++;
            return false;
        }
        // The never-drawn case is checked before the subtraction rather than folded into it, because
        // now - Long.MIN_VALUE overflows to a large negative number and would read as "not enough time
        // has passed", leaving the layer undrawn forever.
        if (!neverDrawn
                && this.kinds[layer] != Kind.TRACKS_POSITION
                && nowMs - this.lastDrawnMs[layer] < this.intervals[layer]) {
            this.skippedTooSoon++;
            return false;
        }

        this.lastHash[layer] = contentHash;
        this.lastDrawnMs[layer] = nowMs;
        this.redraws++;
        return true;
    }

    /**
     * Attempts to throttle a layer that is currently position-tracking.
     *
     * <p>Returns false and changes nothing rather than applying the interval, because the result would
     * be a HUD element that lags behind the thing it follows.
     */
    public boolean tryThrottle(int layer, long intervalMs) {
        checkLayer(layer);
        if (this.kinds[layer] == Kind.TRACKS_POSITION) {
            this.throttleRefused++;
            return false;
        }
        if (intervalMs < 0) {
            throw new IllegalArgumentException("interval must not be negative: " + intervalMs);
        }
        this.intervals[layer] = intervalMs;
        return true;
    }

    /**
     * Forces the next check to redraw, for a resize or a scale change.
     *
     * <p>The cached content is still correct but the pixels it was drawn into are not, so the layer has
     * to be redrawn even though nothing about its content changed.
     */
    public void invalidate(int layer) {
        checkLayer(layer);
        this.lastDrawnMs[layer] = Long.MIN_VALUE;
    }

    /** Forces every layer to redraw. */
    public void invalidateAll() {
        for (int i = 0; i < this.layers; i++) {
            this.lastDrawnMs[i] = Long.MIN_VALUE;
        }
    }

    public Kind kindOf(int layer) {
        checkLayer(layer);
        return this.kinds[layer];
    }

    public long intervalOf(int layer) {
        checkLayer(layer);
        return this.intervals[layer];
    }

    private void checkLayer(int layer) {
        if (layer < 0 || layer >= this.layers) {
            throw new IndexOutOfBoundsException("layer " + layer + " outside " + this.layers);
        }
    }

    public long checks() {
        return this.checks;
    }

    public long redraws() {
        return this.redraws;
    }

    /** Checks skipped because the content was identical. */
    public long skippedUnchanged() {
        return this.skippedUnchanged;
    }

    /** Checks skipped because the layer's interval had not elapsed. */
    public long skippedTooSoon() {
        return this.skippedTooSoon;
    }

    /** Throttle attempts refused because the layer tracks a position. */
    public long throttleRefused() {
        return this.throttleRefused;
    }

    /** Percentage of checks that did not produce a redraw. */
    public double skippedPercent() {
        return this.checks == 0 ? 0.0
                : 100.0 * (this.skippedUnchanged + this.skippedTooSoon) / this.checks;
    }
}
