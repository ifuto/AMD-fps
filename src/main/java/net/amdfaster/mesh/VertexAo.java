package net.amdfaster.mesh;

/**
 * Per-vertex ambient occlusion for voxel faces, and the diagonal rule that keeps it from looking wrong.
 *
 * <p>Ambient occlusion here is not the screen-space kind. It is computed at mesh time from the blocks
 * adjacent to a vertex, which makes it view independent, free at render time, and exact for the case it
 * models: a vertex tucked into a corner receives less ambient light than one on an open face. There are
 * only four distinct values per vertex, by symmetry, so the whole thing is a few integer operations
 * during a build that is already walking every cell.
 *
 * <p>The rule, for a vertex with two edge neighbours and one corner neighbour:
 *
 * <p>If <b>both edge neighbours are solid, the value is zero</b> regardless of the corner. The corner
 * is unreachable in that configuration and counting it would brighten a vertex that is enclosed on both
 * sides. Leaving that case out is the single most common way to get voxel AO subtly wrong, and it shows
 * as corners that glow faintly where they should be dark.
 *
 * <p>Otherwise the value is three minus the number of occluding neighbours.
 *
 * <p><b>The diagonal.</b> A quad is two triangles, and which pair of corners shares the diagonal decides
 * how the four corner values are interpolated across the face. With a fixed diagonal, a face whose
 * corners alternate bright and dark interpolates differently in each triangle, and the seam between them
 * is visible as a diagonal line that is not in the geometry. The fix costs nothing: put the diagonal
 * through the darker pair. This is not a quality option -- without it the AO is correct at every vertex
 * and still looks wrong.
 *
 * <p>Stateless.
 */
public final class VertexAo {

    /** No occlusion at all: an open face away from any corner. */
    public static final int NONE = 3;

    /** Fully enclosed on both edges. */
    public static final int FULL = 0;

    private VertexAo() {
    }

    /**
     * The occlusion value for one vertex.
     *
     * @param side1  the first edge neighbour is solid
     * @param side2  the second edge neighbour is solid
     * @param corner the diagonal neighbour is solid
     * @return 0 to 3, where 3 is unoccluded
     */
    public static int compute(boolean side1, boolean side2, boolean corner) {
        if (side1 && side2) {
            // Both edges solid: the corner cell cannot be reached, so it contributes nothing and the
            // vertex is fully occluded. Testing the corner here would give 1 or 2 for a vertex that is
            // enclosed, which renders as a faint glow in exactly the places AO exists to darken.
            return FULL;
        }
        int occluded = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        return NONE - occluded;
    }

    /**
     * Whether the quad's diagonal should be flipped from the 0-2 pair to the 1-3 pair.
     *
     * <p>Flip when the default pair is the brighter one, so the diagonal runs through the darker pair.
     * The reason is interpolation: each triangle blends its three corners, and a face with alternating
     * bright and dark corners produces a different gradient in each triangle unless the split runs along
     * the darker edge. The seam is visible as a diagonal line across an otherwise flat face.
     *
     * @param ao0 occlusion at corner 0
     * @param ao1 occlusion at corner 1
     * @param ao2 occlusion at corner 2
     * @param ao3 occlusion at corner 3
     * @return true if the diagonal should run between corners 1 and 3
     */
    public static boolean shouldFlipDiagonal(int ao0, int ao1, int ao2, int ao3) {
        return ao0 + ao2 > ao1 + ao3;
    }

    /**
     * The brightness multiplier for an occlusion value.
     *
     * <p>Spread across the range rather than linear in the value, so that the first step of occlusion is
     * visible and the last does not go fully black. Zero still renders as something, because a fully
     * enclosed vertex is in shadow, not absent.
     */
    public static float shade(int ao) {
        return switch (ao) {
            case 0 -> 0.4f;
            case 1 -> 0.6f;
            case 2 -> 0.8f;
            default -> 1.0f;
        };
    }

    /**
     * Packs an occlusion value into the low two bits of a light word.
     *
     * <p>The quad already carries a packed light value per corner, and occlusion needs two bits. Keeping
     * them together means one integer travels per corner instead of two, which matters when a section
     * produces thousands of quads.
     */
    public static int packIntoLight(int light, int ao) {
        return (light & ~0x3) | (ao & 0x3);
    }

    /** Extracts the occlusion value from a packed light word. */
    public static int aoFromLight(int packed) {
        return packed & 0x3;
    }
}
