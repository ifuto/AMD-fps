package net.amdfaster.explosion;

import java.util.Arrays;

/**
 * Holds one blast's destroy list in a reused flat array of packed positions, instead of allocating a
 * set of boxed coordinates per explosion.
 *
 * <p>An explosion resolves its rays into a collection of positions to destroy, and vanilla builds that
 * collection fresh for every blast. Two things make that expensive in exactly the situation that is
 * already expensive. Each entry is a boxed position object -- sixteen bytes of header and three ints,
 * rounded to thirty-two -- sitting in an open hash set that adds roughly forty-eight more per entry. And
 * the whole collection is garbage the moment the blast finishes, so a chain reaction of a hundred TNT
 * does not allocate once, it allocates a hundred times and hands the collector a hundred short-lived
 * collections to walk.
 *
 * <p>Measuring the redundancy in {@link ExplosionBlockCache} put a single blast at 823 distinct blocks.
 * At eighty bytes an entry that is 65,840 bytes a blast against 6,584 for the same positions packed into
 * longs in a flat array -- a factor of ten, and 5.9 megabytes across a hundred-blast chain. The saving is
 * not the memory, which is transient either way. It is the collector's work, which lands on the same
 * thread the explosion runs on and shows up as the hitch the player actually feels.
 *
 * <h2>Reused, not reallocated</h2>
 *
 * <p>The array survives {@link #clear} and is only grown, never shrunk. A chain reaction's blasts are all
 * about the same size, so the first one sizes the buffer and the rest reuse it. Shrinking on clear would
 * reallocate on the next add and undo the point.
 *
 * <h2>Duplicates are dropped here, not later</h2>
 *
 * <p>Two rays cross the same block constantly -- that is the whole finding behind the resistance cache.
 * A destroy list that allowed duplicates would destroy the same block twice, and the second destruction
 * would find air and drop nothing, which is harmless for blocks but wasteful for the item entities each
 * destruction spawns. So {@link #add} reports whether the position was new, and the caller can skip the
 * work for one that was not.
 *
 * <p>Not thread safe. Reached from the server thread, which in singleplayer is inside the client process.
 */
public final class BlastDamageBuffer {

    /** Distinct blocks a single TNT blast destroys, re-measured 2026-10-18. */
    public static final int BLOCKS_PER_BLAST = 823;

    /**
     * Bytes one entry costs the vanilla path: a boxed position rounded to thirty-two, plus roughly
     * forty-eight of open hash set.
     */
    public static final int VANILLA_BYTES_PER_ENTRY = 80;

    /** Bytes one entry costs here. */
    public static final int PACKED_BYTES_PER_ENTRY = 8;

    private static final int INITIAL_CAPACITY = 1024;

    private long[] positions;
    private long[] table;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long blasts;
    private long adds;
    private long duplicates;
    private long growths;

    public BlastDamageBuffer() {
        this.capacity = INITIAL_CAPACITY;
        this.positions = new long[this.capacity];
        this.table = new long[this.capacity];
        this.occupied = new boolean[this.capacity];
    }

    /**
     * Starts a new blast, keeping the arrays.
     *
     * <p>Deliberately does not shrink. A chain's blasts are all about the same size, so shrinking here
     * would force a reallocation on the next add and give back the only thing this class saves.
     */
    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
        this.blasts++;
    }

    /**
     * Records a block to destroy.
     *
     * @param packed the position, as {@link ExplosionBlockCache#pack} produces
     * @return true if this position was not already in the list. False means another ray got there
     *     first, and the caller can skip the destruction work -- and the item entities it would spawn.
     */
    public boolean add(long packed) {
        this.adds++;
        int slot = slotFor(packed);
        if (this.occupied[slot]) {
            this.duplicates++;
            return false;
        }
        if (this.size == this.positions.length) {
            grow();
            slot = slotFor(packed);
        }
        this.table[slot] = packed;
        this.occupied[slot] = true;
        this.positions[this.size++] = packed;
        return true;
    }

    /** How many distinct positions this blast will destroy. */
    public int size() {
        return this.size;
    }

    /**
     * The backing array.
     *
     * <p>Only the first {@link #size()} entries are live. Returning the array rather than a copy is the
     * point -- copying it would allocate the thing this class exists not to allocate.
     */
    public long[] positions() {
        return this.positions;
    }

    /** Bytes this blast's list occupies, against what the boxed path would have used. */
    public int bytesUsed() {
        return this.size * PACKED_BYTES_PER_ENTRY;
    }

    /** Bytes the same list would have cost boxed in a hash set. */
    public int bytesVanilla() {
        return this.size * VANILLA_BYTES_PER_ENTRY;
    }

    /** How many blasts this buffer has held. */
    public long blasts() {
        return this.blasts;
    }

    /** How many positions were offered, duplicates included. */
    public long adds() {
        return this.adds;
    }

    /** How many offers were for a position another ray had already recorded. */
    public long duplicates() {
        return this.duplicates;
    }

    /** How many times the buffer had to grow. Zero after the first blast of a chain, normally. */
    public long growths() {
        return this.growths;
    }

    private int slotFor(long packed) {
        int mask = this.capacity - 1;
        int slot = spread(packed) & mask;
        while (this.occupied[slot] && this.table[slot] != packed) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        long[] oldTable = this.table;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.table = new long[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.growths++;

        int mask = this.capacity - 1;
        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int slot = spread(oldTable[i]) & mask;
            while (this.occupied[slot]) {
                slot = (slot + 1) & mask;
            }
            this.table[slot] = oldTable[i];
            this.occupied[slot] = true;
        }

        long[] grown = new long[this.capacity];
        System.arraycopy(this.positions, 0, grown, 0, this.size);
        this.positions = grown;
    }

    /** Spreads a packed position so adjacent blocks do not land in the same run of slots. */
    private static int spread(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return (int) h & 0x7FFFFFFF;
    }
}
