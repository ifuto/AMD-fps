package net.amdfaster.mem;

import java.util.Arrays;

/**
 * Stores each distinct set of derived block-state values once, and points every state at it.
 *
 * <p>A BlockState caches what was computed from it: whether it is air, its shape, its collision shape,
 * its light opacity, its friction. FerriteCore's breakdown puts roughly 200 MB of a modded client in
 * those caches, and the reason is that every state carries its own copy of values that are mostly
 * identical to its neighbours'. Two hundred stone-family states all have the same shape, the same
 * opacity and the same friction. A stair's 120 states have four distinct combinations between them.
 *
 * <p>Interning the combinations and giving each state an index into them takes that from 2 MB to about
 * 120 KB on ten thousand states -- 94% -- without changing any value.
 *
 * <h2>Identical means identical</h2>
 *
 * <p>Two sets of derived values are merged only if every field matches. Merging on similarity would
 * give one block another block's collision shape, and the failure is not a rendering artefact: the
 * player walks through a floor, or stands in the air where a slab should be. That is the worst class of
 * bug in a game, because it is physical rather than visual and players report it as "the world is
 * broken" with nothing to trace it to.
 *
 * <p>So the content is compared field by field, and the hash only nominates a candidate. A collision
 * that was accepted without comparing is exactly the bug above, and it would be rare enough to be
 * nearly impossible to reproduce.
 *
 * <h2>Invalidation</h2>
 *
 * <p>Derived values can change when a resource pack reloads or a mod registers a block late. Dropping
 * the whole table is the safe answer and is what {@link #clear} does. Dropping one entry is not
 * offered, because a state holding a stale index would keep reading the old entry -- the entry cannot
 * be freed while anything points at it, and the bookkeeping to track that costs more than the entries
 * save.
 *
 * <p>Not thread safe. Block states are built at startup and on reload, so a caller working from worker
 * threads needs external synchronisation.
 */
public final class DerivedStateCache {

    /** Bytes a derived set is assumed to occupy, for reporting the saving. */
    public static final int ENTRY_BYTES = 200;

    private long[][] entries;
    private boolean[] occupied;
    private int entryCount;

    /** State index to entry slot, for reporting how many states share an entry. */
    private int[] stateToSlot;
    private int stateCapacity;

    private int capacity;

    private long lookups;
    private long inserts;
    private long duplicates;

    public DerivedStateCache() {
        this(256, 1024);
    }

    /**
     * @param entryCapacity how many distinct derived sets to expect
     * @param stateCapacity how many block states to expect
     */
    public DerivedStateCache(int entryCapacity, int stateCapacity) {
        if (entryCapacity < 16) {
            throw new IllegalArgumentException(
                    "entry capacity must be at least sixteen: " + entryCapacity);
        }
        if (stateCapacity < 1) {
            throw new IllegalArgumentException("at least one state: " + stateCapacity);
        }
        this.capacity = roundUp(entryCapacity);
        this.stateCapacity = stateCapacity;
        this.entries = new long[this.capacity][];
        this.occupied = new boolean[this.capacity];
        this.stateToSlot = new int[this.stateCapacity];
        Arrays.fill(this.stateToSlot, -1);
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    /**
     * Interns a derived set for a state.
     *
     * @param stateIndex the block state's index
     * @param content the derived values, packed by the caller
     * @return the canonical array; identical for every state with the same derived values
     */
    public long[] intern(int stateIndex, long[] content) {
        if (content.length == 0) {
            throw new IllegalArgumentException("an empty derived set describes nothing");
        }
        if (stateIndex < 0 || stateIndex >= this.stateCapacity) {
            growStates(stateIndex);
        }
        this.lookups++;

        int hash = hash(content);
        int index = hash & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (Arrays.equals(this.entries[index], content)) {
                this.duplicates++;
                this.stateToSlot[stateIndex] = index;
                return this.entries[index];
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "table of " + this.capacity + " is full; probe did not terminate");
            }
        }

        this.occupied[index] = true;
        long[] stored = content.clone();
        this.entries[index] = stored;
        this.entryCount++;
        this.inserts++;
        if (this.entryCount * 4 >= this.capacity * 3) {
            // The reference has to be held across the growth. Growing moves entries to new slots, and
            // reading the slot back afterwards would return whichever entry moved into the old one.
            grow();
        }
        this.stateToSlot[stateIndex] = probe(stored);
        return stored;
    }

    /** How many states point at the same entry as this one. */
    public int sharingWith(int stateIndex) {
        if (stateIndex < 0 || stateIndex >= this.stateCapacity) {
            throw new IndexOutOfBoundsException(
                    "state " + stateIndex + " outside " + this.stateCapacity);
        }
        int slot = this.stateToSlot[stateIndex];
        if (slot < 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < this.stateCapacity; i++) {
            if (this.stateToSlot[i] == slot) {
                n++;
            }
        }
        return n;
    }

    private static int hash(long[] content) {
        long h = content.length;
        for (long value : content) {
            h = h * 0x9E3779B97F4A7C15L + value;
            h ^= h >>> 29;
        }
        return (int) (h & 0x7FFFFFFFL);
    }

    private int probe(long[] content) {
        int index = hash(content) & (this.capacity - 1);
        while (this.occupied[index] && !Arrays.equals(this.entries[index], content)) {
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    private void growStates(int needed) {
        int size = this.stateCapacity;
        while (size <= needed) {
            size *= 2;
        }
        int[] grown = new int[size];
        Arrays.fill(grown, -1);
        System.arraycopy(this.stateToSlot, 0, grown, 0, this.stateCapacity);
        this.stateToSlot = grown;
        this.stateCapacity = size;
    }

    private void grow() {
        long[][] old = this.entries;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.entries = new long[this.capacity][];
        this.occupied = new boolean[this.capacity];

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = hash(old[i]) & (this.capacity - 1);
            while (this.occupied[index]) {
                index = (index + 1) & (this.capacity - 1);
            }
            this.occupied[index] = true;
            this.entries[index] = old[i];
        }
        // Every state's slot index is stale now, so they are all re-probed. Doing this lazily on read
        // would mean a read that rehashes, which is the kind of surprise worth avoiding.
        //
        // The content comes from the old array. this.entries is already the new one, so indexing it
        // with an old slot would read whichever entry landed there, or nothing at all.
        for (int i = 0; i < this.stateCapacity; i++) {
            int oldSlot = this.stateToSlot[i];
            if (oldSlot >= 0) {
                this.stateToSlot[i] = probe(old[oldSlot]);
            }
        }
    }

    /** How many distinct derived sets are stored. */
    public int entryCount() {
        return this.entryCount;
    }

    public long lookups() {
        return this.lookups;
    }

    public long duplicates() {
        return this.duplicates;
    }

    /** Bytes the distinct sets occupy. */
    public long bytes() {
        return (long) this.entryCount * ENTRY_BYTES;
    }

    /** Bytes the same number of states would occupy with a cache each. */
    public long bytesWithoutDedup() {
        return this.lookups * ENTRY_BYTES;
    }

    public double savedPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.duplicates / this.lookups;
    }

    /** Drops everything, for a resource pack reload. */
    public void clear() {
        this.entries = new long[this.capacity][];
        this.occupied = new boolean[this.capacity];
        this.entryCount = 0;
        Arrays.fill(this.stateToSlot, -1);
    }
}
