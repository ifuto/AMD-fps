package net.amdfaster.entity;

import java.util.Arrays;

/**
 * Every drawable entity in a frame, as parallel primitive arrays.
 *
 * <p>This exists because {@link EntityInstance} is a record, and a record per entity per frame is
 * an allocation per entity per frame. A server-side farm with fifty thousand dropped items produces
 * fifty thousand objects sixty times a second -- three million a second -- all of which die young
 * enough that G1 collects them cheaply, but "cheaply" is not "free", and the cost lands on the
 * render thread at exactly the moment the frame budget is already gone.
 *
 * <p>So the scalable path never materialises an entity as an object. It appends eight primitives,
 * gets back an index, and every later stage -- culling, batching, upload -- passes that index
 * around instead. Nothing in this class allocates after it has grown to its largest size, which
 * means a steady-state frame allocates nothing at all here.
 *
 * <p>Not thread-safe. Filled on the render thread, read on the render thread.
 */
public final class EntityBuffer {

    /** Fields per entity, in the order {@link EntityGpuLayout} writes them. */
    public static final int FIELDS = 8;

    private static final int INITIAL_CAPACITY = 256;

    private int[] modelKeys;
    private float[] xs;
    private float[] ys;
    private float[] zs;
    private float[] yaws;
    private int[] lights;
    private float[] halfWidths;
    private float[] heights;
    private int size;

    public EntityBuffer() {
        this(INITIAL_CAPACITY);
    }

    public EntityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.modelKeys = new int[capacity];
        this.xs = new float[capacity];
        this.ys = new float[capacity];
        this.zs = new float[capacity];
        this.yaws = new float[capacity];
        this.lights = new int[capacity];
        this.halfWidths = new float[capacity];
        this.heights = new float[capacity];
    }

    /**
     * Appends one entity and returns its index.
     *
     * <p>{@code y} is the entity's feet, matching Minecraft, so the box runs from {@code y} to
     * {@code y + height}.
     */
    public int add(int modelKey, float x, float y, float z, float yaw, int light,
            float halfWidth, float height) {
        if (halfWidth < 0f) {
            throw new IllegalArgumentException("halfWidth must not be negative: " + halfWidth);
        }
        if (height < 0f) {
            throw new IllegalArgumentException("height must not be negative: " + height);
        }
        if (this.size == this.modelKeys.length) {
            grow();
        }
        int i = this.size++;
        this.modelKeys[i] = modelKey;
        this.xs[i] = x;
        this.ys[i] = y;
        this.zs[i] = z;
        this.yaws[i] = yaw;
        this.lights[i] = light;
        this.halfWidths[i] = halfWidth;
        this.heights[i] = height;
        return i;
    }

    /** Appends an {@link EntityInstance}, for callers that already have one. */
    public int add(EntityInstance instance) {
        return add(instance.modelKey(), instance.x(), instance.y(), instance.z(), instance.yaw(),
                instance.light(), instance.halfWidth(), instance.height());
    }

    private void grow() {
        // Doubling: the amortised cost of appending n entities stays O(n), and the arrays are
        // reused across frames because clear() does not shrink them.
        int next = this.modelKeys.length << 1;
        this.modelKeys = Arrays.copyOf(this.modelKeys, next);
        this.xs = Arrays.copyOf(this.xs, next);
        this.ys = Arrays.copyOf(this.ys, next);
        this.zs = Arrays.copyOf(this.zs, next);
        this.yaws = Arrays.copyOf(this.yaws, next);
        this.lights = Arrays.copyOf(this.lights, next);
        this.halfWidths = Arrays.copyOf(this.halfWidths, next);
        this.heights = Arrays.copyOf(this.heights, next);
    }

    /** Drops every entity but keeps the arrays, so the next frame does not reallocate. */
    public void clear() {
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    /** Current array length, exposed so a test can assert that steady state stops growing. */
    public int capacity() {
        return this.modelKeys.length;
    }

    public int modelKey(int i) {
        return this.modelKeys[i];
    }

    public float x(int i) {
        return this.xs[i];
    }

    public float y(int i) {
        return this.ys[i];
    }

    public float z(int i) {
        return this.zs[i];
    }

    public float yaw(int i) {
        return this.yaws[i];
    }

    public int light(int i) {
        return this.lights[i];
    }

    public float halfWidth(int i) {
        return this.halfWidths[i];
    }

    public float height(int i) {
        return this.heights[i];
    }

    public float minX(int i) {
        return this.xs[i] - this.halfWidths[i];
    }

    public float maxX(int i) {
        return this.xs[i] + this.halfWidths[i];
    }

    public float minY(int i) {
        return this.ys[i];
    }

    public float maxY(int i) {
        return this.ys[i] + this.heights[i];
    }

    public float minZ(int i) {
        return this.zs[i] - this.halfWidths[i];
    }

    public float maxZ(int i) {
        return this.zs[i] + this.halfWidths[i];
    }

    /**
     * Materialises one entity as a record.
     *
     * <p>Deliberately not used anywhere on the hot path -- it is here so tests can compare against
     * {@link EntityInstance}-based expectations, and so the adapter can hand a single entity to
     * code that predates this buffer.
     */
    public EntityInstance instance(int i) {
        return new EntityInstance(this.modelKeys[i], this.xs[i], this.ys[i], this.zs[i],
                this.yaws[i], this.lights[i], this.halfWidths[i], this.heights[i]);
    }
}
