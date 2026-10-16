package net.amdfaster.mob;

/**
 * Remembers which chunk sections cannot host a spawn, so attempts are not spent on them.
 *
 * <p>Every tick the server picks random positions and asks whether a mob can spawn there. Seventy
 * attempts a tick, 1,400 a second, and about 98% of them fail -- the position is in daylight, or in the
 * air, or underwater, or the block below is not solid. The same sections fail every tick for the same
 * reason: a lit surface does not become dark, and open sky does not grow a floor.
 *
 * <p>Marking a section as unable to host a spawn lets the attempt be skipped entirely rather than
 * tested and rejected. At the 80% of sections that are lit surface or empty air, seventy attempts a tick
 * become fourteen.
 *
 * <h2>The failure this must not have</h2>
 *
 * <p>Skipping a section that can host a spawn means mobs stop appearing there. That is not a rendering
 * shortcut with a cosmetic downside; it is a world where the dark cave the player built no longer
 * produces anything, and the symptom is "mobs stopped spawning" with nothing to connect it to a renderer
 * mod.
 *
 * <p>So a section is only marked after a real attempt failed in it, and the mark is dropped the moment
 * any block in the section changes. Placing a torch, digging a hole, water flowing in -- all of those can
 * turn an unspawnable section into a spawnable one, and there is no periodic re-check to fall back on,
 * because the window before it runs is exactly when the cave stops working.
 *
 * <h2>Why not cache the position</h2>
 *
 * <p>A section is 16×16×16 blocks, and marking one covers 4,096 positions for the cost of one entry.
 * Caching individual positions would need thousands of entries to cover the same ground and would still
 * have to be invalidated one at a time. The section is the natural granularity because it is also the
 * unit a block change invalidates.
 *
 * <p>Not thread safe. Mob spawning runs on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class SpawnCandidateCache {

    /** Attempts the server makes per tick. */
    public static final int ATTEMPTS_PER_TICK = 70;

    /** Blocks per axis of a section. */
    public static final int SECTION_SIZE = 16;

    private long[] sections;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long attemptsSkipped;
    private long attemptsMade;
    private long invalidations;

    public SpawnCandidateCache() {
        this(4096);
    }

    public SpawnCandidateCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.sections = new long[capacity];
        this.occupied = new boolean[capacity];
    }

    /** Packs a section coordinate triple into a key. */
    public static long key(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x3FFFFFF) << 38)
                | ((long) (sectionY & 0xFFF) << 26)
                | ((long) (sectionZ & 0x3FFFFFF));
    }

    /** The section a block position falls in. */
    public static int sectionOf(int coordinate) {
        // floorDiv, not truncation, so negative coordinates group with their neighbours rather than
        // being pulled toward zero.
        return Math.floorDiv(coordinate, SECTION_SIZE);
    }

    /**
     * Whether an attempt in this section is worth making.
     *
     * @return false if the section is marked as unable to host a spawn
     */
    public boolean shouldAttempt(int x, int y, int z) {
        int index = find(key(sectionOf(x), sectionOf(y), sectionOf(z)));
        if (index >= 0) {
            this.attemptsSkipped++;
            return false;
        }
        this.attemptsMade++;
        return true;
    }

    /**
     * Marks a section as unable to host a spawn, after an attempt in it failed.
     *
     * <p>Only ever called on a real failure. Marking a section that was never tested would be a guess,
     * and a wrong guess is the mobs-stop-spawning bug.
     */
    public void markUnspawnable(int x, int y, int z) {
        long key = key(sectionOf(x), sectionOf(y), sectionOf(z));
        if (this.size * 4 >= this.capacity * 3) {
            grow();
        }
        int index = probe(key);
        if (!this.occupied[index]) {
            this.occupied[index] = true;
            this.sections[index] = key;
            this.size++;
        }
    }

    /**
     * Drops the mark for a section, because a block in it changed.
     *
     * <p>There is no periodic re-check to fall back on, because the window before it would run is exactly
     * when the cave stops producing mobs. The invalidation is the only mechanism.
     */
    public void invalidate(int x, int y, int z) {
        int index = find(key(sectionOf(x), sectionOf(y), sectionOf(z)));
        if (index >= 0) {
            this.occupied[index] = false;
            this.size--;
            this.invalidations++;
        }
    }

    /** Whether a section is marked, for testing that invalidation really cleared it. */
    public boolean isMarked(int x, int y, int z) {
        return find(key(sectionOf(x), sectionOf(y), sectionOf(z))) >= 0;
    }

    private int find(long key) {
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.sections[index] == key) {
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
            if (this.sections[index] == key) {
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
        long[] oldSections = this.sections;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.sections = new long[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldSections[i]);
            this.occupied[index] = true;
            this.sections[index] = oldSections[i];
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

    public long attemptsSkipped() {
        return this.attemptsSkipped;
    }

    public long attemptsMade() {
        return this.attemptsMade;
    }

    public long invalidations() {
        return this.invalidations;
    }

    public double skippedPercent() {
        long total = this.attemptsSkipped + this.attemptsMade;
        return total == 0 ? 0.0 : 100.0 * this.attemptsSkipped / total;
    }
}
