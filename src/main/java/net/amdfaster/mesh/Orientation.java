package net.amdfaster.mesh;

/**
 * The six axis-aligned face orientations, used to bucket chunk geometry.
 *
 * <p>Bucketing by orientation is what makes true back-face rejection possible without a second
 * pass. The whole section mesh is split into six index ranges while it is being built, and at draw
 * time the ranges whose outward normal points away from the camera are simply not drawn. Because
 * every Minecraft quad is axis-aligned, the test is exact rather than conservative, and because the
 * split happens during meshing it costs nothing — splitting an already-built chunk mesh into six
 * sub-meshes would multiply driver overhead six-fold.
 *
 * <p>Source: {@code docs/notes/09} §6.1.
 */
public enum Orientation {

    NEG_X(0, -1, 0, 0),
    POS_X(1, 1, 0, 0),
    NEG_Y(2, 0, -1, 0),
    POS_Y(3, 0, 1, 0),
    NEG_Z(4, 0, 0, -1),
    POS_Z(5, 0, 0, 1);

    public static final int COUNT = 6;

    /**
     * Which end of the first varying axis each of the four vertices takes, in winding order.
     *
     * <p>The varying axes per orientation are (Y,Z) for X-facing, (X,Z) for Y-facing and (X,Y) for
     * Z-facing. The order is chosen so the geometric normal of the resulting polygon equals the
     * declared normal, which {@code WindingTest} checks for all six.
     */
    private static final int[][] CORNER_A = {
            {0, 0, 1, 1},  // NEG_X
            {1, 1, 0, 0},  // POS_X
            {0, 0, 1, 1},  // POS_Y
            {1, 1, 0, 0},  // NEG_Y
            {0, 1, 1, 0},  // NEG_Z
            {0, 1, 1, 0},  // POS_Z
    };

    /** Which end of the second varying axis each of the four vertices takes, in winding order. */
    private static final int[][] CORNER_B = {
            {0, 1, 1, 0},  // NEG_X
            {0, 1, 1, 0},  // POS_X
            {0, 1, 1, 0},  // NEG_Y
            {0, 1, 1, 0},  // POS_Y
            {1, 1, 0, 0},  // NEG_Z
            {0, 0, 1, 1},  // POS_Z
    };

    /** 0 or 1: which end of the first varying axis vertex {@code i} uses. */
    public int cornerA(int i) {
        return CORNER_A[this.ordinal()][i];
    }

    /** 0 or 1: which end of the second varying axis vertex {@code i} uses. */
    public int cornerB(int i) {
        return CORNER_B[this.ordinal()][i];
    }


    private final int axis;
    private final int nx;
    private final int ny;
    private final int nz;

    Orientation(int axis, int nx, int ny, int nz) {
        this.axis = axis;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    /** 0 = X, 1 = Y, 2 = Z. */
    public int axis() {
        return this.axis;
    }

    public int normalX() {
        return this.nx;
    }

    public int normalY() {
        return this.ny;
    }

    public int normalZ() {
        return this.nz;
    }

    /** True when this orientation points along the positive direction of its axis. */
    public boolean isPositive() {
        return (this.axis == 0 ? this.nx : this.axis == 1 ? this.ny : this.nz) > 0;
    }

    public Orientation opposite() {
        return values()[(this.ordinal() ^ 1)];
    }

    /** The bit for this orientation in the mask returned by {@link #visibleMask}. */
    public int bit() {
        return 1 << this.ordinal();
    }

    /**
     * The set of orientations that can possibly be seen from a camera position, given the bounds
     * of one section.
     *
     * <p>A quad with orientation {@code o} sits on a plane at coordinate {@code p} along o's axis
     * and faces away from the section, so it is visible only when the camera is strictly on the
     * far side of that plane. Every quad in the bucket shares the orientation but not the plane,
     * and {@code p} ranges over {@code [min, min + size)}, so the conservative test per axis is:
     *
     * <pre>
     *   positive orientation visible  <=>  camera &gt;= min
     *   negative orientation visible  <=>  camera &lt;  min + size
     * </pre>
     *
     * <p>When the camera is inside the section both orientations on that axis survive, which is
     * correct. When the camera is a whole section away on an axis, only one survives, so a distant
     * section draws three buckets instead of six — roughly half the triangles.
     *
     * @return a bitmask of {@link #bit()} values
     */
    public static int visibleMask(double cameraX, double cameraY, double cameraZ,
                                  int minX, int minY, int minZ, int size) {
        int mask = 0;

        if (cameraX >= minX) {
            mask |= POS_X.bit();
        }
        if (cameraX < minX + size) {
            mask |= NEG_X.bit();
        }
        if (cameraY >= minY) {
            mask |= POS_Y.bit();
        }
        if (cameraY < minY + size) {
            mask |= NEG_Y.bit();
        }
        if (cameraZ >= minZ) {
            mask |= POS_Z.bit();
        }
        if (cameraZ < minZ + size) {
            mask |= NEG_Z.bit();
        }

        return mask;
    }

    /** How many bits are set in a mask, i.e. how many buckets would be drawn. */
    public static int countBits(int mask) {
        return Integer.bitCount(mask);
    }
}
