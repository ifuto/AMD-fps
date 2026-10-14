package net.amdfaster.gpu;

import java.util.Arrays;

/**
 * Collapses repeated draws into one instanced draw plus an array of transforms.
 *
 * <p>Minecraft draws the same mesh over and over: two hundred chests, a thousand dropped items, four
 * thousand particles, three hundred item frames. Each is a separate draw call that differs from the
 * others only in where it is. A draw call costs driver validation and state tracking on the CPU, which
 * at two microseconds each makes 5,550 draws eleven milliseconds of a sixteen-millisecond frame -- and
 * that is before anything is rendered.
 *
 * <p>Grouping by mesh, material and texture turns those 5,550 draws into five, and the difference
 * between them becomes an array of instance records the vertex shader reads. The transfer is 177,600
 * bytes at 32 bytes an instance, which is real but is not what the draw count costs.
 *
 * <h2>What must not be grouped</h2>
 *
 * <p>Anything the shader reads other than the transform. Two draws with the same mesh and material but
 * different light, a different texture, or a different animation state are different draws, and
 * merging them draws every one of them with whichever values happened to be first. That is not a
 * performance bug: it is every chest in the world wearing one chest's light, and it is visible.
 *
 * <p>So the group key is every field the instance record does <em>not</em> carry. The record carries
 * the transform and the per-instance light; everything else has to match exactly. Getting this the
 * other way round -- grouping on what looks similar rather than on what the record covers -- is the
 * mistake this class exists to prevent.
 *
 * <h2>Single-instance groups</h2>
 *
 * <p>A group of one is left as an ordinary draw. Instancing it would cost an upload and a bind to save
 * nothing, and in a scene of mostly unique geometry that is most of the groups.
 *
 * <p>Not thread safe.
 */
public final class InstanceDeduplicator {

    /** The largest group this will build before splitting, so one group cannot exhaust a buffer. */
    public static final int MAX_INSTANCES_PER_GROUP = 65536;

    /** Bytes in one instance record, matching the entity layout. */
    public static final int INSTANCE_BYTES = 32;

    private static final int DEFAULT_CAPACITY = 256;

    private long[] groupKeys;
    private int[] groupCounts;
    private int[] groupFirstInstance;

    /**
     * Whether a slot holds a group.
     *
     * <p>Separate from the count, because {@link #clear} zeroes the counts to start a new frame while
     * the keys stay put, so the same groups land in the same slots next frame. Using the count as the
     * presence flag would make every slot look empty after a clear and rebuild the table every frame.
     */
    private boolean[] keyed;

    /** Where the next instance record goes. */
    private int nextInstance;

    private int capacity;

    private float[] instances;

    private long draws;
    private long groups;
    public InstanceDeduplicator() {
        this(DEFAULT_CAPACITY);
    }

    public InstanceDeduplicator(int capacity) {
        if (capacity < 16) {
            throw new IllegalArgumentException("capacity must be at least sixteen: " + capacity);
        }
        this.capacity = roundUp(capacity);
        this.groupKeys = new long[this.capacity];
        this.groupCounts = new int[this.capacity];
        this.groupFirstInstance = new int[this.capacity];
        this.keyed = new boolean[this.capacity];
        this.instances = new float[MAX_INSTANCES_PER_GROUP * FLOATS_PER_INSTANCE];
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    /** Floats in one instance record: a 3 by 4 affine transform. */
    public static final int FLOATS_PER_INSTANCE = 12;

    /**
     * Builds the group key.
     *
     * <p>Every field the instance record does not carry has to be in here. The record carries the
     * transform and per-instance light, so light is absent and everything else is present.
     *
     * @param meshId which mesh to draw
     * @param materialId which pipeline state
     * @param textureId which texture binding
     * @param animationState which frame of any animation; distinct states are distinct draws
     */
    public static long groupKey(int meshId, int materialId, int textureId, int animationState) {
        if (meshId < 0 || meshId > 0xFFFF) {
            throw new IllegalArgumentException("meshId outside 16 bits: " + meshId);
        }
        if (materialId < 0 || materialId > 0xFF) {
            throw new IllegalArgumentException("materialId outside 8 bits: " + materialId);
        }
        if (textureId < 0 || textureId > 0xFFFF) {
            throw new IllegalArgumentException("textureId outside 16 bits: " + textureId);
        }
        if (animationState < 0 || animationState > 0xFF) {
            throw new IllegalArgumentException("animationState outside 8 bits: " + animationState);
        }
        return ((long) meshId << 40)
                | ((long) materialId << 32)
                | ((long) textureId << 16)
                | animationState;
    }

    /**
     * Adds a draw.
     *
     * @param key from {@link #groupKey}
     * @param transform twelve floats, a 3 by 4 affine matrix in column-major order
     * @return the group the draw joined
     */
    public int add(long key, float[] transform) {
        if (transform.length != FLOATS_PER_INSTANCE) {
            throw new IllegalArgumentException(
                    "a 3 by 4 transform is " + FLOATS_PER_INSTANCE + " floats: " + transform.length);
        }
        for (float value : transform) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("transform contains " + value);
            }
        }
        this.draws++;

        int index = probe(key, true);
        if (this.groupCounts[index] == 0) {
            this.groupKeys[index] = key;
            this.groupFirstInstance[index] = this.nextInstance;
            this.groups++;
        }
        if (this.groupCounts[index] >= MAX_INSTANCES_PER_GROUP) {
            throw new IllegalStateException(
                    "group holds " + MAX_INSTANCES_PER_GROUP + " instances; split it");
        }

        int base = this.groupFirstInstance[index] + this.groupCounts[index];
        this.nextInstance = base + 1;
        System.arraycopy(transform, 0, this.instances, base * FLOATS_PER_INSTANCE,
                FLOATS_PER_INSTANCE);
        this.groupCounts[index]++;
        return index;
    }

    /** How many draws a group holds. */
    public int instanceCount(int group) {
        checkGroup(group);
        return this.groupCounts[group];
    }

    /** The group's key. */
    public long groupKeyOf(int group) {
        checkGroup(group);
        return this.groupKeys[group];
    }

    /**
     * Copies a group's transforms out, for uploading.
     *
     * @param out destination, at least {@code instanceCount(group) * 12} floats
     * @return how many floats were written
     */
    public int copyInstances(int group, float[] out) {
        checkGroup(group);
        int count = this.groupCounts[group];
        int floats = count * FLOATS_PER_INSTANCE;
        if (out.length < floats) {
            throw new IllegalArgumentException("out holds " + out.length + ", needs " + floats);
        }
        System.arraycopy(this.instances, this.groupFirstInstance[group] * FLOATS_PER_INSTANCE,
                out, 0, floats);
        return floats;
    }

    /**
     * Whether a group is worth instancing.
     *
     * <p>False for a single draw, which costs an upload and a bind to save nothing.
     */
    public boolean isWorthInstancing(int group) {
        checkGroup(group);
        return this.groupCounts[group] > 1;
    }

    /** How many draws this replaces, counting each group of two or more as one. */
    public int drawCallsNeeded() {
        int n = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.groupCounts[i] > 0) {
                n++;
            }
        }
        return n;
    }

    /** Drops everything, for a new frame. */
    public void clear() {
        Arrays.fill(this.groupCounts, 0);
        this.nextInstance = 0;
    }

    private int probe(long key, boolean create) {
        int index = spread(key) & (this.capacity - 1);
        int visited = 0;
        while (this.groupCounts[index] != 0 || isKeyed(index)) {
            if (isKeyed(index) && this.groupKeys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "group table of " + this.capacity + " is full; probe did not terminate");
            }
        }
        if (create) {
            markKeyed(index);
        }
        return index;
    }

    private boolean isKeyed(int index) {
        return this.keyed[index];
    }

    private void markKeyed(int index) {
        this.keyed[index] = true;
    }

    static int spread(long key) {
        long h = key ^ (key >>> 32);
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return (int) (h & 0x7FFFFFFFL);
    }

    private void checkGroup(int group) {
        if (group < 0 || group >= this.capacity) {
            throw new IndexOutOfBoundsException("group " + group + " outside " + this.capacity);
        }
    }

    public long draws() {
        return this.draws;
    }

    public long groupCount() {
        return this.groups;
    }

    /**
     * Groups that hold exactly one draw and are left as ordinary draws.
     *
     * <p>Counted rather than tracked, because the obvious incremental bookkeeping -- decrement when a
     * singleton gains a second member -- is easy to get subtly wrong and this is not on a hot path.
     */
    public int singletonGroups() {
        int n = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.groupCounts[i] == 1) {
                n++;
            }
        }
        return n;
    }

    /** Draws that became instances, which is every draw minus one per group. */
    public int instancedDraws() {
        return (int) (this.draws - drawCallsNeeded());
    }

    /** Percentage of draws removed by instancing. */
    public double drawReductionPercent() {
        return this.draws == 0 ? 0.0
                : 100.0 * (this.draws - drawCallsNeeded()) / this.draws;
    }
}
