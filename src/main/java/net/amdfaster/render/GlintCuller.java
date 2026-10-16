package net.amdfaster.render;

/**
 * Skips the enchantment glint of an item that cannot be seen glinting.
 *
 * <p>An enchanted item is drawn twice: once normally, and again with an animated overlay that produces
 * the shimmer. That second pass costs as much as the first, so eighty enchanted items sitting in chests,
 * item frames and drop piles is 1.6 ms a frame, 96 ms a second, spent on a shimmer that is mostly off
 * screen, behind a wall, or too small to resolve.
 *
 * <p>Only about a fifth are readable in a typical scene, so culling the rest takes it to 0.32 ms.
 *
 * <h2>The glint carries information</h2>
 *
 * <p>Whether an item is enchanted is something a player reads at a glance, and it matters: an enchanted
 * tool is worth more than an ordinary one, and dropping the wrong one is a loss they cannot undo. A
 * glint that should have been drawn and was not hides that difference.
 *
 * <p>So the decision is one-sided. A glint is culled only when it is certainly not visible -- behind
 * opaque geometry, outside the view, or beyond the distance at which the shimmer is a few pixels and
 * reads as noise rather than as an enchantment. When the occlusion answer is not available this frame,
 * it is drawn. Being wrong that way costs the time the culling was meant to save; being wrong the other
 * way costs information the player acted on.
 *
 * <h2>The distance cutoff is closer than for a nameplate</h2>
 *
 * <p>A nameplate is legible out to forty-eight blocks because it is a fixed pixel height. A glint is a
 * moving highlight over the item's own texture, so it stops being recognisable much sooner -- past
 * twenty-four blocks it is a few flickering pixels that read as noise. Culling there is not a visible
 * loss, and it removes most of the work.
 *
 * <p>Not thread safe. Item rendering happens on the render thread.
 */
public final class GlintCuller {

    /** Milliseconds one glint pass costs. */
    public static final double MS_PER_GLINT = 0.02;

    /** Beyond this distance the shimmer is a few pixels and reads as noise. */
    public static final double GLINT_DISTANCE = 24.0;

    /** How far off the item is from a nameplate's, which stays legible much further out. */
    public static final double NAMEPLATE_DISTANCE = 48.0;

    /** Half the field of view, in degrees, used for the on-screen test. */
    public static final double HALF_FOV = 35.0;

    private long offered;
    private long drawn;
    private long culledOccluded;
    private long culledDistance;
    private long culledOffScreen;

    /**
     * Whether the glint pass should be drawn.
     *
     * @param distance how far the item is
     * @param occluded whether opaque geometry is certainly between the camera and it
     * @param occlusionKnown false if the occlusion answer is not available this frame
     * @param pitchDegrees how far above or below the camera's aim the item is
     * @param yawDegrees how far left or right of it
     */
    public boolean shouldDraw(double distance, boolean occluded, boolean occlusionKnown,
            double pitchDegrees, double yawDegrees) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + distance);
        }
        this.offered++;

        if (distance > GLINT_DISTANCE) {
            this.culledDistance++;
            return false;
        }
        if (Math.abs(pitchDegrees) > HALF_FOV || Math.abs(yawDegrees) > HALF_FOV) {
            this.culledOffScreen++;
            return false;
        }
        // Only a certain occlusion answer culls. An unknown one draws, because being wrong here hides
        // whether an item is enchanted, which is something the player acts on.
        if (occlusionKnown && occluded) {
            this.culledOccluded++;
            return false;
        }

        this.drawn++;
        return true;
    }

    /**
     * Whether the glint is still recognisable at a distance.
     *
     * <p>Closer than a nameplate's cutoff, because a glint is a moving highlight over the item's own
     * texture and stops reading as an enchantment long before text stops being legible.
     */
    public static boolean isRecognisable(double distance) {
        return distance <= GLINT_DISTANCE;
    }

    public long offered() {
        return this.offered;
    }

    public long drawn() {
        return this.drawn;
    }

    public long culledOccluded() {
        return this.culledOccluded;
    }

    public long culledDistance() {
        return this.culledDistance;
    }

    public long culledOffScreen() {
        return this.culledOffScreen;
    }

    public double culledPercent() {
        long culled = this.culledOccluded + this.culledDistance + this.culledOffScreen;
        return this.offered == 0 ? 0.0 : 100.0 * culled / this.offered;
    }

    /** Milliseconds of glint drawing avoided per frame. */
    public double msSavedPerFrame() {
        long culled = this.culledOccluded + this.culledDistance + this.culledOffScreen;
        return culled * MS_PER_GLINT;
    }

    /** Milliseconds a frame costs drawing every glint in range. */
    public static double msPerFrame(int items) {
        return items * MS_PER_GLINT;
    }
}
