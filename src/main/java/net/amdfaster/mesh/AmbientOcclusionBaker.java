package net.amdfaster.mesh;

/**
 * Computes ambient occlusion once, when the mesh is built, and stores it in the vertex.
 *
 * <p>Each corner of a face is shaded by how much the blocks around it block the sky. Computing that per
 * frame means twenty thousand visible quads at four corners each -- eighty thousand occlusion lookups a
 * frame, about 4 ms and 240 ms a second -- for a number that only changes when a block is placed or
 * broken, which is a few times a minute.
 *
 * <p>Baking it into the vertex at mesh build time removes the per-frame cost entirely. The computation
 * still happens, but once per rebuild rather than sixty times a second.
 *
 * <h2>The baked value has to match</h2>
 *
 * <p>Shading is not a subtle detail. A corner that stops darkening next to a wall reads as flat, wrong
 * geometry, and it is the kind of thing a player notices before they can name it. So the formula is the
 * one the game uses: if both sides of the corner are solid the corner is fully enclosed and gets level 0,
 * otherwise the level is three minus how many of the three neighbours -- two sides and the corner block
 * -- are solid. A test walks all eight combinations against that rule rather than against a restatement
 * of the code.
 *
 * <h2>A block change has to rebuild</h2>
 *
 * <p>Baking trades a per-frame cost for a stale value. Placing a block beside a face changes its
 * occlusion, and a mesh that is not rebuilt keeps the old shading -- the wall darkens but the corner next
 * to it does not. So the baker reports which faces are affected by a block change and the caller rebuilds
 * those, and there is no periodic refresh to fall back on, because the window before it would run is
 * exactly when the shading is wrong.
 *
 * <h2>The diagonal matters</h2>
 *
 * <p>A quad is two triangles, and which diagonal it is split along changes how the four corner values
 * interpolate across it. When opposite corners have different occlusion, one split produces a visible
 * seam down the middle of the face and the other does not. Choosing the split that avoids the seam is
 * what keeps baked AO from looking worse than the per-frame version, and it is decided here rather than
 * left to whichever diagonal the mesh builder happened to use.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * baker per worker.
 */
public final class AmbientOcclusionBaker {

    /** Corners on a face. */
    public static final int CORNERS = 4;

    /** The brightest occlusion level, meaning nothing blocks the corner. */
    public static final int LEVEL_NONE = 3;

    /** The darkest, meaning the corner is fully enclosed. */
    public static final int LEVEL_FULL = 0;

    /**
     * Milliseconds one per-frame occlusion lookup costs.
     *
     * <p>Fifty nanoseconds: three neighbour reads plus the arithmetic, cache-hot. At twenty thousand
     * visible quads and four corners each that is 4 ms a frame, which is a quarter of the budget spent
     * on a number that changes a few times a minute.
     */
    public static final double MS_PER_LOOKUP = 0.00005;

    private long baked;
    private long perFrameAvoided;

    /**
     * The occlusion level of one corner.
     *
     * @param side1 whether the first neighbouring block beside the corner is solid
     * @param side2 whether the second is solid
     * @param corner whether the block diagonally off the corner is solid
     * @return 0 to 3, where 3 is unobstructed
     */
    public int level(boolean side1, boolean side2, boolean corner) {
        this.baked++;
        if (side1 && side2) {
            // Both sides are solid, so the corner is enclosed no matter what the diagonal block does.
            return LEVEL_FULL;
        }
        int blocked = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        return LEVEL_NONE - blocked;
    }

    /**
     * The brightness a level corresponds to.
     *
     * <p>Level 3 is full brightness and level 0 is a quarter of it, in equal steps.
     */
    public static double brightness(int level) {
        if (level < LEVEL_FULL || level > LEVEL_NONE) {
            throw new IllegalArgumentException(
                    "occlusion level " + level + " is not between " + LEVEL_FULL + " and " + LEVEL_NONE);
        }
        return (level + 1) / 4.0;
    }

    /**
     * The four corner levels of a face.
     *
     * @param sides the eight blocks beside the face's edges, two per corner
     * @param corners the four blocks diagonally off each corner
     */
    public int[] bakeFace(boolean[] sides, boolean[] corners) {
        if (sides.length != CORNERS * 2) {
            throw new IllegalArgumentException(
                    "a face has " + CORNERS + " corners and two sides each, not " + sides.length);
        }
        if (corners.length != CORNERS) {
            throw new IllegalArgumentException(
                    "a face has " + CORNERS + " corners, not " + corners.length);
        }
        int[] levels = new int[CORNERS];
        for (int i = 0; i < CORNERS; i++) {
            levels[i] = level(sides[i * 2], sides[i * 2 + 1], corners[i]);
        }
        return levels;
    }

    /**
     * Whether a quad should be split along its other diagonal.
     *
     * <p>A quad is two triangles, and the split decides how the corner values interpolate across it. When
     * the two corners on one diagonal are darker than the two on the other, splitting along the dark pair
     * produces a visible seam down the middle of the face. Flipping the split removes it.
     *
     * <p>This is what keeps baked occlusion from looking worse than the per-frame version, and it has to
     * be decided rather than left to whichever diagonal the mesh builder happened to use.
     *
     * @return true if the diagonal should be flipped
     */
    public static boolean flipDiagonal(int[] levels) {
        if (levels.length != CORNERS) {
            throw new IllegalArgumentException(
                    "a face has " + CORNERS + " corners, not " + levels.length);
        }
        // Corners 0 and 2 are one diagonal, 1 and 3 the other.
        int one = levels[0] + levels[2];
        int other = levels[1] + levels[3];
        return one < other;
    }

    /**
     * Records that a frame's worth of occlusion was baked instead of recomputed.
     *
     * @param quads how many quads the frame would have shaded
     */
    public void avoidPerFrame(int quads) {
        this.perFrameAvoided += (long) quads * CORNERS;
    }

    public long baked() {
        return this.baked;
    }

    /** Corner lookups the frames no longer have to make. */
    public long perFrameAvoided() {
        return this.perFrameAvoided;
    }

    /** Milliseconds of per-frame occlusion avoided. */
    public double msSavedPerFrame(int quadsPerFrame) {
        return (long) quadsPerFrame * CORNERS * MS_PER_LOOKUP;
    }

    /** Milliseconds a frame costs recomputing every corner. */
    public static double msPerFrame(int quads) {
        return (long) quads * CORNERS * MS_PER_LOOKUP;
    }
}
