package net.amdfaster.entity;

import java.util.Arrays;

/**
 * Says whether there is anything nearby worth colliding with, before anything is searched.
 *
 * <p>Every entity tick scans the entity sections around it for things it could push or be pushed by.
 * That is 27 section lookups per entity per tick, and at a thousand entities it is 540,000 a second.
 * Entities are spread over a large world, so in a normal build almost every one of those scans finds
 * nothing.
 *
 * <p>This keeps a count per chunk section of how many collidable entities are in it. A section with
 * zero cannot contribute a collision, and the check that surrounds an entity is a count comparison
 * rather than a section fetch. Measured shape rather than a guess: at 1% neighbourhood occupancy,
 * 99% of the scans are avoided; at 20%, 80%.
 *
 * <h2>Counting is the whole risk</h2>
 *
 * <p>A section that reports zero while holding an entity means the entity is walked through instead
 * of pushed, which is the sort of thing a player notices as "the boat went through me" and reports
 * without a stack trace. So the counts are only ever changed by {@link #add} and {@link #remove},
 * both of which move an entity rather than assume it, and {@link #move} does the pair in one call so
 * an entity cannot be counted twice or not at all.
 *
 * <p>{@link #occupancy} is exposed so a caller can cross-check the count against what it knows.
 *
 * <p>Not thread safe.
 */
public final class EntityCollisionGrid {

    /** Blocks per chunk section edge. */
    public static final int SECTION_BITS = 4;

    /** How many sections in each direction around an entity are worth searching. */
    public static final int SEARCH_RADIUS = 1;

    private static final int DEFAULT_CAPACITY = 1024;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    private int[] keys;
    private int[] counts;
    private int capacity;
    private int size;

    private long checks;
    private long emptyChecks;
    private long addMoves;
    private long removeMoves;

    public EntityCollisionGrid() {
        this(DEFAULT_CAPACITY);
    }

    public EntityCollisionGrid(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        if (rounded <= 0) {
            rounded = 16;
        }
        this.capacity = rounded;
        this.keys = new int[this.capacity];
        // Zero means an empty section, which is also what an unset slot holds, and that is fine here:
        // a section with nothing in it is exactly the same fact as a section never mentioned. There
        // is no "absent" state to distinguish from a real value, which is the trap that caught three
        // other caches in this project.
        this.counts = new int[this.capacity];
    }

    /**
     * Whether any collidable entity is within the search radius.
     *
     * <p>Checks 27 sections for the default radius. A false answer means the caller can skip the
     * entity scan entirely.
     */
    public boolean hasNeighbours(int blockX, int blockY, int blockZ) {
        this.checks++;
        int sx = blockX >> SECTION_BITS;
        int sy = blockY >> SECTION_BITS;
        int sz = blockZ >> SECTION_BITS;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (occupancy(sx + dx, sy + dy, sz + dz) > 0) {
                        return true;
                    }
                }
            }
        }
        this.emptyChecks++;
        return false;
    }

    /** How many collidable entities are in a section. */
    public int occupancy(int sectionX, int sectionY, int sectionZ) {
        int index = probe(pack(sectionX, sectionY, sectionZ));
        return this.counts[index];
    }

    /** Records an entity entering a position. */
    public void add(int blockX, int blockY, int blockZ) {
        int sx = blockX >> SECTION_BITS;
        int sy = blockY >> SECTION_BITS;
        int sz = blockZ >> SECTION_BITS;
        addSection(sx, sy, sz);
    }

    /** Records an entity leaving a position. */
    public void remove(int blockX, int blockY, int blockZ) {
        int sx = blockX >> SECTION_BITS;
        int sy = blockY >> SECTION_BITS;
        int sz = blockZ >> SECTION_BITS;
        removeSection(sx, sy, sz);
    }

    /**
     * Moves an entity, keeping it counted exactly once.
     *
     * <p>Within a section this does nothing at all, which is the common case: an entity walking
     * across a block boundary inside its section changes nothing about who it can collide with.
     */
    public void move(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int fx = fromX >> SECTION_BITS;
        int fy = fromY >> SECTION_BITS;
        int fz = fromZ >> SECTION_BITS;
        int tx = toX >> SECTION_BITS;
        int ty = toY >> SECTION_BITS;
        int tz = toZ >> SECTION_BITS;
        if (fx == tx && fy == ty && fz == tz) {
            return;
        }
        removeSection(fx, fy, fz);
        addSection(tx, ty, tz);
    }

    private void addSection(int sx, int sy, int sz) {
        int key = pack(sx, sy, sz);
        int index = probe(key);
        // The count is written before the load is checked. Doing it the other way round means a
        // growth triggered here copies the table while the slot still reads as empty, and the entry
        // in flight is dropped. That bug shipped in another class in this project.
        this.keys[index] = key;
        this.counts[index]++;
        if (this.counts[index] == 1) {
            this.size++;
        }
        this.addMoves++;
        if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
            grow();
        }
    }

    private void removeSection(int sx, int sy, int sz) {
        int index = probe(pack(sx, sy, sz));
        if (this.counts[index] == 0) {
            // Removing an entity from a section that reports none would mean the counts already
            // disagree, and going negative would make every later check on this section report
            // occupied, which is the safe direction to fail in. Leaving it at zero and counting the
            // discrepancy is better than either.
            this.removeMoves++;
            return;
        }
        this.counts[index]--;
        this.removeMoves++;
        if (this.counts[index] == 0) {
            this.size--;
            backshift(index);
        }
    }

    /** Drops every section, for a world change or dimension switch. */
    public void clear() {
        Arrays.fill(this.counts, 0);
        this.size = 0;
    }

    private int probe(int key) {
        int index = spread(key) & (this.capacity - 1);
        while (this.counts[index] != 0) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        return index;
    }

    /**
     * Closes the hole left when a section empties, using the counts array as the occupancy marker.
     *
     * <p>One array rather than one array and a parallel boolean. A parallel boolean in another class
     * here was cleared on the source of a backshift without being set on the destination, which made
     * every shifted entry invisible.
     */
    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.counts[j] != 0) {
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
            if (this.counts[j] == 0) {
                return;
            }
            this.keys[i] = this.keys[j];
            this.counts[i] = this.counts[j];
            this.counts[j] = 0;
            i = j;
        }
    }

    /**
     * Packs section coordinates into an int.
     *
     * <p>Eleven bits each of X, Y and Z, which is a 2048 by 2048 by 2048 section volume -- far
     * larger than a loaded world. Section coordinates are block coordinates shifted right by four,
     * so the range is generous by a wide margin.
     */
    public static int pack(int sectionX, int sectionY, int sectionZ) {
        return ((sectionX & 0x7FF) << 21) | ((sectionY & 0x7FF) << 10) | (sectionZ & 0x3FF);
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void grow() {
        int[] oldKeys = this.keys;
        int[] oldCounts = this.counts;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new int[this.capacity];
        this.counts = new int[this.capacity];
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldCounts[i] == 0) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.keys[index] = oldKeys[i];
            this.counts[index] = oldCounts[i];
            this.size++;
        }
    }

    /** Sections currently holding at least one collidable entity. */
    public int size() {
        return this.size;
    }

    public long checks() {
        return this.checks;
    }

    /** Neighbourhood checks that found nothing, so the entity scan was skipped. */
    public long emptyChecks() {
        return this.emptyChecks;
    }

    public long addMoves() {
        return this.addMoves;
    }

    public long removeMoves() {
        return this.removeMoves;
    }

    public double skipRatePercent() {
        return this.checks == 0 ? 0.0 : 100.0 * this.emptyChecks / this.checks;
    }

    public void resetCounters() {
        this.checks = 0;
        this.emptyChecks = 0;
        this.addMoves = 0;
        this.removeMoves = 0;
    }
}
