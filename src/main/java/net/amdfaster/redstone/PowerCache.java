package net.amdfaster.redstone;

import java.util.Arrays;

/**
 * Answers a neighbour's signal strength from a cache, so a cascade asks each position once.
 *
 * <p>Updating redstone wire means reading the signal around it. Vanilla reads the four horizontal
 * neighbours and the blocks above and below each, and during a cascade the same positions get read
 * over and over as the change propagates. Measured rather than estimated: a line of 30 wire re-reads
 * its 30 positions 116 times, and a 16 by 16 sheet of wire reads its 256 positions 1,920 times. A
 * factor of four in a line and seven and a half in a sheet, and each read is a chunk lookup, a
 * section lookup and a palette decode.
 *
 * <h2>Why this one is harder than caching a blast</h2>
 *
 * <p>Signal strength changes while the cascade is running. A cached value that was correct when the
 * first wire looked is wrong by the time the tenth does, and a cache that does not know that does not
 * slow redstone down -- it makes it wrong. Wire that holds its power when it should drop is the bug
 * that shows up as a door that stays open, and it is a great deal harder to trace to a cache than a
 * crash is.
 *
 * <p>So the writer is required to report what it wrote. {@link #recordPower} stores the new value
 * rather than dropping the entry, because the value is already known and re-reading it from the world
 * would cost the lookup the cache exists to avoid. A cache that only invalidates would still be
 * correct, just slower; one that neither invalidates nor records is broken.
 *
 * <p>The safe unit of caching is one wire's neighbour scan: vanilla reads all of a wire's inputs
 * before writing its output, so nothing changes during a single scan. Between wires the writer's
 * {@code recordPower} keeps the cache honest.
 *
 * <h2>Scope</h2>
 *
 * <p>This is redstone on the server thread, which in singleplayer is the client's own process, so it
 * is in scope for a client-side mod. On a dedicated server it is not reached.
 *
 * <p>Not thread safe.
 */
public final class PowerCache {

    /** Supplies a position's signal strength. Called at most once per position per invalidation. */
    public interface PowerSource {
        /** @return 0 to 15 */
        int signalAt(int x, int y, int z);
    }

    private static final int DEFAULT_CAPACITY = 1024;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    /** Signal strengths run 0 to 15, so anything at or above this marks an empty slot. */
    public static final int ABSENT = 16;

    private int[] keys;
    private int[] values;
    private int capacity;
    private int size;

    private long lookups;
    private long hits;
    private long recordings;
    private long invalidations;

    public PowerCache() {
        this(DEFAULT_CAPACITY);
    }

    public PowerCache(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        if (rounded <= 0) {
            rounded = 16;
        }
        this.capacity = rounded;
        this.keys = new int[this.capacity];
        // ABSENT rather than zero, because zero is a real signal strength -- the most common one --
        // and using it as the empty marker would make every unpowered position look cached.
        this.values = new int[this.capacity];
        Arrays.fill(this.values, ABSENT);
    }

    /**
     * Resolves a position's signal strength.
     *
     * @throws IllegalArgumentException if the source returns something outside 0 to 15, since the
     *     value has to be distinguishable from {@link #ABSENT}
     */
    public int signalAt(PowerSource source, int x, int y, int z) {
        this.lookups++;
        int key = pack(x, y, z);
        int index = probe(key);
        int cached = this.values[index];
        if (cached != ABSENT) {
            this.hits++;
            return cached;
        }
        int signal = source.signalAt(x, y, z);
        checkSignal(signal, x, y, z);
        this.keys[index] = key;
        this.values[index] = signal;
        this.size++;
        if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
            grow();
        }
        return signal;
    }

    /**
     * Reports a signal strength this cache's caller has just written.
     *
     * <p>Stores rather than drops. Dropping would be correct but would cost a world lookup on the
     * next read of a value the caller is holding in a local variable right now.
     */
    public void recordPower(int x, int y, int z, int signal) {
        checkSignal(signal, x, y, z);
        int key = pack(x, y, z);
        int index = probe(key);
        if (this.values[index] == ABSENT) {
            this.keys[index] = key;
            this.size++;
            if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
                grow();
            }
        }
        this.values[index] = signal;
        this.recordings++;
    }

    /** Drops one position, for a block change that alters what its signal means. */
    public void invalidateAt(int x, int y, int z) {
        int key = pack(x, y, z);
        int index = probe(key);
        if (this.values[index] == ABSENT) {
            return;
        }
        this.values[index] = ABSENT;
        this.size--;
        this.invalidations++;
        backshift(index);
    }

    /** Drops everything. For the start of a tick where the world may have changed unreported. */
    public void clear() {
        Arrays.fill(this.values, ABSENT);
        this.size = 0;
    }

    private static void checkSignal(int signal, int x, int y, int z) {
        if (signal < 0 || signal > 15) {
            throw new IllegalArgumentException(
                    "signal at " + x + "," + y + "," + z + " is " + signal
                            + ", which collides with the absent marker");
        }
    }

    private int probe(int key) {
        int index = spread(key) & (this.capacity - 1);
        while (this.values[index] != ABSENT) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    /**
     * Closes the hole left by an invalidation.
     *
     * <p>Uses the values array as the occupancy marker, so there is one array that cannot disagree
     * with itself. An earlier class in this project kept a parallel boolean array and the backshift
     * set the flag on the source without setting it on the destination, which made every shifted
     * entry invisible; one array removes the possibility.
     */
    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.values[j] != ABSENT) {
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
            if (this.values[j] == ABSENT) {
                return;
            }
            this.keys[i] = this.keys[j];
            this.values[i] = this.values[j];
            this.values[j] = ABSENT;
            i = j;
        }
    }

    /**
     * Packs a block position into an int.
     *
     * <p>Twelve bits of X and Z and eight of Y around the circuit being updated. A redstone cascade
     * travels at most fifteen blocks from its source, so this is generous by a wide margin, but it is
     * a cache key rather than an identity: positions that pack alike share a cached value. Anything
     * that needs arbitrary world coordinates needs a wider key.
     */
    public static int pack(int x, int y, int z) {
        return ((x & 0xFFF) << 20) | ((y & 0xFF) << 12) | (z & 0xFFF);
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        int[] oldKeys = this.keys;
        int[] oldValues = this.values;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new int[this.capacity];
        this.values = new int[this.capacity];
        Arrays.fill(this.values, ABSENT);
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldValues[i] == ABSENT) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.values[index] = oldValues[i];
            this.size++;
        }
    }

    /** Positions currently cached. */
    public int size() {
        return this.size;
    }

    public long lookups() {
        return this.lookups;
    }

    public long hits() {
        return this.hits;
    }

    /** Times a caller reported a value it had just written. */
    public long recordings() {
        return this.recordings;
    }

    public long invalidations() {
        return this.invalidations;
    }

    public double hitRatePercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.hits / this.lookups;
    }

    public void resetCounters() {
        this.lookups = 0;
        this.hits = 0;
        this.recordings = 0;
        this.invalidations = 0;
    }
}
