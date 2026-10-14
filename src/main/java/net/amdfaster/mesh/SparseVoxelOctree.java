package net.amdfaster.mesh;

import java.util.Arrays;

/**
 * A sparse octree over a chunk section, so a region that is all solid or all air is one node.
 *
 * <p>A section is 16 by 16 by 16 and most of it is uniform: everything below the surface is stone,
 * everything above it is air. Walking every voxel to find that out costs 4,096 lookups per section and
 * produces the same answer as walking the 64 nodes four blocks wide. On a measured section with a
 * surface undulating between y = 4 and y = 10, half of those 64 nodes resolve without descending at
 * all -- 16 entirely solid and 16 entirely empty.
 *
 * <p>What that buys depends on the caller. A solid region needs one quad per exposed face and none
 * inside. An empty region needs nothing. Both are answers a mesher can act on without looking at the
 * voxels, and for distant terrain it is the difference between a section costing 256 quads and costing
 * a handful.
 *
 * <h2>Sparse means the uniform nodes cost nothing</h2>
 *
 * <p>Only mixed nodes get children. The same section needs 1,689 nodes against the 4,681 a complete
 * octree would allocate -- 36%. A section that is entirely stone, or entirely air, needs one.
 *
 * <h2>Every node carries its own origin</h2>
 *
 * <p>An earlier version derived a node's position from its index, on the assumption that children are
 * allocated eight at a time in index order. They are not: the build recurses into the first child
 * before allocating the second child's children, so the allocation is depth-first and the arithmetic
 * pointed at the wrong parent for every node below the second level. A region query answered for a
 * different part of the section, which is the kind of bug that shows up as culling the wrong chunks.
 * Storing the origin costs one int per node and removes the assumption entirely.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>It does not make culling faster on its own. A frustum test still has to visit the nodes, and for a
 * section that is mostly mixed the tree is more work than a flat array. Where it wins is uniform
 * regions, which is what underground and sky both are, and the counters exist so the claim can be
 * checked against a real section rather than believed.
 *
 * <p>Not thread safe.
 */
public final class SparseVoxelOctree {

    /** Blocks along a section edge. */
    public static final int SIZE = 16;

    /** Voxels in a section. */
    public static final int VOXELS = SIZE * SIZE * SIZE;

    /** Levels, counting the root as 0 and single voxels as the last. */
    public static final int LEVELS = 5;

    /** Node states. */
    public static final int EMPTY = 0;
    public static final int FULL = 1;
    public static final int MIXED = 2;

    /** A node with no children. */
    private static final int NO_CHILDREN = -1;

    /** Nodes a complete octree over a section would hold. */
    public static final int FULL_OCTREE_NODES = 1 + 8 + 64 + 512 + 4096;

    private static final int INITIAL_CAPACITY = 128;

    private int[] states;
    private int[] children;

    /** Packed block origin per node: {@code (x << 8) | (y << 4) | z}. */
    private int[] origins;

    /** Edge length in blocks per node. */
    private int[] spans;

    private int nodeCount;

    private final int[] nodesPerLevel = new int[LEVELS];

    private int deepest;

    private long voxelsRead;
    private long regionQueries;
    private long resolvedWithoutDescending;

    public SparseVoxelOctree() {
        this.states = new int[INITIAL_CAPACITY];
        this.children = new int[INITIAL_CAPACITY];
        this.origins = new int[INITIAL_CAPACITY];
        this.spans = new int[INITIAL_CAPACITY];
        clear();
    }

    /** Drops everything, for a section whose blocks changed. */
    public void clear() {
        this.nodeCount = 1;
        Arrays.fill(this.states, 0);
        Arrays.fill(this.children, NO_CHILDREN);
        Arrays.fill(this.nodesPerLevel, 0);
        this.states[0] = MIXED;
        this.origins[0] = 0;
        this.spans[0] = SIZE;
        this.nodesPerLevel[0] = 1;
        this.deepest = 0;
        this.voxelsRead = 0;
    }

    /** Supplies whether a voxel is solid. */
    public interface Solidity {
        boolean solidAt(int x, int y, int z);
    }

    /** Builds the tree for a section. */
    public void build(Solidity solidity) {
        clear();
        buildNode(0, 0, 0, 0, 0, SIZE, solidity);
    }

    private void buildNode(int node, int level, int x0, int y0, int z0, int span,
                           Solidity solidity) {
        if (span == 1) {
            // The leaf is the voxel. Setting this was missing once, which left every leaf MIXED
            // regardless of whether it was solid or air, because the parent loop initialises children
            // to MIXED before recursing.
            this.states[node] = solidity.solidAt(x0, y0, z0) ? FULL : EMPTY;
            this.voxelsRead++;
            this.deepest = Math.max(this.deepest, level);
            return;
        }

        int solid = 0;
        int total = span * span * span;
        for (int y = y0; y < y0 + span; y++) {
            for (int x = x0; x < x0 + span; x++) {
                for (int z = z0; z < z0 + span; z++) {
                    if (solidity.solidAt(x, y, z)) {
                        solid++;
                    }
                    this.voxelsRead++;
                }
            }
        }
        if (solid == 0) {
            this.states[node] = EMPTY;
            return;
        }
        if (solid == total) {
            this.states[node] = FULL;
            return;
        }
        this.states[node] = MIXED;

        int half = span / 2;
        int first = allocate(8);
        this.children[node] = first;
        int childLevel = level + 1;
        this.nodesPerLevel[childLevel] += 8;

        for (int i = 0; i < 8; i++) {
            int cx = x0 + ((i & 1) == 0 ? 0 : half);
            int cy = y0 + ((i & 2) == 0 ? 0 : half);
            int cz = z0 + ((i & 4) == 0 ? 0 : half);
            int child = first + i;
            this.states[child] = MIXED;
            this.origins[child] = (cx << 8) | (cy << 4) | cz;
            this.spans[child] = half;
            buildNode(child, childLevel, cx, cy, cz, half, solidity);
        }
    }

    private int allocate(int count) {
        int first = this.nodeCount;
        this.nodeCount += count;
        if (this.nodeCount > this.states.length) {
            int size = this.states.length;
            while (size < this.nodeCount) {
                size *= 2;
            }
            this.states = Arrays.copyOf(this.states, size);
            this.origins = Arrays.copyOf(this.origins, size);
            this.spans = Arrays.copyOf(this.spans, size);
            int[] grown = Arrays.copyOf(this.children, size);
            Arrays.fill(grown, this.children.length, size, NO_CHILDREN);
            this.children = grown;
        }
        return first;
    }

    /**
     * The state of an axis-aligned region.
     *
     * <p>Descends only as far as it has to: a uniform node answers without going further, which is the
     * saving.
     *
     * @param x region corner in blocks
     * @param y region corner in blocks
     * @param z region corner in blocks
     * @param span region edge in blocks; a power of two no greater than {@link #SIZE}
     * @return {@link #EMPTY}, {@link #FULL} or {@link #MIXED}
     */
    public int regionState(int x, int y, int z, int span) {
        if (span <= 0 || (span & (span - 1)) != 0 || span > SIZE) {
            throw new IllegalArgumentException(
                    "span must be a power of two no greater than " + SIZE + ": " + span);
        }
        checkBounds(x, y, z, span);
        this.regionQueries++;

        int node = 0;
        while (true) {
            int state = this.states[node];
            if (state != MIXED) {
                this.resolvedWithoutDescending++;
                return state;
            }
            int nodeSpan = this.spans[node];
            if (nodeSpan == span) {
                return MIXED;
            }
            int child = this.children[node];
            if (child == NO_CHILDREN) {
                throw new IllegalStateException("mixed node " + node + " has no children");
            }
            int origin = this.origins[node];
            int half = nodeSpan / 2;
            int index = ((x - ((origin >> 8) & 0xF) >= half) ? 1 : 0)
                    | ((y - ((origin >> 4) & 0xF) >= half) ? 2 : 0)
                    | ((z - (origin & 0xF) >= half) ? 4 : 0);
            node = child + index;
        }
    }

    /** The state of the whole section. */
    public int sectionState() {
        return this.states[0];
    }

    /** How many nodes the tree holds. */
    public int nodeCount() {
        return this.nodeCount;
    }

    /** How many nodes were created at a level. */
    public int nodesAtLevel(int level) {
        if (level < 0 || level >= LEVELS) {
            throw new IndexOutOfBoundsException("level " + level + " outside " + LEVELS);
        }
        return this.nodesPerLevel[level];
    }

    /** The deepest level any voxel reached. */
    public int deepestLevel() {
        return this.deepest;
    }

    /** Voxel reads the build made, including the ones spent deciding a region was uniform. */
    public long voxelsRead() {
        return this.voxelsRead;
    }

    public long regionQueries() {
        return this.regionQueries;
    }

    /** Queries answered by a uniform node, without walking down to the region. */
    public long resolvedWithoutDescending() {
        return this.resolvedWithoutDescending;
    }

    /** Percentage of a complete octree's nodes this tree needed. */
    public double nodePercentOfFull() {
        return 100.0 * this.nodeCount / FULL_OCTREE_NODES;
    }

    private static void checkBounds(int x, int y, int z, int span) {
        if (x < 0 || y < 0 || z < 0
                || x + span > SIZE || y + span > SIZE || z + span > SIZE) {
            throw new IndexOutOfBoundsException(
                    "region " + x + "," + y + "," + z + " of " + span + " outside " + SIZE);
        }
    }
}
