package net.amdfaster.mesh;

/**
 * Merges distant terrain into larger quads, keeping the silhouette.
 *
 * <p>Distant ground does not need a quad per block. Merging four by four cuts a 16 by 16 section face
 * from 256 quads to 16, which is 93.75% fewer, and eight by eight cuts it to four. Chunk columns near
 * the edge of render distance are mostly this, and that is where the draw count goes.
 *
 * <h2>The height has to be the maximum, not the average</h2>
 *
 * <p>A merged quad covering a peak and the valley beside it has to sit at the peak. Averaging puts it
 * in between and the mountain sinks below where it should be, which is a change in the skyline -- the
 * most visible thing about distant terrain, and the one a player notices without being able to say
 * what moved. Taking the maximum keeps the outline exact and can only add ground that was behind the
 * peak anyway.
 *
 * <h2>Colour</h2>
 *
 * <p>The merged quad takes the colour of its highest cell rather than the most common one. A snow cap
 * is one cell of snow over fifteen of rock, and the common colour would erase the cap, which is again
 * the skyline. The highest cell is the one actually visible from a distance.
 *
 * <h2>What merging cannot do</h2>
 *
 * <p>It cannot represent a hole. A merged quad is a single height, so a cave entrance or an overhang
 * inside the merged area is filled in. That is acceptable at the distances this is used for, and
 * {@link #levelForDistance} keeps fine detail where the player can resolve it: level 0 does not merge
 * at all.
 *
 * <p>Not thread safe.
 */
public final class LodMerger {

    /** Blocks along a section face. */
    public static final int SIZE = 16;

    /** Cells in a section face. */
    public static final int CELLS = SIZE * SIZE;

    /** The coarsest merge: one quad for the whole face. */
    public static final int MAX_LEVEL = 4;

    /** Distances at which each level starts, in blocks. */
    private static final double[] LEVEL_DISTANCES = {0.0, 96.0, 192.0, 320.0, 512.0};

    private long merges;
    private long quadsIn;
    private long quadsOut;

    /** One merged quad. */
    public static final class Merged {
        /** Corner of the merged area, in cells. */
        public final int cellX;
        public final int cellZ;

        /** Edge of the merged area, in cells. */
        public final int span;

        /** Height of the highest block covered. */
        public final int height;

        /** Colour of the highest block covered. */
        public final int colour;

        Merged(int cellX, int cellZ, int span, int height, int colour) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.span = span;
            this.height = height;
            this.colour = colour;
        }
    }

    /**
     * The merge level for a distance.
     *
     * <p>Coarse where the player cannot resolve the detail, fine where they can.
     */
    public static int levelForDistance(double distance) {
        if (Double.isNaN(distance) || distance < 0) {
            throw new IllegalArgumentException("distance must be a real number: " + distance);
        }
        int level = 0;
        for (int i = LEVEL_DISTANCES.length - 1; i >= 0; i--) {
            if (distance >= LEVEL_DISTANCES[i]) {
                level = i;
                break;
            }
        }
        return level;
    }

    /** The edge of a merged area at a level, in cells. */
    public static int spanAt(int level) {
        checkLevel(level);
        return 1 << level;
    }

    /**
     * Merges a section face.
     *
     * @param heights block height per cell, row-major, {@link #CELLS} long
     * @param colours colour per cell, same layout
     * @param level merge level from {@link #levelForDistance}
     * @param out filled with the merged quads; must hold at least {@link #quadsAt} entries
     * @return how many quads were written
     */
    public int merge(int[] heights, int[] colours, int level, Merged[] out) {
        if (heights.length != CELLS) {
            throw new IllegalArgumentException(
                    "heights must hold " + CELLS + " cells: " + heights.length);
        }
        if (colours.length != CELLS) {
            throw new IllegalArgumentException(
                    "colours must hold " + CELLS + " cells: " + colours.length);
        }
        checkLevel(level);
        int needed = quadsAt(level);
        if (out.length < needed) {
            throw new IllegalArgumentException(
                    "out must hold " + needed + " quads: " + out.length);
        }

        int span = spanAt(level);
        int groups = SIZE / span;
        int n = 0;
        this.merges++;
        this.quadsIn += CELLS;

        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                int bestHeight = Integer.MIN_VALUE;
                int bestColour = 0;
                for (int z = 0; z < span; z++) {
                    for (int x = 0; x < span; x++) {
                        int index = (gz * span + z) * SIZE + (gx * span + x);
                        int height = heights[index];
                        // Strictly greater, so the first cell wins ties. Taking the last would make a
                        // flat area's colour depend on iteration order, which is the kind of thing
                        // that flickers between frames when the mesh is rebuilt on another thread.
                        if (height > bestHeight) {
                            bestHeight = height;
                            bestColour = colours[index];
                        }
                    }
                }
                out[n++] = new Merged(gx * span, gz * span, span, bestHeight, bestColour);
            }
        }
        this.quadsOut += n;
        return n;
    }

    /** How many quads a level produces. */
    public static int quadsAt(int level) {
        checkLevel(level);
        int groups = SIZE >> level;
        return groups * groups;
    }

    private static void checkLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be 0.." + MAX_LEVEL + ": " + level);
        }
    }

    public long merges() {
        return this.merges;
    }

    public long quadsIn() {
        return this.quadsIn;
    }

    public long quadsOut() {
        return this.quadsOut;
    }

    /** Percentage of quads removed by merging. */
    public double reductionPercent() {
        return this.quadsIn == 0 ? 0.0 : 100.0 * (this.quadsIn - this.quadsOut) / this.quadsIn;
    }
}
