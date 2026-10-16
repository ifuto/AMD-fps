package net.amdfaster.cull;

/**
 * Rejects a whole meshlet with one bitwise test when none of its faces point at the camera.
 *
 * <p>Backface culling normally happens in the rasteriser, after the vertex shader has already transformed
 * every vertex. The work of shading vertices that produce triangles facing away is spent regardless, which
 * on geometry-heavy scenes is most of the geometry cost. Rejecting a cluster before it reaches that stage
 * saves the vertex work as well as the rasterisation.
 *
 * <p>Voxels make this unusually cheap. A block has six faces and a meshlet is made of block faces, so the set
 * of directions a meshlet can possibly face is a subset of six -- expressible as six bits. Testing whether any
 * of those directions faces the camera is a bitwise AND against a mask derived from the view, and if the
 * result is zero the whole meshlet is behind the camera and can be dropped.
 *
 * <h2>The mask is a union, so it is conservative</h2>
 *
 * <p>A meshlet's mask is the OR of its triangles' faces, which means it claims every direction any of them
 * could face and possibly more. That is the safe direction: a mask that is too generous keeps a meshlet that
 * could have been dropped, which costs a little, while one that is too narrow drops a meshlet that was
 * visible, which is a hole in the world.
 *
 * <h2>A mixed meshlet is not culled</h2>
 *
 * <p>A meshlet containing both a north face and a south face has a mask with two bits set, and no view
 * direction makes both point away -- so it survives every test. That is correct and it is common, because
 * greedy meshing tends to group coplanar faces together but not always. The saving comes from the meshlets
 * that are uniform, which on a flat wall or a floor is most of them.
 *
 * <h2>Why this is not the same as the boundary mask</h2>
 *
 * <p>{@link SectionBoundaryMask} says which faces of a section are open, so the culler knows whether to look
 * inside it at all. This says which faces a meshlet contains, so the culler knows whether the meshlet can be
 * facing away. They answer different questions at different scales and both apply: a section with an open
 * face is examined, and within it the meshlets facing away are dropped.
 *
 * <h2>Why it is cheap enough to always run</h2>
 *
 * <p>The test is one AND and one comparison per meshlet, against a sphere test or a cone test that needs
 * several multiplications. On a scene with thousands of meshlets the difference is whether the cull is a
 * measurable part of the frame or not, and a cull that costs too much is a cull that gets turned off.
 *
 * <p>Not thread safe. Culling runs on worker threads, so a caller using this from several needs one instance
 * per worker.
 */
public final class MeshletFaceMaskCuller {

    /** The six face directions, as bits. */
    public static final int DOWN = 1;
    public static final int UP = 2;
    public static final int NORTH = 4;
    public static final int SOUTH = 8;
    public static final int WEST = 16;
    public static final int EAST = 32;

    /** All six faces, which is the mask of a meshlet that cannot be culled at all. */
    public static final int ALL_FACES = 63;

    /** Milliseconds one mask test costs. */
    public static final double MS_PER_TEST = 0.00001;

    /** Milliseconds shading one meshlet's vertices costs, which is what culling avoids. */
    public static final double MS_PER_MESHLET = 0.004;

    private long tested;
    private long culled;
    private long kept;

    /**
     * The mask of directions the camera can see, from its facing.
     *
     * <p>A camera looking north cannot see north faces -- they point away from it -- so north is absent from
     * the mask and present in the mask of a camera looking south.
     *
     * @param vertical which way the camera looks up or down, or zero if level
     */
    public static int visibleMask(int facing, int vertical) {
        int mask = 0;
        // A camera looking north sees south faces, and so on for each pair.
        if (facing != NORTH) {
            mask |= NORTH;
        }
        if (facing != SOUTH) {
            mask |= SOUTH;
        }
        if (facing != WEST) {
            mask |= WEST;
        }
        if (facing != EAST) {
            mask |= EAST;
        }
        if (vertical > 0) {
            // Looking up, the down faces point away.
            mask |= UP;
        } else if (vertical < 0) {
            mask |= DOWN;
        } else {
            mask |= UP | DOWN;
        }
        return mask;
    }

    /**
     * Whether a meshlet should be drawn.
     *
     * @param meshletMask the union of its triangles' face directions
     * @param cameraMask from {@link #visibleMask}
     * @return true if any of its faces could be facing the camera
     */
    public boolean shouldDraw(int meshletMask, int cameraMask) {
        if (meshletMask < 0 || meshletMask > ALL_FACES) {
            throw new IllegalArgumentException(
                    "a face mask has six bits, not " + Integer.toBinaryString(meshletMask));
        }
        this.tested++;

        if ((meshletMask & cameraMask) == 0) {
            // None of the directions this meshlet can face is one the camera can see, so every triangle in it
            // points away.
            this.culled++;
            return false;
        }
        this.kept++;
        return true;
    }

    public long tested() {
        return this.tested;
    }

    public long culled() {
        return this.culled;
    }

    public long kept() {
        return this.kept;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of vertex shading avoided. */
    public double msSaved() {
        return this.culled * MS_PER_MESHLET;
    }

    /** Milliseconds the tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /** Milliseconds drawing every meshlet would cost. */
    public double msWithoutCulling() {
        return this.tested * MS_PER_MESHLET;
    }

    /**
     * The share of the meshlet shading avoided, net of the tests.
     *
     * <p>Zero before any meshlet has been tested. A culler that has tested nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The test cost is subtracted, because a cull that costs as much as the shading it avoids saves
     * nothing and should report that.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved() - msTesting()) / without;
    }
}
