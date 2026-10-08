package net.amdfaster.light;

/**
 * Packed lightmap coordinates.
 *
 * <p>The bit layout is Minecraft's, not ours: block light in bits 4..7 and sky light in bits
 * 20..23, matching {@code LightTexture.pack(int block, int sky)} in 1.21.11 (verified against the
 * published mappings for that version). Staying compatible means a light value computed here can be
 * handed to vanilla's lightmap without a conversion, and a value read back from a vanilla vertex
 * means the same thing.
 *
 * <p>The twelve unused bits in the middle are what make the four-sample average here safe: summing
 * four packed values cannot carry one channel into the other.
 */
public final class LightValue {

    /** Highest level for either channel. */
    public static final int MAX = 15;

    private static final int BLOCK_SHIFT = 4;
    private static final int SKY_SHIFT = 20;
    private static final int CHANNEL_MASK = 0xF;

    /** Four channels summed need six bits, and six is what the gap between the fields allows. */
    private static final int SUM_MASK = 0x3F;

    private LightValue() {
    }

    public static int pack(int block, int sky) {
        if (block < 0 || block > MAX || sky < 0 || sky > MAX) {
            throw new IllegalArgumentException("light out of range 0.." + MAX + ": block=" + block
                    + " sky=" + sky);
        }
        return (sky << SKY_SHIFT) | (block << BLOCK_SHIFT);
    }

    public static int block(int packed) {
        return (packed >> BLOCK_SHIFT) & CHANNEL_MASK;
    }

    public static int sky(int packed) {
        return (packed >> SKY_SHIFT) & CHANNEL_MASK;
    }

    /** Both channels maxed: what a light source block and full daylight both produce. */
    public static int fullBright() {
        return pack(MAX, MAX);
    }

    /** Neither channel: a sealed space with no torch in it. */
    public static int dark() {
        return 0;
    }

    /**
     * Raises the block channel to at least {@code emission}, leaving sky alone.
     *
     * <p>Emissive blocks light their own surface. Raising rather than setting matters: a glowing
     * block standing in torchlight must not be made darker by its own emission.
     */
    public static int withEmission(int packed, int emission) {
        if (emission <= 0) {
            return packed;
        }
        return pack(Math.max(block(packed), Math.min(emission, MAX)), sky(packed));
    }

    /**
     * The mean of four packed values, truncated.
     *
     * <p>Correct only because the channels are separated by twelve zero bits: the block sums land in
     * bits 4..9 and the sky sums in bits 20..25, so adding four packed values mixes nothing. That is
     * the entire reason the average is one integer add rather than four extract-add-repack cycles,
     * which matters because this runs four times per face per quad during meshing.
     */
    public static int average4(int a, int b, int c, int d) {
        int sum = a + b + c + d;
        int block = ((sum >> BLOCK_SHIFT) & SUM_MASK) >> 2;
        int sky = ((sum >> SKY_SHIFT) & SUM_MASK) >> 2;
        return (sky << SKY_SHIFT) | (block << BLOCK_SHIFT);
    }

    /** The same mean computed channel by channel, kept so the test can cross-check the fast one. */
    public static int average4Slow(int a, int b, int c, int d) {
        return pack((block(a) + block(b) + block(c) + block(d)) / 4,
                (sky(a) + sky(b) + sky(c) + sky(d)) / 4);
    }

    /**
     * Texture coordinate into the 16x16 lightmap for a level.
     *
     * <p>Offset by half a texel: sampling exactly on a texel border with linear filtering bleeds the
     * neighbouring level in, which shows up as a faint seam at every light level boundary.
     */
    public static float lightmapCoord(int level) {
        return (Math.max(0, Math.min(level, MAX)) + 0.5f) / 16.0f;
    }
}
