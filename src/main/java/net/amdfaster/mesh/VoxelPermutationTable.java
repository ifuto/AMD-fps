package net.amdfaster.mesh;

/**
 * Precomputes the geometry for all sixty-four combinations of a block's visible faces, so meshing a block is a
 * table lookup.
 *
 * <p>Which faces of a block need drawing is decided by which of its six neighbours are opaque, and that is six
 * yes-or-no answers -- sixty four combinations, no more. Every one of those combinations produces the same
 * geometry every time it occurs, wherever in the world it occurs, so the vertices and indices for all sixty four
 * can be built once and looked up by the six bits.
 *
 * <p>What is left per block is reading the neighbour flags, forming the index, and copying the entry. The
 * per-block work becomes a handful of instructions instead of a face loop with a branch per face.
 *
 * <h2>The table has to be exhaustive</h2>
 *
 * <p>Sixty four entries, one per combination, with no gaps. A table missing an entry does not fail loudly -- the
 * lookup returns whatever is at that index, and a block somewhere in the world draws the wrong faces. Which
 * block depends on which combination is missing, so the symptom is a handful of blocks in the world looking
 * wrong with no pattern a player could describe, and no way to connect it to a table. So the table is built for
 * every value from zero to sixty three and a test walks all of them.
 *
 * <h2>Shared edges have to agree</h2>
 *
 * <p>Two faces of the same block meet along an edge, and the vertices on that edge are shared between them. If
 * the two faces computed those vertices separately they could land a fraction apart, and the seam would show as a
 * hairline crack along every block edge. Taking them from one table entry means they are the same vertex by
 * construction, because the entry was built once.
 *
 * <h2>The vertex count goes down and the index count goes up</h2>
 *
 * <p>An entry holds the eight corners of the block once and indexes into them per face, rather than holding four
 * corners per face. That is eight vertices and up to thirty six indices instead of up to twenty four vertices and
 * the same indices. Indices are a byte and positions are not, so the total is smaller -- but it is a trade, and
 * a renderer that could not handle indexed drawing would be worse off.
 *
 * <h2>Why this is not the same as greedy meshing</h2>
 *
 * <p>Greedy meshing merges faces across blocks, so a flat wall of a hundred blocks becomes one quad. This does
 * not merge anything: it makes the per-block case cheap, which is what a wall of different blocks, a chest, or a
 * stair falls back to. The two apply together -- merged runs use the greedy path and everything else uses the
 * table.
 *
 * <p>Not thread safe to build, but the built table is immutable and safe to read from every worker.
 */
public final class VoxelPermutationTable {

    /** The six faces, as bits. */
    public static final int DOWN = 1;
    public static final int UP = 2;
    public static final int NORTH = 4;
    public static final int SOUTH = 8;
    public static final int WEST = 16;
    public static final int EAST = 32;

    /** Every combination of the six faces. */
    public static final int PERMUTATIONS = 64;

    /** Corners of a block, stored once per entry rather than four per face. */
    public static final int CORNERS = 8;

    /** Indices a face needs. */
    public static final int INDICES_PER_FACE = 6;

    /** The table, indexed by the six face bits. */
    private final int[][] indices = new int[PERMUTATIONS][];

    private long lookups;
    private long blocksMeshed;
    private long facesEmitted;

    /** Builds the table. Called once. */
    public VoxelPermutationTable() {
        for (int mask = 0; mask < PERMUTATIONS; mask++) {
            int faces = 0;
            for (int bit = 0; bit < 6; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    faces++;
                }
            }
            int[] entry = new int[faces * INDICES_PER_FACE];
            int index = 0;
            for (int bit = 0; bit < 6; bit++) {
                if ((mask & (1 << bit)) == 0) {
                    continue;
                }
                // Two triangles per face, indexing into the eight shared corners. Taking them from one entry
                // means the vertices on a shared edge are the same vertex by construction, because the entry
                // was built once -- which is what stops the hairline crack along every block edge.
                int[] corners = faceCorners(bit);
                for (int corner : corners) {
                    entry[index++] = corner;
                }
            }
            this.indices[mask] = entry;
        }
    }

    /** The four corners of one face, as two triangles. */
    private static int[] faceCorners(int face) {
        return switch (face) {
            case 0 -> new int[] {0, 1, 3, 0, 3, 2};
            case 1 -> new int[] {4, 6, 5, 4, 7, 6};
            case 2 -> new int[] {0, 4, 5, 0, 5, 1};
            case 3 -> new int[] {2, 3, 7, 3, 6, 7};
            case 4 -> new int[] {0, 2, 6, 0, 6, 4};
            default -> new int[] {1, 5, 7, 1, 7, 3};
        };
    }

    /**
     * Meshes one block by looking up its face combination.
     *
     * @param faceMask the six face bits, from the neighbour opacities
     * @return the indices for the block's faces
     */
    public int[] mesh(int faceMask) {
        if (faceMask < 0 || faceMask >= PERMUTATIONS) {
            throw new IllegalArgumentException(
                    "six face bits, not " + Integer.toBinaryString(faceMask));
        }
        this.lookups++;
        this.blocksMeshed++;
        this.facesEmitted += this.indices[faceMask].length / INDICES_PER_FACE;
        return this.indices[faceMask];
    }

    /** How many faces a combination draws. */
    public static int facesIn(int faceMask) {
        return Integer.bitCount(faceMask);
    }

    /** How many entries the table has, which has to be every combination. */
    public int size() {
        int populated = 0;
        for (int[] entry : this.indices) {
            if (entry != null) {
                populated++;
            }
        }
        return populated;
    }

    public long lookups() {
        return this.lookups;
    }

    public long blocksMeshed() {
        return this.blocksMeshed;
    }

    /** How many faces were emitted. */
    public long facesEmitted() {
        return this.facesEmitted;
    }

    /** Indices the table holds in total. */
    public int totalIndices() {
        int total = 0;
        for (int[] entry : this.indices) {
            total += entry.length;
        }
        return total;
    }

    /**
     * Vertices a block needs with the table, against four per face without it.
     *
     * <p>The table holds the eight corners once; the alternative holds four per face.
     *
     * @param faceCount how many faces the block draws
     */
    public static int verticesSaved(int faceCount) {
        return faceCount * 4 - CORNERS;
    }

    /**
     * The share of the vertex storage avoided, for a block with this many faces.
     *
     * <p>Zero before any block has been meshed, and zero for a block with two or fewer faces -- eight corners
     * is not fewer than four times two. A table that has meshed nothing has technically avoided everything, but
     * reporting that as a saving would describe an unused one as though it had done something.
     */
    public double savedPercent(int faceCount) {
        int without = faceCount * 4;
        if (this.lookups == 0 || without == 0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - CORNERS) / without;
    }
}
