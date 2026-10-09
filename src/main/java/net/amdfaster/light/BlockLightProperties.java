package net.amdfaster.light;

/**
 * Light properties indexed by block identity rather than by position.
 *
 * <p>There are two caches here and they answer different questions. The position-keyed block state
 * cache answers "what is at this coordinate", and it has to be large because the working set is the
 * whole section being meshed. This one answers "what does this kind of block do to light", and its
 * working set is the block registry -- on the order of a thousand entries for the entire game,
 * regardless of how much world is loaded. So it is small enough to stay in L1 for the whole run, and
 * it is a flat array lookup with no hashing at all.
 *
 * <p>Indexing by identity is the point. A light update asks about the same handful of block kinds over
 * and over -- stone, air, dirt, leaves -- and a position-keyed cache misses on every new coordinate
 * even when the answer is the same block it just looked up. Keying by block id turns that into a hit
 * every time.
 *
 * <p>Everything is packed into one int so a lookup is one array read and the properties of a block are
 * always consistent with each other. Separate arrays would be three reads and three chances to update
 * one without the others.
 *
 * <p>Not thread safe.
 */
public final class BlockLightProperties {

    /** Attenuation, bits 0-3. 0 is clear, 15 blocks everything. */
    private static final int ATTENUATION_MASK = 0xF;

    /** Light emitted, bits 4-7. */
    private static final int EMISSION_SHIFT = 4;
    private static final int EMISSION_MASK = 0xF;

    /** Whether the block diffuses skylight without blocking it: leaves, cobweb. Bit 8. */
    private static final int FILTERS_SKY_BIT = 1 << 8;

    /**
     * Whether the block's own shape lets light past it: slabs, stairs, fences. Bit 9.
     *
     * <p>Minecraft computes this from the block's occlusion shape rather than storing it, and it
     * matters because a slab does not fill its cell -- light gets around it even though its bounding
     * box is opaque.
     */
    private static final int USE_SHAPE_BIT = 1 << 9;

    /**
     * Whether an entry has been registered at all. Bit 10.
     *
     * <p>This bit is not optional and its absence was a real bug. A plain opaque block -- stone, dirt,
     * most of the world -- packs to exactly fifteen: attenuation 15, no emission, no flags. That is
     * also the value the table is filled with for ids nobody has registered, so the two were
     * indistinguishable and every ordinary solid block reported itself as unknown. The properties
     * cannot carry their own presence; they need a bit that says so.
     */
    private static final int PRESENT_BIT = 1 << 10;

    /**
     * The raw value held for a block that has not been registered: fully opaque, emits nothing, and
     * with the presence bit clear. Blocking is the safe default -- assuming an unknown block lets light
     * through would light caves that should be dark, which is visible and wrong, whereas assuming it
     * blocks costs nothing visible.
     */
    public static final int UNREGISTERED = OpacityField.OPAQUE;

    private final int[] properties;
    private int highestId;
    private long lookups;

    public BlockLightProperties(int blockCount) {
        if (blockCount <= 0) {
            throw new IllegalArgumentException("block count must be positive: " + blockCount);
        }
        this.properties = new int[blockCount];
        java.util.Arrays.fill(this.properties, UNREGISTERED);
    }

    /** Sized for Minecraft's block registry, which is on the order of a thousand blocks. */
    public static BlockLightProperties forMinecraft() {
        return new BlockLightProperties(4096);
    }

    public int blockCount() {
        return this.properties.length;
    }

    /** Bytes of table. A position cache for one section of coordinates costs hundreds of times this. */
    public int tableBytes() {
        return this.properties.length << 2;
    }

    public int highestRegisteredId() {
        return this.highestId;
    }

    public long lookups() {
        return this.lookups;
    }

    public void register(int blockId, int attenuation, int emission, boolean filtersSky, boolean usesShape) {
        check(attenuation, "attenuation");
        check(emission, "emission");
        if (blockId < 0 || blockId >= this.properties.length) {
            throw new IllegalArgumentException("block id out of range: " + blockId);
        }
        this.properties[blockId] = (attenuation & ATTENUATION_MASK)
                | ((emission & EMISSION_MASK) << EMISSION_SHIFT)
                | (filtersSky ? FILTERS_SKY_BIT : 0)
                | (usesShape ? USE_SHAPE_BIT : 0)
                | PRESENT_BIT;
        this.highestId = Math.max(this.highestId, blockId);
    }

    /**
     * A block that light passes straight through: air, glass, most non-solid blocks.
     *
     * <p>Attenuation 0 means the step costs the floor of 1, so light travels the same distance through
     * glass as through air -- which is correct, and is exactly what the naive {@code level - 1 -
     * opacity} formulation gets wrong.
     */
    public void registerClear(int blockId) {
        register(blockId, 0, 0, false, false);
    }

    /** A full opaque block: stone, dirt, ore. Blocks everything, emits nothing. */
    public void registerOpaque(int blockId) {
        register(blockId, OpacityField.OPAQUE, 0, false, false);
    }

    /** A light source: torch, glowstone, lantern. Emits, and lets light through. */
    public void registerEmitter(int blockId, int emission) {
        register(blockId, 0, emission, false, false);
    }

    /** Leaves and cobweb: they attenuate but do not block, and they break the skylight fall. */
    public void registerSkyFilter(int blockId) {
        register(blockId, 1, 0, true, false);
    }

    private static void check(int value, String what) {
        if (value < 0 || value > 15) {
            throw new IllegalArgumentException(what + " does not fit in a nibble: " + value);
        }
    }

    public int rawOf(int blockId) {
        this.lookups++;
        return inRange(blockId) ? this.properties[blockId] : UNREGISTERED;
    }

    public int attenuationOf(int blockId) {
        return rawOf(blockId) & ATTENUATION_MASK;
    }

    public int emissionOf(int blockId) {
        return (rawOf(blockId) >>> EMISSION_SHIFT) & EMISSION_MASK;
    }

    public boolean isOpaque(int blockId) {
        return attenuationOf(blockId) >= OpacityField.OPAQUE;
    }

    /**
     * Whether the block breaks the free downward fall of skylight.
     *
     * <p>Level-15 skylight falls without attenuating only through blocks that do not filter. Leaves
     * filter, which is why a forest floor is dimmer than an open field even though both are outdoors.
     */
    public boolean filtersSkylight(int blockId) {
        return (rawOf(blockId) & FILTERS_SKY_BIT) != 0;
    }

    public boolean usesShapeForOcclusion(int blockId) {
        return (rawOf(blockId) & USE_SHAPE_BIT) != 0;
    }

    /** Whether a lookup fell outside the table, which for an unregistered id it must. */
    private boolean inRange(int blockId) {
        return blockId >= 0 && blockId < this.properties.length;
    }

    public boolean isRegistered(int blockId) {
        return inRange(blockId) && (this.properties[blockId] & PRESENT_BIT) != 0;
    }

    public void clear() {
        java.util.Arrays.fill(this.properties, UNREGISTERED);
        this.highestId = 0;
        this.lookups = 0;
    }
}
