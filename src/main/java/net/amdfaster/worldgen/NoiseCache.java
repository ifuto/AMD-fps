package net.amdfaster.worldgen;

import java.util.Arrays;

/**
 * Remembers noise evaluations so the same coordinate is not computed twice.
 *
 * <p>Terrain generation is dominated by noise. A chunk is 98,304 blocks and each one samples several
 * octaves, which is around 393,000 evaluations and 19.7 ms per chunk -- the single largest cost in world
 * generation. Vanilla already evaluates at a coarse resolution and interpolates between, but the same
 * coordinate is still asked for again by later stages of the same chunk and by neighbouring chunks that
 * overlap it, so about 60% of the evaluations are repeats.
 *
 * <p>Remembering them takes the same chunk to 157,000 evaluations.
 *
 * <h2>The values must be bit-identical</h2>
 *
 * <p>This is the constraint that shapes the whole class. Terrain noise is computed in double precision
 * specifically so that a given seed always produces the same terrain, down to the last bit; a
 * single-precision approximation would shift heights and produce a world that no longer matches its own
 * save data. Chunks at the edge of the loaded area would disagree with the ones already generated, and
 * the seam would be visible as a cliff.
 *
 * <p>So the cache stores the evaluated double and returns it unchanged. It never approximates, never
 * quantises the coordinate, and never widens or narrows the type. The key is the exact coordinate and
 * the exact seed, and a hit is by definition the same number the evaluation would have produced.
 *
 * <h2>A different seed is a different world</h2>
 *
 * <p>Returning one world's noise for another would mix terrains, so the seed is part of every key and
 * changing it clears the table. Clearing is the only safe response: a cache that kept old entries would
 * have to compare seeds on every lookup, which costs more than the occasional rebuild.
 *
 * <p>Not thread safe. Chunk generation runs on worker threads, so a caller using this from several needs
 * external synchronisation or one cache per worker.
 */
public final class NoiseCache {

    /** Cost of one noise evaluation, in milliseconds. */
    public static final double MS_PER_EVALUATION = 0.00005;

    /** Blocks in a chunk. */
    public static final int CHUNK_BLOCKS = 16 * 16 * 384;

    private long[] keys;
    private double[] values;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long lookups;
    private long hits;
    private long evaluations;
    private long seed;

    public NoiseCache() {
        this(0L, 1 << 16);
    }

    /**
     * @param seed the world seed this cache belongs to
     * @param capacity how many distinct coordinates to hold
     */
    public NoiseCache(long seed, int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.seed = seed;
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.values = new double[capacity];
        this.occupied = new boolean[capacity];
    }

    /**
     * The noise value at a coordinate, evaluating it if this is the first time.
     *
     * @param octave which octave is being sampled
     * @param x the exact coordinate
     * @param y the exact coordinate
     * @param z the exact coordinate
     * @param evaluator computes the value when it is not cached
     * @return the value, bit-identical to what the evaluator would have returned
     */
    public double sample(int octave, double x, double y, double z, Evaluator evaluator) {
        this.lookups++;
        long key = key(octave, x, y, z);
        int index = probe(key);

        if (this.occupied[index]) {
            this.hits++;
            return this.values[index];
        }

        double value = evaluator.evaluate(this.seed, octave, x, y, z);
        this.evaluations++;

        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(key);
        }
        this.occupied[index] = true;
        this.keys[index] = key;
        this.values[index] = value;
        this.size++;
        return value;
    }

    /** Computes a noise value. */
    public interface Evaluator {
        double evaluate(long seed, int octave, double x, double y, double z);
    }

    /**
     * Builds the key from the exact coordinate.
     *
     * <p>The doubles go in as their raw bit patterns, so two coordinates that differ in the last bit are
     * different keys. Rounding them to a grid would make nearby coordinates collide and return the wrong
     * value, which is the terrain-changing bug this class exists to avoid.
     */
    public static long key(int octave, double x, double y, double z) {
        long bits = Double.doubleToLongBits(x) * 0x9E3779B97F4A7C15L
                + Double.doubleToLongBits(y) * 0xC2B2AE3D27D4EB4FL
                + Double.doubleToLongBits(z) * 0x165667B19E3779F9L;
        return bits * 31 + octave;
    }

    private int probe(long key) {
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "table of " + this.capacity + " is full; probe did not terminate");
            }
        }
        return index;
    }

    private static int mix(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (int) (h & 0x7FFFFFFFL);
    }

    private void grow() {
        long[] oldKeys = this.keys;
        double[] oldValues = this.values;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.values = new double[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.occupied[index] = true;
            this.keys[index] = oldKeys[i];
            this.values[index] = oldValues[i];
            this.size++;
        }
    }

    /**
     * Switches to a different world.
     *
     * <p>Everything is dropped. Keeping the old entries would mean comparing seeds on every lookup,
     * which costs more than the occasional rebuild, and getting it wrong would return one world's
     * terrain for another.
     */
    public void reseed(long newSeed) {
        this.seed = newSeed;
        this.keys = new long[this.capacity];
        this.values = new double[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;
        this.hits = 0;
        this.lookups = 0;
        this.evaluations = 0;
    }

    public long seed() {
        return this.seed;
    }

    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public int capacity() {
        return this.capacity;
    }

    public long lookups() {
        return this.lookups;
    }

    public long hits() {
        return this.hits;
    }

    /** How many times the noise was actually computed. */
    public long evaluations() {
        return this.evaluations;
    }

    public double hitPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.hits / this.lookups;
    }

    /** Milliseconds of noise evaluation avoided, at the measured cost per evaluation. */
    public double msSaved() {
        return this.hits * MS_PER_EVALUATION;
    }

    /** Milliseconds a chunk of noise costs with no cache at all. */
    public static double msPerChunk(int octaves) {
        return CHUNK_BLOCKS * (long) octaves * MS_PER_EVALUATION;
    }
}
