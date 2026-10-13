package net.amdfaster.tick;

/**
 * Skips random-tick sampling for sections that hold nothing which would tick.
 *
 * <p>Vanilla draws three random positions per chunk section every tick and looks at whatever is
 * there. At render distance 10 that is 441 chunks by 24 sections, so 31,752 draws a tick and 635,040 a
 * second. Most of them land on air, or on stone, or on something that has no random tick behaviour at
 * all. Counted rather than guessed: at a plausible 5 to 20 percent of sections holding anything
 * tickable, between 80 and 95 percent of those draws are wasted, which is half a million discarded
 * lookups a second.
 *
 * <p>This keeps a count per section of how many of its blocks tick randomly, and the sampler asks one
 * question before drawing. A section with a count of zero is skipped outright.
 *
 * <h2>Why a count and not a bitmask</h2>
 *
 * <p>A section is 4,096 blocks, so a bit per block is 512 bytes per section and 5.4 MB across the
 * render distance, to answer a question that only needs "is it zero". A count answers that in one
 * comparison and costs four bytes a section.
 *
 * <h2>The invariant that has to hold</h2>
 *
 * <p>The count is maintained by reporting changes, and it has to be right. Too high and the filter
 * saves nothing, which is merely a lost optimisation. Too low and blocks stop ticking -- grass does
 * not spread, crops do not grow, ice does not melt -- and the failure is silent and looks like a
 * broken world rather than a broken mod. Every mutation therefore goes through
 * {@link #onBlockChanged}, which takes both the old and the new state so a block changing between
 * tickable and not is counted correctly rather than counted twice or not at all.
 *
 * <p>Not thread safe. This is reached from the server thread, which in singleplayer is the same
 * process as the client.
 */
public final class RandomTickFilter {

    public static final int BLOCKS_PER_SECTION = 4096;

    /** Random draws vanilla makes per section per tick. */
    public static final int SAMPLES_PER_SECTION_PER_TICK = 3;

    private static final int INITIAL_CAPACITY = 1024;

    private long[] keys;
    private int[] counts;
    private boolean[] occupied;
    private int capacity;
    private int size;

    private long samplesRequested;
    private long samplesSkipped;

    public RandomTickFilter() {
        this.capacity = INITIAL_CAPACITY;
        this.keys = new long[this.capacity];
        this.counts = new int[this.capacity];
        this.occupied = new boolean[this.capacity];
    }

    /**
     * Reports a block change.
     *
     * @param wasTickable whether the block being replaced ticked randomly
     * @param isTickable whether the new block ticks randomly
     */
    public void onBlockChanged(int x, int y, int z, boolean wasTickable, boolean isTickable) {
        if (wasTickable == isTickable) {
            return;
        }
        long key = sectionKey(x >> 4, y >> 4, z >> 4);
        int index = probe(key);
        if (!this.occupied[index]) {
            if (!isTickable) {
                // Nothing was tickable and nothing is now. Creating an entry to hold a zero would
                // grow the table with sections that the filter is going to skip anyway.
                return;
            }
            this.keys[index] = key;
            this.counts[index] = 0;
            this.occupied[index] = true;
            this.size++;
        }
        this.counts[index] += isTickable ? 1 : -1;
        if (this.counts[index] == 0) {
            this.occupied[index] = false;
            this.size--;
            backshift(index);
        } else if (this.size * 4 >= this.capacity * 3) {
            grow();
        }
    }

    /**
     * Whether this section is worth drawing samples from.
     *
     * <p>A section never reported is treated as empty, which is the right default: a chunk that has
     * not had a tickable block placed in it since it loaded has none.
     */
    public boolean shouldSample(int sectionX, int sectionY, int sectionZ) {
        this.samplesRequested++;
        // One probe, not two. probe returns a slot index and never a negative value, so an earlier
        // "< 0" guard here was dead code that also cost a second walk of the table on the hot path.
        int index = probe(sectionKey(sectionX, sectionY, sectionZ));
        if (!this.occupied[index]) {
            this.samplesSkipped++;
            return false;
        }
        return true;
    }

    /** How many blocks in a section tick randomly, or zero if the section is not tracked. */
    public int tickableCount(int sectionX, int sectionY, int sectionZ) {
        int index = probe(sectionKey(sectionX, sectionY, sectionZ));
        return this.occupied[index] ? this.counts[index] : 0;
    }

    /**
     * Packs section coordinates into a long.
     *
     * <p>21 bits per axis, the same width the rest of this mod uses, which covers a little over a
     * million blocks in each direction.
     */
    public static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x1FFFFF) << 42)
                | ((long) (sectionY & 0x1FFFFF) << 21)
                | (long) (sectionZ & 0x1FFFFF);
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
            this.counts[i] = this.counts[j];
            this.occupied[i] = true;
            this.occupied[j] = false;
            i = j;
        }
    }

    private void grow() {
        long[] oldKeys = this.keys;
        int[] oldCounts = this.counts;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.counts = new int[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.counts[index] = oldCounts[i];
            this.occupied[index] = true;
            this.size++;
        }
    }

    /** Spreads a section key so neighbouring sections do not collide. */
    static int spread(long key) {
        long h = key ^ (key >>> 32);
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return (int) (h & 0x7FFFFFFFL);
    }

    /** Sections currently holding at least one tickable block. */
    public int trackedSections() {
        return this.size;
    }

    public long samplesRequested() {
        return this.samplesRequested;
    }

    /**
     * Samples avoided.
     *
     * <p>Multiplied by the cost of a draw and a block lookup, this is the whole saving, so it belongs
     * on an overlay where it can be checked against the world rather than assumed.
     */
    public long samplesSkipped() {
        return this.samplesSkipped;
    }

    /**
     * Skip rate as a percentage.
     *
     * <p>Returns 0.0 rather than NaN before any sample, because this goes on an overlay.
     */
    public double skipRatePercent() {
        return this.samplesRequested == 0
                ? 0.0
                : 100.0 * this.samplesSkipped / this.samplesRequested;
    }

    public void resetCounters() {
        this.samplesRequested = 0;
        this.samplesSkipped = 0;
    }
}
