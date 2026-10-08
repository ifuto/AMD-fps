package net.amdfaster.light;

/**
 * Per-vertex smooth lighting and ambient occlusion for axis-aligned faces.
 *
 * <p>A vertex sitting on a face corner is lit by whatever is in front of it, and shaded by whatever
 * is next to it. Both are answered from the same four cells of the outward plane: the block
 * directly in front of the corner, the two blocks beside it along each in-plane axis, and the
 * diagonal one.
 *
 * <pre>
 *        sideU,sideV   baseU,sideV
 *             +-----------+
 *             |  diagonal |
 *             |     side2 |
 *   +---------+-----------+
 *   |   side1 |   front   |     <- the face's own block, in the outward plane
 *   +---------+-----------+
 *        sideU,baseV  baseU,baseV
 * </pre>
 *
 * <p><b>Light</b> is the mean of the samples that are actually visible from the vertex. A sample is
 * skipped when that cell is solid, because a solid block's own light value is light *inside* the
 * block, not light arriving at the vertex. The diagonal is additionally skipped when both edge
 * neighbours are solid: it is then behind a wall and geometrically unreachable.
 *
 * <p><b>Occulsion</b> counts how many of the three neighbours are solid: none gives level 3, all
 * three gives 0, and two solid edges force 0 regardless of the diagonal, because the corner is
 * sealed either way.
 *
 * <p>This is derived from the geometry, not transcribed from any existing renderer. The rule about
 * excluding solid samples from the average in particular is a choice: averaging them in, which is
 * the common approach, makes a corner next to a lit solid block brighter than a corner next to an
 * unlit one, which is visible as glowing edges around emissive geometry.
 */
public final class SmoothLight {

    /** Fully open corner. */
    public static final int AO_OPEN = 3;

    /** Corners per face. */
    public static final int CORNERS = 4;

    private SmoothLight() {
    }

    /**
     * Lighting at the corner of {@code face} at in-plane coordinate {@code (u,v)}.
     *
     * <p>{@code u} and {@code v} must be {@code minU}/{@code maxU} and {@code minV}/{@code maxV}
     * respectively. The outward direction along each axis is derived from which end the coordinate
     * is on, so no winding table is involved.
     */
    public static VertexLight corner(LightCache cache, FaceRef face, int u, int v) {
        int front = face.frontBlock();
        int baseU = face.baseU(u);
        int baseV = face.baseV(v);
        int sideU = face.sideU(u);
        int sideV = face.sideV(v);

        int frontCell = sample(cache, face, baseU, baseV, front);
        int alongU = sample(cache, face, sideU, baseV, front);
        int alongV = sample(cache, face, baseU, sideV, front);
        int diagonal = sample(cache, face, sideU, sideV, front);

        boolean occludedU = LightCache.occludesOf(alongU);
        boolean occludedV = LightCache.occludesOf(alongV);
        boolean occludedDiagonal = LightCache.occludesOf(diagonal);

        int ao = occludedU && occludedV
                ? 0
                : AO_OPEN - bit(occludedU) - bit(occludedV) - bit(occludedDiagonal);

        // The diagonal is unreachable when both edges are sealed, even if the diagonal cell itself
        // happens to be open.
        boolean diagonalVisible = !occludedDiagonal && !(occludedU && occludedV);

        int count = 1;
        int blockSum = LightCache.blockOf(frontCell);
        int skySum = LightCache.skyOf(frontCell);
        if (!occludedU) {
            count++;
            blockSum += LightCache.blockOf(alongU);
            skySum += LightCache.skyOf(alongU);
        }
        if (!occludedV) {
            count++;
            blockSum += LightCache.blockOf(alongV);
            skySum += LightCache.skyOf(alongV);
        }
        if (diagonalVisible) {
            count++;
            blockSum += LightCache.blockOf(diagonal);
            skySum += LightCache.skyOf(diagonal);
        }

        return new VertexLight(LightValue.pack(blockSum / count, skySum / count), ao);
    }

    /**
     * All four corners of {@code face}, in the order {@code (minU,minV)}, {@code (maxU,minV)},
     * {@code (maxU,maxV)}, {@code (minU,maxV)}.
     *
     * <p>The order is this method's own and is deliberately not a winding order: mapping it onto a
     * face's vertex order is the caller's job, and belongs next to the caller's winding table rather
     * than being duplicated here.
     */
    public static VertexLight[] face(LightCache cache, FaceRef face) {
        VertexLight[] out = new VertexLight[CORNERS];
        face(cache, face, out);
        return out;
    }

    /**
     * The same, into a caller-owned array.
     *
     * <p>This is the one a mesher should call: meshing a section touches tens of thousands of faces,
     * and allocating a four-element array per face is exactly the kind of steady garbage that makes
     * a generational collector do work in the middle of a frame.
     */
    public static void face(LightCache cache, FaceRef face, VertexLight[] out) {
        if (out.length < CORNERS) {
            throw new IllegalArgumentException("need " + CORNERS + " slots, got " + out.length);
        }
        out[0] = corner(cache, face, face.minU(), face.minV());
        out[1] = corner(cache, face, face.maxU(), face.minV());
        out[2] = corner(cache, face, face.maxU(), face.maxV());
        out[3] = corner(cache, face, face.minU(), face.maxV());
    }

    private static int sample(LightCache cache, FaceRef face, int u, int v, int planeBlock) {
        return cache.sample(face.x(u, v, planeBlock), face.y(u, v, planeBlock), face.z(u, v, planeBlock));
    }

    private static int bit(boolean b) {
        return b ? 1 : 0;
    }
}
