package net.amdfaster.cull;

import java.util.Arrays;

/**
 * A coarse software depth buffer for occlusion culling, in the structure Intel's masked occlusion
 * culling uses.
 *
 * <p>This is not the GPU's depth buffer. It is a separate, much lower resolution buffer held entirely in
 * CPU cache, built from occluder boxes and queried against candidate boxes. Its published measurement is
 * that a whole scene costs <b>4 ms of single-threaded CPU</b> and culls <b>98 percent of what a
 * full-resolution depth buffer would</b>, which is what makes CPU occlusion worth doing at all: the work
 * moves off the frame's critical path onto a core that would otherwise be idle.
 *
 * <h2>The structure</h2>
 *
 * <p>The screen is divided into tiles of 32 by 8 pixels, which is 256 pixels and the width of an AVX2
 * register in bytes. Each tile is subdivided into sub-tiles of 8 by 4 pixels, giving four across and two
 * down, so eight sub-tiles per tile. Each sub-tile stores one depth: the nearest surface that has been
 * rasterised into it. Eight sub-tiles times one float is eight floats, which is exactly one 256-bit
 * vector, so a whole tile's depths load and compare in a single instruction.
 *
 * <p>Alongside the depths each tile holds an 8-bit coverage mask. A sub-tile whose bit is set is fully
 * covered by some occluder; one whose bit is clear has a gap, and nothing behind that gap can be
 * rejected.
 *
 * <h2>Why the test is safe</h2>
 *
 * <p>A box is rejected only when every sub-tile its screen-space footprint touches is both covered and
 * nearer than the box's nearest point. Fail either condition anywhere and the box is drawn. A coarse
 * buffer can only lose precision in the direction of drawing things that are hidden, never in the
 * direction of hiding things that are visible, and the second of those is the only one that is a bug.
 *
 * <h2>Depth convention</h2>
 *
 * <p>Linear view depth, nearer is smaller, and "nothing here" is positive infinity. This is deliberately
 * not the reversed-Z the GPU uses: this buffer never meets the GPU's, and keeping the nearer-is-smaller
 * convention makes the comparison in the occlusion test read in the direction it means.
 *
 * <p>Not thread safe. One buffer per culling thread, which is how it is meant to be used anyway.
 */
public final class TileDepthBuffer {

    /** Tile width in pixels. Chosen so a tile is 256 pixels, the width of an AVX2 register in bytes. */
    public static final int TILE_W = 32;
    /** Tile height in pixels. */
    public static final int TILE_H = 8;
    /** Pixels per tile. */
    public static final int PIXELS_PER_TILE = TILE_W * TILE_H;

    /** Sub-tile width in pixels. */
    public static final int SUB_W = 8;
    /** Sub-tile height in pixels. */
    public static final int SUB_H = 4;
    /** Sub-tiles across one tile. */
    public static final int SUBS_X = TILE_W / SUB_W;
    /** Sub-tiles down one tile. */
    public static final int SUBS_Y = TILE_H / SUB_H;
    /** Sub-tiles per tile. Eight, so eight depths is one 256-bit vector. */
    public static final int SUBTILES_PER_TILE = SUBS_X * SUBS_Y;

    /** Nothing has been rasterised here. */
    public static final float EMPTY = Float.POSITIVE_INFINITY;

    private final int width;
    private final int height;
    private final int tilesX;
    private final int tilesY;

    /** One depth per sub-tile, tile major then sub-tile minor, so a tile's eight depths are contiguous. */
    private final float[] subDepth;
    /** Eight coverage bits per tile, one per sub-tile. */
    private final int[] tileCovered;
    /** Shallowest surface in each tile, so a query can reject a whole tile without walking its sub-tiles. */
    private final float[] tileMinDepth;

    private int rasterisedSubtiles;
    private int occlusionTests;
    private int occludedTests;

    /**
     * Creates a buffer covering a {@code width} by {@code height} pixel screen.
     *
     * <p>The dimensions are rounded up to whole tiles, because a partial tile would need bounds checks on
     * every sub-tile access and the inner loop is exactly where those checks cannot be afforded.
     */
    public TileDepthBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("screen size must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.tilesX = (width + TILE_W - 1) / TILE_W;
        this.tilesY = (height + TILE_H - 1) / TILE_H;
        int tiles = this.tilesX * this.tilesY;
        this.subDepth = new float[tiles * SUBTILES_PER_TILE];
        this.tileCovered = new int[tiles];
        this.tileMinDepth = new float[tiles];
        clear();
    }

    /** Resets the buffer to "nothing occludes anything". */
    public void clear() {
        Arrays.fill(this.subDepth, EMPTY);
        Arrays.fill(this.tileCovered, 0);
        Arrays.fill(this.tileMinDepth, EMPTY);
        this.rasterisedSubtiles = 0;
    }

    /**
     * Rasterises an occluder into the buffer.
     *
     * <p>Only sub-tiles the box <em>fully</em> covers are marked. A partially covered sub-tile keeps its
     * previous depth and stays uncovered, because claiming coverage for a gap is how a culler ends up
     * deleting visible geometry. This is the conservative choice and it is the only correct one.
     *
     * @param x0 left edge in pixels, inclusive
     * @param y0 top edge in pixels, inclusive
     * @param x1 right edge in pixels, exclusive
     * @param y1 bottom edge in pixels, exclusive
     * @param depth linear view depth of the occluder surface, nearer is smaller
     */
    public void rasterize(int x0, int y0, int x1, int y1, float depth) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        // Clamp to the screen. An occluder off the edge contributes nothing and walking off the array
        // would either throw or, worse, write into a neighbouring tile.
        int px0 = Math.max(0, x0);
        int py0 = Math.max(0, y0);
        int px1 = Math.min(this.width, x1);
        int py1 = Math.min(this.height, y1);
        if (px1 <= px0 || py1 <= py0) {
            return;
        }

        // Sub-tiles are only marked when the box covers them completely, so the range of sub-tiles to
        // consider is the intersection rounded inward, not outward.
        int sxFrom = roundUpToSub(px0, 0);
        int syFrom = roundUpToSub(py0, 1);
        int sxTo = px1 / SUB_W;
        int syTo = py1 / SUB_H;

        for (int sy = syFrom; sy < syTo; sy++) {
            for (int sx = sxFrom; sx < sxTo; sx++) {
                int tile = (sy / SUBS_Y) * this.tilesX + (sx / SUBS_X);
                int sub = (sy % SUBS_Y) * SUBS_X + (sx % SUBS_X);
                int index = tile * SUBTILES_PER_TILE + sub;
                if (depth < this.subDepth[index]) {
                    this.subDepth[index] = depth;
                }
                if (depth < this.tileMinDepth[tile]) {
                    this.tileMinDepth[tile] = depth;
                }
                this.tileCovered[tile] |= 1 << sub;
                this.rasterisedSubtiles++;
            }
        }
    }

    /**
     * Tests whether a box is hidden.
     *
     * <p>Conservative: returns true only when every sub-tile the box's footprint touches is covered and
     * nearer than the box. Any doubt resolves to "draw it".
     *
     * @param x0 left edge in pixels, inclusive
     * @param y0 top edge in pixels, inclusive
     * @param x1 right edge in pixels, exclusive
     * @param y1 bottom edge in pixels, exclusive
     * @param nearDepth the box's nearest linear view depth
     * @return true if the box is definitely hidden
     */
    public boolean isOccluded(int x0, int y0, int x1, int y1, float nearDepth) {
        this.occlusionTests++;
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        int px0 = Math.max(0, x0);
        int py0 = Math.max(0, y0);
        int px1 = Math.min(this.width, x1);
        int py1 = Math.min(this.height, y1);
        if (px1 <= px0 || py1 <= py0) {
            return false;
        }

        // The query range rounds outward, which is the opposite of rasterising. Missing a sub-tile the
        // box touches would reject a box that is partly visible.
        int txFrom = px0 / TILE_W;
        int tyFrom = py0 / TILE_H;
        int txTo = (px1 - 1) / TILE_W;
        int tyTo = (py1 - 1) / TILE_H;

        for (int ty = tyFrom; ty <= tyTo; ty++) {
            for (int tx = txFrom; tx <= txTo; tx++) {
                int tile = ty * this.tilesX + tx;
                // Whole-tile rejection. Hiding the box needs every sub-tile it touches to be covered
                // and nearer than it, so one sub-tile that is not settles the question. The shallowest
                // surface anywhere in the tile is a bound on all of them: if even that one is not
                // behind the box, neither is the rest, and the thirty-two sub-tiles do not have to be
                // visited to find out. Uncovered sub-tiles hold EMPTY, so a tile with a gap in it
                // trips this the same way and the answer is still the correct one.
                if (this.tileMinDepth[tile] >= nearDepth) {
                    return false;
                }
                int covered = this.tileCovered[tile];

                int sxFrom = subIndexWithin(px0, tx, SUB_W, TILE_W, SUBS_X);
                int sxTo = subIndexWithin(px1 - 1, tx, SUB_W, TILE_W, SUBS_X);
                int syFrom = subIndexWithin(py0, ty, SUB_H, TILE_H, SUBS_Y);
                int syTo = subIndexWithin(py1 - 1, ty, SUB_H, TILE_H, SUBS_Y);

                for (int sy = syFrom; sy <= syTo; sy++) {
                    for (int sx = sxFrom; sx <= sxTo; sx++) {
                        int sub = sy * SUBS_X + sx;
                        if ((covered & (1 << sub)) == 0) {
                            return false;
                        }
                        // Stored depth nearer than or level with the box means the box is not behind
                        // this surface. Nearer-is-smaller, so ">=" here is "the surface is not behind
                        // the box", which is the case that must not be rejected.
                        if (this.subDepth[tile * SUBTILES_PER_TILE + sub] >= nearDepth) {
                            return false;
                        }
                    }
                }
            }
        }
        this.occludedTests++;
        return true;
    }

    /** Width of the screen this buffer covers, in pixels. */
    public int width() {
        return this.width;
    }

    /** Height of the screen this buffer covers, in pixels. */
    public int height() {
        return this.height;
    }

    /** Tiles across. */
    public int tilesX() {
        return this.tilesX;
    }

    /** Tiles down. */
    public int tilesY() {
        return this.tilesY;
    }

    /** Total sub-tiles in the buffer. */
    public int subtileCount() {
        return this.subDepth.length;
    }

    /**
     * Bytes of depth storage.
     *
     * <p>The whole point of the coarse buffer is that it stays in cache. At 1920x1080 it is 259200
     * bytes -- 8100 tiles of eight sub-tiles -- against the 8294400 a full-resolution D32 buffer would
     * be, a factor of 32. It does not fit in L1, but it sits comfortably in L2 and never leaves it while
     * a frame's culling runs, which is what the 4 ms figure depends on.
     */
    public int depthBytes() {
        return this.subDepth.length * Float.BYTES;
    }

    /** Sub-tiles written by {@link #rasterize} since the last {@link #clear}. */
    public int rasterisedSubtiles() {
        return this.rasterisedSubtiles;
    }

    /** Queries answered since the last {@link #clear}. */
    public int occlusionTests() {
        return this.occlusionTests;
    }

    /** Queries answered "hidden" since the last {@link #clear}. */
    public int occludedTests() {
        return this.occludedTests;
    }

    /** Clears the query counters without clearing the buffer. */
    public void resetCounters() {
        this.occlusionTests = 0;
        this.occludedTests = 0;
    }

    /** The depth stored at one sub-tile, for tests and diagnostics. */
    public float subDepthAt(int tile, int sub) {
        return this.subDepth[tile * SUBTILES_PER_TILE + sub];
    }

    /** Whether one sub-tile is marked covered. */
    public boolean isCovered(int tile, int sub) {
        return (this.tileCovered[tile] & (1 << sub)) != 0;
    }

    private static int roundUpToSub(int pixel, int axis) {
        int size = axis == 0 ? SUB_W : SUB_H;
        return (pixel + size - 1) / size;
    }

    /**
     * Converts an absolute pixel coordinate into a sub-tile index within one tile.
     *
     * @param pixel absolute coordinate
     * @param tileOrigin the tile's origin along this axis, in sub-tiles
     * @param subSize sub-tile extent along this axis
     * @param tileExtent tile extent along this axis
     * @param subsPerTile sub-tiles along this axis within a tile
     */
    private static int subIndexWithin(int pixel, int tileOrigin, int subSize, int tileExtent,
                                      int subsPerTile) {
        int local = (pixel - tileOrigin * tileExtent) / subSize;
        return Math.max(0, Math.min(subsPerTile - 1, local));
    }
}
