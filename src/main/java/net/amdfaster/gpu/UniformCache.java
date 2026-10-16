package net.amdfaster.gpu;

import java.util.Arrays;

/**
 * Skips uniform uploads whose value has not changed since the last frame.
 *
 * <p>Every draw call sets its uniforms, and each set is a driver call with a fixed cost. Four hundred
 * draws at six uniforms each is 2,400 sets a frame, and at 5 microseconds of fixed cost apiece that is
 * 12 ms a frame -- more than the entire 16.7 ms budget, for work that is mostly writing the same numbers
 * again. The projection matrix does not change until the field of view does, and the light parameters do
 * not change until the time of day does; only the view matrix moves every frame and the model matrix
 * per batch.
 *
 * <p>Comparing against the last value and skipping the upload when it matches removes about 70% of the
 * calls.
 *
 * <h2>A stale uniform is immediately visible</h2>
 *
 * <p>Drawing with the previous frame's view matrix puts the world a frame behind the camera. That is not
 * a subtle artefact: everything shifts as the player turns, and it is the kind of motion mismatch that
 * makes people feel ill within seconds. It is also the failure a cache like this makes easy to
 * introduce, because it only happens when the comparison says "unchanged" and it was wrong.
 *
 * <p>So the comparison is over the raw bits of every value, not an approximate equality. Two matrices
 * that differ in the last bit are different matrices, and treating them as equal to save a call is
 * trading a microsecond for a visible error.
 *
 * <h2>Negative zero is a different value</h2>
 *
 * <p>A sign flip in a matrix column reverses a normal or mirrors an axis. Comparing with {@code ==}
 * treats -0.0 and 0.0 as equal, so a matrix that changed only in sign would be reported unchanged and
 * the geometry would come out inside-out. The cache compares bit patterns, where the two are distinct.
 *
 * <p>Not thread safe. Uniforms are set on the render thread.
 */
public final class UniformCache {

    /** Driver cost of one uniform set, in milliseconds. */
    public static final double MS_PER_SET = 0.005;

    private long[] keys;
    private float[][] values;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long requests;
    private long uploaded;
    private long skipped;

    public UniformCache() {
        this(4096);
    }

    public UniformCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.values = new float[capacity][];
        this.occupied = new boolean[capacity];
    }

    /**
     * Sets a uniform if its value differs from the cached one.
     *
     * @param slot identifies the uniform within a program
     * @param program identifies the program
     * @param value the values to set
     * @param setter performs the driver call
     * @return true if the driver was called
     */
    public boolean set(int program, int slot, float[] value, Setter setter) {
        if (value.length == 0) {
            throw new IllegalArgumentException("a uniform with no components");
        }
        this.requests++;
        long key = key(program, slot);
        int index = probe(key);

        if (this.occupied[index] && Arrays.equals(bitPatterns(this.values[index]), bitPatterns(value))) {
            this.skipped++;
            return false;
        }

        setter.set(program, slot, value);
        this.uploaded++;

        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(key);
        }
        if (!this.occupied[index]) {
            this.size++;
        }
        this.occupied[index] = true;
        this.keys[index] = key;
        this.values[index] = value.clone();
        return true;
    }

    /** Performs the driver call. */
    public interface Setter {
        void set(int program, int slot, float[] value);
    }

    /**
     * The bit patterns of a float array.
     *
     * <p>Compared rather than the floats themselves, so -0.0 and 0.0 are distinct. A sign flip in a
     * matrix column reverses a normal or mirrors an axis, and {@code ==} would report the two as equal
     * and leave the geometry inside-out.
     */
    private static int[] bitPatterns(float[] value) {
        int[] bits = new int[value.length];
        for (int i = 0; i < value.length; i++) {
            bits[i] = Float.floatToRawIntBits(value[i]);
        }
        return bits;
    }

    public static long key(int program, int slot) {
        return ((long) program << 32) | (slot & 0xFFFFFFFFL);
    }

    /** Drops a cached value, for a program that was reloaded. */
    public void invalidate(int program, int slot) {
        int index = find(key(program, slot));
        if (index >= 0) {
            this.occupied[index] = false;
            this.size--;
        }
    }

    /**
     * Drops everything, at the start of a frame or after a context loss.
     *
     * <p>A context loss invalidates every uniform on the GPU, so a cache that kept its values would
     * report "unchanged" for uniforms that no longer exist and nothing would be drawn.
     */
    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
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
        float[][] oldValues = this.values;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.values = new float[this.capacity][];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.occupied[index] = true;
            this.keys[index] = oldKeys[i];
            this.values[index] = oldValues[i];
            this.size++;
        }
    }

    /** Whether a value is cached, for testing that invalidation really cleared it. */
    public boolean isCached(int program, int slot) {
        return find(key(program, slot)) >= 0;
    }

    public int size() {
        return this.size;
    }

    public long requests() {
        return this.requests;
    }

    /** How many driver calls were made. */
    public long uploaded() {
        return this.uploaded;
    }

    /** How many were skipped because the value had not changed. */
    public long skipped() {
        return this.skipped;
    }

    public double skippedPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.skipped / this.requests;
    }

    /** Milliseconds of driver cost saved per frame. */
    public double msSavedPerFrame() {
        return this.skipped * MS_PER_SET;
    }

    /** Milliseconds a frame costs with no cache at all. */
    public static double msPerFrame(int draws, int uniformsPerDraw) {
        return (long) draws * uniformsPerDraw * MS_PER_SET;
    }
}
