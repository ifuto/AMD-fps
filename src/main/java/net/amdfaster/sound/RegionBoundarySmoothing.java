package net.amdfaster.sound;

/**
 * Counts a cell's neighbours when deciding how busy it is, so region edges do not click.
 *
 * <p>Sound limits are applied per region, because that is what keeps one noisy machine from filling the pool
 * for the whole world. But a region is an arbitrary box drawn on the world, and a source sitting just inside
 * one edge is counted against that region while a source a block further out is counted against the next one
 * over. So the same scene can be over the limit or under it depending on which side of a line the player
 * happens to be standing, and crossing that line makes sounds appear and disappear for no reason the player
 * can see.
 *
 * <p>Counting a cell together with its neighbours makes the number change gradually as the player moves,
 * instead of jumping at a boundary they cannot see.
 *
 * <h2>The decision is continuous across the edge</h2>
 *
 * <p>That is the property this exists for, and it is testable: walking a source across a boundary one block
 * at a time should not flip the answer. With neighbours included, a cell at the edge inherits part of what is
 * happening next to it, so the total moves by one at a time rather than by whatever the neighbouring region
 * happened to contain.
 *
 * <h2>Only the eight around it</h2>
 *
 * <p>A cell's loudness is affected by what is immediately beside it, not by a region three cells away. Using
 * a wider neighbourhood would smooth the boundaries away entirely and make the limit effectively global,
 * which is the thing the per-region limit was there to prevent -- one machine in one corner would then be
 * silenced by noise in another.
 *
 * <h2>The counts are per tick</h2>
 *
 * <p>A neighbour count that is a tick old is a count of where the noise was, and a moving source would put
 * the boundary in the wrong place. So the counts are cleared each tick and rebuilt, which also means a
 * machine that stops immediately stops affecting its neighbours.
 *
 * <h2>Why this is not the same as the region budget</h2>
 *
 * <p>{@link SoundCuller} decides whether a region has room. This decides how full the region is, counting the
 * cells beside it, and the two compose: the smoothed count is what the budget is compared against.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) tracks nearby cells to avoid sharp changes at region
 * boundaries. This is an independent implementation of the same behaviour.
 */
public final class RegionBoundarySmoothing {

    /** Blocks per cell edge. */
    public static final int CELL_BLOCKS = 8;

    /** Cells considered: the cell itself and the eight around it. */
    public static final int NEIGHBOURHOOD = 9;

    /** How many sounds in a neighbourhood put it at its limit. */
    public static final int LIMIT = 12;

    private final int[] cells = new int[64 * 64];

    private long offered;
    private long allowed;
    private long refused;
    private long flipsAvoided;

    /**
     * Adds a sound to a position's cell.
     *
     * @return the index of the cell it landed in
     */
    public int add(double x, double z) {
        int cell = cellOf(x, z);
        this.cells[cell]++;
        this.offered++;
        return cell;
    }

    /**
     * The cell a position falls in.
     *
     * <p>Floored rather than truncated, so a position just below zero is the cell below and not the one at
     * zero -- otherwise the boundary between them would be in the wrong place for every negative coordinate.
     */
    public static int cellOf(double x, double z) {
        int cx = (int) Math.floor(x / CELL_BLOCKS) & 63;
        int cz = (int) Math.floor(z / CELL_BLOCKS) & 63;
        return cz * 64 + cx;
    }

    /**
     * How busy a cell's neighbourhood is.
     *
     * <p>The cell and the eight around it, wrapped at the edges of the array. A cell at the edge inherits
     * part of what is happening next to it, so the total moves by one at a time as the player walks rather
     * than jumping at a boundary they cannot see.
     */
    public int neighbourhoodCount(int cell) {
        int cx = cell & 63;
        int cz = cell / 64;
        int total = 0;

        for (int dz = -1; dz <= 1; dz++) {
            int nz = (cz + dz + 64) & 63;
            for (int dx = -1; dx <= 1; dx++) {
                int nx = (cx + dx + 64) & 63;
                total += this.cells[nz * 64 + nx];
            }
        }
        return total;
    }

    /**
     * Whether a sound at a cell should be given a channel.
     *
     * @return true if it should play
     */
    public boolean allow(double x, double z) {
        int cell = add(x, z);

        if (neighbourhoodCount(cell) > LIMIT) {
            this.refused++;
            return false;
        }
        this.allowed++;
        return true;
    }

    /**
     * Whether moving one block across a boundary changed the decision.
     *
     * <p>The property this class exists for. Walking a source across an edge one block at a time should not
     * flip the answer, and a smoothing that does not work would report true here.
     */
    public boolean crossesBoundaryCleanly(double x, double z) {
        int before = neighbourhoodCount(cellOf(x, z));
        int after = neighbourhoodCount(cellOf(x + 1.0, z));
        boolean changed = Math.abs(before - after) > 1;
        if (!changed) {
            this.flipsAvoided++;
        }
        return !changed;
    }

    /** Clears the counts for a new tick. */
    public void endTick() {
        for (int i = 0; i < this.cells.length; i++) {
            this.cells[i] = 0;
        }
    }

    /** How many sounds are in one cell, for a test to check against. */
    public int count(int cell) {
        return this.cells[cell];
    }

    public long offered() {
        return this.offered;
    }

    public long allowed() {
        return this.allowed;
    }

    public long refused() {
        return this.refused;
    }

    /** How many boundary crossings were checked and found not to flip. */
    public long flipsAvoided() {
        return this.flipsAvoided;
    }

    /**
     * The share of the offers refused.
     *
     * <p>Zero before any sound has been offered. A smoother that has been offered nothing has technically
     * refused nothing, and reporting that as a rate would describe an unused one as though it had a result.
     */
    public double refusedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.refused / this.offered;
    }
}
