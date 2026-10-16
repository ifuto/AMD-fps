package net.amdfaster.mem;

import java.util.Arrays;

/**
 * Stores each distinct baked quad and each distinct multipart instance once.
 *
 * <p>FerriteCore's own breakdown of a modded client puts BlockState at roughly 970 MB and models at
 * roughly 950 MB, and within models the largest single line is multipart instances at about 200 MB
 * collapsing to 1,500 -- a factor of 133. The mechanism is unglamorous: the same fence arm, the same
 * stair variant, the same slab edge is baked once per block state that uses it, and there are tens of
 * thousands of block states. Storing each distinct one once and pointing the rest at it removes the
 * duplication without changing what is drawn.
 *
 * <p>Those optimisations were removed from FerriteCore in the 1.21.5 model rewrite and, as of 8.0.4,
 * have not come back. So the duplication is live in a current modded client, and this is the part that
 * addresses it.
 *
 * <h2>Two quads are the same only if every field is</h2>
 *
 * <p>Position, both UVs, colour, light and tint index, on all four vertices. Missing one is not a
 * memory bug, it is a rendering bug: merging two quads that differ only in tint index draws a grass
 * block with a stone colour, and merging two that differ only in light draws a lit face as dark. Both
 * are the kind of thing a player reports as "textures are wrong" with no way to trace it. So the
 * comparison is over the whole content, and the hash only nominates a candidate.
 *
 * <p>A hash collision that was accepted without comparing would be exactly that bug, and it would be
 * rare enough to be nearly impossible to reproduce.
 *
 * <h2>Identity matters as much as equality</h2>
 *
 * <p>The point is that callers hold the same object, so the memory is shared rather than duplicated.
 * Interning that returned an equal but distinct instance would pass every equality test and save
 * nothing, which is why the tests check reference identity rather than {@code equals}.
 *
 * <p>Not thread safe. Model baking happens on worker threads, so a caller baking in parallel needs one
 * deduplicator per thread and a merge at the end, or external synchronisation.
 */
public final class ModelDeduplicator {

    /** Longs in one quad's content: four vertices of position, UV, colour and light, plus a tint index. */
    public static final int QUAD_LONGS = 4 * 6 + 1;

    /** Floats in a vertex's geometry and shading. */
    public static final int FLOATS_PER_VERTEX = 5;

    /** Bytes a quad occupies when stored. */
    public static final int QUAD_BYTES = QUAD_LONGS * 8;

    private long[][] quads;
    private boolean[] quadOccupied;
    private int quadCount;

    private long[][] instances;
    private boolean[] instanceOccupied;
    private int instanceCount;

    private int capacity;

    private long quadLookups;
    private long quadInserts;
    private long quadDuplicates;
    private long instanceLookups;
    private long instanceInserts;
    private long instanceDuplicates;

    public ModelDeduplicator() {
        this(1024);
    }

    public ModelDeduplicator(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        this.capacity = roundUp(capacity);
        allocate();
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    private void allocate() {
        this.quads = new long[this.capacity][];
        this.quadOccupied = new boolean[this.capacity];
        this.instances = new long[this.capacity][];
        this.instanceOccupied = new boolean[this.capacity];
        this.quadCount = 0;
        this.instanceCount = 0;
    }

    /**
     * Packs a quad's content for interning.
     *
     * @param geometry twenty floats: four vertices of x, y, z, u, v
     * @param colours four packed colours
     * @param lights four packed lights
     * @param tintIndex the tint index, or -1 for none
     * @return {@link #QUAD_LONGS} longs
     */
    public static long[] packQuad(float[] geometry, int[] colours, int[] lights, int tintIndex) {
        if (geometry.length != 4 * FLOATS_PER_VERTEX) {
            throw new IllegalArgumentException(
                    "four vertices of five floats: " + geometry.length);
        }
        if (colours.length != 4 || lights.length != 4) {
            throw new IllegalArgumentException(
                    "four colours and four lights: " + colours.length + ", " + lights.length);
        }
        long[] content = new long[QUAD_LONGS];
        for (int v = 0; v < 4; v++) {
            long packed = 0;
            for (int i = 0; i < FLOATS_PER_VERTEX; i++) {
                // Bit patterns rather than values, so that -0.0 and 0.0 stay distinct. They compare
                // equal as floats, and a quad built with one is not necessarily the quad built with the
                // other; treating them as the same would be the subtle merge this class must not do.
                packed = packed * 0x9E3779B97F4A7C15L
                        + Float.floatToRawIntBits(geometry[v * FLOATS_PER_VERTEX + i]);
            }
            packed = packed * 31 + colours[v];
            packed = packed * 31 + lights[v];
            content[v] = packed;
        }
        content[4] = tintIndex;
        return content;
    }

    /**
     * Returns the canonical quad for this content, interning it if it is new.
     *
     * <p>The returned array is owned by the deduplicator. Callers must not write to it, since every
     * other holder of the same quad would see the change.
     */
    public long[] internQuad(long[] content) {
        if (content.length != QUAD_LONGS) {
            throw new IllegalArgumentException("a quad is " + QUAD_LONGS + " longs: "
                    + content.length);
        }
        this.quadLookups++;
        int hash = hash(content);
        int index = hash & (this.capacity - 1);
        int visited = 0;

        while (this.quadOccupied[index]) {
            if (Arrays.equals(this.quads[index], content)) {
                this.quadDuplicates++;
                return this.quads[index];
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "quad table of " + this.capacity + " is full; probe did not terminate");
            }
        }

        this.quadOccupied[index] = true;
        this.quads[index] = content.clone();
        this.quadCount++;
        this.quadInserts++;
        if (this.quadCount * 4 >= this.capacity * 3) {
            growQuads();
        }
        return this.quads[index];
    }

    /**
     * Returns the canonical multipart instance for a predicate, interning it if it is new.
     *
     * <p>The predicate is whatever identifies the instance, packed by the caller.
     */
    public long[] internInstance(long[] predicate) {
        if (predicate.length == 0) {
            throw new IllegalArgumentException("an empty predicate identifies nothing");
        }
        this.instanceLookups++;
        int hash = hash(predicate);
        int index = hash & (this.capacity - 1);
        int visited = 0;

        while (this.instanceOccupied[index]) {
            if (Arrays.equals(this.instances[index], predicate)) {
                this.instanceDuplicates++;
                return this.instances[index];
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "instance table of " + this.capacity + " is full; probe did not terminate");
            }
        }

        this.instanceOccupied[index] = true;
        this.instances[index] = predicate.clone();
        this.instanceCount++;
        this.instanceInserts++;
        if (this.instanceCount * 4 >= this.capacity * 3) {
            growInstances();
        }
        return this.instances[index];
    }

    private static int hash(long[] content) {
        long h = content.length;
        for (long value : content) {
            h = h * 0x9E3779B97F4A7C15L + value;
            h ^= h >>> 29;
        }
        return (int) (h & 0x7FFFFFFFL);
    }

    private void growQuads() {
        long[][] old = this.quads;
        boolean[] oldOccupied = this.quadOccupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.quads = new long[this.capacity][];
        this.quadOccupied = new boolean[this.capacity];
        // The instance table shares the capacity, so it has to move too, or its indices would point
        // into a table of the old size.
        long[][] oldInstances = this.instances;
        boolean[] oldInstanceOccupied = this.instanceOccupied;
        this.instances = new long[this.capacity][];
        this.instanceOccupied = new boolean[this.capacity];

        this.quadCount = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = hash(old[i]) & (this.capacity - 1);
            while (this.quadOccupied[index]) {
                index = (index + 1) & (this.capacity - 1);
            }
            this.quadOccupied[index] = true;
            this.quads[index] = old[i];
            this.quadCount++;
        }
        this.instanceCount = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (!oldInstanceOccupied[i]) {
                continue;
            }
            int index = hash(oldInstances[i]) & (this.capacity - 1);
            while (this.instanceOccupied[index]) {
                index = (index + 1) & (this.capacity - 1);
            }
            this.instanceOccupied[index] = true;
            this.instances[index] = oldInstances[i];
            this.instanceCount++;
        }
    }

    private void growInstances() {
        // Same shape as growQuads; the tables share a capacity so either growth moves both.
        growQuads();
    }

    /** How many distinct quads are stored. */
    public int quadCount() {
        return this.quadCount;
    }

    /** How many distinct instances are stored. */
    public int instanceCount() {
        return this.instanceCount;
    }

    public long quadLookups() {
        return this.quadLookups;
    }

    public long quadDuplicates() {
        return this.quadDuplicates;
    }

    public long instanceDuplicates() {
        return this.instanceDuplicates;
    }

    /** Bytes the distinct quads occupy. */
    public long quadBytes() {
        return (long) this.quadCount * QUAD_BYTES;
    }

    /** Bytes the same number of quads would occupy without deduplication. */
    public long quadBytesWithoutDedup() {
        return this.quadLookups * QUAD_BYTES;
    }

    /**
     * Percentage of quad storage removed.
     *
     * <p>Counted in lookups rather than inserts, because a lookup is a quad that some block state would
     * otherwise have stored its own copy of.
     */
    public double quadSavedPercent() {
        return this.quadLookups == 0 ? 0.0
                : 100.0 * this.quadDuplicates / this.quadLookups;
    }

    /** Drops everything, for a resource pack reload. */
    public void clear() {
        allocate();
    }
}
