package net.amdfaster.mesh;

import java.util.Arrays;

/**
 * Recognises the cube face every full block emits, so the mesher can reference it instead of
 * rebuilding it.
 *
 * <p>Most of a solid chunk is stone, and every stone face is the same four corners of a unit cube.
 * The mesher emits them anyway, once per exposed face, because it does not know that the quad it is
 * holding is the same shape as the ten thousand it has already emitted. A section with 15% of its
 * faces exposed is around 600 quads and 2,400 vertices, and the geometry of every one of them is one
 * of six shapes.
 *
 * <p>What identifying them buys is not the arithmetic of building a face, which is cheap. It is that
 * a quad known to be a template can be emitted as an index, an offset and a light value rather than
 * four full vertices, and that a renderer can instance it rather than draw it. Both of those need to
 * know the quad is standard before they can do anything, and that is what this answers.
 *
 * <h2>Matching has to be exact or the reuse is visible</h2>
 *
 * <p>A quad that is almost a full face -- a block model inset by a sixteenth, a connected-texture
 * variant, a face whose winding is reversed -- is not a template. Claiming it is one produces a face
 * at the wrong size or facing the wrong way, which is not a performance bug that can be measured
 * away. So the comparison is exact against the canonical corners, with a tolerance only for the
 * float arithmetic that produced them, and a quad that fails on any corner is reported as no match.
 *
 * <p>The winding matters separately from the corners. The same four corners traversed the other way
 * round is the inside of the cube, and back-face culling would remove it. Two quads with identical
 * corner sets and opposite winding get different answers.
 *
 * <p>Not thread safe.
 */
public final class QuadTemplateLibrary {

    /** One template per cube face. */
    public static final int TEMPLATE_COUNT = 6;

    /** Corners in a quad. */
    public static final int VERTICES = 4;

    /** Coordinates per corner. */
    public static final int COORDS = 3;

    /**
     * Tolerance for comparing a produced corner against the canonical one.
     *
     * <p>Wide enough for float arithmetic that added a block offset and subtracted it again, and
     * narrow enough that a sixteenth-of-a-block inset does not pass. A block model inset by 1/16 is
     * 0.0625, which is more than two orders of magnitude above this.
     */
    public static final float TOLERANCE = 1e-4f;

    /** No template matches. */
    public static final int NO_MATCH = -1;

    /**
     * The six canonical faces, each four corners of three coordinates, wound counter-clockwise seen
     * from outside.
     *
     * <p>Order matches {@link Orientation}: down, up, north, south, west, east.
     */
    private static final float[][][] TEMPLATES = {
        // down (y = 0), seen from below
        {{0f, 0f, 1f}, {0f, 0f, 0f}, {1f, 0f, 0f}, {1f, 0f, 1f}},
        // up (y = 1), seen from above
        {{0f, 1f, 0f}, {0f, 1f, 1f}, {1f, 1f, 1f}, {1f, 1f, 0f}},
        // north (z = 0)
        {{1f, 0f, 0f}, {0f, 0f, 0f}, {0f, 1f, 0f}, {1f, 1f, 0f}},
        // south (z = 1)
        {{0f, 0f, 1f}, {1f, 0f, 1f}, {1f, 1f, 1f}, {0f, 1f, 1f}},
        // west (x = 0)
        {{0f, 0f, 0f}, {0f, 0f, 1f}, {0f, 1f, 1f}, {0f, 1f, 0f}},
        // east (x = 1)
        {{1f, 0f, 1f}, {1f, 0f, 0f}, {1f, 1f, 0f}, {1f, 1f, 1f}},
    };

    /** The standard texture corners, in the same order as the face winding above. */
    private static final float[][] UV = {{0f, 1f}, {0f, 0f}, {1f, 0f}, {1f, 1f}};

    private long quads;
    private long matches;
    private final long[] perTemplate = new long[TEMPLATE_COUNT];

    /**
     * Identifies a quad as one of the six full cube faces.
     *
     * @param corners twelve floats, four corners of three coordinates, in winding order
     * @return a template index, or {@link #NO_MATCH}
     * @throws IllegalArgumentException if the array is not twelve long
     */
    public int templateFor(float[] corners) {
        checkCorners(corners);
        return templateFor(corners, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Identifies a quad, allowing for the offset of the block it belongs to.
     *
     * <p>The mesher works in section-local coordinates, so the corners it holds are the canonical ones
     * plus a whole-block offset. Comparing against the offset rather than translating the corners back
     * avoids rounding that could push a real match outside the tolerance.
     *
     * @param offsetX the block's X within the section
     * @param offsetY the block's Y within the section
     * @param offsetZ the block's Z within the section
     * @return a template index, or {@link #NO_MATCH}
     */
    public int templateFor(float[] corners, float offsetX, float offsetY, float offsetZ) {
        checkCorners(corners);
        this.quads++;
        for (int template = 0; template < TEMPLATE_COUNT; template++) {
            if (matches(corners, template, offsetX, offsetY, offsetZ)) {
                this.matches++;
                this.perTemplate[template]++;
                return template;
            }
        }
        return NO_MATCH;
    }

    private static boolean matches(float[] corners, int template,
                                   float offsetX, float offsetY, float offsetZ) {
        float[][] want = TEMPLATES[template];
        for (int v = 0; v < VERTICES; v++) {
            int base = v * COORDS;
            if (Math.abs(corners[base] - (want[v][0] + offsetX)) > TOLERANCE
                    || Math.abs(corners[base + 1] - (want[v][1] + offsetY)) > TOLERANCE
                    || Math.abs(corners[base + 2] - (want[v][2] + offsetZ)) > TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a quad's texture corners are the standard ones for its template.
     *
     * <p>A full cube face with the wrong UVs is still the right shape but not the same draw, so a
     * caller instancing templates has to check this too. Rotations of the standard set are accepted,
     * because a rotated texture is the same geometry and a renderer can rotate the UV in the shader.
     *
     * @param uvs eight floats, four corners of U and V
     */
    public boolean hasStandardUvs(float[] uvs) {
        if (uvs.length != VERTICES * 2) {
            throw new IllegalArgumentException("expected " + (VERTICES * 2) + " uv floats: "
                    + uvs.length);
        }
        // Each canonical corner must appear exactly once, in any order. Comparing as a multiset rather
        // than in sequence is what lets a rotated texture count as standard.
        boolean[] used = new boolean[VERTICES];
        for (int v = 0; v < VERTICES; v++) {
            int found = -1;
            for (int c = 0; c < VERTICES; c++) {
                if (used[c]) {
                    continue;
                }
                if (Math.abs(uvs[v * 2] - UV[c][0]) <= TOLERANCE
                        && Math.abs(uvs[v * 2 + 1] - UV[c][1]) <= TOLERANCE) {
                    found = c;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            used[found] = true;
        }
        return true;
    }

    /**
     * The canonical corners of a template, for a caller that wants the geometry rather than a match.
     *
     * @param template a template index from {@link #templateFor}
     * @param out twelve floats to fill
     */
    public void templateVertices(int template, float[] out) {
        if (template < 0 || template >= TEMPLATE_COUNT) {
            throw new IndexOutOfBoundsException("template " + template + " outside " + TEMPLATE_COUNT);
        }
        if (out.length < VERTICES * COORDS) {
            throw new IllegalArgumentException("out must hold " + (VERTICES * COORDS) + " floats");
        }
        float[][] corners = TEMPLATES[template];
        for (int v = 0; v < VERTICES; v++) {
            out[v * COORDS] = corners[v][0];
            out[v * COORDS + 1] = corners[v][1];
            out[v * COORDS + 2] = corners[v][2];
        }
    }

    /**
     * Whether two quads with the same corner set are the same face.
     *
     * <p>They are not, if the winding is reversed: the same four corners traversed the other way round
     * is the inside of the cube, and back-face culling would drop it. Worth having as a method because
     * a caller comparing corner sets alone would get this wrong.
     */
    public boolean sameWinding(float[] a, float[] b) {
        checkCorners(a);
        checkCorners(b);
        for (int i = 0; i < VERTICES * COORDS; i++) {
            if (Math.abs(a[i] - b[i]) > TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /** The canonical texture corners, copied. */
    public static float[][] standardUvs() {
        float[][] copy = new float[VERTICES][2];
        for (int i = 0; i < VERTICES; i++) {
            copy[i] = Arrays.copyOf(UV[i], 2);
        }
        return copy;
    }

    private static void checkCorners(float[] corners) {
        if (corners.length != VERTICES * COORDS) {
            throw new IllegalArgumentException("expected " + (VERTICES * COORDS)
                    + " corner floats: " + corners.length);
        }
    }

    public long quads() {
        return this.quads;
    }

    /** Quads recognised as one of the six templates. */
    public long matches() {
        return this.matches;
    }

    public long matchesFor(int template) {
        if (template < 0 || template >= TEMPLATE_COUNT) {
            throw new IndexOutOfBoundsException("template " + template + " outside " + TEMPLATE_COUNT);
        }
        return this.perTemplate[template];
    }

    public double matchRatePercent() {
        return this.quads == 0 ? 0.0 : 100.0 * this.matches / this.quads;
    }
}
