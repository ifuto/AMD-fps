package net.amdfaster.explosion;

/**
 * Quantifies what the packed destroy list actually saves, and says plainly where the saving is and is not
 * large.
 *
 * <p>{@link BlastDamageBuffer} replaces a {@code List<BlockPos>} with a reused {@code long[]}, and the
 * per-blast byte counts are easy to state: 823 distinct blocks at 16 bytes each is 13,168 bytes, against
 * 6,584 bytes in a packed array that is allocated once and never again. That part is a 2 to 1 on the
 * distinct blocks and closer to 59 to 1 against the per-lookup bound, since a ray that reaches an already
 * listed block would otherwise make another position object for it.
 *
 * <p>What the numbers do not support is calling that a large share of the game's garbage. Minecraft
 * allocates at upwards of 800 MB a second on a busy server, and a hundred explosions a second at the
 * distinct-block count add 0.16 per cent of that. So the honest claim is narrower and more useful: the
 * saving is not big in steady state, it is concentrated. A TNT chain puts dozens of blasts in one tick,
 * and a tick is 50 milliseconds, so an allocation that is negligible spread over a second is not
 * negligible inside the tick it lands in. Young collections are driven by rate over eden size, and a
 * burst that would have triggered one mid-tick is the difference between a frame that hitched and one that
 * did not.
 *
 * <p>The other thing worth stating, because it decides whether this optimisation does anything at all, is
 * that the JIT cannot remove these allocations on its own. Escape analysis scalar-replaces an object only
 * when it stays inside the method that made it. An explosion's destroy list is a {@code List<BlockPos>}
 * handed to the level to destroy, which makes every position in it globally escaping -- stored in a
 * collection and passed out -- and a globally escaping object is allocated for real. So the packed array
 * is not duplicating something the compiler already did.
 *
 * <p>The cost being avoided is also worth being precise about. Individual allocation is cheap: a TLAB
 * bump is a pointer increment. What allocation rate buys is collection frequency, and halving the rate
 * halves how often a young collection interrupts. The measured pair for a scalar-replaced versus a real
 * 16 byte allocation is 1.919 against 3.781 nanoseconds and zero against 16 bytes, so the per-object cost
 * is small and the frequency effect is the thing that shows up in a frame time.
 *
 * @see <a href="https://docs.papermc.io/paper/aikars-flags/">Aikar's flags -- Minecraft's allocation rate
 *      reaches at least 800 MB/s on a 30 player server, mostly short-lived objects such as block
 *      positions</a>
 * @see <a href="https://shipilev.net/jvm/anatomy-quarks/18-scalar-replacement/">JVM Anatomy Quark 18 --
 *      HotSpot C2 does scalar replacement rather than stack allocation, and it needs the object
 *      decomposable everywhere it is used; 1.919 ns and ~0 B replaced against 3.781 ns and 16 B not</a>
 * @see <a href="https://mechanical-sympathy.blogspot.com/2013/07/java-garbage-collection-distilled.html">
 *      Mechanical Sympathy -- individual allocation is very cheap, and the rate at which minor collections
 *      occur is directly proportional to the allocation rate</a>
 */
public final class BlastAllocationModel {

    /** Distinct blocks one TNT blast destroys. */
    public static final int BLOCKS_PER_BLAST = BlastDamageBuffer.BLOCKS_PER_BLAST;

    /** Block lookups one blast makes, of which most hit a block already in the list. */
    public static final int LOOKUPS_PER_BLAST = ChainReactionDeduplicator.LOOKUPS_PER_BLAST;

    /**
     * Bytes in a {@code BlockPos} on the heap: a 16 byte header and field pair.
     *
     * <p>The measured figure for a 16 byte allocation that escape analysis could not remove.
     */
    public static final int BLOCKPOS_BYTES = 16;

    /** Bytes per entry in the packed array. */
    public static final int PACKED_BYTES = BlastDamageBuffer.PACKED_BYTES_PER_ENTRY;

    /**
     * The allocation rate a busy Minecraft server reaches, in bytes per second.
     *
     * <p>Used as the denominator for "how much of the game's garbage is this", and deliberately a large
     * figure: a claim of significance measured against a small baseline would be a claim about the
     * baseline.
     */
    public static final long GAME_RATE_BYTES_PER_SECOND = 800L * 1024 * 1024;

    /** Nanoseconds per scalar-replaced allocation, measured. */
    public static final double NS_SCALAR_REPLACED = 1.919;

    /** Nanoseconds per allocation the JIT could not remove, measured. */
    public static final double NS_NOT_REPLACED = 3.781;

    private BlastAllocationModel() {
    }

    /**
     * One blast's allocation, before and after.
     *
     * @param vanillaDistinct bytes for one position object per destroyed block
     * @param vanillaPerLookup bytes if every ray hit made its own position, which is the bound a
     *                         {@code List<BlockPos>} built during the ray walk approaches
     * @param packedFirstBlast bytes for the array's first allocation
     * @param packedSteady     bytes once the array is warm, which is nothing
     */
    public record Blast(int vanillaDistinct, int vanillaPerLookup, int packedFirstBlast,
                        int packedSteady) {

        /** The saving on the distinct-block count, which is the conservative one. */
        public double distinctRatio() {
            return this.vanillaDistinct / (double) Math.max(1, this.packedFirstBlast);
        }

        /** The saving against the per-lookup bound. */
        public double perLookupRatio() {
            return this.vanillaPerLookup / (double) Math.max(1, this.packedFirstBlast);
        }
    }

    /** One blast's allocation, at the counts measured for {@link BlastDamageBuffer}. */
    public static Blast perBlast() {
        return new Blast(BLOCKS_PER_BLAST * BLOCKPOS_BYTES,
                LOOKUPS_PER_BLAST * BLOCKPOS_BYTES,
                BLOCKS_PER_BLAST * PACKED_BYTES,
                0);
    }

    /**
     * Young collections per second for an allocation rate and an eden size.
     *
     * <p>This is the whole mechanism. A young collection fires when eden fills, so the interval is eden
     * over rate, and halving the rate doubles the interval. Individual allocation cost barely enters into
     * it.
     */
    public static double youngCollectionsPerSecond(long bytesPerSecond, long edenBytes) {
        require(bytesPerSecond >= 0, "a rate must not be negative");
        require(edenBytes > 0, "eden must have a size");
        return bytesPerSecond / (double) edenBytes;
    }

    /**
     * The eden size for a heap and a young generation percentage.
     *
     * <p>Named because the percentage is the thing that moves the answer more than anything in the
     * explosion does. G1 sizes the young generation down to about 5 per cent by default, which is far too
     * little for this workload, and the widely used tuning raises it to 30 or 40 -- a sixfold difference
     * in collection frequency for the same allocation rate.
     */
    public static long edenBytes(long heapBytes, int youngPercent) {
        require(heapBytes > 0, "a heap must have a size");
        require(youngPercent >= 1 && youngPercent <= 100, "a percentage is between 1 and 100");
        return heapBytes * youngPercent / 100;
    }

    /**
     * What a blast rate adds to the game's allocation, as a percentage of it.
     *
     * <p>The number that keeps this honest. At the distinct-block count a hundred blasts a second is
     * 0.16 per cent of 800 MB/s, and even at the per-lookup bound it is under 5 per cent. The saving is
     * real and it is not a large share of the steady state -- which is why the argument for it is about
     * where the allocation lands, not how much there is.
     */
    public static double percentOfGameRate(int blastsPerSecond, int bytesPerBlast) {
        require(blastsPerSecond >= 0, "a rate must not be negative");
        require(bytesPerBlast >= 0, "a size must not be negative");
        return 100.0 * ((long) blastsPerSecond * bytesPerBlast) / GAME_RATE_BYTES_PER_SECOND;
    }

    /**
     * How much slower an allocation the JIT could not remove is, against one it could.
     *
     * <p>Roughly double, and 16 bytes against none. Worth having because it says what escaping the
     * destroy list costs per object, which is separate from the collection frequency effect and small
     * next to it.
     */
    public static double scalarReplacementSlowdown() {
        return NS_NOT_REPLACED / NS_SCALAR_REPLACED;
    }

    /**
     * Whether an allocation site can be scalar replaced.
     *
     * <p>The three things that defeat it are storing the object in a field, passing it into a collection,
     * and returning it. An explosion's destroy list does the second, so every position in it is allocated
     * for real and no compiler setting changes that.
     *
     * @param storedInField    assigned to an instance or static field
     * @param putInCollection  added to a list, map or other collection
     * @param returnedFromMethod handed back to a caller
     */
    public static boolean canScalarReplace(boolean storedInField, boolean putInCollection,
                                           boolean returnedFromMethod) {
        return !storedInField && !putInCollection && !returnedFromMethod;
    }

    /** Whether the explosion's destroy list is one the JIT can remove. It is not. */
    public static boolean destroyListEscapes() {
        return !canScalarReplace(false, true, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
