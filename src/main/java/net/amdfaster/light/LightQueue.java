package net.amdfaster.light;

import java.util.Arrays;

/**
 * A queue of coordinates ordered by light level, with duplicates rejected.
 *
 * <p>Two properties that a plain FIFO does not have, and light propagation needs both.
 *
 * <p><b>Bucketing by level.</b> A flood fill must process bright cells before dim ones, because a
 * cell's final level is the maximum over its neighbours minus attenuation, and processing a dim cell
 * first means recomputing it when a brighter neighbour is reached later. Sixteen buckets make that
 * ordering free: push is an append to a bucket, and popping takes from the highest non-empty one. No
 * comparison, no heap, and the level comes back with the coordinate rather than being recomputed.
 *
 * <p><b>Deduplication.</b> Without it the queue grows far faster than the fill progresses. A cell is
 * reached from up to six neighbours and enqueued by each, and in a large open area the same cell can
 * be enqueued dozens of times before it is processed once. Starlight's writeup is explicit that
 * removing redundant re-registration is one of the main wins over the vanilla engine.
 *
 * <p>Coordinates are {@code long} values from {@code SectionCoord.key}, so a queue entry is one
 * primitive rather than an object. A flood fill over a section touches thousands of cells, and
 * allocating a position object per enqueue is how a light update ends up costing more in garbage
 * collection than in arithmetic.
 *
 * <p>Not thread safe.
 */
public final class LightQueue {

    private static final int INITIAL_BUCKET = 64;

    /** One growable array per light level, 0 to 15. */
    private final long[][] buckets;
    private final int[] sizes;

    /** Open-addressed membership table, with a parallel occupancy array. */
    private long[] table;
    private boolean[] occupied;
    private int tableMask;
    private int memberCount;

    private int count;
    private int rejectedDuplicates;
    private int highestNonEmpty = -1;

    public LightQueue() {
        this(1024);
    }

    /**
     * @param expectedMembers sizing hint for the membership table; it grows if exceeded
     */
    public LightQueue(int expectedMembers) {
        this.buckets = new long[LightField.MAX_LEVEL + 1][];
        this.sizes = new int[this.buckets.length];
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new long[INITIAL_BUCKET];
        }
        int capacity = 1;
        while (capacity < expectedMembers * 2) {
            capacity <<= 1;
        }
        this.table = new long[Math.max(capacity, 16)];
        this.occupied = new boolean[this.table.length];
        this.tableMask = this.table.length - 1;
    }

    /**
     * Enqueues a coordinate at a level, unless it is already queued.
     *
     * @return false when the coordinate was already in the queue. The caller can skip any work it
     *         was about to do on the assumption that this enqueue would cause a visit.
     */
    public boolean push(int level, long coordinate) {
        if (level < 0 || level > LightField.MAX_LEVEL) {
            throw new IllegalArgumentException("light level outside 0..15: " + level);
        }
        if (!addMember(coordinate)) {
            this.rejectedDuplicates++;
            return false;
        }
        if (this.memberCount * 2 >= this.table.length) {
            rehash(this.table.length * 2);
        }
        long[] bucket = this.buckets[level];
        if (this.sizes[level] == bucket.length) {
            this.buckets[level] = Arrays.copyOf(bucket, bucket.length * 2);
        }
        this.buckets[level][this.sizes[level]++] = coordinate;
        this.count++;
        if (level > this.highestNonEmpty) {
            this.highestNonEmpty = level;
        }
        return true;
    }

    /** True when there is anything left to process. */
    public boolean isEmpty() {
        return this.count == 0;
    }

    public int size() {
        return this.count;
    }

    /** The level {@link #poll()} will return, or -1 when the queue is empty. */
    public int peekLevel() {
        return this.highestNonEmpty;
    }

    /**
     * Removes and returns a coordinate from the highest non-empty bucket.
     *
     * <p>Call {@link #peekLevel()} first for its level; the two always agree, and returning them
     * separately avoids allocating a pair object per pop, which at thousands of pops per update is
     * the difference between a queue that costs nothing and one that shows up in a profile.
     */
    public long poll() {
        if (this.count == 0) {
            throw new IllegalStateException("poll on an empty queue");
        }
        int level = this.highestNonEmpty;
        long coordinate = this.buckets[level][--this.sizes[level]];
        this.count--;
        removeMember(coordinate);
        if (this.sizes[level] == 0) {
            // Walk down to the next non-empty bucket. Amortised over the whole fill this is at most
            // sixteen steps per level, not per pop.
            while (this.highestNonEmpty >= 0 && this.sizes[this.highestNonEmpty] == 0) {
                this.highestNonEmpty--;
            }
        }
        return coordinate;
    }

    /** Drops everything. Called between updates; the tables keep their capacity. */
    public void clear() {
        Arrays.fill(this.sizes, 0);
        Arrays.fill(this.occupied, false);
        this.memberCount = 0;
        this.count = 0;
        this.rejectedDuplicates = 0;
        this.highestNonEmpty = -1;
    }

    /** Enqueues that were dropped because the coordinate was already queued. */
    public int rejectedDuplicates() {
        return this.rejectedDuplicates;
    }

    /** Enqueues that were accepted. */
    public int acceptedCount() {
        return this.count;
    }

    private static int hash(long value) {
        long h = value * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) h;
    }

    /**
     * Inserts into the membership table.
     *
     * <p>Zero cannot be used as the empty-slot sentinel here, because
     * {@code SectionCoord.key(MIN, MIN, MIN)} is legitimately zero. A parallel occupancy array costs
     * a byte per slot and removes the whole class of bug where a real coordinate collides with the
     * sentinel -- the same bug that a light field's origin would otherwise hit.
     */
    private boolean addMember(long coordinate) {
        int slot = hash(coordinate) & this.tableMask;
        while (this.occupied[slot]) {
            if (this.table[slot] == coordinate) {
                return false;
            }
            slot = (slot + 1) & this.tableMask;
        }
        this.occupied[slot] = true;
        this.table[slot] = coordinate;
        this.memberCount++;
        return true;
    }

    private void removeMember(long coordinate) {
        int slot = hash(coordinate) & this.tableMask;
        while (this.occupied[slot]) {
            if (this.table[slot] == coordinate) {
                // Backshift the rest of the probe chain rather than leaving a tombstone. With linear
                // probing a tombstone has to be kept for lookups to terminate, and a flood fill that
                // enqueues and dequeues the same cells repeatedly would fill the table with them.
                this.occupied[slot] = false;
                this.memberCount--;
                int next = (slot + 1) & this.tableMask;
                while (this.occupied[next]) {
                    long moved = this.table[next];
                    this.occupied[next] = false;
                    this.memberCount--;
                    insertRaw(moved);
                    next = (next + 1) & this.tableMask;
                }
                return;
            }
            slot = (slot + 1) & this.tableMask;
        }
    }

    private void insertRaw(long coordinate) {
        int slot = hash(coordinate) & this.tableMask;
        while (this.occupied[slot]) {
            slot = (slot + 1) & this.tableMask;
        }
        this.occupied[slot] = true;
        this.table[slot] = coordinate;
        this.memberCount++;
    }

    private void rehash(int newCapacity) {
        long[] oldTable = this.table;
        boolean[] oldOccupied = this.occupied;
        this.table = new long[newCapacity];
        this.occupied = new boolean[newCapacity];
        this.tableMask = newCapacity - 1;
        this.memberCount = 0;
        for (int i = 0; i < oldTable.length; i++) {
            if (oldOccupied[i]) {
                insertRaw(oldTable[i]);
            }
        }
    }
}
