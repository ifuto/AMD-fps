package net.amdfaster.cull;

/**
 * Which faces of a section are sealed, and what that lets the culler skip without looking at geometry.
 *
 * <p>A section face is sealed when every one of the 256 blocks on its outer layer is opaque. When it is,
 * nothing on the far side of that face can be seen through it, so the neighbouring section behind it
 * needs no occlusion query at all -- not a coarse one, not a conservative one, none. The test is a
 * single bit, and it is exact rather than conservative, which is unusual for culling and worth having.
 *
 * <p>This is the cheap half of graph culling. The expensive half walks a connectivity graph of cells and
 * portals; this answers the immediate-neighbour case, which in a built-up world is most of the sections
 * a frame considers, and it answers it during the mesh build that is already reading every block.
 *
 * <h2>Why the mask is rebuilt with the section</h2>
 *
 * <p>One placed block can unseal a face and one removed block can seal it. The mask is derived from the
 * same pass that meshes the section, so it cannot drift out of step with the geometry it describes. A
 * mask computed once at load and left alone would eventually claim a wall is solid after the player put
 * a window in it, and the result would be a hole in the world that walks with the build.
 *
 * <p>Immutable once built.
 */
public final class SectionBoundaryMask {

    /** Negative X. */
    public static final int FACE_X_NEG = 0;
    /** Positive X. */
    public static final int FACE_X_POS = 1;
    /** Negative Y. */
    public static final int FACE_Y_NEG = 2;
    /** Positive Y. */
    public static final int FACE_Y_POS = 3;
    /** Negative Z. */
    public static final int FACE_Z_NEG = 4;
    /** Positive Z. */
    public static final int FACE_Z_POS = 5;

    public static final int FACES = 6;

    /** Blocks along one face edge. A face is 16 by 16, so 256 blocks decide one bit. */
    public static final int EDGE = 16;
    /** Blocks on one face. */
    public static final int CELLS_PER_FACE = EDGE * EDGE;

    /** Every face open. Nothing can be skipped. */
    public static final int OPEN = 0;
    /** Every face sealed. Only this section is visible from inside it. */
    public static final int SEALED = (1 << FACES) - 1;

    /** Decides opacity for one block, so the caller can use whatever representation it already has. */
    public interface OpacitySource {
        /** @return true if the block at section-local coordinates is opaque */
        boolean isOpaque(int x, int y, int z);
    }

    private final int mask;

    public SectionBoundaryMask(int mask) {
        if ((mask & ~SEALED) != 0) {
            throw new IllegalArgumentException("mask has bits outside the six faces: " + mask);
        }
        this.mask = mask;
    }

    /** The six bits, one per face. */
    public int mask() {
        return this.mask;
    }

    public boolean isSealed(int face) {
        return (this.mask & (1 << face)) != 0;
    }

    /** How many faces are sealed, which is how much this section can hide from its neighbours. */
    public int sealedFaceCount() {
        return Integer.bitCount(this.mask);
    }

    /**
     * Whether the neighbouring section reached through {@code face} can be skipped.
     *
     * <p>Exact, not conservative: a sealed face admits no light and no sight lines, so anything behind
     * it is genuinely invisible from this section.
     */
    public boolean hidesNeighbour(int face) {
        return isSealed(face);
    }

    /**
     * The face index through which a neighbour at the given axis offset is reached.
     *
     * @param dx -1, 0 or 1
     * @param dy -1, 0 or 1
     * @param dz -1, 0 or 1
     * @return the face, or -1 if the offset is not a face neighbour (itself, or diagonal)
     */
    public static int faceForNeighbour(int dx, int dy, int dz) {
        int nonzero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (nonzero != 1) {
            // Diagonal neighbours share only an edge or a corner, and a sealed face says nothing about
            // them. Reporting a face here would cull a section that is visible around the corner.
            return -1;
        }
        if (dx < 0) {
            return FACE_X_NEG;
        }
        if (dx > 0) {
            return FACE_X_POS;
        }
        if (dy < 0) {
            return FACE_Y_NEG;
        }
        if (dy > 0) {
            return FACE_Y_POS;
        }
        if (dz < 0) {
            return FACE_Z_NEG;
        }
        return FACE_Z_POS;
    }

    /**
     * The face through which a neighbour is seen from the other side.
     *
     * <p>If B is reached from A through A's positive-X face, then A is reached from B through B's
     * negative-X face. The two sections seal independently, and both directions matter.
     */
    public static int oppositeFace(int face) {
        return face ^ 1;
    }

    /**
     * Builds the mask by scanning the six outer layers of a section.
     *
     * <p>Six faces of 256 blocks is 1536 lookups, and each of the twelve edges is counted twice because
     * two faces meet there. Deduplicating the edges would save 48 lookups out of 1536 and cost more in
     * branching than it saves, so the overlap is left in.
     *
     * <p>Scanning stops at the first non-opaque block on a face. A face that is mostly glass still has to
     * be reported open, and finding that out early is the common case in a built world.
     */
    public static SectionBoundaryMask scan(OpacitySource blocks) {
        int mask = 0;
        for (int face = 0; face < FACES; face++) {
            if (faceSealed(face, blocks)) {
                mask |= 1 << face;
            }
        }
        return new SectionBoundaryMask(mask);
    }

    private static boolean faceSealed(int face, OpacitySource blocks) {
        for (int a = 0; a < EDGE; a++) {
            for (int b = 0; b < EDGE; b++) {
                int x;
                int y;
                int z;
                switch (face) {
                    case FACE_X_NEG -> { x = 0; y = a; z = b; }
                    case FACE_X_POS -> { x = EDGE - 1; y = a; z = b; }
                    case FACE_Y_NEG -> { x = a; y = 0; z = b; }
                    case FACE_Y_POS -> { x = a; y = EDGE - 1; z = b; }
                    case FACE_Z_NEG -> { x = a; y = b; z = 0; }
                    default -> { x = a; y = b; z = EDGE - 1; }
                }
                if (!blocks.isOpaque(x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }
}
