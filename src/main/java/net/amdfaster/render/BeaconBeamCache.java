package net.amdfaster.render;

/**
 * Keeps the beacon beam mesh and supplies position, colour and animation as uniforms.
 *
 * <p>A beacon beam is a column of light reaching to the sky, drawn as a stack of segments that rotate
 * and scroll. Vanilla rebuilds that mesh every frame: twelve beacons in a large base at sixty-four
 * segments each is 768 segments regenerated, about 0.4 ms a frame and 23 ms a second. The shape never
 * changes. What changes is where the beacon is, what colour the glass above it is, and how far the
 * animation has scrolled.
 *
 * <p>Caching the shape and supplying the rest as uniforms takes the rebuild to the frames where the
 * colour actually changed.
 *
 * <h2>A colour change has to show</h2>
 *
 * <p>A player who swaps the glass above a beacon expects the beam to change colour, and it is a visible,
 * deliberate act -- they placed the glass to get that colour. A cached mesh carrying the old colour
 * would ignore it. So the colour is part of the cache key, and a change either rebuilds or is supplied
 * as a uniform; either way the beam follows the glass.
 *
 * <h2>The animation must not stop</h2>
 *
 * <p>The beam rotates and scrolls, and a frozen one looks like a painted pole. That is the same class of
 * wrongness as frozen rain: the scene feels dead and the player cannot say why. So the scroll offset is
 * a uniform applied every frame, never baked into the mesh.
 *
 * <p>Both things that change often -- position and animation -- are cheap to change. The thing that is
 * expensive to change, the shape, does not change at all unless the colour does.
 *
 * <p>Not thread safe. Beacon rendering happens on the render thread.
 */
public final class BeaconBeamCache {

    /** Segments in one beam. */
    public static final int SEGMENTS = 64;

    /** Milliseconds one beam costs to rebuild. */
    public static final double MS_PER_REBUILD = SEGMENTS * 0.0005;

    /** Milliseconds setting the uniforms costs. */
    public static final double MS_PER_UNIFORM = 0.001;

    private long cachedKey;
    private boolean hasCache;

    private long frames;
    private long rebuilt;
    private long reused;

    /**
     * Whether the mesh has to be rebuilt this frame.
     *
     * @param colour the beam colour, packed from the glass above the beacon
     * @return true if the caller has to rebuild
     */
    public boolean needsRebuild(long colour) {
        this.frames++;
        if (this.hasCache && this.cachedKey == colour) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedKey = colour;
        this.hasCache = true;
        return true;
    }

    /**
     * How far the animation has scrolled.
     *
     * <p>A uniform applied every frame. Baking it into the mesh would mean rebuilding to animate, which
     * is the cost this exists to remove -- and freezing it instead would leave a painted pole where a
     * beam of light should be.
     */
    public static double scrollAt(long worldTime, double speed) {
        return worldTime * speed;
    }

    /**
     * How far the beam has rotated.
     *
     * <p>Also a uniform, for the same reason.
     */
    public static double rotationAt(long worldTime, double degreesPerTick) {
        return worldTime * degreesPerTick;
    }

    /**
     * Packs a beam colour.
     *
     * <p>The cache key. Two beams with the same glass share a mesh; a beam whose glass changed does not.
     */
    public static long colourKey(int red, int green, int blue) {
        return ((long) (red & 0xFF) << 16) | ((long) (green & 0xFF) << 8) | (blue & 0xFF);
    }

    /** Drops the cached mesh, for a graphics setting change. */
    public void invalidate() {
        this.hasCache = false;
    }

    public boolean hasCache() {
        return this.hasCache;
    }

    public long cachedColour() {
        return this.cachedKey;
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
        return rebuildShare * MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    /** Milliseconds a frame costs rebuilding every time. */
    public static double msPerFrameWithoutCache() {
        return MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    /**
     * The share of the rebuild avoided.
     *
     * <p>Zero before any frame has been drawn. A cache that has done nothing has technically avoided all
     * of it, but reporting a saving for an unused cache reads as a result when it is the absence of one.
     */
    public double savedPercent() {
        double without = msPerFrameWithoutCache();
        if (without == 0.0 || this.frames == 0) {
            return 0.0;
        }
        return 100.0 * (without - msPerFrame()) / without;
    }
}
