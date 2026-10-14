package net.amdfaster.block;

/**
 * The twelve piston collision shapes, built once and shared by every piston in the world.
 *
 * <p>A piston has six facings and two states, so twelve shapes. Vanilla recomputes them per block
 * state as the model is baked, and a piston-heavy farm has hundreds of block states that all resolve
 * to the same seven geometries -- because the six retracted facings are all just a full cube.
 *
 * <p>Counted rather than estimated: 12 states collapse to 7 distinct shapes, a 41.7% reduction, and
 * the collapse is entirely in the retracted case. Extended pistons are genuinely six different
 * geometries, since the shaft points somewhere.
 *
 * <p>That is a small saving on its own. The reason it is worth doing is that it is the same shape of
 * problem as every other cached geometry in the game, at a scale where the answer can be checked:
 * build the shapes, canonicalise them through {@link ShapeCache}, and the count that comes out is
 * either right or visibly wrong. Pistons are where the pattern gets proved before it is applied to
 * stairs, walls and fences, where there are hundreds of states and no way to eyeball the result.
 *
 * <h2>Why the boxes are what they are</h2>
 *
 * <p>An extended piston is a base three quarters of a block deep on the side away from the face, plus
 * a half-block-wide shaft filling the remaining quarter. The shaft is centred, so its cross section
 * runs from a quarter to three quarters on the two axes the piston does not face. That matches what
 * the player can walk on: the head is a platform and the shaft is climbable from the side.
 *
 * <p>The boxes are given in whatever order is natural to construct and {@link BoxShape} sorts them.
 * Without that sort the two boxes of an extended piston would sometimes compare unequal to an
 * identically built shape, and the cache would hold duplicates it could have collapsed.
 *
 * <p>Immutable and thread safe once {@link #instance()} has returned.
 */
public final class PistonShapes {

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /** Facings: two directions per axis. */
    public static final int FACINGS = 6;

    /** Retracted and extended. */
    public static final int STATES = 2;

    /** How deep the base of an extended piston is, in blocks. */
    public static final float BASE_THICKNESS = 0.75f;

    /** The shaft's cross section, centred in the block. */
    public static final float SHAFT_LOW = 0.25f;
    public static final float SHAFT_HIGH = 0.75f;

    private static volatile PistonShapes instance;

    private final BoxShape[] shapes;
    private final ShapeCache cache;

    private PistonShapes() {
        this.cache = new ShapeCache();
        this.shapes = new BoxShape[FACINGS * STATES];
        for (int axis = 0; axis < 3; axis++) {
            for (int d = 0; d < 2; d++) {
                boolean positive = d == 1;
                for (int extended = 0; extended < STATES; extended++) {
                    int index = index(axis, positive, extended == 1);
                    this.shapes[index] = this.cache.canonical(build(axis, positive, extended == 1));
                }
            }
        }
    }

    public static PistonShapes instance() {
        PistonShapes local = instance;
        if (local == null) {
            synchronized (PistonShapes.class) {
                local = instance;
                if (local == null) {
                    local = new PistonShapes();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * The collision shape for a piston.
     *
     * @param axis {@link #AXIS_X}, {@link #AXIS_Y} or {@link #AXIS_Z}
     * @param positive whether the piston faces the positive direction on that axis
     * @param extended whether the piston is pushed out
     */
    public BoxShape shape(int axis, boolean positive, boolean extended) {
        return this.shapes[index(axis, positive, extended)];
    }

    /** How many distinct shapes the twelve states actually produced. */
    public int distinctShapes() {
        return this.cache.size();
    }

    /** States offered, which is always twelve. */
    public long statesOffered() {
        return this.cache.offered();
    }

    /** States that shared a shape already built. */
    public long statesCollapsed() {
        return this.cache.duplicates();
    }

    private static int index(int axis, boolean positive, boolean extended) {
        checkAxis(axis);
        return (axis * 2 + (positive ? 1 : 0)) * STATES + (extended ? 1 : 0);
    }

    private static void checkAxis(int axis) {
        if (axis < AXIS_X || axis > AXIS_Z) {
            throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        }
    }

    /**
     * Builds one piston's boxes.
     *
     * <p>Package visible so the tests can check the geometry against the numbers in the class
     * comment rather than only against each other.
     */
    static BoxShape build(int axis, boolean positive, boolean extended) {
        // Checked here and not only in index. Without it an out-of-range axis falls through both
        // loops without matching, every box becomes a full cube, and the caller gets a piston that
        // collides like a solid block instead of an exception.
        checkAxis(axis);
        if (!extended) {
            return new BoxShape(0f, 0f, 0f, 1f, 1f, 1f);
        }
        float baseMin = positive ? 0f : 1f - BASE_THICKNESS;
        float baseMax = positive ? BASE_THICKNESS : 1f;
        float[] box = new float[12];
        // Base, then shaft. BoxShape sorts them, so the order here is not part of the shape's
        // identity and does not have to match the order another builder would use.
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                box[i] = baseMin;
                box[i + 3] = baseMax;
            } else {
                box[i] = 0f;
                box[i + 3] = 1f;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                box[6 + i] = positive ? BASE_THICKNESS : 0f;
                box[6 + i + 3] = positive ? 1f : 1f - BASE_THICKNESS;
            } else {
                box[6 + i] = SHAFT_LOW;
                box[6 + i + 3] = SHAFT_HIGH;
            }
        }
        return new BoxShape(box);
    }
}
