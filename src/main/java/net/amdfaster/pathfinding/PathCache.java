package net.amdfaster.pathfinding;

import java.util.Arrays;

/**
 * Stops a mob recomputing a path that has already failed, and stops one search reading the same
 * block three times.
 *
 * <p>Two different wastes, and they need different answers.
 *
 * <p>Within a single search, neighbouring nodes are reached from several parents, so the same
 * position's walkability gets read over and over. At an expansion limit of 64 with each node reached
 * about three times, two thirds of the reads are repeats, and each read is a block-state lookup.
 * That one is safe to cache for the length of the search and no longer, because blocks can change
 * between searches.
 *
 * <p>Across searches, a mob that cannot reach its target recomputes the whole failure whenever the
 * target moves. A mob staring at a player behind glass does this every second forever. Fifty mobs at
 * an expansion limit of 32 is 12,800 block reads a second, and at a 95% failure rate almost all of
 * it is the same failure again.
 *
 * <h2>Why a failed path can be remembered at all</h2>
 *
 * <p>Because the failure is a fact about the world and the target, not about the mob. If the target
 * is where it was and the world has not changed, the answer is the same. So a memo is keyed on the
 * entity, the target position and a world version, and {@link #bumpWorldVersion} voids every memo
 * when a block changes. A memo that survived a block placement would leave a mob unable to path to a
 * door someone just opened, which is a bug with no error message.
 *
 * <p>The world version is a single counter rather than per-position tracking. That is coarse: one
 * block placed anywhere clears every mob's memo. It is also correct, and correct-and-coarse is the
 * right trade when the alternative is fine-grained-and-wrong. A mob recomputing one path costs
 * microseconds; a mob that never paths again costs a player's evening.
 *
 * <p>Not thread safe.
 */
public final class PathCache {

    /** Supplies whether a position can be walked on. Called at most once per position per search. */
    public interface WalkabilitySource {
        boolean walkableAt(int x, int y, int z);
    }

    private static final int DEFAULT_CAPACITY = 512;

    /** How many ticks a recorded failure is believed for. */
    public static final int FAILURE_TICKS = 40;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    /** Tri-state: 0 not present, 1 not walkable, 2 walkable. Zero is never a real answer. */
    private static final byte ABSENT = 0;
    private static final byte NOT_WALKABLE = 1;
    private static final byte WALKABLE = 2;

    private int[] walkKeys;
    private byte[] walkStates;
    private int walkCapacity;
    private int walkCount;

    private long[] memoKeys;
    private int[] memoTicks;
    private int memoCapacity;
    private int memoSize;

    private long worldVersion;

    private long walkReads;
    private long walkHits;
    private long memoHits;
    private long memoRecords;
    private long memoVoids;

    public PathCache() {
        this(DEFAULT_CAPACITY, DEFAULT_CAPACITY);
    }

    public PathCache(int walkCapacity, int memoCapacity) {
        if (walkCapacity < 16 || memoCapacity < 16) {
            throw new IllegalArgumentException(
                    "capacities must be at least sixteen: " + walkCapacity + ", " + memoCapacity);
        }
        this.walkCapacity = roundUp(walkCapacity);
        this.walkKeys = new int[this.walkCapacity];
        this.walkStates = new byte[this.walkCapacity];
        this.memoCapacity = roundUp(memoCapacity);
        this.memoKeys = new long[this.memoCapacity];
        this.memoTicks = new int[this.memoCapacity];
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    /**
     * Starts a new search, dropping the per-position cache.
     *
     * <p>Walkability is only trusted for the length of one search. Blocks can change between
     * searches, and a cache that spanned them would route a mob through a block that is there now.
     */
    public void beginSearch() {
        Arrays.fill(this.walkStates, ABSENT);
        this.walkCount = 0;
    }

    /** Resolves walkability, asking the world only the first time this search. */
    public boolean walkableAt(WalkabilitySource source, int x, int y, int z) {
        this.walkReads++;
        int key = packPosition(x, y, z);
        int index = walkProbe(key);
        byte cached = this.walkStates[index];
        if (cached != ABSENT) {
            this.walkHits++;
            return cached == WALKABLE;
        }
        boolean walkable = source.walkableAt(x, y, z);
        // The state is written before the load is checked. Writing it afterwards means a growth
        // triggered here copies the table while the slot still reads as absent, and the entry in
        // flight is dropped. Two other classes in this project shipped that.
        this.walkKeys[index] = key;
        this.walkStates[index] = walkable ? WALKABLE : NOT_WALKABLE;
        this.walkCount++;
        if (this.walkCount * LOAD_DENOMINATOR >= this.walkCapacity * LOAD_NUMERATOR) {
            growWalk();
        }
        return walkable;
    }

    /**
     * Whether this entity has already failed to reach this target in this version of the world.
     *
     * @param tick the current tick, for the expiry window
     */
    public boolean isKnownUnreachable(int entityId, int targetX, int targetY, int targetZ, int tick) {
        long key = memoKey(entityId, targetX, targetY, targetZ);
        int index = memoProbe(key);
        if (this.memoTicks[index] == 0) {
            return false;
        }
        if (tick - this.memoTicks[index] > FAILURE_TICKS) {
            // Expired. Removed rather than left, so a mob that has been failing for a long time does
            // not keep a stale entry around to be compared against forever.
            this.memoTicks[index] = 0;
            this.memoSize--;
            memoBackshift(index);
            return false;
        }
        this.memoHits++;
        return true;
    }

    /**
     * Records that a search for this target failed.
     *
     * @param tick the tick the failure happened on
     */
    public void recordFailure(int entityId, int targetX, int targetY, int targetZ, int tick) {
        long key = memoKey(entityId, targetX, targetY, targetZ);
        int index = memoProbe(key);
        // A tick of zero would read as an empty slot, since that is what the memo table uses to mean
        // "no failure recorded". Shifting it to one loses a tick of validity and keeps the table
        // coherent, which is the cheaper mistake to make.
        int stored = tick == 0 ? 1 : tick;
        boolean isNew = this.memoTicks[index] == 0;
        this.memoKeys[index] = key;
        this.memoTicks[index] = stored;
        if (isNew) {
            this.memoSize++;
        }
        this.memoRecords++;
        if (this.memoSize * LOAD_DENOMINATOR >= this.memoCapacity * LOAD_NUMERATOR) {
            growMemo();
        }
    }

    /**
     * Voids every recorded failure.
     *
     * <p>Called when a block changes. Coarse -- one block placed anywhere clears every mob's memo --
     * and correct, which is the trade this makes deliberately.
     */
    public void bumpWorldVersion() {
        this.worldVersion++;
        Arrays.fill(this.memoTicks, 0);
        this.memoSize = 0;
        this.memoVoids++;
    }

    public long worldVersion() {
        return this.worldVersion;
    }

    /**
     * Builds a memo key.
     *
     * <p>The world version is mixed in so a memo cannot outlive the world it describes, even if a
     * caller forgets to check. The position fields are narrower than a full block coordinate because
     * a path target is within a few hundred blocks of the mob; the entity id takes what is left.
     */
    public long memoKey(int entityId, int targetX, int targetY, int targetZ) {
        long key = entityId & 0xFFFF_FFFFL;
        key = key << 12 | (targetX & 0xFFF);
        key = key << 8 | (targetY & 0xFF);
        key = key << 12 | (targetZ & 0xFFF);
        key = key << 8 | (this.worldVersion & 0xFF);
        return key;
    }

    /** Packs a block position into an int, twelve bits of X and Z and eight of Y. */
    public static int packPosition(int x, int y, int z) {
        return ((x & 0xFFF) << 20) | ((y & 0xFF) << 12) | (z & 0xFFF);
    }

    private int walkProbe(int key) {
        int index = spread(key) & (this.walkCapacity - 1);
        while (this.walkStates[index] != ABSENT) {
            if (this.walkKeys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.walkCapacity - 1);
        }
        return index;
    }

    private int memoProbe(long key) {
        int index = spread((int) (key ^ (key >>> 32))) & (this.memoCapacity - 1);
        while (this.memoTicks[index] != 0) {
            if (this.memoKeys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.memoCapacity - 1);
        }
        return index;
    }

    private void memoBackshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.memoCapacity - 1);
            while (this.memoTicks[j] != 0) {
                int ideal = spread((int) (this.memoKeys[j] ^ (this.memoKeys[j] >>> 32)))
                        & (this.memoCapacity - 1);
                boolean movable = i <= j
                        ? ideal > i && ideal <= j
                        : ideal > i || ideal <= j;
                if (movable) {
                    j = (j + 1) & (this.memoCapacity - 1);
                } else {
                    break;
                }
            }
            if (this.memoTicks[j] == 0) {
                return;
            }
            this.memoKeys[i] = this.memoKeys[j];
            this.memoTicks[i] = this.memoTicks[j];
            this.memoTicks[j] = 0;
            i = j;
        }
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void growWalk() {
        int[] oldKeys = this.walkKeys;
        byte[] oldStates = this.walkStates;
        int oldCapacity = this.walkCapacity;
        this.walkCapacity = oldCapacity * 2;
        this.walkKeys = new int[this.walkCapacity];
        this.walkStates = new byte[this.walkCapacity];
        this.walkCount = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldStates[i] == ABSENT) {
                continue;
            }
            int index = walkProbe(oldKeys[i]);
            this.walkKeys[index] = oldKeys[i];
            this.walkStates[index] = oldStates[i];
            this.walkCount++;
        }
    }

    private void growMemo() {
        long[] oldKeys = this.memoKeys;
        int[] oldTicks = this.memoTicks;
        int oldCapacity = this.memoCapacity;
        this.memoCapacity = oldCapacity * 2;
        this.memoKeys = new long[this.memoCapacity];
        this.memoTicks = new int[this.memoCapacity];
        this.memoSize = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldTicks[i] == 0) {
                continue;
            }
            int index = memoProbe(oldKeys[i]);
            this.memoKeys[index] = oldKeys[i];
            this.memoTicks[index] = oldTicks[i];
            this.memoSize++;
        }
    }

    public long walkReads() {
        return this.walkReads;
    }

    /** Walkability reads answered from the current search's cache. */
    public long walkHits() {
        return this.walkHits;
    }

    public long memoHits() {
        return this.memoHits;
    }

    public long memoRecords() {
        return this.memoRecords;
    }

    public long memoVoids() {
        return this.memoVoids;
    }

    public double walkHitRatePercent() {
        return this.walkReads == 0 ? 0.0 : 100.0 * this.walkHits / this.walkReads;
    }
}
