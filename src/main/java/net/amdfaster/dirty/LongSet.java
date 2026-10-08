package net.amdfaster.dirty;

import java.util.Arrays;

/**
 * An open-addressed set of packed section coordinates.
 *
 * <p>Deliberately minimal and deliberately scannable: the rebuild scheduler has to walk everything
 * pending once per frame to put it in priority order, which a {@code HashSet<Long>} would also do
 * but only after boxing every coordinate on the way in and allocating a node for it. A TNT storm
 * marks thousands of sections in a tick, and this is on the render thread.
 */
final class LongSet {

    /** Marker for an empty slot. {@link SectionCoord#key} never produces it. */
    static final long EMPTY = Long.MIN_VALUE;

    private static final int LOAD_PERCENT = 70;

    private long[] keys;
    private int size;
    private int threshold;

    LongSet(int capacity) {
        this.keys = new long[capacity];
        Arrays.fill(this.keys, EMPTY);
        this.threshold = thresholdFor(capacity);
    }

    private static int thresholdFor(int capacity) {
        return Math.max(1, capacity * LOAD_PERCENT / 100);
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return (int) h;
    }

    private int slot(long key) {
        int mask = this.keys.length - 1;
        int i = hash(key) & mask;
        while (true) {
            long existing = this.keys[i];
            if (existing == EMPTY || existing == key) {
                return i;
            }
            i = (i + 1) & mask;
        }
    }

    /** Adds a key. Returns false if it was already present. */
    boolean add(long key) {
        if (this.size >= this.threshold) {
            grow();
        }
        int i = slot(key);
        if (this.keys[i] != EMPTY) {
            return false;
        }
        this.keys[i] = key;
        this.size++;
        return true;
    }

    boolean contains(long key) {
        return this.keys[slot(key)] != EMPTY;
    }

    /** Removes a key. Returns false if it was not present. */
    boolean remove(long key) {
        int mask = this.keys.length - 1;
        int i = slot(key);
        if (this.keys[i] == EMPTY) {
            return false;
        }
        // Linear probing means a hole left behind would terminate later probes early, so every
        // entry after the hole in its probe run has to be reinserted. Skipping this is the classic
        // open-addressing bug: entries become unreachable while still occupying slots.
        this.keys[i] = EMPTY;
        this.size--;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            long candidate = this.keys[j];
            if (candidate == EMPTY) {
                break;
            }
            int home = hash(candidate) & mask;
            // The entry at j belongs at home. It is displaced if home is not in the cyclic interval
            // (i, j] -- that is, if removing the hole at i would put it before its own probe start.
            boolean displaced = (i <= j) ? (home <= i || home > j) : (home <= i && home > j);
            if (displaced) {
                this.keys[i] = candidate;
                this.keys[j] = EMPTY;
                i = j;
            }
        }
        return true;
    }

    private void grow() {
        long[] old = this.keys;
        int next = old.length << 1;
        this.keys = new long[next];
        Arrays.fill(this.keys, EMPTY);
        this.threshold = thresholdFor(next);
        this.size = 0;
        int mask = next - 1;
        for (long key : old) {
            if (key == EMPTY) {
                continue;
            }
            int j = hash(key) & mask;
            while (this.keys[j] != EMPTY) {
                j = (j + 1) & mask;
            }
            this.keys[j] = key;
            this.size++;
        }
    }

    void clear() {
        Arrays.fill(this.keys, EMPTY);
        this.size = 0;
    }

    int size() {
        return this.size;
    }

    /** The backing table, for scanning. Entries equal to {@link #EMPTY} are absent. */
    long[] table() {
        return this.keys;
    }
}
