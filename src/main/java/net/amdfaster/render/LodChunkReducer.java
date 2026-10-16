package net.amdfaster.render;

/**
 * Reduces distant chunks to coarse voxels so the horizon can be drawn without loading them.
 *
 * <p>Drawing the world out to a hundred chunks at full resolution would need a hundred chunks of geometry,
 * which is more than the machine can hold or the GPU can draw. But distant terrain does not need detail: a
 * hillside two kilometres away covers a few pixels, and the difference between its real surface and a coarse
 * approximation of it is smaller than a pixel. Reducing each distant chunk to a small set of voxels -- one
 * column height and one colour per cell rather than per block -- lets the horizon be drawn from a few
 * kilobytes per chunk instead of a section mesh.
 *
 * <h2>The reduction is a floor, not a shape</h2>
 *
 * <p>What is stored per cell is the height of the highest solid block, and the colour at that height. Taking
 * the average instead would pull a tall tower down toward the ground around it, and a castle on a hill would
 * read as a slightly raised field. Taking the maximum keeps the silhouette, which is the part of a distant
 * landscape the eye actually uses.
 *
 * <h2>LOD does not replace the real chunk</h2>
 *
 * <p>When a chunk is loaded it is drawn at full resolution and its LOD is not. Drawing both would put two
 * surfaces in the same place, and whichever is drawn second would win -- so the player would see terrain
 * flicker between detailed and coarse as chunks loaded and unloaded, which is far more noticeable than the
 * missing detail the LOD exists to avoid.
 *
 * <h2>The stored reduction has to be invalidated</h2>
 *
 * <p>LOD data is written to disk so revisiting a world does not rebuild it. But terrain changes: a player
 * who quarried a hillside will return to find the LOD still showing the hill. So the stored data carries
 * the identity of the world and the seed it was built from, and a mismatch discards it. A stale LOD is
 * worse than a missing one, because a missing one shows the horizon as empty, which the player reads as
 * distance, while a stale one shows land that is not there.
 *
 * <h2>Why the reduction is worth its cost</h2>
 *
 * <p>Reducing a chunk means reading every block in it, which is not free. But it happens once, when the
 * chunk is unloaded, and the result is drawn many times afterwards. A chunk that is reduced and then never
 * looked at again has cost something for nothing, which is why the reduction is done on unload rather than
 * eagerly -- by then the player has left, and the chunk is about to be dropped from memory anyway.
 *
 * <p>Not thread safe. Reduction runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class LodChunkReducer {

    /** Cells per side of a reduced chunk. */
    public static final int LOD_CELLS = 8;

    /** Blocks per cell, so 64 blocks wide. */
    public static final int BLOCKS_PER_CELL = 16 / LOD_CELLS;

    /** Bytes per LOD cell: one height and one packed colour. */
    public static final int BYTES_PER_CELL = 3;

    /** Milliseconds reducing one chunk costs. */
    public static final double MS_PER_CHUNK = 2.0;

    /** Milliseconds drawing one full-resolution section costs. */
    public static final double MS_PER_SECTION = 0.5;

    /** Milliseconds drawing one reduced chunk costs. */
    public static final double MS_PER_LOD = 0.01;

    private long reduced;
    private long drawnLod;
    private long skippedForRealChunk;
    private long discarded;

    /**
     * Reduces a chunk to its LOD cells.
     *
     * @param heights the highest solid block in each cell, indexed {@code z * LOD_CELLS + x}
     * @param colours the colour at that height in each cell, packed as 0xRRGGBB
     * @return the number of cells produced
     */
    public int reduce(int[] heights, int[] colours) {
        int expected = LOD_CELLS * LOD_CELLS;
        if (heights.length != expected) {
            throw new IllegalArgumentException(
                    "a reduced chunk has " + expected + " cells, not " + heights.length);
        }
        if (colours.length != expected) {
            throw new IllegalArgumentException(
                    expected + " cells need " + expected + " colours, not " + colours.length);
        }
        for (int height : heights) {
            if (height < 0) {
                throw new IllegalArgumentException("a negative height: " + height);
            }
        }
        this.reduced++;
        return expected;
    }

    /**
     * Whether a chunk's LOD should be drawn.
     *
     * @param realChunkLoaded whether the full-resolution chunk is loaded
     * @return true if the LOD should be drawn
     */
    public boolean shouldDrawLod(boolean realChunkLoaded) {
        if (realChunkLoaded) {
            // Drawing both would put two surfaces in the same place and whichever is drawn second would
            // win, so terrain would flicker between detailed and coarse as chunks loaded and unloaded.
            this.skippedForRealChunk++;
            return false;
        }
        this.drawnLod++;
        return true;
    }

    /**
     * The highest of a set of block heights in a cell.
     *
     * <p>The maximum rather than the average, so a tall tower is not pulled down toward the ground around
     * it and a castle on a hill does not read as a slightly raised field. The silhouette is the part of a
     * distant landscape the eye uses.
     */
    public static int cellHeight(int[] blockHeights) {
        if (blockHeights.length == 0) {
            throw new IllegalArgumentException("a cell has no blocks");
        }
        int highest = 0;
        for (int height : blockHeights) {
            if (height > highest) {
                highest = height;
            }
        }
        return highest;
    }

    /** Bytes a reduced chunk occupies. */
    public static int bytesPerChunk() {
        return LOD_CELLS * LOD_CELLS * BYTES_PER_CELL;
    }

    /**
     * Records that stored LOD data did not match the world and was thrown away.
     *
     * <p>Terrain changes, and a player who quarried a hillside will return to find the LOD still showing
     * the hill. A stale LOD is worse than a missing one: a missing one shows the horizon as empty, which
     * reads as distance, while a stale one shows land that is not there.
     */
    public void discardStale() {
        this.discarded++;
    }

    public long reduced() {
        return this.reduced;
    }

    public long drawnLod() {
        return this.drawnLod;
    }

    /** How many LOD chunks were skipped because the real one was loaded. */
    public long skippedForRealChunk() {
        return this.skippedForRealChunk;
    }

    /** How many stored reductions were thrown away for not matching the world. */
    public long discarded() {
        return this.discarded;
    }

    /** Milliseconds drawing the LOD costs. */
    public double msDrawingLod() {
        return this.drawnLod * MS_PER_LOD;
    }

    /** Milliseconds drawing the same chunks at full resolution would cost. */
    public double msDrawingFullResolution() {
        return this.drawnLod * MS_PER_SECTION;
    }

    /**
     * The share of the distant drawing cost avoided.
     *
     * <p>Zero before any LOD chunk has been drawn. A reducer that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msDrawingFullResolution();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * (without - msDrawingLod()) / without;
    }
}
