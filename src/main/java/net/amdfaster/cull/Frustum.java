package net.amdfaster.cull;

/**
 * Six planes, extracted once per frame from the view-projection matrix, used to reject meshlets
 * before they ever reach a draw.
 *
 * <p>This is the CPU mirror of what the compute shader does. Keeping the two in step is the point
 * of the tests here: the shader runs the same arithmetic on 64 threads at a time, and if the CPU
 * version is wrong the fallback path disagrees with the fast path and objects appear and disappear
 * depending on which one answered.
 *
 * <p>Planes use the convention that a point {@code p} is on the inside when
 * {@code n . p + d >= 0}, and are normalised at extraction time so the AABB test can compare
 * directly.
 *
 * <p>Depth is assumed to be <b>reversed-Z in [0,1]</b>, which is what the renderer uses:
 * {@code D32_SFLOAT} with near at 1.0 and far at 0.0. That changes which two planes the third row
 * contributes, and getting it the wrong way round culls everything near the camera.
 */
public final class Frustum {

    /** Plane indices. */
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int BOTTOM = 2;
    public static final int TOP = 3;
    public static final int NEAR = 4;
    public static final int FAR = 5;
    public static final int PLANE_COUNT = 6;

    private final float[] planes = new float[PLANE_COUNT * 4];

    /**
     * @param viewProjection 4x4, column-major, the same layout a Vulkan push constant block wants
     */
    public Frustum(float[] viewProjection) {
        if (viewProjection.length != 16) {
            throw new IllegalArgumentException("view-projection must be 16 floats, got "
                    + viewProjection.length);
        }
        // Rows of a column-major matrix.
        float[] r0 = {viewProjection[0], viewProjection[4], viewProjection[8], viewProjection[12]};
        float[] r1 = {viewProjection[1], viewProjection[5], viewProjection[9], viewProjection[13]};
        float[] r2 = {viewProjection[2], viewProjection[6], viewProjection[10], viewProjection[14]};
        float[] r3 = {viewProjection[3], viewProjection[7], viewProjection[11], viewProjection[15]};

        set(LEFT, add(r3, r0));
        set(RIGHT, sub(r3, r0));
        set(BOTTOM, add(r3, r1));
        set(TOP, sub(r3, r1));
        // Reversed-Z [0,1]: the near plane is z >= 0, which is row2 on its own, and the far plane
        // is w - z >= 0. A conventional [-1,1] projection would use row3 + row2 and row3 - row2.
        set(NEAR, r2.clone());
        set(FAR, sub(r3, r2));
    }

    private static float[] add(float[] a, float[] b) {
        return new float[] {a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]};
    }

    private void set(int plane, float[] coefficients) {
        float length = (float) Math.sqrt(coefficients[0] * coefficients[0]
                + coefficients[1] * coefficients[1] + coefficients[2] * coefficients[2]);
        if (length == 0f) {
            // Degenerate plane: keep it, but as "everything is inside" so it cannot cull wrongly.
            this.planes[plane * 4] = 0f;
            this.planes[plane * 4 + 1] = 0f;
            this.planes[plane * 4 + 2] = 0f;
            this.planes[plane * 4 + 3] = 1f;
            return;
        }
        float inv = 1f / length;
        this.planes[plane * 4] = coefficients[0] * inv;
        this.planes[plane * 4 + 1] = coefficients[1] * inv;
        this.planes[plane * 4 + 2] = coefficients[2] * inv;
        this.planes[plane * 4 + 3] = coefficients[3] * inv;
    }

    public float normalX(int plane) {
        return this.planes[plane * 4];
    }

    public float normalY(int plane) {
        return this.planes[plane * 4 + 1];
    }

    public float normalZ(int plane) {
        return this.planes[plane * 4 + 2];
    }

    public float distance(int plane) {
        return this.planes[plane * 4 + 3];
    }

    /** Signed distance from a point to a plane; negative means outside. */
    public float distanceTo(int plane, float x, float y, float z) {
        return normalX(plane) * x + normalY(plane) * y + normalZ(plane) * z + distance(plane);
    }

    public boolean containsPoint(float x, float y, float z) {
        for (int p = 0; p < PLANE_COUNT; p++) {
            if (distanceTo(p, x, y, z) < 0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * True unless the box lies entirely outside at least one plane.
     *
     * <p>Tests the corner furthest along each plane normal: if even that corner is outside, the
     * whole box is. This can report "visible" for a box that straddles a corner of the frustum and
     * is really outside -- conservative in the safe direction, which is what culling has to be.
     */
    public boolean intersectsAabb(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        for (int p = 0; p < PLANE_COUNT; p++) {
            float nx = normalX(p);
            float ny = normalY(p);
            float nz = normalZ(p);
            float px = nx >= 0f ? maxX : minX;
            float py = ny >= 0f ? maxY : minY;
            float pz = nz >= 0f ? maxZ : minZ;
            if (nx * px + ny * py + nz * pz + distance(p) < 0f) {
                return false;
            }
        }
        return true;
    }

    /** A pure translation, handy for tests and for the camera-relative origin the renderer uses. */
    public static float[] translation(float x, float y, float z) {
        return new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                x, y, z, 1f};
    }

    /** A 4x4 identity, which makes clip space coincide with world space. */
    public static float[] identity() {
        return new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f};
    }
}
