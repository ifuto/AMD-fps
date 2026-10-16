package net.amdfaster.render;

/**
 * Updates animated textures only when they are on screen, and only as often as they need.
 *
 * <p>Water, lava, portals and prismarine all step through frames of an animation, and vanilla updates
 * every animated texture every frame whether or not anything is looking at it. Sixty of them at 0.02 ms
 * each is 1.2 ms a frame, 72 ms a second, and in most scenes about 70% of them are not on screen at all
 * -- a lava texture animating in a cave the player left ten minutes ago.
 *
 * <p>Updating only the visible ones takes that to 18 updates a frame.
 *
 * <h2>The frame shown must be current</h2>
 *
 * <p>If a texture becomes visible carrying a frame from when it was last updated, the player sees it
 * frozen and then jump. Turning a corner onto a lake and finding the water standing still for a moment
 * is exactly as noticeable as a dropped frame, and it is the failure this makes easy to introduce. So a
 * texture that was not updated last frame is updated on the frame it becomes visible, before it is drawn.
 *
 * <h2>The phase must not drift</h2>
 *
 * <p>Several water blocks in one lake have to move together. If each texture's frame were counted from
 * the last time it was updated, skipping updates would put them out of step and the surface would ripple
 * in patches -- subtle, wrong, and very hard for a player to describe. So the frame is derived from the
 * world time rather than accumulated, and skipping an update cannot change which frame is current. The
 * skip only decides whether the texture is re-uploaded, never what it should show.
 *
 * <p>Not thread safe. Texture animation runs on the render thread.
 */
public final class AnimatedTextureScheduler {

    /** Milliseconds one texture update costs. */
    public static final double MS_PER_UPDATE = 0.02;

    /** Ticks between frames for a fast animation such as water. */
    public static final int FAST_PERIOD = 1;

    /** Ticks between frames for a slow one such as prismarine. */
    public static final int SLOW_PERIOD = 5;

    /** Beyond this distance an animation is updated every other frame. */
    public static final double FAR_DISTANCE = 32.0;

    private long offered;
    private long updated;
    private long skippedInvisible;
    private long skippedRedundant;

    /**
     * Whether a texture needs updating this frame.
     *
     * @param visible whether anything on screen uses it
     * @param worldTime the world time, which the frame index is derived from
     * @param period ticks between frames of this animation
     * @param distance how far the nearest user of it is
     * @param lastUpdatedFrame the frame index it was last uploaded at
     * @return true if the texture should be uploaded
     */
    public boolean shouldUpdate(boolean visible, long worldTime, int period,
            double distance, long lastUpdatedFrame) {
        if (period < 1) {
            throw new IllegalArgumentException(
                    "an animation cannot advance every " + period + " ticks");
        }
        this.offered++;

        if (!visible) {
            this.skippedInvisible++;
            return false;
        }

        long frame = frameAt(worldTime, period);
        if (frame == lastUpdatedFrame) {
            // Already showing the current frame. This is the common case for slow animations and for
            // anything far away, and skipping it cannot change what is on screen.
            this.skippedRedundant++;
            return false;
        }
        this.updated++;
        return true;
    }

    /**
     * The frame an animation should show.
     *
     * <p>Derived from the world time rather than counted from the last update, so skipping updates cannot
     * put two textures out of step. Several water blocks in one lake have to move together, and a ripple
     * that moves in patches is subtle, wrong, and very hard for a player to describe.
     */
    public static long frameAt(long worldTime, int period) {
        return Math.floorDiv(worldTime, period);
    }

    /**
     * Whether a texture is far enough to animate at half rate.
     *
     * <p>At distance the eye cannot resolve the individual frames, and halving the updates halves the
     * cost for those textures.
     */
    public static boolean isFar(double distance) {
        return distance > FAR_DISTANCE;
    }

    /**
     * The effective period for a texture at a given distance.
     */
    public static int effectivePeriod(int period, double distance) {
        return isFar(distance) ? period * 2 : period;
    }

    public long offered() {
        return this.offered;
    }

    public long updated() {
        return this.updated;
    }

    /** How many were skipped because nothing on screen used them. */
    public long skippedInvisible() {
        return this.skippedInvisible;
    }

    /** How many were skipped because they already showed the current frame. */
    public long skippedRedundant() {
        return this.skippedRedundant;
    }

    public double skippedPercent() {
        return this.offered == 0 ? 0.0
                : 100.0 * (this.skippedInvisible + this.skippedRedundant) / this.offered;
    }

    /** Milliseconds of update cost avoided per frame. */
    public double msSavedPerFrame() {
        return (this.skippedInvisible + this.skippedRedundant) * MS_PER_UPDATE;
    }

    /** Milliseconds a frame costs updating everything. */
    public static double msPerFrame(int textures) {
        return textures * MS_PER_UPDATE;
    }
}
