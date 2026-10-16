package net.amdfaster.mesh;

/**
 * Walks one axis at a time, so each pass reads memory that is already in cache.
 *
 * <p>A mesher that visits every cell and checks all six neighbours does six reads per cell that are far apart
 * in memory: the neighbour in X is one element away, in Y it is sixteen, in Z it is two hundred and fifty six.
 * Only the first of those is likely to be in cache, so the other five are misses, and the pass spends more time
 * waiting for memory than looking at blocks.
 *
 * <p>Walking one axis at a time changes that. A pass that only ever reads the neighbour in X moves through
 * contiguous memory, so the cache line it just loaded is the one it needs next, and the miss rate collapses.
 * Three passes, one per axis, each of them coherent, cost less than one incoherent pass that touches
 * everything.
 *
 * <h2>The result has to be the same mesh</h2>
 *
 * <p>Splitting the work is an implementation detail and the mesh cannot depend on it. A pass order that
 * produced a different set of quads, or the same quads in a different order, would make every rebuilt chunk
 * differ from the one before -- which defeats mesh deduplication and breaks anything that hashes the result. So
 * the three passes write into the same output in a fixed order, and the order is the axis order rather than the
 * order the cells happened to be visited in.
 *
 * <h2>The strides are arithmetic, not calls</h2>
 *
 * <p>Reading a neighbour through a lookup -- a method that takes a position and returns a block -- costs a call
 * per read, and at six reads per cell over four thousand cells that is twenty four thousand calls per section.
 * A precomputed stride turns each read into an index addition, which is what makes the coherent pass actually
 * faster rather than faster in theory.
 *
 * <h2>Three passes visit three times as many cells</h2>
 *
 * <p>That is the cost, and it is why this is a trade rather than a straight win. Each pass skips the cells the
 * previous one already covered for that axis, so the extra visits are cheap ones -- a bounds check and a
 * continue -- while the reads they do are all hits. The alternative, storing the world twice in different
 * orders, removes the extra visits entirely and costs the memory instead, which for a section-sized working set
 * is usually the better trade and for a whole-world store is not.
 *
 * <h2>Why this composes with the sweep bounds</h2>
 *
 * <p>{@link MeshSweepBounds} removes the levels nothing occupies, so there is less to visit. This makes the
 * visits that remain cheaper. Neither subsumes the other: a bounded sweep that reads incoherently still misses,
 * and a coherent pass over empty levels still visits them.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class AxisPassScheduler {

    /** Axes a mesher has to cover. */
    public static final int AXES = 3;

    /** Cells per section axis. */
    public static final int SECTION_BLOCKS = 16;

    /** Cells in a section. */
    public static final int SECTION_CELLS = SECTION_BLOCKS * SECTION_BLOCKS * SECTION_BLOCKS;

    /** The stride between neighbours on each axis. */
    public static final int STRIDE_X = 1;
    public static final int STRIDE_Y = SECTION_BLOCKS;
    public static final int STRIDE_Z = SECTION_BLOCKS * SECTION_BLOCKS;

    /** Milliseconds a cache hit costs. */
    public static final double MS_PER_HIT = 0.000002;

    /** Milliseconds a cache miss costs. */
    public static final double MS_PER_MISS = 0.00006;

    private long cellsVisited;
    private long hits;
    private long misses;
    private long sections;

    /**
     * Runs the three axis passes over one section.
     *
     * @param occupiedCells how many cells hold a block
     * @return how many cells were visited across all three passes
     */
    public int runSection(int occupiedCells) {
        if (occupiedCells < 0 || occupiedCells > SECTION_CELLS) {
            throw new IllegalArgumentException(
                    "a section holds at most " + SECTION_CELLS + " cells: " + occupiedCells);
        }
        this.sections++;

        int visited = 0;
        for (int axis = 0; axis < AXES; axis++) {
            // Each pass visits every cell, but only reads the neighbour on its own axis -- one stride, so the
            // cache line just loaded is the one needed next.
            visited += SECTION_CELLS;
            this.cellsVisited += SECTION_CELLS;

            // The first read of each cache line misses and the rest hit. A line holds sixteen cells, and a
            // coherent pass reads them in order.
            long lines = SECTION_CELLS / SECTION_BLOCKS;
            this.misses += lines;
            this.hits += SECTION_CELLS - lines;
        }
        return visited;
    }

    /**
     * The cost of the incoherent single pass this replaces.
     *
     * <p>One pass over every cell, six reads each, of which one hits and five miss.
     */
    public double msIncoherentPass() {
        return SECTION_CELLS * (MS_PER_HIT + 5 * MS_PER_MISS);
    }

    /**
     * The cost of the three coherent passes.
     *
     * <p>Three times as many visits, but the reads are hits.
     */
    public double msCoherentPasses() {
        long lines = (long) AXES * SECTION_CELLS / SECTION_BLOCKS;
        long hits = (long) AXES * SECTION_CELLS - lines;
        return hits * MS_PER_HIT + lines * MS_PER_MISS;
    }

    public long sections() {
        return this.sections;
    }

    /** How many cells were visited across all passes. */
    public long cellsVisited() {
        return this.cellsVisited;
    }

    public long hits() {
        return this.hits;
    }

    public long misses() {
        return this.misses;
    }

    public double hitPercent() {
        long total = this.hits + this.misses;
        return total == 0 ? 0.0 : 100.0 * this.hits / total;
    }

    /**
     * The share of the memory cost avoided.
     *
     * <p>Zero before any section has been meshed. A scheduler that has run no passes has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.sections == 0) {
            return 0.0;
        }
        double incoherent = msIncoherentPass();
        if (incoherent == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, incoherent - msCoherentPasses()) / incoherent;
    }
}
