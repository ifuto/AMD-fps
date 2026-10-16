package net.amdfaster.fluid;

/**
 * Merges the tops of adjacent fluids at the same level into fewer quads.
 *
 * <p>Every water block draws its own top face. A lake a hundred blocks across is ten thousand blocks, and
 * ten thousand quads of the same flat surface at the same height, all with the same texture -- which is a
 * great deal of geometry and a great deal of overdraw for what is visually one plane.
 *
 * <p>Greedy merging combines runs of same-level fluid into rectangles, so a flat lake becomes a handful of
 * quads instead of ten thousand. The vertices are the same shape; there are simply far fewer of them.
 *
 * <h2>Only the same level merges</h2>
 *
 * <p>Water flowing down a slope is a staircase, and each step is a visible edge. Merging across a level
 * change would flatten the staircase into a ramp, and a waterfall would lose the steps that make it look
 * like falling water. So merging is by level, and a boundary between two levels stays a boundary.
 *
 * <h2>The surface keeps its actual height</h2>
 *
 * <p>A fluid block's top is not always at the top of the block -- a source is full, a flowing one is
 * lower, and the height is what the player sees. A merged quad takes the height of the cells it covers, and
 * since they are all at the same level they agree. A merger that used a single height for the whole lake
 * would raise the shallow edges to the depth of the middle, which is a visible error in exactly the place
 * the player looks -- the shoreline.
 *
 * <h2>The merge is per level, not per body</h2>
 *
 * <p>Two lakes at different heights in the same column stack do not merge, and neither do two connected
 * pools at different levels. Treating them as one body would produce a surface that does not exist.
 *
 * <h2>Why this is not the same as the greedy mesher</h2>
 *
 * <p>{@link net.amdfaster.mesh.voxel.GreedyMesher} merges the faces of solid blocks, where a face exists
 * only where a block meets something else. A fluid's top exists wherever there is fluid, so the merging
 * question is different -- it is about runs of the same level rather than about which faces are exposed --
 * and the shape of the result is a rectangle over a region rather than a rectangle over a boundary.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class FluidSurfaceMerger {

    /** Blocks per section axis. */
    public static final int SECTION_BLOCKS = 16;

    /** Milliseconds drawing one fluid quad costs. */
    public static final double MS_PER_QUAD = 0.0004;

    /** Bytes one merged quad occupies. */
    public static final int BYTES_PER_QUAD = 36;

    private long blocks;
    private long quads;

    /**
     * Merges one level of a fluid surface.
     *
     * <p>Walks the level in rows and extends each run as far as the level continues, then stacks runs of the
     * same width into rectangles.
     *
     * @param fluidAt whether each cell of the level holds fluid, indexed {@code z * SECTION_BLOCKS + x}
     * @return how many quads the level became
     */
    public int mergeLevel(boolean[] fluidAt) {
        int cells = SECTION_BLOCKS * SECTION_BLOCKS;
        if (fluidAt.length != cells) {
            throw new IllegalArgumentException(
                    "a section level has " + cells + " cells, not " + fluidAt.length);
        }
        this.blocks += countFluid(fluidAt);

        boolean[] consumed = new boolean[cells];
        int merged = 0;

        for (int z = 0; z < SECTION_BLOCKS; z++) {
            int x = 0;
            while (x < SECTION_BLOCKS) {
                int index = z * SECTION_BLOCKS + x;
                if (!fluidAt[index] || consumed[index]) {
                    x++;
                    continue;
                }
                // Extend the run along this row as far as the fluid continues.
                int width = 1;
                while (x + width < SECTION_BLOCKS
                        && fluidAt[index + width]
                        && !consumed[index + width]) {
                    width++;
                }
                // Stack rows of the same width, so a rectangular lake becomes one quad rather than one per
                // row.
                int depth = 1;
                while (z + depth < SECTION_BLOCKS && sameRun(fluidAt, consumed,
                        (z + depth) * SECTION_BLOCKS + x, width)) {
                    depth++;
                }
                for (int dz = 0; dz < depth; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        consumed[(z + dz) * SECTION_BLOCKS + x + dx] = true;
                    }
                }
                merged++;
                x += width;
            }
        }
        this.quads += merged;
        return merged;
    }

    /** Whether a run of this width at this index is all fluid and unconsumed. */
    private static boolean sameRun(boolean[] fluidAt, boolean[] consumed, int index, int width) {
        for (int i = 0; i < width; i++) {
            if (!fluidAt[index + i] || consumed[index + i]) {
                return false;
            }
        }
        return true;
    }

    private static int countFluid(boolean[] fluidAt) {
        int count = 0;
        for (boolean fluid : fluidAt) {
            if (fluid) {
                count++;
            }
        }
        return count;
    }

    /** A level with every cell fluid, for tests and for a full lake section. */
    public static boolean[] full() {
        boolean[] out = new boolean[SECTION_BLOCKS * SECTION_BLOCKS];
        java.util.Arrays.fill(out, true);
        return out;
    }

    public long blocks() {
        return this.blocks;
    }

    /** How many quads the surfaces became. */
    public long quads() {
        return this.quads;
    }

    /** Bytes of vertex data the merged surfaces occupy. */
    public long bytes() {
        return this.quads * (long) BYTES_PER_QUAD;
    }

    public double quadsPerBlock() {
        return this.blocks == 0 ? 0.0 : (double) this.quads / this.blocks;
    }

    /** Milliseconds drawing the merged surfaces costs. */
    public double msDrawing() {
        return this.quads * MS_PER_QUAD;
    }

    /** Milliseconds drawing one quad per fluid block would cost. */
    public double msWithoutMerging() {
        return this.blocks * MS_PER_QUAD;
    }

    /**
     * The share of the fluid drawing avoided.
     *
     * <p>Zero before any surface has been merged. A merger that has merged nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutMerging();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msDrawing()) / without;
    }
}
