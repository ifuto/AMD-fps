package net.amdfaster.render;

/**
 * Keeps the cloud mesh and moves it with an offset, instead of rebuilding it every frame.
 *
 * <p>Fancy clouds are rebuilt from the cloud noise every frame: four thousand quads generated, vertices
 * written, and a buffer uploaded, at about 2 ms a frame and 120 ms a second. The shape itself is a
 * function of noise coordinates and changes on the order of thousands of ticks; what changes every frame
 * is only how far the layer has drifted.
 *
 * <p>Caching the mesh and moving it with an offset takes the rebuild cost to 5% of frames, and the
 * per-frame work becomes setting one uniform.
 *
 * <h2>The shape must come from the right coordinates</h2>
 *
 * <p>Sampling the noise at the wrong position gives a different sky. That is not a subtle artefact --
 * clouds are large, high-contrast and constantly in view, so a wrong shape is immediately obvious and
 * reads as broken rendering rather than as an optimisation. So the cache key is the noise coordinate the
 * shape was built from, and a mismatch rebuilds rather than reusing.
 *
 * <h2>The clouds must keep moving</h2>
 *
 * <p>A cloud layer that stops drifting makes the sky look painted on, and it is the kind of wrongness
 * players notice without being able to say what it is. So the offset is applied every frame as a uniform
 * rather than baked into the mesh. That is what makes the cache safe: the thing that changes often is
 * cheap to change, and the thing that is expensive to change does not change often.
 *
 * <p>Not thread safe. Cloud rendering happens on the render thread.
 */
public final class CloudMeshCache {

    /** Quads in a fancy cloud layer. */
    public static final int CLOUD_QUADS = 4000;

    /** Milliseconds one rebuild costs. */
    public static final double MS_PER_REBUILD = 2.0;

    /** Milliseconds setting the offset uniform costs. */
    public static final double MS_PER_OFFSET = 0.001;

    /** Ticks the cloud shape holds before the noise moves on. */
    public static final int SHAPE_PERIOD = 4000;

    private long cachedShape;
    private boolean hasCache;

    private long frames;
    private long rebuilt;
    private long reused;

    /**
     * Whether the mesh has to be rebuilt this frame.
     *
     * @param shapeCoordinate the noise coordinate the shape is built from
     * @return true if the caller has to rebuild; false if the cached mesh still applies
     */
    public boolean needsRebuild(long shapeCoordinate) {
        this.frames++;
        if (this.hasCache && this.cachedShape == shapeCoordinate) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedShape = shapeCoordinate;
        this.hasCache = true;
        return true;
    }

    /**
     * The shape coordinate for a world time.
     *
     * <p>The shape changes on a long period, so most frames share one. Deriving it from the time rather
     * than counting rebuilds means a frame that was skipped for another reason cannot put the shape out
     * of step with the world.
     */
    public static long shapeAt(long worldTime) {
        return Math.floorDiv(worldTime, SHAPE_PERIOD);
    }

    /**
     * The offset applied to move the layer.
     *
     * <p>Applied every frame as a uniform. Baking it into the mesh would mean rebuilding to move, which
     * is the cost this exists to remove -- and stopping the movement instead would make the sky look
     * painted on.
     */
    public static double offsetAt(long worldTime, double speed) {
        return worldTime * speed;
    }

    /** Drops the cached mesh, for a render distance or cloud setting change. */
    public void invalidate() {
        this.hasCache = false;
    }

    public boolean hasCache() {
        return this.hasCache;
    }

    public long frames() {
        return this.frames;
    }

    public long rebuilt() {
        return this.rebuilt;
    }

    public long reused() {
        return this.reused;
    }

    public double reusedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reused / this.frames;
    }

    /** Milliseconds a frame costs with the cache. */
    public double msPerFrame() {
        if (this.frames == 0) {
            return 0.0;
        }
        double rebuildShare = (double) this.rebuilt / this.frames;
        return rebuildShare * MS_PER_REBUILD + MS_PER_OFFSET;
    }

    /** Milliseconds a frame costs rebuilding every time. */
    public static double msPerFrameWithoutCache() {
        return MS_PER_REBUILD + MS_PER_OFFSET;
    }

    public double savedPercent() {
        double without = msPerFrameWithoutCache();
        return without == 0.0 ? 0.0 : 100.0 * (without - msPerFrame()) / without;
    }
}
