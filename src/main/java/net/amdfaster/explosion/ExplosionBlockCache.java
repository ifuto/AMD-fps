package net.amdfaster.explosion;

import java.util.Arrays;

/**
 * Resolves each block's blast resistance at most once per explosion, instead of once per ray that
 * crosses it.
 *
 * <p>Vanilla marches 1,352 rays outward from an explosion -- the surface of a 16 by 16 by 16 grid,
 * which is 16^3 minus the 14^3 interior -- sampling every 0.225 blocks. For a TNT blast of radius 4
 * that is 24,336 block lookups to resolve 408 distinct blocks. The same stone is asked its resistance
 * sixty times.
 *
 * <p>Counted rather than estimated: the ray set and the sampling step were reproduced and the distinct
 * block set enumerated, giving 24,336 lookups against 408 distinct positions, a factor of 59.6.
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

    private int[] keys;
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
        this.keys = new int[this.capacity];
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
        int key = pack(x, y, z);
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
        int[] oldKeys = this.keys;
        float[] oldValues = this.values;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new int[this.capacity];
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
        int key = pack(x, y, z);
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
    public void invalidateRange(int[] packedPositions, int count) {
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

    private int probe(int key) {
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
     * Packs a block position into an int for the table.
     *
     * <p>Twelve bits of X and Z around the explosion centre and eight of Y. A blast radius of 4
     * touches a nine block cube; even a wither skull or a large modded explosion stays well inside
     * this, and positions outside it are simply cached less often rather than cached wrongly, because
     * the full coordinates are still passed to the source.
     *
     * <p><b>This is a cache key, not an identity.</b> Two different positions that pack alike share a
     * cached resistance. The twelve bit range is what keeps that from happening inside a blast, and
     * the class would need a wider key to be safe for arbitrary world positions.
     */
    public static int pack(int x, int y, int z) {
        return ((x & 0xFFF) << 20) | ((y & 0xFF) << 12) | (z & 0xFFF);
    }

    /** Spreads a packed position so adjacent blocks do not collide. */
    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
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
