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

    /** Where the occlusion level sits in a packed word; bits 24 and 25. */
    private static final int AO_SHIFT = 24;

    /** Everything a packed word holds except the occlusion level. */
    private static final int LIGHT_MASK = 0x00FFFFFF;

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

    /**
     * The two channels and the occlusion level in one word, for the vertex stream.
     *
     * <p>The lightmap coordinate occupies bits 4..7 and 20..23, so bits 24..31 are unused and the
     * occlusion level goes in the lowest two of them. One 4-byte attribute instead of two, and the
     * shader that unpacks it does so once per vertex while the fetch is in flight.
     */
    public int packed() {
        return packLight(this.light, this.ao);
    }

    /** The same packing as {@link #packed()}, without allocating a {@code VertexLight}. */
    public static int packLight(int light, int ao) {
        if (ao < AO_MIN || ao > AO_MAX) {
            throw new IllegalArgumentException("ao out of range 0..3: " + ao);
        }
        return (light & LIGHT_MASK) | (ao << AO_SHIFT);
    }

    /** The occlusion level of a packed word. */
    public static int unpackAo(int packed) {
        return (packed >>> AO_SHIFT) & AO_MAX;
    }

    /** The lightmap coordinate of a packed word, with the occlusion bits removed. */
    public static int unpackLight(int packed) {
        return packed & LIGHT_MASK;
    }

    /** Rebuilds a {@code VertexLight} from a packed word. */
    public static VertexLight unpack(int packed) {
        return new VertexLight(unpackLight(packed), unpackAo(packed));
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
