package net.amdfaster.particle;

/**
 * Drops the particles that provably cannot be seen, and leaves the ones that can alone.
 *
 * <p>Particles are fill-rate bound rather than vertex bound. Half a million of them at eight pixels
 * square is 32 million pixels, fifteen times the screen, and the published figure for that load is
 * 2.81 ms as billboards against 0.71 ms for the same count as cubes -- so the cost is pixels covered,
 * and cutting the number of billboards is what helps.
 *
 * <h2>Why the obvious version of this is wrong</h2>
 *
 * <p>At half a million particles there are fifteen per screen cell on average, and keeping only the
 * front one of each cell would discard 93.5% of them. That is a large number and it is not available,
 * because particles blend. Two overlapping smoke particles produce a colour that neither produces
 * alone, and dropping the one behind changes the result -- a visible change in exactly the dense
 * situation the optimisation is aimed at. Opaque geometry can be culled this way because the depth
 * test discards the hidden one anyway and blending is off. Translucent geometry cannot.
 *
 * <p>So this culls only what cannot contribute a pixel at all, where "cannot" is provable rather than
 * probable:
 *
 * <ul>
 *   <li><b>Off screen.</b> The billboard does not touch the viewport.</li>
 *   <li><b>Fully transparent.</b> Alpha is zero, so every blend is a no-op.</li>
 *   <li><b>Entirely behind opaque geometry.</b> Every pixel it would touch fails the depth test, which
 *       the GPU would discard anyway -- but the draw call and the vertex work are not free, and that is
 *       what this removes.</li>
 * </ul>
 *
 * <p>None of those change a single blended colour, because in each case the particle contributes
 * nothing to any pixel.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not merge overlapping translucent particles. Merging N of them into fewer would cut fill
 * and would change the accumulated colour, which is the same trade as culling them and is not this
 * class's to make. A caller that wants it can do so knowingly, with the appearance change stated.
 *
 * <p>Not thread safe.
 */
public final class ParticleCuller {

    /** Screen cells along X used for the depth test. */
    public static final int TILE_W = 32;

    /** Screen cells along Y. */
    public static final int TILE_H = 8;

    private final int screenWidth;
    private final int screenHeight;

    /**
     * Nearest opaque depth per cell, in reversed-Z so larger is nearer.
     *
     * <p>Reversed rather than conventional because the depth buffer this reads is reversed-Z, and
     * comparing in the buffer's own convention avoids a sign flip that is easy to get backwards.
     */
    private final float[] tileDepth;

    private long submitted;
    private long kept;
    private long culledOffScreen;
    private long culledTransparent;
    private long culledBehind;

    public ParticleCuller(int screenWidth, int screenHeight) {
        if (screenWidth < TILE_W || screenHeight < TILE_H) {
            throw new IllegalArgumentException(
                    "a screen is at least one cell: " + screenWidth + "x" + screenHeight);
        }
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        int cellsX = (screenWidth + TILE_W - 1) / TILE_W;
        int cellsY = (screenHeight + TILE_H - 1) / TILE_H;
        this.tileDepth = new float[cellsX * cellsY];
        reset();
    }

    /** Clears the depth cells, for a new frame. */
    public void reset() {
        // Reversed Z: the far plane is zero, so a cleared cell accepts everything.
        java.util.Arrays.fill(this.tileDepth, 0.0f);
    }

    /**
     * Records opaque geometry in a screen rectangle.
     *
     * @param x0 left edge in pixels
     * @param y0 top edge in pixels
     * @param x1 right edge in pixels, exclusive
     * @param y1 bottom edge in pixels, exclusive
     * @param depth the depth at that rectangle, reversed Z
     */
    public void markOpaque(int x0, int y0, int x1, int y1, float depth) {
        int cellsX = (this.screenWidth + TILE_W - 1) / TILE_W;
        int cx0 = Math.max(0, x0 / TILE_W);
        int cy0 = Math.max(0, y0 / TILE_H);
        int cx1 = Math.min(cellsX - 1, Math.max(0, (x1 - 1) / TILE_W));
        int cellsY = (this.screenHeight + TILE_H - 1) / TILE_H;
        int cy1 = Math.min(cellsY - 1, Math.max(0, (y1 - 1) / TILE_H));

        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int index = cy * cellsX + cx;
                // Nearest wins. A cell only becomes conservative as more geometry is marked, and a
                // particle is culled only when every cell it touches is nearer than it is.
                this.tileDepth[index] = Math.max(this.tileDepth[index], depth);
            }
        }
    }

    /**
     * Decides whether a particle should be drawn.
     *
     * @param x0 left edge of the billboard in pixels
     * @param y0 top edge in pixels
     * @param x1 right edge in pixels, exclusive
     * @param y1 bottom edge in pixels, exclusive
     * @param depth the particle's depth, reversed Z
     * @param alpha the particle's alpha, 0 to 1
     * @return true if it should be drawn
     */
    public boolean keep(int x0, int y0, int x1, int y1, float depth, float alpha) {
        this.submitted++;

        if (alpha <= 0.0f) {
            this.culledTransparent++;
            return false;
        }
        if (x1 <= 0 || y1 <= 0 || x0 >= this.screenWidth || y0 >= this.screenHeight) {
            this.culledOffScreen++;
            return false;
        }
        if (isEntirelyBehind(x0, y0, x1, y1, depth)) {
            this.culledBehind++;
            return false;
        }
        this.kept++;
        return true;
    }

    private boolean isEntirelyBehind(int x0, int y0, int x1, int y1, float depth) {
        int cellsX = (this.screenWidth + TILE_W - 1) / TILE_W;
        int cellsY = (this.screenHeight + TILE_H - 1) / TILE_H;
        int cx0 = Math.max(0, x0 / TILE_W);
        int cy0 = Math.max(0, y0 / TILE_H);
        int cx1 = Math.min(cellsX - 1, Math.max(0, (x1 - 1) / TILE_W));
        int cy1 = Math.min(cellsY - 1, Math.max(0, (y1 - 1) / TILE_H));

        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                // Every cell has to be nearer. One cell that is not means some pixel of the particle
                // survives the depth test, and skipping it would remove a visible particle.
                if (this.tileDepth[cy * cellsX + cx] <= depth) {
                    return false;
                }
            }
        }
        return true;
    }

    public long submitted() {
        return this.submitted;
    }

    public long kept() {
        return this.kept;
    }

    public long culledOffScreen() {
        return this.culledOffScreen;
    }

    public long culledTransparent() {
        return this.culledTransparent;
    }

    public long culledBehind() {
        return this.culledBehind;
    }

    /** Percentage of submitted particles that were dropped. */
    public double culledPercent() {
        return this.submitted == 0 ? 0.0
                : 100.0 * (this.culledOffScreen + this.culledTransparent + this.culledBehind)
                        / this.submitted;
    }
}
