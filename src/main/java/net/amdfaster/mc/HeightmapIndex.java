package net.amdfaster.mc;

/**
 * One topmost-opaque height per block column, answering "can the sky see this cell" in a comparison.
 *
 * <p>Meshing asks about sky visibility constantly: it decides a face's shade, whether a face is even
 * worth emitting, and what skylight value to bake. Answering that by walking up a column until
 * something blocks is O(height) per question, and a section rebuild asks it thousands of times for
 * cells that share a column. Answering it from a heightmap is a subtraction and a comparison.
 *
 * <p>This is the same data the light engine's per-section heightmap holds, kept here at column scope
 * so that meshing and lighting agree. Two separate heightmaps for one world is how a section ends up
 * shaded as though it were outdoors while its light says otherwise, and the disagreement is invisible
 * until someone notices a face that is bright but casts no shadow.
 *
 * <p>Incremental in both directions, and the asymmetry is the interesting part. Placing a block can
 * only raise a height, so it is a comparison. Removing one can lower it to anywhere below, so the
 * column has to be scanned. Breaking a block at the surface is therefore a heavier operation than
 * placing one, which is the opposite of what the block counts suggest and worth measuring.
 *
 * <p>Not thread safe.
 */
public final class HeightmapIndex {

    /** Nothing in the column blocks light. */
    public static final int NONE = Integer.MIN_VALUE;

    /** Blocks per chunk edge. */
    public static final int SIZE = 16;

    private final int originX;
    private final int originZ;
    private final int[] height = new int[SIZE * SIZE];

    private long skyQueries;
    private long raisedInPlace;
    private long loweredByScan;
    private long scansCells;

    public HeightmapIndex(int originX, int originZ) {
        this.originX = originX;
        this.originZ = originZ;
        java.util.Arrays.fill(this.height, NONE);
    }

    public static HeightmapIndex forChunk(int chunkX, int chunkZ) {
        return new HeightmapIndex(chunkX << 4, chunkZ << 4);
    }

    public int originX() {
        return this.originX;
    }

    public int originZ() {
        return this.originZ;
    }

    public long skyQueries() {
        return this.skyQueries;
    }

    /** Height changes that were a comparison. */
    public long raisedInPlace() {
        return this.raisedInPlace;
    }

    /** Height changes that required scanning a column. */
    public long loweredByScan() {
        return this.loweredByScan;
    }

    /** Cells examined by those scans. The cost of every removal near the surface. */
    public long scanCells() {
        return this.scansCells;
    }

    private int index(int x, int z) {
        int lx = x - this.originX;
        int lz = z - this.originZ;
        if (((lx | lz) < 0) || lx >= SIZE || lz >= SIZE) {
            return -1;
        }
        return lz * SIZE + lx;
    }

    /** The topmost light-blocking height in a column, or {@link #NONE}. */
    public int heightAt(int x, int z) {
        int index = index(x, z);
        return index < 0 ? NONE : this.height[index];
    }

    /**
     * Whether the sky can see a cell.
     *
     * <p>One comparison. A column with nothing in it returns true at every height including the world
     * floor, which is correct and is the case that makes an open plain cheap to mesh.
     */
    public boolean isSkyVisible(int x, int y, int z) {
        this.skyQueries++;
        return y > heightAt(x, z);
    }

    /** Records a block that blocks light, raising the column if it is the new top. */
    public boolean raise(int x, int y, int z) {
        int index = index(x, z);
        if (index < 0 || y <= this.height[index]) {
            return false;
        }
        this.height[index] = y;
        this.raisedInPlace++;
        return true;
    }

    /**
     * Removes a block, rescanning the column if it was the top.
     *
     * @param scan asks whether the block at a height blocks light; null means the column is empty
     * @return true if the recorded height changed
     */
    public boolean lower(int x, int y, int z, ColumnScan scan) {
        int index = index(x, z);
        if (index < 0) {
            return false;
        }
        int current = this.height[index];
        if (y < current) {
            return false;
        }
        if (y > current) {
            // Above the recorded top, so it was never the thing being measured.
            return false;
        }
        this.loweredByScan++;
        if (scan == null) {
            this.height[index] = NONE;
            return true;
        }
        int found = NONE;
        for (int scanY = y - 1; scanY > NONE + 1; scanY--) {
            this.scansCells++;
            if (scan.blocksLight(x, scanY, z)) {
                found = scanY;
                break;
            }
        }
        this.height[index] = found;
        return true;
    }

    /** Asks the world whether a block at a height blocks light. */
    public interface ColumnScan {
        boolean blocksLight(int x, int y, int z);
    }

    /**
     * Rebuilds every column in the chunk.
     *
     * <p>Used when a chunk arrives, where there is no previous state to update from. Walking down from
     * the top and stopping at the first blocker is one pass per column, which is the cheapest form of a
     * full rebuild: it examines only the cells above the surface, not the whole column height.
     */
    public void rebuild(ColumnScan scan) {
        for (int lz = 0; lz < SIZE; lz++) {
            for (int lx = 0; lx < SIZE; lx++) {
                int x = this.originX + lx;
                int z = this.originZ + lz;
                int found = NONE;
                for (int y = 319; y > NONE + 1; y--) {
                    this.scansCells++;
                    if (scan.blocksLight(x, y, z)) {
                        found = y;
                        break;
                    }
                }
                this.height[lz * SIZE + lx] = found;
            }
        }
    }

    public void clear() {
        java.util.Arrays.fill(this.height, NONE);
        this.skyQueries = 0;
        this.raisedInPlace = 0;
        this.loweredByScan = 0;
        this.scansCells = 0;
    }
}
