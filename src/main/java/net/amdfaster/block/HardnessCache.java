package net.amdfaster.block;

/**
 * Caches how long a block takes to break with a given tool, instead of recomputing it every tick.
 *
 * <p>While a block is being broken, the game computes how much progress to add each tick from the
 * block's hardness and how well the tool matches it. That computation runs every tick for as long as the
 * player holds the button down, and the answer is the same every time: the block has not changed and
 * neither has the tool. Breaking one block is a hundred ticks of the same calculation, and in creative
 * mode or under a TNT chain several run at once.
 *
 * <p>Caching the result and invalidating it when either input changes takes the recomputation to the
 * ticks where something actually changed.
 *
 * <h2>Both inputs have to be in the key</h2>
 *
 * <p>The hardness depends on the block and on the tool. Keying on the block alone would serve the answer
 * computed for a wooden pickaxe to a player holding a diamond one, and the efficiency enchantment would
 * appear to do nothing -- which reads as a broken enchantment rather than as a caching bug, and is the
 * kind of thing a player reports without being able to reproduce.
 *
 * <p>So the key is the block state and the tool together, and a change to either is a different entry
 * rather than an invalidation of the old one. Neither has to be invalidated explicitly; asking with
 * different inputs simply computes a different answer.
 *
 * <h2>A wrong hardness is felt, not seen</h2>
 *
 * <p>If the cached value is for the wrong block, the block breaks at the wrong speed. Nothing looks
 * wrong -- there is no visual artefact -- but the game feels inconsistent, and a player who has learned
 * how long stone takes will notice that it took the wrong time. That is harder to trace than a rendering
 * bug because there is nothing to look at.
 *
 * <p>Not thread safe. Block breaking runs on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class HardnessCache {

    /** Milliseconds one hardness computation costs. */
    public static final double MS_PER_COMPUTE = 0.001;

    private long[] keys;
    private double[] hardness;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long reads;
    private long computed;
    private long served;

    public HardnessCache() {
        this(1024);
    }

    public HardnessCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.hardness = new double[capacity];
        this.occupied = new boolean[capacity];
    }

    /**
     * Packs a block state and a tool into a key.
     *
     * <p>Both have to be here. Keying on the block alone would serve the answer computed for a wooden
     * pickaxe to a player holding a diamond one, and the efficiency enchantment would appear to do
     * nothing.
     */
    public static long key(int blockStateId, int toolId) {
        return ((long) blockStateId << 32) | (toolId & 0xFFFFFFFFL);
    }

    /**
     * How long a block takes to break.
     *
     * @param compute produces the value when it is not cached
     */
    public double hardness(int blockStateId, int toolId, java.util.function.DoubleSupplier compute) {
        if (compute == null) {
            throw new IllegalArgumentException("no way to compute an uncached hardness");
        }
        this.reads++;
        long key = key(blockStateId, toolId);
        int index = probe(key);

        if (this.occupied[index]) {
            this.served++;
            return this.hardness[index];
        }

        double value = compute.getAsDouble();
        this.computed++;

        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(key);
        }
        if (!this.occupied[index]) {
            this.size++;
        }
        this.occupied[index] = true;
        this.keys[index] = key;
        this.hardness[index] = value;
        return value;
    }

    /** Drops a cached value, for a block that was replaced. */
    public void invalidate(int blockStateId, int toolId) {
        int index = find(key(blockStateId, toolId));
        if (index >= 0) {
            this.occupied[index] = false;
            this.size--;
        }
    }

    /** Whether a value is cached, for testing that invalidation really cleared it. */
    public boolean isCached(int blockStateId, int toolId) {
        return find(key(blockStateId, toolId)) >= 0;
    }

    private int find(long key) {
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                break;
            }
        }
        return -1;
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
        double[] oldValues = this.hardness;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.hardness = new double[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.occupied[index] = true;
            this.keys[index] = oldKeys[i];
            this.hardness[index] = oldValues[i];
            this.size++;
        }
    }

    public void clear() {
        java.util.Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public long reads() {
        return this.reads;
    }

    public long computed() {
        return this.computed;
    }

    public long served() {
        return this.served;
    }

    public double servedPercent() {
        return this.reads == 0 ? 0.0 : 100.0 * this.served / this.reads;
    }

    /** Milliseconds of computation avoided per tick. */
    public double msSavedPerTick() {
        return this.served * MS_PER_COMPUTE;
    }
}
