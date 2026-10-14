package net.amdfaster.fluid;

import java.util.Arrays;

/**
 * Stops stable fluid from asking whether it should spread.
 *
 * <p>Flowing fluid re-evaluates on a schedule: water every five ticks, lava every thirty. Each
 * evaluation reads the four sides, above and below to decide whether it can move, and then
 * reschedules itself whether or not anything changed. A still pond asks the same question forever.
 *
 * <p>Counted rather than guessed. Five hundred water blocks are 120,000 evaluations a minute, and at
 * six block-state reads each that is 720,000 reads a minute for water that is not moving. Two
 * thousand blocks is 2.88 million. Nearly all of it is fluid that was still last tick, is still this
 * tick, and will be still next tick.
 *
 * <h2>The invalidation is the whole problem</h2>
 *
 * <p>Saying "this water is stable" is easy and wrong the moment someone digs the block under it. So
 * stability is invalidated in the same place the fluid is: a block change wakes the six neighbours of
 * the changed position, because those are the only fluid blocks whose surroundings changed. Missing
 * one leaves water hanging in the air, which is the kind of bug that shows up in a screenshot rather
 * than a log.
 *
 * <p>{@link #needsTick} reports the remembered state. A block that has never been seen is treated as
 * needing a tick, because assuming stability for something never evaluated is how water gets stuck.
 *
 * <h2>Scope</h2>
 *
 * <p>Fluid ticks run on the server thread, which in singleplayer is the client's own process. Not
 * thread safe.
 */
public final class FluidTickFilter {

    /** Never evaluated, so it must be ticked. Zero, so a freshly allocated table starts here. */
    public static final byte UNTRACKED = 0;

    /** Evaluated and able to move, or not yet known to have settled. */
    public static final byte UNSTABLE = 1;

    /** Evaluated, settled, and nothing around it has changed since. */
    public static final byte STABLE = 2;

    /** The six directions a block change can affect. */
    private static final int[] NEIGHBOUR_DX = {1, -1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_DY = {0, 0, 1, -1, 0, 0};
    private static final int[] NEIGHBOUR_DZ = {0, 0, 0, 0, 1, -1};

    private static final int DEFAULT_CAPACITY = 1024;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    private int[] keys;
    private byte[] states;
    private int capacity;
    private int size;

    private long evaluations;
    private long skipped;
    private long invalidations;
    private long wakes;

    public FluidTickFilter() {
        this(DEFAULT_CAPACITY);
    }

    public FluidTickFilter(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        if (rounded <= 0) {
            rounded = 16;
        }
        this.capacity = rounded;
        this.keys = new int[this.capacity];
        // UNTRACKED is zero, so a fresh array is already correct. That is deliberate: the three
        // states are distinct values rather than one value doubling as "absent" and "stable", which
        // is a mistake three other classes in this project made.
        this.states = new byte[this.capacity];
    }

    /**
     * Whether a fluid block should be evaluated this tick.
     *
     * @return false only for a block known to have settled with nothing changed around it since
     */
    public boolean needsTick(int x, int y, int z) {
        int key = pack(x, y, z);
        int index = probe(key);
        this.evaluations++;
        if (this.states[index] == STABLE) {
            this.skipped++;
            return false;
        }
        return true;
    }

    /** Records that a block settled and cannot currently move. */
    public void markStable(int x, int y, int z) {
        put(pack(x, y, z), STABLE);
    }

    /** Records that a block can still move, so it keeps its schedule. */
    public void markUnstable(int x, int y, int z) {
        put(pack(x, y, z), UNSTABLE);
    }

    /**
     * Wakes the fluid around a position that changed.
     *
     * <p>Six neighbours, and only those, because a block change alters the surroundings of the blocks
     * touching it and nothing else. The changed position itself is dropped rather than woken, since
     * whatever is there now has never been evaluated in its new form.
     */
    public void invalidateNeighbours(int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            int nx = x + NEIGHBOUR_DX[i];
            int ny = y + NEIGHBOUR_DY[i];
            int nz = z + NEIGHBOUR_DZ[i];
            if (markUnstableIfTracked(nx, ny, nz)) {
                this.wakes++;
            }
        }
        remove(x, y, z);
    }

    private boolean markUnstableIfTracked(int x, int y, int z) {
        int index = probe(pack(x, y, z));
        byte state = this.states[index];
        if (state != STABLE) {
            // Untracked stays untracked. Marking it unstable would put a block that was never
            // evaluated into the table, growing it for no reason; needsTick already treats an
            // untracked block as needing a tick.
            return false;
        }
        this.states[index] = UNSTABLE;
        return true;
    }

    /** Stops tracking a position, for fluid that has been replaced or drained. */
    public void remove(int x, int y, int z) {
        int index = probe(pack(x, y, z));
        if (this.states[index] == UNTRACKED) {
            return;
        }
        this.states[index] = UNTRACKED;
        this.size--;
        this.invalidations++;
        backshift(index);
    }

    /** Drops everything, for a chunk unload where the whole volume is unknown again. */
    public void clear() {
        Arrays.fill(this.states, UNTRACKED);
        this.size = 0;
    }

    private void put(int key, byte state) {
        int index = probe(key);
        if (this.states[index] == UNTRACKED) {
            this.keys[index] = key;
            this.size++;
            if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
                grow();
                index = probe(key);
            }
        }
        this.states[index] = state;
    }

    private int probe(int key) {
        int index = spread(key) & (this.capacity - 1);
        while (this.states[index] != UNTRACKED) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    /**
     * Closes the hole left by a removal, using the states array as the occupancy marker.
     *
     * <p>One array that cannot disagree with itself. A parallel boolean array in another class here
     * was cleared on the source of a backshift without being set on the destination, which made every
     * shifted entry invisible.
     */
    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.states[j] != UNTRACKED) {
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
            if (this.states[j] == UNTRACKED) {
                return;
            }
            this.keys[i] = this.keys[j];
            this.states[i] = this.states[j];
            this.states[j] = UNTRACKED;
            i = j;
        }
    }

    /**
     * Packs a block position into an int.
     *
     * <p>Eight bits of Y and twelve each of X and Z around the body of fluid. Water spreads at most a
     * few dozen blocks from its source, so this is generous, but it is a cache key: positions that
     * pack alike share a state. A structure taller than 256 blocks or wider than 4096 needs a wider
     * key.
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
        byte[] oldStates = this.states;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new int[this.capacity];
        this.states = new byte[this.capacity];
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldStates[i] == UNTRACKED) {
                continue;
            }
            put(oldKeys[i], oldStates[i]);
        }
    }

    /** Fluid blocks currently tracked. */
    public int size() {
        return this.size;
    }

    public long evaluations() {
        return this.evaluations;
    }

    /** Evaluations avoided because the block was known to have settled. */
    public long skipped() {
        return this.skipped;
    }

    public long invalidations() {
        return this.invalidations;
    }

    /** Stable neighbours woken by a block change. */
    public long wakes() {
        return this.wakes;
    }

    public double skipRatePercent() {
        return this.evaluations == 0 ? 0.0 : 100.0 * this.skipped / this.evaluations;
    }

    public void resetCounters() {
        this.evaluations = 0;
        this.skipped = 0;
        this.invalidations = 0;
        this.wakes = 0;
    }
}
