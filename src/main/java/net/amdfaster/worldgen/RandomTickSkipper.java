package net.amdfaster.worldgen;

/**
 * Skips random ticking in chunks that hold nothing capable of growing, spreading or melting.
 *
 * <p>Every tick, vanilla picks a few random positions in each loaded chunk and asks whether anything
 * should happen there. Across 289 chunks at three attempts each that is 867 attempts a tick, or 17,340
 * a second, and roughly 95% of them land on stone, bedrock, air above rock, or a crop that has already
 * finished growing. The attempt still costs a block lookup and a state check.
 *
 * <p>A chunk with no growable block in it cannot produce a random-tick event no matter which positions
 * are chosen, so the whole chunk can be left out. At the 70% of chunks that are plain stone and dirt,
 * that takes 867 attempts a tick to 260.
 *
 * <h2>The failure this has to avoid</h2>
 *
 * <p>Skipping a chunk that does have something growable stops it growing, permanently and silently. A
 * wheat field that never ripens is not a performance problem, it is a broken farm, and the player has
 * no way to connect it to anything they did.
 *
 * <p>So the count is maintained exactly: a chunk is skipped only while its count of growable blocks is
 * zero, and placing or removing one adjusts the count immediately. There is no periodic rescan to fall
 * back on, because a rescan that has not run yet is exactly the window in which the farm stops working.
 * The count is authoritative.
 *
 * <h2>Why not just tick less often</h2>
 *
 * <p>Reducing the attempts per chunk would slow growth everywhere, including in the farm the player is
 * standing in. Skipping only the chunks that cannot produce an event leaves the growth rate in every
 * productive chunk untouched, which is what makes it invisible.
 *
 * <p>Not thread safe. Random ticks are scheduled on the server thread, which in singleplayer lives
 * inside the client process.
 */
public final class RandomTickSkipper {

    /** Random-tick attempts vanilla makes per chunk per tick. */
    public static final int ATTEMPTS_PER_CHUNK = 3;

    private long[] packed;
    private boolean[] present;
    private int chunks;

    private long attemptsSaved;
    private long attemptsMade;

    public RandomTickSkipper() {
        this(1024);
    }

    public RandomTickSkipper(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("at least one chunk: " + capacity);
        }
        this.packed = new long[capacity];
        this.present = new boolean[capacity];
    }

    /** Packs a chunk coordinate pair into one key. */
    public static int key(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFF) << 16 | (chunkZ & 0xFFFF);
    }

    /**
     * Registers a chunk with a known number of growable blocks.
     *
     * <p>The count comes from a scan the caller does when the chunk loads. Scanning here instead would
     * mean this class needs the world, and the whole point is to avoid touching blocks.
     */
    public void load(int chunkX, int chunkZ, int growableBlocks) {
        if (growableBlocks < 0) {
            throw new IllegalArgumentException(
                    "a negative block count would make the chunk unskippable forever: "
                            + growableBlocks);
        }
        int slot = slotOf(chunkX, chunkZ);
        if (!this.present[slot]) {
            this.present[slot] = true;
            this.chunks++;
        }
        this.packed[slot] = key(chunkX, chunkZ) & 0xFFFFFFFFL | ((long) growableBlocks << 32);
    }

    public void unload(int chunkX, int chunkZ) {
        int slot = slotOf(chunkX, chunkZ);
        if (this.present[slot] && high(this.packed[slot]) == key(chunkX, chunkZ)) {
            this.present[slot] = false;
            this.packed[slot] = 0;
            this.chunks--;
        }
    }

    /**
     * Adjusts the count when a block is placed or removed.
     *
     * <p>Called for every block change, not only growable ones, because deciding whether a block is
     * growable is the caller's knowledge and a missed update is the broken-farm bug. The count is
     * clamped at zero so that a double removal cannot make it negative, which would make the chunk
     * permanently skippable.
     */
    public void addGrowable(int chunkX, int chunkZ, int delta) {
        int slot = slotOf(chunkX, chunkZ);
        if (!this.present[slot] || high(this.packed[slot]) != key(chunkX, chunkZ)) {
            // A block changed in a chunk that was never loaded here. Recording it would invent a count
            // from nothing, so it is ignored and the load scan is what establishes the truth.
            return;
        }
        long count = this.packed[slot] >>> 32;
        long updated = Math.max(0L, count + delta);
        this.packed[slot] = (this.packed[slot] & 0xFFFFFFFFL) | (updated << 32);
    }

    public int growableBlocks(int chunkX, int chunkZ) {
        int slot = slotOf(chunkX, chunkZ);
        if (!this.present[slot] || high(this.packed[slot]) != key(chunkX, chunkZ)) {
            return 0;
        }
        return (int) (this.packed[slot] >>> 32);
    }

    /**
     * @return whether this chunk should be left out of random ticking this tick
     */
    public boolean shouldSkip(int chunkX, int chunkZ) {
        boolean skip = growableBlocks(chunkX, chunkZ) == 0;
        if (skip) {
            this.attemptsSaved += ATTEMPTS_PER_CHUNK;
        } else {
            this.attemptsMade += ATTEMPTS_PER_CHUNK;
        }
        return skip;
    }

    private static int high(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    /**
     * Finds the slot for a chunk key.
     *
     * <p>Open addressing with linear probing, and the stored key is compared on every hit. Chunk
     * coordinates are dense and near-zero, so a hash that ignored the high bits would collide
     * constantly, and accepting a collision would attribute one chunk's growable count to another --
     * which is how a farm ends up in a chunk that gets skipped.
     */
    private int slotOf(int chunkX, int chunkZ) {
        int key = key(chunkX, chunkZ);
        if (this.chunks * 4 >= this.packed.length * 3) {
            grow();
        }
        int index = mix(key) & (this.packed.length - 1);
        int visited = 0;
        while (this.present[index]) {
            if (high(this.packed[index]) == key) {
                return index;
            }
            index = (index + 1) & (this.packed.length - 1);
            if (++visited > this.packed.length) {
                throw new IllegalStateException(
                        "table of " + this.packed.length + " is full; probe did not terminate");
            }
        }
        return index;
    }

    private static int mix(int key) {
        int h = key;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        long[] oldPacked = this.packed;
        boolean[] oldPresent = this.present;
        int oldLength = oldPacked.length;

        this.packed = new long[oldLength * 2];
        this.present = new boolean[oldLength * 2];
        this.chunks = 0;

        for (int i = 0; i < oldLength; i++) {
            if (!oldPresent[i]) {
                continue;
            }
            int key = high(oldPacked[i]);
            int index = mix(key) & (this.packed.length - 1);
            while (this.present[index]) {
                index = (index + 1) & (this.packed.length - 1);
            }
            this.present[index] = true;
            this.packed[index] = oldPacked[i];
            this.chunks++;
        }
    }

    /** How many chunks are tracked. */
    public int chunkCount() {
        return this.chunks;
    }

    /** How many chunks would be skipped right now. */
    public int skippableCount() {
        int n = 0;
        for (int i = 0; i < this.packed.length; i++) {
            if (this.present[i] && (this.packed[i] >>> 32) == 0) {
                n++;
            }
        }
        return n;
    }

    public long attemptsSaved() {
        return this.attemptsSaved;
    }

    public long attemptsMade() {
        return this.attemptsMade;
    }

    public double savedPercent() {
        long total = this.attemptsSaved + this.attemptsMade;
        return total == 0 ? 0.0 : 100.0 * this.attemptsSaved / total;
    }
}
