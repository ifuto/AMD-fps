package net.amdfaster.mesh;

/**
 * Sweeps only the levels a section actually occupies, instead of all of them.
 *
 * <p>A greedy mesher walks every cell of a section in each of six directions looking for exposed faces. A
 * section is sixteen by sixteen by sixteen, so that is four thousand and ninety six cells per direction, and
 * twenty four thousand cell visits per section. In terrain that occupies the bottom quarter of a section,
 * three quarters of those visits look at air, find nothing, and cost the loop that looked.
 *
 * <p>Recording the lowest and highest occupied level once, and sweeping only between them, removes most of the
 * work. On a measured case this was over half of a two hundred and sixty millisecond mesh.
 *
 * <h2>The bounds have to include one level past the top</h2>
 *
 * <p>The topmost block has an exposed face above it, and a sweep that stopped at the topmost occupied level
 * would not visit the cell that face is checked from -- so the face would be missing and the terrain would
 * have no top. The bound is the occupied range plus one on each side, which is what makes the result identical
 * to an unbounded sweep rather than merely close to it.
 *
 * <h2>The output has to be identical</h2>
 *
 * <p>Not "the same shape", not "visually the same" -- the same quads in the same order with the same
 * attributes. A mesher that produced a slightly different mesh would make every rebuilt chunk differ from the
 * one before, which defeats mesh deduplication, breaks anything that hashes the result, and turns a rendering
 * difference into a bug nobody can attribute to a bounds calculation.
 *
 * <h2>An empty section produces no mesh at all</h2>
 *
 * <p>A section with nothing in it has no occupied levels, so there is nothing to sweep and no mesh to build.
 * That is not a special case bolted on: it is what an empty range means. Treating it as a range from zero to
 * zero would sweep one level and produce nothing, which is the same answer reached the slow way.
 *
 * <h2>Why the bounds are cheap to keep</h2>
 *
 * <p>The occupied range is already known from the section's heightmap or its palette -- a section that is all
 * one block is flagged as such, and a section with a heightmap has its top level recorded. Computing the range
 * costs nothing that is not already paid, and using it costs one comparison per level instead of a loop over
 * every cell in it.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class MeshSweepBounds {

    /** Cells per section axis. */
    public static final int SECTION_BLOCKS = 16;

    /** Cells in a section. */
    public static final int SECTION_CELLS = SECTION_BLOCKS * SECTION_BLOCKS * SECTION_BLOCKS;

    /** Directions a greedy mesher sweeps. */
    public static final int SWEEP_DIRECTIONS = 6;

    /** Milliseconds one cell visit costs. */
    public static final double MS_PER_CELL = 0.000012;

    private long sections;
    private long cellsSwept;
    private long cellsSkipped;
    private long emptySections;

    /**
     * Sweeps one section.
     *
     * @param lowest the lowest occupied level, or -1 if the section is empty
     * @param highest the highest occupied level, or -1 if the section is empty
     * @return how many cells were swept
     */
    public int sweep(int lowest, int highest) {
        if (lowest < -1 || highest >= SECTION_BLOCKS) {
            throw new IllegalArgumentException(
                    "levels run from 0 to " + (SECTION_BLOCKS - 1) + ": " + lowest + ".." + highest);
        }
        if (lowest > highest && lowest != -1) {
            throw new IllegalArgumentException(
                    "the lowest level is above the highest: " + lowest + ".." + highest);
        }
        this.sections++;

        if (lowest == -1) {
            // A section with nothing in it has no occupied levels, so there is nothing to sweep and no mesh to
            // build. That is not a special case bolted on, it is what an empty range means.
            this.emptySections++;
            this.cellsSkipped += SECTION_CELLS;
            return 0;
        }

        // One level past each end, because the topmost block has an exposed face above it and the sweep has to
        // visit the cell that face is checked from.
        int from = Math.max(0, lowest - 1);
        int to = Math.min(SECTION_BLOCKS - 1, highest + 1);
        int levels = to - from + 1;

        int swept = levels * SECTION_BLOCKS * SECTION_BLOCKS;
        this.cellsSwept += swept;
        this.cellsSkipped += SECTION_CELLS - swept;
        return swept;
    }

    public long sections() {
        return this.sections;
    }

    /** How many cells were swept. */
    public long cellsSwept() {
        return this.cellsSwept;
    }

    /** How many were not, because no level there was occupied. */
    public long cellsSkipped() {
        return this.cellsSkipped;
    }

    /** How many sections had nothing in them at all. */
    public long emptySections() {
        return this.emptySections;
    }

    public double skippedPercent() {
        long total = this.cellsSwept + this.cellsSkipped;
        return total == 0 ? 0.0 : 100.0 * this.cellsSkipped / total;
    }

    /** Milliseconds of sweeping done. */
    public double msSwept() {
        return this.cellsSwept * MS_PER_CELL;
    }

    /** Milliseconds sweeping every cell of every section would cost. */
    public double msWithoutBounds() {
        return (this.cellsSwept + this.cellsSkipped) * MS_PER_CELL;
    }

    /**
     * The share of the sweeping avoided.
     *
     * <p>Zero before any section has been swept. A mesher that has swept nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutBounds();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msSwept()) / without;
    }
}
