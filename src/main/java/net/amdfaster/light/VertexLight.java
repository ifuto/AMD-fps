package net.amdfaster.light;

/**
 * The lighting at one vertex of a face: a lightmap coordinate and an ambient occlusion level.
 *
 * <p>Per-vertex rather than per-quad is the point. A greedy-merged quad can span many blocks, and
 * the light at its two ends can differ by the full 0..15 range; one value for the whole quad is
 * what makes large merged faces look flat and wrong. Storing it per corner lets the rasteriser
 * interpolate it for free -- the interpolator is idle during a voxel pass anyway, so per-vertex
 * lighting costs nothing that per-quad lighting did not already cost.
 *
 * @param light packed lightmap coordinate, see {@link LightValue}
 * @param ao    ambient occlusion level, 0 (fully occluded) to 3 (open)
 */
public record VertexLight(int light, int ao) {

    /** Lowest occlusion level. */
    public static final int AO_MIN = 0;

    /** Highest occlusion level: nothing nearby blocking the corner. */
    public static final int AO_MAX = 3;

    /**
     * Brightness multiplier per occlusion level, indexed by {@link #ao()}.
     *
     * <p>These are Minecraft's values, and they are not evenly spaced: the jump from level 0 to
     * level 1 is much larger than the others. That is deliberate -- the fully occluded corner has to
     * read as clearly darker, or corners stop looking like corners, while the top three levels are
     * subtle enough that a smooth gradient between them looks natural.
     */
    private static final float[] SHADE = {0.2f, 0.6f, 0.8f, 1.0f};

    /** Brightness multiplier for an occlusion level. */
    public static float shadeFor(int ao) {
        if (ao < AO_MIN || ao > AO_MAX) {
            throw new IllegalArgumentException("ao out of range 0..3: " + ao);
        }
        return SHADE[ao];
    }

    public VertexLight {
        if (ao < AO_MIN || ao > AO_MAX) {
            throw new IllegalArgumentException("ao out of range 0..3: " + ao);
        }
    }

    /** Brightness multiplier at this vertex. */
    public float shade() {
        return SHADE[this.ao];
    }

    /** Fully lit and unoccluded. */
    public static VertexLight fullBright() {
        return new VertexLight(LightValue.fullBright(), AO_MAX);
    }

    /** Lightmap U for the block channel. */
    public float lightmapU() {
        return LightValue.lightmapCoord(LightValue.block(this.light));
    }

    /** Lightmap V for the sky channel. */
    public float lightmapV() {
        return LightValue.lightmapCoord(LightValue.sky(this.light));
    }
}
