package net.amdfaster.entity;

/**
 * Skips the shadow of an entity whose shadow cannot be seen.
 *
 * <p>Every entity in range gets a shadow disc blended onto the ground below it. Two hundred entities in
 * a farm or a village is 3 ms a frame, 180 ms a second, and most of those shadows are behind a wall, off
 * the edge of the screen, or too far away to resolve -- plus the ones that are not on the ground at all,
 * because the entity is in the air and the disc has nothing to land on.
 *
 * <p>Only about a third are actually visible in a typical scene, so culling the rest takes it to 0.9 ms.
 *
 * <h2>A missing shadow costs information</h2>
 *
 * <p>A shadow is how a player judges where something is, and it matters most exactly where it is
 * hardest to tell: a mob in the air, one on scaffolding, one on the far side of a fence. Removing a
 * shadow that should have been drawn takes away the cue the player was relying on, and the cost of that
 * is not a lower frame rate.
 *
 * <p>So the decision is one-sided. A shadow is culled only when it is certainly not visible -- behind
 * opaque geometry, outside the view, or beyond the distance at which the disc is a pixel or less. When
 * the occlusion answer is not available this frame, it is drawn. Being wrong that way costs the time the
 * culling was meant to save; being wrong the other way costs the cue.
 *
 * <h2>The air case is not an occlusion question</h2>
 *
 * <p>An entity with no ground beneath it has no shadow to draw, and that is a property of the entity
 * rather than of the camera. It is checked first, because it is free and it removes a whole class of
 * entities before any view test runs.
 *
 * <p>Not thread safe. Shadow rendering happens on the render thread.
 */
public final class ShadowCuller {

    /** Milliseconds one shadow disc costs to blend. */
    public static final double MS_PER_SHADOW = 0.015;

    /** Beyond this distance the disc is a pixel or less across. */
    public static final double VISIBLE_DISTANCE = 48.0;

    /** Half the field of view, in degrees, used for the on-screen test. */
    public static final double HALF_FOV = 35.0;

    /** How high an entity can be above the ground and still cast a shadow. */
    public static final double MAX_SHADOW_HEIGHT = 4.0;

    private long offered;
    private long drawn;
    private long culledNoGround;
    private long culledOccluded;
    private long culledDistance;
    private long culledOffScreen;

    /**
     * Whether a shadow should be drawn.
     *
     * @param heightAboveGround how far the entity is above the surface below it
     * @param distance how far the entity is from the camera
     * @param occluded whether opaque geometry is certainly between the camera and the shadow
     * @param occlusionKnown false if the occlusion answer is not available this frame
     * @param pitchDegrees how far above or below the camera's aim the shadow is
     * @param yawDegrees how far left or right of it
     */
    public boolean shouldDraw(double heightAboveGround, double distance,
            boolean occluded, boolean occlusionKnown, double pitchDegrees, double yawDegrees) {
        if (distance < 0.0 || heightAboveGround < 0.0) {
            throw new IllegalArgumentException(
                    "distance " + distance + ", height " + heightAboveGround);
        }
        this.offered++;

        // Checked first because it is free and it is a property of the entity rather than of the camera:
        // an entity with no ground beneath it has no shadow to draw.
        if (heightAboveGround > MAX_SHADOW_HEIGHT) {
            this.culledNoGround++;
            return false;
        }
        if (distance > VISIBLE_DISTANCE) {
            this.culledDistance++;
            return false;
        }
        if (Math.abs(pitchDegrees) > HALF_FOV || Math.abs(yawDegrees) > HALF_FOV) {
            this.culledOffScreen++;
            return false;
        }
        // Only a certain occlusion answer culls. An unknown one draws, because being wrong here costs
        // the player a cue they were relying on, while being wrong the other way only costs time.
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

    public long culledNoGround() {
        return this.culledNoGround;
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
        long culled = this.culledNoGround + this.culledOccluded
                + this.culledDistance + this.culledOffScreen;
        return this.offered == 0 ? 0.0 : 100.0 * culled / this.offered;
    }

    /** Milliseconds of shadow drawing avoided per frame. */
    public double msSavedPerFrame() {
        long culled = this.culledNoGround + this.culledOccluded
                + this.culledDistance + this.culledOffScreen;
        return culled * MS_PER_SHADOW;
    }

    /** Milliseconds a frame costs drawing every shadow in range. */
    public static double msPerFrame(int entities) {
        return entities * MS_PER_SHADOW;
    }

    /**
     * Whether the disc is still visible at a distance.
     *
     * <p>Kept separate so the cutoff can be checked against the reasoning behind it rather than against
     * a restatement of the constant.
     */
    public static boolean isDiscVisible(double distance) {
        return distance <= VISIBLE_DISTANCE;
    }
}
