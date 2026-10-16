package net.amdfaster.render;

/**
 * Keeps the end portal layer geometry and animates it with uniforms.
 *
 * <p>An end portal is drawn as a stack of translucent layers, each with its own scrolling texture
 * offset and opacity. Six portals in a base at sixteen layers each is ninety-six layers regenerated
 * every frame, about 1 ms a frame and 58 ms a second. The geometry of every layer is the same plane;
 * what differs is where its texture has scrolled to and how opaque it is.
 *
 * <p>Caching the geometry and supplying the offsets as uniforms takes the rebuild to essentially zero.
 *
 * <h2>The layer order must not change</h2>
 *
 * <p>The layers are translucent, and blending is order-dependent: layer A over layer B is not layer B
 * over layer A. Reordering them changes the colour of the portal, which is visible on the one block in
 * the game that every player looks at closely. So the cache preserves the order the layers were
 * supplied in, and a test asserts it.
 *
 * <h2>The animation must not stop</h2>
 *
 * <p>The portal scrolls and shimmers, and a frozen one looks like a painted texture. That is the same
 * class of wrongness as frozen rain and a frozen beacon beam: the scene feels dead and the player cannot
 * say why. So the scroll offset is a uniform applied every frame, never baked into the geometry.
 *
 * <p>Both things that change often -- the offsets and the opacities -- are cheap to change. The thing
 * that is expensive to change, the geometry, does not change at all.
 *
 * <p>Not thread safe. Portal rendering happens on the render thread.
 */
public final class EndPortalCache {

    /** Layers in one portal. */
    public static final int LAYERS = 16;

    /** Milliseconds one layer costs to rebuild. */
    public static final double MS_PER_LAYER = 0.01;

    /** Milliseconds setting the uniforms costs. */
    public static final double MS_PER_UNIFORM = 0.001;

    private int cachedLayerCount;

    private long frames;
    private long rebuilt;
    private long reused;

    /**
     * Whether the layer geometry has to be rebuilt this frame.
     *
     * @param layerCount how many layers this portal draws
     * @return true if the caller has to rebuild
     */
    public boolean needsRebuild(int layerCount) {
        if (layerCount < 1) {
            throw new IllegalArgumentException("a portal with " + layerCount + " layers is not a portal");
        }
        this.frames++;
        if (this.cachedLayerCount == layerCount) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedLayerCount = layerCount;
        return true;
    }

    /**
     * How far a layer's texture has scrolled.
     *
     * <p>A uniform applied every frame. Baking it into the geometry would mean rebuilding to animate,
     * which is the cost this exists to remove -- and freezing it instead would leave a painted texture
     * where a portal should be.
     */
    public static double scrollAt(int layer, long worldTime, double speed) {
        // Each layer scrolls at its own rate, which is what makes the portal look like it has depth
        // rather than being a single moving picture.
        return worldTime * speed * (layer + 1);
    }

    /**
     * A layer's opacity.
     *
     * <p>Also a uniform. The layers fade with depth, and that fade is part of the effect.
     */
    public static double opacityAt(int layer, int layerCount) {
        if (layerCount < 1) {
            throw new IllegalArgumentException("a portal with " + layerCount + " layers is not a portal");
        }
        return 1.0 - (double) layer / layerCount;
    }

    /**
     * Whether the layers are still in the order they were supplied.
     *
     * <p>The property that matters. Blending is order-dependent, so reordering the layers changes the
     * colour of the portal, which is visible on the one block in the game that every player looks at
     * closely.
     */
    public static boolean preservesOrder(int[] layerOrder) {
        for (int i = 1; i < layerOrder.length; i++) {
            if (layerOrder[i] <= layerOrder[i - 1]) {
                return false;
            }
        }
        return true;
    }

    /** Drops the cached geometry, for a graphics setting change. */
    public void invalidate() {
        this.cachedLayerCount = 0;
    }

    /** How many layers the cached geometry has. */
    public int cachedLayerCount() {
        return this.cachedLayerCount;
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
        return rebuildShare * this.cachedLayerCount * MS_PER_LAYER + MS_PER_UNIFORM;
    }

    /** Milliseconds a frame costs rebuilding every time. */
    public static double msPerFrameWithoutCache(int portals) {
        return (long) portals * LAYERS * MS_PER_LAYER + MS_PER_UNIFORM;
    }

    /**
     * The share of the rebuild avoided.
     *
     * <p>Zero before any frame has been drawn. A cache that has done nothing has technically avoided all
     * of it, but reporting a saving for an unused cache reads as a result when it is the absence of one.
     */
    public double savedPercent() {
        double without = msPerFrameWithoutCache(1);
        if (without == 0.0 || this.frames == 0) {
            return 0.0;
        }
        return 100.0 * (without - msPerFrame()) / without;
    }
}
