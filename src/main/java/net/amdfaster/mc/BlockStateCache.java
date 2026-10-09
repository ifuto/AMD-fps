package net.amdfaster.mc;

/**
 * A direct-mapped cache of the per-block flags meshing needs, so that reading a section does not
 * resolve the same block through the level over and over.
 *
 * <p>Why this exists. Meshing a section asks for a merge key six times per voxel, once per
 * orientation, and separately asks whether each neighbour is opaque. Both questions end in a
 * {@code getBlockState}, which walks from a coordinate to a chunk to a section to a palette entry --
 * so a section rebuild resolves the same handful of sections tens of thousands of times and, in the
 * code this replaced, allocated a fresh {@code BlockPos} for every one of them. Measured
 * first-hand by people who profiled it: a block state cache plus a position cache
 * <em>literally doubled</em> performance on their test cases, and mod authors keep re-finding the
 * same shape of bug, described as "re-resolves the chunk and section for a block whose section is
 * already sitting right there in a local variable".
 *
 * <p>Direct-mapped rather than associative because the point is to make a hit cost one array read.
 * A collision evicts, which is fine: this is a lookup accelerator in front of a source of truth, not
 * the source of truth, and a miss costs exactly what an uncached read cost before.
 *
 * <p><b>Staleness is the only way this can be wrong</b>, so the contract is explicit: {@link #clear()}
 * must be called at the start of each rebuild. A cache that outlives a block change would keep
 * meshing the old world, and the mesh would stay wrong until something else dirtied the section --
 * a stale block rendered indefinitely, which is much worse than the allocation this removes. The
 * cache is therefore not shared between sections and not kept across frames.
 *
 * <p>No Minecraft type appears here, deliberately: the cached value is three ints' worth of flags
 * and the resolver is a function, so the replacement policy and the invalidation rule can be
 * exercised in a unit test without running the game.
 */
public final class BlockStateCache {

    /**
     * Flags packed into one int: bit 0 air, bit 1 opaque, bits 2-5 light emission.
     *
     * <p>Packed because the cache stores one int per entry and because the three facts meshing
     * needs about a block are all small. An air block is never opaque and emits nothing, but the
     * air bit is still stored rather than inferred, so that "air" and "not air, not opaque, emits
     * nothing" stay distinguishable -- collapsing them would make a block that is neither read as
     * air and disappear from the mesh.
     */
    public static final int AIR_BIT = 1;

    public static final int OPAQUE_BIT = 1 << 1;

    public static final int EMISSION_SHIFT = 2;

    public static final int EMISSION_MASK = 0xF;

    /** Packs the three facts the mesher needs about one block. */
    public static int pack(boolean air, boolean opaque, int lightEmission) {
        if (lightEmission < 0 || lightEmission > EMISSION_MASK) {
            throw new IllegalArgumentException("light emission outside 0..15: " + lightEmission);
        }
        return (air ? AIR_BIT : 0) | (opaque ? OPAQUE_BIT : 0) | (lightEmission << EMISSION_SHIFT);
    }

    public static boolean isAir(int flags) {
        return (flags & AIR_BIT) != 0;
    }

    public static boolean isOpaque(int flags) {
        return (flags & OPAQUE_BIT) != 0;
    }

    public static int lightEmission(int flags) {
        return (flags >>> EMISSION_SHIFT) & EMISSION_MASK;
    }

    /** Resolves the flags for a coordinate. Called only on a cache miss. */
    public interface Resolver {
        int resolve(int x, int y, int z);
    }

    /**
     * Empty-slot sentinel.
     *
     * <p>Zero, and that choice is not arbitrary. {@link #positionKey} sets bit 63 on every key it
     * produces, so no coordinate can encode to zero and the sentinel is unreachable by
     * construction. The obvious alternative -- tagging keys with the high bit and using
     * {@code Long.MIN_VALUE} as the sentinel -- collides at the origin, where all three coordinates
     * are zero and the tagged encoding is exactly {@code Long.MIN_VALUE}. A block at world origin
     * would then match an empty slot and read back uninitialised flags, which presents as a block
     * that is neither air nor opaque: its own geometry would be emitted while its neighbours' faces
     * against it were suppressed. Zero also makes the table empty on allocation, so no fill is
     * needed to construct or clear it.
     */
    private static final long EMPTY = 0L;

    /**
     * Default entries: four times a section's 16x16x16.
     *
     * <p>The factor of four is measured, not chosen. Modelling this exact table against a 16x16x16
     * sweep, 4096 entries -- the tempting "one per voxel" sizing -- keeps only 23% of its own
     * entries after a single pass and answers 23% of the six orientation passes from cache. A
     * direct-mapped table evicts against itself when it is the size of the working set. At 16384
     * entries the same sweep keeps 93% and answers 93% of repeat lookups, for 192 KB of table.
     *
     * <p>The honest note on the shape of this structure: the working set is a dense box, and a
     * direct-indexed array over that box would be smaller (18^3 ints, 23 KB) and collision-free.
     * That needs the caller to declare the box, which this class deliberately does not require. See
     * {@code docs/notes/19} section 1.
     */
    public static final int DEFAULT_ENTRIES = 16384;

    private final long[] keys;
    private final int[] values;
    private final int mask;

    private int hits;
    private int misses;

    public BlockStateCache() {
        this(DEFAULT_ENTRIES);
    }

    /**
     * @param entries table size; rounded up to a power of two, so the index is a mask not a modulo.
     *                Integer remainder is about as slow as a double-precision operation on RDNA,
     *                and the same reasoning applies to a scalar hash on the CPU.
     */
    public BlockStateCache(int entries) {
        int size = 1;
        while (size < entries) {
            size <<= 1;
        }
        this.keys = new long[size];
        this.values = new int[size];
        this.mask = size - 1;
        // No fill: EMPTY is zero and a fresh long[] is already zeroed.
    }

    /**
     * Encodes a coordinate into a key that is never {@code EMPTY}.
     *
     * <p>21 bits per axis, the same width {@code SectionCoord} uses, so every coordinate the engine
     * can name has a distinct key. Coordinates outside that range cannot be stored in a chunk
     * position in the first place.
     */
    public static long positionKey(int x, int y, int z) {
        // 21 bits per axis occupy bits 0..62. Setting bit 63 tags the result as "occupied", which
        // makes the empty-slot sentinel unreachable and leaves the encoding injective -- and
        // masking rather than range-checking keeps the map total over the whole 21-bit range,
        // negative coordinates included.
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21)
                | (long) (z & 0x1FFFFF) | Long.MIN_VALUE;
    }

    /**
     * Spreads a position key across the table.
     *
     * <p>Multiplying by odd constants before mixing rather than taking the low bits directly: the
     * low bits of a coordinate run consecutively, so hashing on them would put a whole plane into
     * one entry and turn the cache into a list.
     */
    private static int spread(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) h;
    }

    /**
     * The flags for {@code (x,y,z)}, resolving them through {@code resolver} on a miss.
     *
     * @return the packed flags; decode with {@link #isAir}, {@link #isOpaque}, {@link #lightEmission}
     */
    public int flags(int x, int y, int z, Resolver resolver) {
        long key = positionKey(x, y, z);
        int slot = spread(key) & this.mask;
        if (this.keys[slot] == key) {
            this.hits++;
            return this.values[slot];
        }
        this.misses++;
        int flags = resolver.resolve(x, y, z);
        this.keys[slot] = key;
        this.values[slot] = flags;
        return flags;
    }

    /**
     * Drops everything. Must be called at the start of each rebuild; see the class documentation for
     * what happens if it is not.
     */
    public void clear() {
        java.util.Arrays.fill(this.keys, EMPTY);
        java.util.Arrays.fill(this.values, 0);
        this.hits = 0;
        this.misses = 0;
    }

    /** Cached lookups since the last {@link #clear()}. Exposed so the hit rate can be asserted. */
    public int hits() {
        return this.hits;
    }

    /** Resolutions since the last {@link #clear()}. */
    public int misses() {
        return this.misses;
    }

    public int entries() {
        return this.keys.length;
    }
}
