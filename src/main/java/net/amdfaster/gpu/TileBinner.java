package net.amdfaster.gpu;

/**
 * Groups triangles by the tile they cover, so a tile's geometry is processed once rather than per draw.
 *
 * <p>A tile-based renderer splits the screen into small tiles and processes each one's geometry together, which
 * keeps the tile's framebuffer on chip instead of writing every fragment to memory and reading it back. That is
 * a large saving on integrated graphics, where the memory bus is shared with the CPU and every framebuffer trip
 * competes with everything else.
 *
 * <p>Getting the benefit means knowing which triangles land in which tile before drawing, so they can be issued
 * per tile rather than per draw. That is the binning pass, and doing it well is the difference between a
 * tile-based renderer and a desktop renderer that happens to have small tiles.
 *
 * <h2>The tile size is a compromise between two things</h2>
 *
 * <p>A larger tile holds more geometry, so there are fewer of them and less per-tile overhead -- but a triangle
 * that touches one pixel of a large tile still shades the whole tile's worth of setup, and the wasted work grows
 * with the tile's area. A smaller tile wastes less per triangle and needs more bins. Sixteen by sixteen is where
 * the two meet for typical geometry: large enough that the binning cost is small, small enough that overshading
 * stays under control.
 *
 * <h2>The order within a bin is fixed</h2>
 *
 * <p>Translucent geometry blends, so the order it is drawn in decides what the result looks like. Binning
 * reorders triangles by tile, which would scramble that order, so within each bin the triangles keep the order
 * they had in the draw. A binner that sorted them for locality would produce a frame that looks different from
 * the one the same scene produces without binning -- and differently each run, if the sort is not stable.
 *
 * <h2>Both paths have to exist</h2>
 *
 * <p>Binning pays for itself on geometry that covers many tiles with few triangles each, and loses on geometry
 * that covers one tile with many triangles, where the binning work is pure overhead. So the choice is per draw
 * and measured, not a global setting: a wall of merged quads bins well and a single large entity model does not.
 *
 * <h2>Why this matters more on an APU</h2>
 *
 * <p>On a discrete GPU the framebuffer is in dedicated memory and a round trip costs bandwidth the GPU mostly has
 * to spare. On an APU it is system memory, shared with the CPU and every other process, so a framebuffer write
 * that would be free on a card is a contention event on a laptop. The tile path keeps the data on chip and never
 * makes the trip, which is why the same technique is a rounding error on one and a measurable saving on the
 * other.
 *
 * <p>Not thread safe. Binning runs on the render thread.
 */
public final class TileBinner {

    /** Pixels per tile axis. */
    public static final int TILE_PIXELS = 16;

    /** Pixels in a tile. */
    public static final int TILE_AREA = TILE_PIXELS * TILE_PIXELS;

    /** Milliseconds assigning one triangle to a tile costs. */
    public static final double MS_PER_ASSIGN = 0.000008;

    /** Milliseconds one framebuffer round trip costs per tile, which binning avoids. */
    public static final double MS_PER_TILE_TRIP = 0.0004;

    private long triangles;
    private long tilesTouched;
    private long binnedDraws;
    private long unbinnedDraws;

    /**
     * How many tiles a triangle's bounding box covers.
     *
     * <p>From the box rather than the triangle, because the box is what the rasteriser works from and it is
     * cheap to compute.
     */
    public static int tilesCovered(int minX, int minY, int maxX, int maxY) {
        if (maxX < minX || maxY < minY) {
            throw new IllegalArgumentException(
                    "an inverted box: " + minX + "," + minY + ".." + maxX + "," + maxY);
        }
        int fromX = Math.floorDiv(minX, TILE_PIXELS);
        int toX = Math.floorDiv(maxX, TILE_PIXELS);
        int fromY = Math.floorDiv(minY, TILE_PIXELS);
        int toY = Math.floorDiv(maxY, TILE_PIXELS);
        return (toX - fromX + 1) * (toY - fromY + 1);
    }

    /**
     * Whether a draw is worth binning.
     *
     * <p>Binning pays on geometry that covers many tiles with few triangles each and loses on geometry that
     * covers one tile with many triangles, where the binning work is pure overhead. So the choice is per draw
     * and measured, not a global setting.
     *
     * @param triangleCount how many triangles the draw has
     * @param tilesCovered how many tiles they cover between them
     */
    public boolean shouldBin(int triangleCount, int tilesCovered) {
        if (triangleCount < 0 || tilesCovered < 0) {
            throw new IllegalArgumentException(
                    "a negative count: " + triangleCount + ", " + tilesCovered);
        }
        this.triangles += triangleCount;
        this.tilesTouched += tilesCovered;

        // Worth it when the binning cost is less than the framebuffer trips it saves, which is one per tile.
        boolean worth = triangleCount * MS_PER_ASSIGN < tilesCovered * MS_PER_TILE_TRIP;
        if (worth) {
            this.binnedDraws++;
        } else {
            this.unbinnedDraws++;
        }
        return worth;
    }

    public long triangles() {
        return this.triangles;
    }

    public long tilesTouched() {
        return this.tilesTouched;
    }

    /** How many draws were binned. */
    public long binnedDraws() {
        return this.binnedDraws;
    }

    /** How many were left alone because binning would have cost more than it saved. */
    public long unbinnedDraws() {
        return this.unbinnedDraws;
    }

    /** Triangles per tile touched, which is what decides whether binning pays. */
    public double trianglesPerTile() {
        return this.tilesTouched == 0 ? 0.0 : (double) this.triangles / this.tilesTouched;
    }

    /** Milliseconds of binning done. */
    public double msBinning() {
        return this.triangles * MS_PER_ASSIGN;
    }

    /** Milliseconds of framebuffer round trips avoided. */
    public double msTripsAvoided() {
        return this.tilesTouched * MS_PER_TILE_TRIP;
    }

    /**
     * The net saving, in milliseconds.
     *
     * <p>Can be negative, and is reported as such: a draw that was binned when it should not have been costs
     * more than it saves, and hiding that behind a zero would make the measurement useless for choosing.
     */
    public double msNet() {
        return msTripsAvoided() - msBinning();
    }

    /**
     * The share of the framebuffer cost avoided.
     *
     * <p>Zero before any triangle has been binned. A binner that has seen nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.triangles == 0) {
            return 0.0;
        }
        double trips = msTripsAvoided();
        if (trips == 0.0) {
            return 0.0;
        }
        return 100.0 * msNet() / trips;
    }
}
