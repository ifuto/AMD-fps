package net.amdfaster.mc;

/**
 * The merge key that decides which faces greedy meshing is allowed to join.
 *
 * <p>Two faces merge into one quad only when their keys are equal, so the key has to encode
 * everything that would make the merged quad wrong. Leaving any of these out is a visible bug:
 *
 * <ul>
 *   <li><b>sprite</b> -- omit it and a wall of stone merges into a quad carrying one stone texture
 *       stretched over the whole run.</li>
 *   <li><b>tint</b> -- omit it and grass next to uncoloured dirt merges, smearing one biome's colour
 *       across the other.</li>
 *   <li><b>light</b> -- omit it and the lightmap coordinate of whichever block was visited first
 *       covers the whole merged quad, so a torch-lit block and a dark one share a brightness. This
 *       is the classic greedy-meshing bug.</li>
 *   <li><b>emission</b> -- omit it and an emissive block's self-lit surface merges into a neighbour
 *       that should not glow.</li>
 *   <li><b>cutout</b> -- omit it and leaves merge with stone, so a quad that needs an alpha-tested
 *       pipeline draws in the opaque one and shows holes as black.</li>
 * </ul>
 *
 * <p>The layout is a packed 32-bit word because the mesher compares keys millions of times per
 * section and an {@code int} compare is one instruction:
 *
 * <pre>
 * bit   31      present; 0 means air, and NO_GEOMETRY is therefore 0
 * bits  17..30  sprite id, 14 bits
 * bits  13..16  block light level, 0..15
 * bits   9..12  sky light level, 0..15
 * bits   5..8   tint index plus one; 0 means untinted, so untinted encodes as 0 and NO_GEOMETRY
 *               stays distinguishable from a real untinted block by the present bit alone
 * bits   1..4   light emission, 0..15
 * bit    0      cutout or translucent: needs the alpha-tested pipeline, not the opaque one
 * </pre>
 *
 * <p>Fourteen bits gives 16384 distinct sprites. A large modpack can exceed that, so
 * {@link #encode} throws rather than silently aliasing two sprites onto one key, which would merge
 * blocks that look nothing alike.
 */
public final class BlockKeys {

    /** The key for air, and for anything else that should produce no geometry. */
    public static final int NO_GEOMETRY = 0;

    private static final int PRESENT_SHIFT = 31;
    private static final int SPRITE_SHIFT = 17;
    private static final int SPRITE_BITS = 14;
    private static final int BLOCK_LIGHT_SHIFT = 13;
    private static final int SKY_LIGHT_SHIFT = 9;
    private static final int TINT_SHIFT = 5;
    private static final int EMISSION_SHIFT = 1;
    private static final int CUTOUT_BIT = 1;

    private static final int LEVEL_MASK = 0xF;
    private static final int CUTOUT_MASK = 1;

    /** Largest sprite id that fits. */
    public static final int MAX_SPRITE = (1 << SPRITE_BITS) - 1;

    /** Largest light level, matching Minecraft's 0..15. */
    public static final int MAX_LIGHT = 15;

    /** Largest emission level. */
    public static final int MAX_EMISSION = 15;

    /** The tint index that means "no tint". */
    public static final int NO_TINT = -1;

    /** Largest tint index that fits, given that 0 is reserved for "no tint". */
    public static final int MAX_TINT = 14;

    private BlockKeys() {
    }

    /**
     * Packs a key.
     *
     * @param sprite    sprite id, 0..{@link #MAX_SPRITE}
     * @param blockLight block light level, 0..15
     * @param skyLight  sky light level, 0..15
     * @param tint      tint index, or {@link #NO_TINT} for untinted
     * @param emission  light emission, 0..15
     * @param cutout    true when the face needs the alpha-tested pipeline
     */
    public static int encode(int sprite, int blockLight, int skyLight, int tint, int emission,
                             boolean cutout) {
        if (sprite < 0 || sprite > MAX_SPRITE) {
            throw new IllegalArgumentException("sprite id " + sprite + " does not fit in "
                    + SPRITE_BITS + " bits (0.." + MAX_SPRITE + "); raise the field width rather "
                    + "than let two sprites alias onto one key");
        }
        if (blockLight < 0 || blockLight > MAX_LIGHT) {
            throw new IllegalArgumentException("block light out of range: " + blockLight);
        }
        if (skyLight < 0 || skyLight > MAX_LIGHT) {
            throw new IllegalArgumentException("sky light out of range: " + skyLight);
        }
        if (tint != NO_TINT && (tint < 0 || tint > MAX_TINT)) {
            throw new IllegalArgumentException("tint out of range: " + tint
                    + " (use " + NO_TINT + " for untinted)");
        }
        if (emission < 0 || emission > MAX_EMISSION) {
            throw new IllegalArgumentException("emission out of range: " + emission);
        }
        int packed = 1 << PRESENT_SHIFT;
        packed |= sprite << SPRITE_SHIFT;
        packed |= blockLight << BLOCK_LIGHT_SHIFT;
        packed |= skyLight << SKY_LIGHT_SHIFT;
        packed |= (tint == NO_TINT ? 0 : tint + 1) << TINT_SHIFT;
        packed |= emission << EMISSION_SHIFT;
        packed |= cutout ? CUTOUT_BIT : 0;
        return packed;
    }

    /** True for any key that produces geometry. */
    public static boolean isPresent(int key) {
        return key != NO_GEOMETRY;
    }

    public static int sprite(int key) {
        return (key >>> SPRITE_SHIFT) & ((1 << SPRITE_BITS) - 1);
    }

    public static int blockLight(int key) {
        return (key >>> BLOCK_LIGHT_SHIFT) & LEVEL_MASK;
    }

    public static int skyLight(int key) {
        return (key >>> SKY_LIGHT_SHIFT) & LEVEL_MASK;
    }

    /** The tint index, or {@link #NO_TINT} when the face is untinted. */
    public static int tint(int key) {
        int field = (key >>> TINT_SHIFT) & LEVEL_MASK;
        return field == 0 ? NO_TINT : field - 1;
    }

    public static int emission(int key) {
        return (key >>> EMISSION_SHIFT) & LEVEL_MASK;
    }

    public static boolean isCutout(int key) {
        return (key & CUTOUT_MASK) != 0;
    }

    /**
     * The two light channels packed the way {@code LightValue} packs them, so a key can be handed
     * straight to the lighting code without unpacking and repacking by hand.
     */
    public static int light(int key) {
        return (skyLight(key) << 20) | (blockLight(key) << 4);
    }

    /**
     * Whether two keys may merge.
     *
     * <p>Kept as a method rather than inlined as {@code ==} so the rule has one home and a name. It
     * is an equality test today, and the point of naming it is that if it ever stops being one --
     * say two sprites are allowed to share an atlas page and a merged quad can address both -- the
     * mesher does not have to change.
     */
    public static boolean canMerge(int a, int b) {
        return a == b;
    }
}
