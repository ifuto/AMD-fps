package net.amdfaster.hopper;

import java.util.Arrays;

/**
 * Remembers what is next to a hopper, and remembers when there is nothing to move.
 *
 * <p>A hopper tries to transfer every eight ticks, and each attempt searches the block above it for
 * a container and the block it points at for a container. Item farms and sorting walls are hundreds
 * of hoppers, so at 200 hoppers that is 10,000 container searches per 200 ticks, and in a farm where
 * most hoppers are idle it is nearly all waste.
 *
 * <p>Two separate things are worth caching and they get confused often enough to be worth naming.
 *
 * <p>The first is <em>which container is at that position</em>. That changes only when the block
 * changes, which for a built farm is never. The cache answers it and a block change calls
 * {@link #invalidateAt}.
 *
 * <p>The second is <em>whether there is anything to move</em>. That changes whenever an item arrives,
 * which is often, so it cannot be cached on a timer. {@link #isIdle} reports the remembered state and
 * the caller clears it when an item is inserted. A hopper that is idle and has not been woken does
 * not search at all, which is the saving that matters: at 95% idle it is 95% of the searches gone,
 * not 95% of them made cheaper.
 *
 * <h2>Correctness</h2>
 *
 * <p>A hopper that stays asleep while items sit above it is a farm that silently stops working. So
 * the idle flag is only ever set by the caller that has just looked and found nothing, and any insert
 * must clear it. {@link #wakeAll} exists for the case where the mod cannot hook every insert path --
 * a coarse wake is slow and correct, a missing wake is fast and broken, and this project takes slow.
 *
 * <p>Not thread safe.
 */
public final class HopperTransferCache {

    /**
     * Supplies the container at a position.
     *
     * <p>Returns a token identifying it, or {@link #NO_CONTAINER} for a position with nothing to
     * extract from. The token is opaque; the point is that asking twice is expensive and the answer
     * does not change until the block does.
     */
    public interface ContainerLookup {
        /** @return a container token, or {@link #NO_CONTAINER} */
        long containerAt(int x, int y, int z);
    }

    /** A position with no container. Distinct from zero, which is a legitimate token. */
    public static final long NO_CONTAINER = -1L;

    private static final int DEFAULT_CAPACITY = 512;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    private int[] keys;
    private long[] tokens;
    private int capacity;
    private int size;

    private boolean[] idle;
    private int hoppers;

    private long lookups;
    private long hits;
    private long searches;
    private long skipped;
    private long wakes;
    private long invalidations;

    public HopperTransferCache() {
        this(DEFAULT_CAPACITY, 256);
    }

    /**
     * @param capacity initial size of the position table
     * @param hopperCount how many hoppers to track idle state for
     */
    public HopperTransferCache(int capacity, int hopperCount) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        if (hopperCount <= 0) {
            throw new IllegalArgumentException("hopperCount must be positive: " + hopperCount);
        }
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        if (rounded <= 0) {
            rounded = 16;
        }
        this.capacity = rounded;
        this.keys = new int[this.capacity];
        // NO_CONTAINER rather than zero as the empty marker. Zero is a plausible container token, and
        // using it as "absent" would make every uncached position look cached.
        this.tokens = new long[this.capacity];
        Arrays.fill(this.tokens, NO_CONTAINER);
        this.hoppers = hopperCount;
        this.idle = new boolean[this.hoppers];
    }

    /** Resolves the container at a position, asking the world only the first time. */
    public long containerAt(ContainerLookup lookup, int x, int y, int z) {
        this.lookups++;
        int key = pack(x, y, z);
        int index = probe(key);
        long cached = this.tokens[index];
        if (cached != NO_CONTAINER) {
            this.hits++;
            return cached;
        }
        long token = lookup.containerAt(x, y, z);
        this.keys[index] = key;
        this.tokens[index] = token;
        this.size++;
        if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
            grow();
        }
        return token;
    }

    /** Drops a position's container. Called when the block there changes. */
    public void invalidateAt(int x, int y, int z) {
        int key = pack(x, y, z);
        int index = probe(key);
        if (this.tokens[index] == NO_CONTAINER) {
            return;
        }
        this.tokens[index] = NO_CONTAINER;
        this.size--;
        this.invalidations++;
        backshift(index);
    }

    /**
     * Whether a transfer attempt is worth making.
     *
     * <p>False means the hopper looked recently and there was nothing to move, and nothing has been
     * inserted since. Counted separately from the container lookups, because this is the check that
     * skips the whole scan.
     */
    public boolean shouldAttempt(int hopperId) {
        checkHopper(hopperId);
        if (this.idle[hopperId]) {
            this.skipped++;
            return false;
        }
        this.searches++;
        return true;
    }

    /**
     * Records what a transfer attempt found.
     *
     * @param didMove whether anything moved, which is also whether there may be more to move
     */
    public void afterAttempt(int hopperId, boolean didMove) {
        checkHopper(hopperId);
        this.idle[hopperId] = !didMove;
    }

    /** Wakes one hopper. Called when an item is inserted where it draws from. */
    public void wake(int hopperId) {
        checkHopper(hopperId);
        if (!this.idle[hopperId]) {
            return;
        }
        this.idle[hopperId] = false;
        this.wakes++;
    }

    /**
     * Wakes every hopper.
     *
     * <p>For the case where not every insert path can be hooked. Slower than a targeted wake and
     * correct, where a missed wake is fast and breaks a farm.
     */
    public void wakeAll() {
        for (int i = 0; i < this.hoppers; i++) {
            if (this.idle[i]) {
                this.idle[i] = false;
                this.wakes++;
            }
        }
    }

    /** Grows the tracked set, preserving idle state for hoppers already known. */
    public void growTo(int hopperCount) {
        if (hopperCount <= this.hoppers) {
            return;
        }
        this.idle = Arrays.copyOf(this.idle, hopperCount);
        this.hoppers = hopperCount;
    }

    private void checkHopper(int hopperId) {
        if (hopperId < 0 || hopperId >= this.hoppers) {
            throw new IndexOutOfBoundsException(
                    "hopperId " + hopperId + " outside " + this.hoppers);
        }
    }

    /**
     * Packs a block position into an int.
     *
     * <p>Twenty-six bits of X and Z and twelve of Y. A farm's hoppers sit within a few hundred blocks
     * of each other, but this is a cache key, so positions that pack alike share an answer. Anything
     * needing arbitrary world coordinates needs a wider key.
     */
    public static int pack(int x, int y, int z) {
        return (x << 12) ^ (z << 1) ^ ((y & 0xFFF) << 20);
    }

    private int probe(int key) {
        int index = spread(key) & (this.capacity - 1);
        while (this.tokens[index] != NO_CONTAINER) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    /**
     * Closes the hole left by an invalidation, using the tokens array as the occupancy marker.
     *
     * <p>One array rather than one array and a parallel boolean. Another class in this project kept
     * both, and its backshift cleared the flag on the source without setting it on the destination,
     * which made every shifted entry invisible to later lookups.
     */
    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.tokens[j] != NO_CONTAINER) {
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
            if (this.tokens[j] == NO_CONTAINER) {
                return;
            }
            this.keys[i] = this.keys[j];
            this.tokens[i] = this.tokens[j];
            this.tokens[j] = NO_CONTAINER;
            i = j;
        }
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        int[] oldKeys = this.keys;
        long[] oldTokens = this.tokens;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new int[this.capacity];
        this.tokens = new long[this.capacity];
        Arrays.fill(this.tokens, NO_CONTAINER);
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldTokens[i] == NO_CONTAINER) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.tokens[index] = oldTokens[i];
            this.size++;
        }
    }

    public int size() {
        return this.size;
    }

    public int hopperCount() {
        return this.hoppers;
    }

    public long lookups() {
        return this.lookups;
    }

    public long hits() {
        return this.hits;
    }

    /** Transfer attempts that ran. */
    public long searches() {
        return this.searches;
    }

    /** Transfer attempts skipped because the hopper was known to be idle. */
    public long skipped() {
        return this.skipped;
    }

    public long wakes() {
        return this.wakes;
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
        this.searches = 0;
        this.skipped = 0;
        this.wakes = 0;
        this.invalidations = 0;
    }
}
