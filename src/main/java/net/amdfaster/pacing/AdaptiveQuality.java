package net.amdfaster.pacing;

/**
 * Moves the render distance to hold a target frame time, instead of leaving it fixed.
 *
 * <p>The render distance is a number the player sets once and then has to manage by hand. A village, a
 * TNT chain or a crowded server pushes frame times well past it, and the distance that was fine over
 * open plains is suddenly too much -- but by the time the player notices and opens the menu, the moment
 * has passed. Holding the distance where it happens to be costs frames that a small reduction would
 * have saved.
 *
 * <p>This watches the frame time and moves the distance one chunk at a time to stay near the target,
 * giving the chunks back when the load drops.
 *
 * <h2>It must not oscillate</h2>
 *
 * <p>A controller with a single threshold hunts. Dropping the distance makes frames faster, which looks
 * like headroom, which raises the distance again, which makes frames slower -- and the player watches the
 * world breathing in and out. So the two directions have separate thresholds with a dead band between
 * them: at 20 ms it drops a chunk, at 14 ms it adds one, and anywhere in between it does nothing at all.
 *
 * <h2>It must not lurch</h2>
 *
 * <p>A change the player can see is worse than the stutter it was meant to fix. One chunk per
 * adjustment, and a cooldown between adjustments, so a bad frame does not immediately trigger another
 * change before the first one has had time to show up in the measurement.
 *
 * <h2>It must not decide from one frame</h2>
 *
 * <p>A single long frame is a garbage collection, a chunk loading, or the player opening a menu. Reacting
 * to it drops the distance for something that was never a sustained load, and the world shrinks for no
 * reason the player can see. The decision uses an average over a window, so only a real change in load
 * moves anything.
 *
 * <p>Not thread safe. Frame timing is measured on the render thread.
 */
public final class AdaptiveQuality {

    /** 60 fps. */
    public static final double TARGET_MS = 1000.0 / 60.0;

    /** Above this, drop a chunk. */
    public static final double DROP_ABOVE_MS = 20.0;

    /** Below this, add a chunk back. */
    public static final double RAISE_BELOW_MS = 14.0;

    /** Frames averaged before deciding. */
    public static final int WINDOW = 20;

    /** Frames to wait after a change, so the change shows up in the average. */
    public static final int COOLDOWN = 30;

    /** The closest the distance is ever allowed to go. */
    public static final int MIN_DISTANCE = 4;

    private final int maxDistance;

    private final double[] window = new double[WINDOW];
    private int samples;
    private int cursor;

    private int distance;
    private int cooldownLeft;

    private long drops;
    private long raises;
    private long held;

    /**
     * @param startingDistance the distance to begin at
     * @param maxDistance the player's own setting, which is never exceeded
     */
    public AdaptiveQuality(int startingDistance, int maxDistance) {
        if (maxDistance < MIN_DISTANCE) {
            throw new IllegalArgumentException(
                    "a maximum of " + maxDistance + " is below the floor of " + MIN_DISTANCE);
        }
        if (startingDistance < MIN_DISTANCE || startingDistance > maxDistance) {
            throw new IllegalArgumentException(
                    "starting distance " + startingDistance + " outside "
                            + MIN_DISTANCE + ".." + maxDistance);
        }
        this.distance = startingDistance;
        this.maxDistance = maxDistance;
    }

    /**
     * Records a frame and decides whether to move.
     *
     * @param frameMs how long the frame took
     * @return the distance to use from now on
     */
    public int record(double frameMs) {
        if (frameMs <= 0.0 || Double.isNaN(frameMs) || Double.isInfinite(frameMs)) {
            throw new IllegalArgumentException("a frame took " + frameMs + " ms");
        }
        this.window[this.cursor] = frameMs;
        this.cursor = (this.cursor + 1) % WINDOW;
        this.samples = Math.min(this.samples + 1, WINDOW);

        if (this.cooldownLeft > 0) {
            this.cooldownLeft--;
            this.held++;
            return this.distance;
        }
        // Until the window is full the average is of too few frames to mean anything, and the first
        // frame of a session is usually a chunk loading.
        if (this.samples < WINDOW) {
            this.held++;
            return this.distance;
        }

        double average = average();
        if (average > DROP_ABOVE_MS && this.distance > MIN_DISTANCE) {
            this.distance--;
            this.cooldownLeft = COOLDOWN;
            this.drops++;
        } else if (average < RAISE_BELOW_MS && this.distance < this.maxDistance) {
            this.distance++;
            this.cooldownLeft = COOLDOWN;
            this.raises++;
        } else {
            this.held++;
        }
        return this.distance;
    }

    /** The mean frame time over the window. */
    public double average() {
        if (this.samples == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < this.samples; i++) {
            total += this.window[i];
        }
        return total / this.samples;
    }

    public int distance() {
        return this.distance;
    }

    public int maxDistance() {
        return this.maxDistance;
    }

    /**
     * Whether a frame time sits in the dead band.
     *
     * <p>Exposed so a test can check that the band is what stops the hunting, rather than taking the
     * controller's word for it.
     */
    public static boolean inDeadBand(double frameMs) {
        return frameMs >= RAISE_BELOW_MS && frameMs <= DROP_ABOVE_MS;
    }

    /**
     * Whether a run of frame times would leave the distance unchanged.
     *
     * <p>The property that matters: a load that sits in the dead band must not move the distance at all,
     * because moving it is what the player sees.
     */
    public boolean isStable(double frameMs, int frames) {
        int before = this.distance;
        for (int i = 0; i < frames; i++) {
            record(frameMs);
        }
        return this.distance == before;
    }

    public long drops() {
        return this.drops;
    }

    public long raises() {
        return this.raises;
    }

    public long held() {
        return this.held;
    }

    /**
     * Sections drawn at the current distance against the maximum, which is roughly the work saved.
     *
     * <p>Sections scale with the square of the distance, so dropping from 12 to 10 is not a sixth less
     * work but nearly a third.
     */
    public double loadFraction() {
        return (double) this.distance * this.distance
                / ((double) this.maxDistance * this.maxDistance);
    }

    public double savedPercent() {
        return 100.0 * (1.0 - loadFraction());
    }
}
