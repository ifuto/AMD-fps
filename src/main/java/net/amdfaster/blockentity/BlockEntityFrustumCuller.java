package net.amdfaster.blockentity;

/**
 * Leaves block entities that are outside the view out of the draw list.
 *
 * <p>Chunk culling decides which sections to draw, and a section is drawn if any part of it is visible. But
 * a section sixteen blocks across contains block entities all over it, and with a seventy degree field of
 * view only about a fifth of the horizontal circle is on screen at once. A storeroom spread through a
 * section has most of its chests outside the view, and every one of them is still assembled and drawn
 * because the section they are in passed the chunk test.
 *
 * <p>Testing each block entity against the view and dropping the ones outside removes that work. It is a
 * different question from occlusion -- a chest can be plainly visible through open air and still be behind
 * the player -- and both tests apply.
 *
 * <h2>The test uses the block entity's bounds, not its centre</h2>
 *
 * <p>A chest is a box and a sign is a plane, and either can straddle the edge of the view. Testing the
 * centre point would drop a chest whose left half is on screen, and the player would see it vanish as they
 * turned toward it -- the opposite of what turning toward something should do. Testing the bounds means the
 * entity is dropped only when none of it can be on screen.
 *
 * <h2>A margin at the edge</h2>
 *
 * <p>The view boundary is where the test is least certain: the projection is steepest there, and a block
 * entity sitting exactly on it would flip between drawn and dropped as the camera moved by fractions of a
 * degree, which shows up as flickering at the edge of the screen. Keeping a small margin means an entity
 * has to be clearly outside before it is dropped, and the ones near the boundary are simply drawn, which
 * costs a little and looks correct.
 *
 * <h2>Why this is not the same as chunk culling</h2>
 *
 * <p>Chunk culling is coarse by design -- it answers for sixteen blocks at a time, because that is the unit
 * the mesh is built in. A block entity is a single object inside that unit, and the coarse answer cannot
 * distinguish it from its neighbours. Asking the chunk culler to be finer would mean rebuilding the
 * sections it exists to skip.
 *
 * <p>Not thread safe. Culling runs on the render thread.
 */
public final class BlockEntityFrustumCuller {

    /** Milliseconds one bounds test costs. */
    public static final double MS_PER_TEST = 0.0005;

    /** Milliseconds drawing one block entity costs. */
    public static final double MS_PER_DRAW = 0.01;

    /** Degrees of margin kept at the edge of the view. */
    public static final double EDGE_MARGIN_DEGREES = 2.0;

    private long tested;
    private long culled;
    private long drawn;
    private long inMargin;

    /**
     * Whether a block entity is inside the view.
     *
     * @param angleFromCentre how far the entity's nearest point is from the centre of the view, in degrees
     * @param halfFieldOfView half the horizontal field of view, in degrees
     * @return true if it should be drawn
     */
    public boolean shouldDraw(double angleFromCentre, double halfFieldOfView) {
        if (angleFromCentre < 0.0) {
            throw new IllegalArgumentException(
                    "a negative angle: " + angleFromCentre);
        }
        if (halfFieldOfView <= 0.0) {
            throw new IllegalArgumentException(
                    "a non-positive field of view: " + halfFieldOfView);
        }
        this.tested++;

        if (angleFromCentre <= halfFieldOfView) {
            this.drawn++;
            return true;
        }
        if (angleFromCentre <= halfFieldOfView + EDGE_MARGIN_DEGREES) {
            // Just outside. The projection is steepest at the edge and an entity sitting exactly on it
            // would flip between drawn and dropped as the camera moved by fractions of a degree, which
            // shows up as flickering. Drawing it costs a little and looks correct.
            this.inMargin++;
            this.drawn++;
            return true;
        }
        this.culled++;
        return false;
    }

    /**
     * The angle from the centre of the view to the nearest point of a box.
     *
     * <p>The nearest point rather than the centre, so an entity straddling the edge of the view is kept.
     * Dropping a chest whose left half is on screen would make it vanish as the player turned toward it,
     * which is the opposite of what turning toward something should do.
     *
     * @param centreAngle the angle to the centre of the box
     * @param halfWidth the angular half-width of the box as seen from the camera
     */
    public static double nearestAngle(double centreAngle, double halfWidth) {
        double nearest = Math.abs(centreAngle) - halfWidth;
        return Math.max(0.0, nearest);
    }

    public long tested() {
        return this.tested;
    }

    public long culled() {
        return this.culled;
    }

    public long drawn() {
        return this.drawn;
    }

    /** How many were drawn only because they were within the edge margin. */
    public long inMargin() {
        return this.inMargin;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of drawing avoided. */
    public double msSaved() {
        return this.culled * MS_PER_DRAW;
    }

    /** Milliseconds the bounds tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /** Milliseconds a frame costs drawing every block entity in a visible section. */
    public double msWithoutCulling() {
        return this.tested * MS_PER_DRAW;
    }

    /**
     * The share of the block entity drawing cost avoided, net of the tests.
     *
     * <p>Zero before any block entity has been tested. A culler that has tested nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        double net = msSaved() - msTesting();
        return 100.0 * Math.max(0.0, net) / without;
    }
}
