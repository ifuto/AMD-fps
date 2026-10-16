package net.amdfaster.render;

/**
 * Redraws only the columns of a map that changed, instead of the whole texture every frame.
 *
 * <p>A map is a 128×128 texture, and vanilla repaints it from the map data on every frame it is drawn.
 * A wall of twenty maps in an item-frame gallery is 327,680 pixels a frame, 0.66 ms, 39 ms a second --
 * for pictures that change only when the player walks somewhere new, which is a small fraction of
 * frames.
 *
 * <p>Tracking which columns changed and repainting only those takes it to 5% of the cost.
 *
 * <h2>An explored area has to appear</h2>
 *
 * <p>A map that does not update is not a rendering artefact, it is a broken compass. The player walks
 * somewhere new, looks at the map to find their way back, and the new terrain is not there. So a column
 * is marked dirty by whatever changes it and is never cleared except by being repainted. There is no
 * periodic full repaint to fall back on, because the window before it would run is exactly when the map
 * is wrong, and a player who has learned not to trust the map stops using it.
 *
 * <h2>Why columns and not pixels</h2>
 *
 * <p>Map updates arrive as columns: the player moves east and a new column of terrain comes into range.
 * Repainting the changed columns matches the shape of the update, so a single step east repaints one
 * column rather than 16,384 pixels. A per-pixel dirty flag would need 16,384 bits per map to describe
 * something that is always a contiguous run.
 *
 * <p>Not thread safe. Map rendering happens on the render thread while map data is updated on the client
 * thread, so a caller crossing between them needs external synchronisation.
 */
public final class MapTextureCache {

    /** Pixels per axis of a map. */
    public static final int MAP_SIZE = 128;

    /** Milliseconds one pixel costs to paint. */
    public static final double MS_PER_PIXEL = 0.000002;

    private final boolean[] dirtyColumns = new boolean[MAP_SIZE];
    private int dirtyCount;

    private long frames;
    private long fullRepaints;
    private long columnsRepainted;
    private long pixelsRepainted;

    /**
     * Marks a column as changed.
     *
     * <p>Called by whatever updates the map data. A missed call is a map that never shows the new
     * terrain, so this is the one thing the class cannot afford to get wrong.
     */
    public void markDirty(int column) {
        if (column < 0 || column >= MAP_SIZE) {
            throw new IndexOutOfBoundsException("column " + column + " of " + MAP_SIZE);
        }
        if (!this.dirtyColumns[column]) {
            this.dirtyColumns[column] = true;
            this.dirtyCount++;
        }
    }

    /** Marks a run of columns, for a player who moved several blocks at once. */
    public void markDirtyRange(int fromColumn, int toColumn) {
        int start = Math.max(0, Math.min(fromColumn, toColumn));
        int end = Math.min(MAP_SIZE - 1, Math.max(fromColumn, toColumn));
        for (int column = start; column <= end; column++) {
            markDirty(column);
        }
    }

    /**
     * How many columns need repainting this frame.
     *
     * @return zero if the map is unchanged and can be drawn as it is
     */
    public int repaint() {
        this.frames++;
        if (this.dirtyCount == 0) {
            return 0;
        }
        if (this.dirtyCount == MAP_SIZE) {
            this.fullRepaints++;
        }
        int columns = this.dirtyCount;
        this.columnsRepainted += columns;
        this.pixelsRepainted += (long) columns * MAP_SIZE;
        java.util.Arrays.fill(this.dirtyColumns, false);
        this.dirtyCount = 0;
        return columns;
    }

    /** Whether a column is waiting to be repainted. */
    public boolean isDirty(int column) {
        if (column < 0 || column >= MAP_SIZE) {
            throw new IndexOutOfBoundsException("column " + column + " of " + MAP_SIZE);
        }
        return this.dirtyColumns[column];
    }

    /** How many columns are waiting. */
    public int dirtyCount() {
        return this.dirtyCount;
    }

    /** Marks everything, for a map that was loaded fresh or changed scale. */
    public void markAllDirty() {
        java.util.Arrays.fill(this.dirtyColumns, true);
        this.dirtyCount = MAP_SIZE;
    }

    public long frames() {
        return this.frames;
    }

    /** How many times the whole map had to be repainted. */
    public long fullRepaints() {
        return this.fullRepaints;
    }

    public long columnsRepainted() {
        return this.columnsRepainted;
    }

    public long pixelsRepainted() {
        return this.pixelsRepainted;
    }

    /**
     * Pixels a frame repaints on average.
     *
     * <p>Compared against the whole map to show what the tracking saved.
     */
    public double pixelsPerFrame() {
        return this.frames == 0 ? 0.0 : (double) this.pixelsRepainted / this.frames;
    }

    /** Milliseconds a frame costs with the tracking. */
    public double msPerFrame() {
        return pixelsPerFrame() * MS_PER_PIXEL;
    }

    /** Milliseconds a frame costs repainting the whole map every time. */
    public static double msPerFrameWithoutCache() {
        return (long) MAP_SIZE * MAP_SIZE * MS_PER_PIXEL;
    }

    public double savedPercent() {
        double without = msPerFrameWithoutCache();
        return without == 0.0 ? 0.0 : 100.0 * (without - msPerFrame()) / without;
    }
}
