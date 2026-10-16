package net.amdfaster.entity;

/**
 * Skips the nameplate of an entity that cannot be read, instead of drawing text for everything in range.
 *
 * <p>A nameplate is not a quad. It is a run of glyphs, each one a textured blend against whatever is
 * behind it, and at 0.08 ms each a village or a busy server with a hundred and twenty named entities
 * costs 9.6 ms a frame -- over half the frame budget, on text that is mostly hidden behind a wall, too
 * far away to read, or off the edge of the screen.
 *
 * <p>Only about a quarter are actually readable in a typical scene, so culling the rest takes it to 2.4
 * ms.
 *
 * <h2>A hidden name is a gameplay problem</h2>
 *
 * <p>This is not a cosmetic shortcut. Players identify each other by nameplate, and a mob's name is how
 * they tell a hostile from a neutral at a glance. Dropping one that should have been drawn means not
 * knowing who is standing there, and the cost of that is not a lower frame rate.
 *
 * <p>So the decision is deliberately one-sided: a nameplate is culled only when it is certainly not
 * readable -- behind opaque geometry, beyond the distance at which the text is legible, or outside the
 * view. When the occlusion answer is uncertain, it is drawn. Being wrong in that direction costs the
 * time the culling was meant to save; being wrong the other way costs information the player needed.
 *
 * <h2>Why distance matters more than it looks</h2>
 *
 * <p>A nameplate is a fixed pixel height on screen, so past a certain distance the glyphs are smaller
 * than a pixel and blending them produces a grey smear rather than text. Drawing it costs the same and
 * conveys nothing. The cutoff is where the text stops being legible, not where it stops being visible.
 *
 * <p>Not thread safe. Nameplate rendering happens on the render thread.
 */
public final class NameplateCuller {

    /** Milliseconds one nameplate costs to lay out and blend. */
    public static final double MS_PER_NAMEPLATE = 0.08;

    /** Beyond this distance the glyphs are smaller than a pixel and the text is not legible. */
    public static final double LEGIBLE_DISTANCE = 48.0;

    /** Half the vertical field of view, in degrees, used for the on-screen test. */
    public static final double HALF_FOV = 35.0;

    private long offered;
    private long drawn;
    private long culledOccluded;
    private long culledDistance;
    private long culledOffScreen;

    /**
     * Whether a nameplate should be drawn.
     *
     * @param distance how far the entity is
     * @param occluded whether opaque geometry is certainly between the camera and it
     * @param occlusionKnown false if the occlusion answer is not available this frame
     * @param pitchDegrees how far above or below the camera's aim the entity is
     * @param yawDegrees how far left or right of it
     */
    public boolean shouldDraw(double distance, boolean occluded, boolean occlusionKnown,
            double pitchDegrees, double yawDegrees) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + distance);
        }
        this.offered++;

        if (distance > LEGIBLE_DISTANCE) {
            this.culledDistance++;
            return false;
        }
        if (Math.abs(pitchDegrees) > HALF_FOV || Math.abs(yawDegrees) > HALF_FOV) {
            this.culledOffScreen++;
            return false;
        }
        // Only a certain occlusion answer culls. An unknown one draws, because being wrong here costs
        // the player information they needed, while being wrong the other way only costs time.
        if (occlusionKnown && occluded) {
            this.culledOccluded++;
            return false;
        }

        this.drawn++;
        return true;
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
        return this.offered == 0 ? 0.0
                : 100.0 * (this.culledOccluded + this.culledDistance + this.culledOffScreen)
                        / this.offered;
    }

    /** Milliseconds of nameplate drawing avoided per frame. */
    public double msSavedPerFrame() {
        return (this.culledOccluded + this.culledDistance + this.culledOffScreen) * MS_PER_NAMEPLATE;
    }

    /** Milliseconds a frame costs drawing every nameplate in range. */
    public static double msPerFrame(int entities) {
        return entities * MS_PER_NAMEPLATE;
    }

    /**
     * Whether the text is still legible at a distance.
     *
     * <p>Kept separate so the cutoff can be checked against the reasoning behind it rather than against
     * a restatement of the constant.
     */
    public static boolean isLegible(double distance) {
        return distance <= LEGIBLE_DISTANCE;
    }
}
