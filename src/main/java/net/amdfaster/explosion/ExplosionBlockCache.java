package net.amdfaster.explosion;

import java.util.Arrays;

/**
 * Resolves each block's blast resistance at most once per explosion, instead of once per ray that
 * crosses it.
 *
 * <p>Vanilla marches 1,352 rays outward from an explosion -- the surface of a 16 by 16 by 16 grid,
 * which is 16^3 minus the 14^3 interior. Two numbers are easy to confuse and were confused here once:
 * the ray advances <b>0.3 blocks</b> per step, and its intensity falls by <b>0.225</b> per step. The
 * first is a distance and the second is a strength; taking the strength for the distance makes the blast
 * reach 4 blocks instead of 5.4 and undercounts the blocks it touches by half.
 *
 * <p>A TNT ray starts at intensity {@code 4 * (0.7 .. 1.3)}, so it survives 13 to 24 steps and averages
 * 18, reaching 5.4 blocks. At 18 steps the 1,352 rays make <b>24,336 block lookups against 823 distinct
 * positions, a factor of 29.6</b>. The same stone is asked its resistance thirty times.
 *
 * <p>Re-measured rather than estimated, and the distinct count is not a single number: it depends on
 * where inside its block the blast sits, and runs 768 to 823 across a block corner, a quarter point and
 * a block centre. 823 is the block-centre case, which is where a primed TNT actually is. An earlier
 * figure of 408 came from a model that walked inward from a 4 block radius in 0.225 block steps from the
 * origin, which matched the lookup count by coincidence and the distinct count not at all.
 *
 * <p>For a chain reaction the redundancy multiplies rather than adds, because adjacent blasts overlap
 * heavily and each one re-resolves the shared volume. A hundred TNT detonating in one tick is 2.4
 * million lookups against a distinct set that barely grows past the first blast's.
 *
 * <h2>The correctness hazard, and how it is handled</h2>
 *
 * <p>A cached resistance goes stale the moment the block is destroyed, and explosions destroy blocks.
 * Vanilla is helpful here by accident: {@code Explosion.explode} resolves every ray first and collects
 * what to destroy, and only {@code finalizeExplosion} removes anything. So no block changes during one
 * explosion's raycast phase, and caching across that whole phase is safe.
 *
 * <p>Across explosions in the same tick it is not, because blast B's rays can cross ground blast A
 * already removed. The caller must therefore report every removal through {@link #invalidate}, and
 * {@link #invalidateRange} exists because destruction arrives as a list rather than one at a time. A
 * cache that cannot be invalidated is not a cache, it is a way to compute an explosion against a world
 * that no longer exists.
 *
 * <p>Not thread safe. In singleplayer the integrated server runs in the same process as the client, so
 * this is reached from the server thread; it is not shared with the render thread.
 */
public final class ExplosionBlockCache {

    /** Supplies a block's blast resistance. Called at most once per position per invalidation. */
    public interface BlockSource {
        /**
         * @return the block's resistance to being destroyed. Negative means it cannot be destroyed,
         *     which vanilla uses for bedrock and barriers.
         */
        float resistanceAt(int x, int y, int z);
    }

    private static final int DEFAULT_CAPACITY = 4096;

    /** Field masks for {@link #pack}: 26 + 12 + 26 = 64 bits. */
    private static final long XZ_MASK = (1L << 26) - 1L;
    private static final long Y_MASK = (1L << 12) - 1L;

    private long[] keys;
    private float[] values;
    private boolean[] occupied;
    private int capacity;
    private int size;

    private long lookups;
    private long hits;
    private long invalidations;

    public ExplosionBlockCache() {
        this(DEFAULT_CAPACITY);
    }

    public ExplosionBlockCache(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        if (rounded <= 0) {
            rounded = 16;
        }
        this.capacity = rounded;
        this.keys = new long[this.capacity];
        this.values = new float[this.capacity];
        this.occupied = new boolean[this.capacity];
    }

    /**
     * How full the table is before it doubles.
     *
     * <p>Three quarters, the same threshold {@code mem/Canonicalizer} uses. Open addressing degrades
     * fast past this: probe length grows roughly as 1/(1-load), so going from 0.75 to 0.9 more than
     * doubles the average probe, and at 1.0 a probe that does not find its key never terminates at
     * all. That last part is not hypothetical -- it is what this class did before it could grow.
     */
    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    /**
     * Resolves a block's resistance, asking the world only if it has not been asked already.
     */
    public float resistanceAt(BlockSource source, int x, int y, int z) {
        this.lookups++;
        long key = pack(x, y, z);
        int index = probe(key);
        if (this.occupied[index]) {
            this.hits++;
            return this.values[index];
        }
        float resistance = source.resistanceAt(x, y, z);
        this.keys[index] = key;
        this.values[index] = resistance;
        this.occupied[index] = true;
        this.size++;
        if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
            grow();
        }
        return resistance;
    }

    /**
     * Doubles the table and rehashes.
     *
     * <p>Without this the table fills, and {@link #probe} on a full table with a missing key walks
     * every slot and comes back to where it started. A cache that hangs the server thread on the
     * 4,097th distinct block of a large chain reaction is worse than no cache.
     */
    private void grow() {
        long[] oldKeys = this.keys;
        float[] oldValues = this.values;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.values = new float[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.values[index] = oldValues[i];
            this.occupied[index] = true;
            this.size++;
        }
    }

    /**
     * Drops one position after it changes.
     *
     * <p>Open addressing means a removed entry has to close its hole or later probes stop short. This
     * walks the cluster and shifts, the same approach {@code mem/Canonicalizer} uses and for the same
     * reason: a tombstoned table accumulates one dead slot per removal and every lookup then crosses
     * all of them, which is the opposite of what a cache is for.
     */
    public void invalidate(int x, int y, int z) {
        long key = pack(x, y, z);
        int index = probe(key);
        if (!this.occupied[index]) {
            return;
        }
        this.occupied[index] = false;
        this.size--;
        this.invalidations++;
        backshift(index);
    }

    /** Drops a run of positions, which is how destruction actually arrives. */
    public void invalidateRange(long[] packedPositions, int count) {
        for (int i = 0; i < count; i++) {
            int index = probe(packedPositions[i]);
            if (this.occupied[index]) {
                this.occupied[index] = false;
                this.size--;
                this.invalidations++;
                backshift(index);
            }
        }
    }

    /**
     * Drops everything.
     *
     * <p>For the start of a tick where the world may have changed in ways nobody reported. Blunt, but
     * a cache that is wrong is worse than an empty one, and this is the escape hatch that makes the
     * rest safe to use.
     */
    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    private int probe(long key) {
        int index = spread(key) & (this.capacity - 1);
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.occupied[j]) {
                int ideal = spread(this.keys[j]) & (this.capacity - 1);
                boolean movable = i <= j
                        ? ideal > i && ideal <= j
                        : ideal > i || ideal <= j;
                if (movable) {
                    j = (j + 1) & (this.capacity - 1);
                } else {
                    break;
                }
            }
            if (!this.occupied[j]) {
                return;
            }
            this.keys[i] = this.keys[j];
            this.values[i] = this.values[j];
            this.occupied[i] = true;
            this.occupied[j] = false;
            i = j;
        }
    }

    /**
     * Packs a block position into the key used by the table.
     *
     * <p>Twenty-six bits of X, twelve of Y, twenty-six of Z: exactly sixty-four, and the layout
     * Minecraft itself packs a block position into. X and Z reach the world border at 29,999,984 and Y
     * the build limits, so no two distinct positions in a world share a key.
     *
     * <p><b>This used to be an int, and that was a bug rather than a trade.</b> Twelve bits of X and Z
     * and eight of Y fit a single blast comfortably, which is what the old comment argued, but the
     * eight bit Y field only spans 256 blocks and the world is 384 tall -- so y=-64 and y=192, both
     * ordinary build heights, packed to the same key and shared a resistance. Inside one blast that is
     * unreachable; across a chain reaction it is not, and a wrong resistance is a wrong explosion
     * rather than a slow one. Widening the key costs eight bytes a slot and removes the case.
     *
     * <p>Sign extension on the way back out is the part that is easy to get wrong: each field is
     * recovered by shifting it into the top of the long and shifting back down arithmetically, which
     * extends the field's own sign rather than the key's.
     */
    public static long pack(int x, int y, int z) {
        return (((long) x & XZ_MASK) << 38)
                | (((long) y & Y_MASK) << 26)
                | ((long) z & XZ_MASK);
    }

    /** Recovers the x coordinate from a packed key. */
    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    /** Recovers the y coordinate from a packed key. */
    public static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    /** Recovers the z coordinate from a packed key. */
    public static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }

    /** Spreads a packed position so adjacent blocks do not collide. */
    static int spread(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return (int) h & 0x7FFFFFFF;
    }

    /** Distinct blocks currently cached. */
    public int size() {
        return this.size;
    }

    public int capacity() {
        return this.capacity;
    }

    public long lookups() {
        return this.lookups;
    }

    /** Lookups answered from the cache. The ratio against {@link #lookups()} is the saving. */
    public long hits() {
        return this.hits;
    }

    public long invalidations() {
        return this.invalidations;
    }

    /** Hit rate as a percentage. Returns 0.0 rather than NaN before any lookup. */
    public double hitRatePercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.hits / this.lookups;
    }

    public void resetCounters() {
        this.lookups = 0;
        this.hits = 0;
        this.invalidations = 0;
    }
}
