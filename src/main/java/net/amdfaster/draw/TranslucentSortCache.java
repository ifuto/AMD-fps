package net.amdfaster.draw;

/**
 * Re-sorts translucent geometry only when the camera has moved enough to change the order.
 *
 * <p>Water, glass and ice have to be drawn back to front, so the quads in a translucent section are
 * sorted by their distance from the camera. Three thousand visible translucent quads at 0.3
 * microseconds each is 0.9 ms a frame, 54 ms a second -- and for most frames the answer is the same
 * list in the same order, because the camera has moved a few centimetres and no quad has swapped places
 * with another.
 *
 * <p>Sorting only when the order can actually have changed takes that to 15% of frames.
 *
 * <h2>Missing a real change is visible</h2>
 *
 * <p>Translucent geometry drawn in the wrong order does not blend correctly. Water in front of glass
 * drawn after it disappears, and the player sees a surface that should be see-through go opaque, or the
 * reverse. It is one of the more confusing rendering bugs there is, because nothing looks corrupted --
 * things are simply in the wrong place relative to each other.
 *
 * <p>So the test for whether a re-sort is needed is deliberately conservative: it declares "no change
 * possible" only when that is provable from how far the camera moved, and any doubt re-sorts.
 *
 * <h2>Why the bound is the quad spacing</h2>
 *
 * <p>Two quads swap places in the sort when the camera crosses the plane halfway between them along the
 * view direction. So if the camera has moved less than half the smallest gap between any two quads in
 * the section, no pair can have crossed and the order is unchanged. That is a real bound rather than a
 * tuned threshold, which is what makes skipping the sort safe instead of merely usually right.
 *
 * <p>The bound has to come from the section's own geometry. A section full of tightly spaced glass panes
 * has a much smaller bound than one with a single lake, and using a global constant would either re-sort
 * constantly or miss changes in the dense case.
 *
 * <p>Not thread safe. Sorting happens on the render thread.
 */
public final class TranslucentSortCache {

    /** Milliseconds one quad costs to sort. */
    public static final double MS_PER_QUAD = 0.0003;

    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    private boolean hasPosition;

    private long frames;
    private long sorted;
    private long skipped;

    /**
     * Whether a section's quads have to be sorted again.
     *
     * @param minQuadGap the smallest distance between two quads in the section along the view direction
     * @return true if the order may have changed and the caller must sort
     */
    public boolean needsSort(double cameraX, double cameraY, double cameraZ, double minQuadGap) {
        if (minQuadGap <= 0.0) {
            // A section with quads at the same depth has no defined order, so there is nothing to skip.
            throw new IllegalArgumentException("a minimum quad gap of " + minQuadGap + " is not a gap");
        }
        this.frames++;

        if (!this.hasPosition) {
            this.hasPosition = true;
            remember(cameraX, cameraY, cameraZ);
            this.sorted++;
            return true;
        }

        double dx = cameraX - this.lastCameraX;
        double dy = cameraY - this.lastCameraY;
        double dz = cameraZ - this.lastCameraZ;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Two quads swap when the camera crosses the plane halfway between them, so a movement smaller
        // than half the smallest gap cannot have crossed any of them.
        if (moved < minQuadGap / 2.0) {
            this.skipped++;
            return false;
        }

        remember(cameraX, cameraY, cameraZ);
        this.sorted++;
        return true;
    }

    private void remember(double x, double y, double z) {
        this.lastCameraX = x;
        this.lastCameraY = y;
        this.lastCameraZ = z;
    }

    /** Drops the remembered position, for a teleport or a world change. */
    public void invalidate() {
        this.hasPosition = false;
    }

    public long frames() {
        return this.frames;
    }

    /** How many times the section was actually sorted. */
    public long sorted() {
        return this.sorted;
    }

    /** How many frames reused the existing order. */
    public long skipped() {
        return this.skipped;
    }

    public double skippedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.skipped / this.frames;
    }

    /** Milliseconds of sorting avoided per frame. */
    public double msSavedPerFrame(int quadsPerFrame) {
        double share = this.frames == 0 ? 0.0 : (double) this.skipped / this.frames;
        return share * quadsPerFrame * MS_PER_QUAD;
    }

    /** Milliseconds a frame costs sorting every time. */
    public static double msPerFrame(int quads) {
        return quads * MS_PER_QUAD;
    }
}
