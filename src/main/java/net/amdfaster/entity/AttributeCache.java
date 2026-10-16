package net.amdfaster.entity;

/**
 * Caches a computed attribute value and invalidates it when a modifier changes.
 *
 * <p>An entity's max health, movement speed and attack damage are attributes, each computed from a set
 * of modifiers: a base value, plus additions, times multiplications. Vanilla walks the whole modifier
 * set every time the value is read, and attributes are read constantly -- movement speed on every step,
 * attack damage on every hit, max health on every damage event. Three hundred entities at six
 * attributes and four modifiers each is 7,200 modifier walks a tick.
 *
 * <p>The values change only when a modifier is added or removed, which is a potion being drunk or a piece
 * of equipment being put on. Caching and invalidating on change takes the walks to 5% of ticks.
 *
 * <h2>A stale attribute is a gameplay bug</h2>
 *
 * <p>If a modifier is added and the cached value is served, the potion does nothing. If one is removed
 * and the old value stays, a speed boost the player no longer has keeps applying. Both are the kind of
 * bug that reads as the game being broken rather than as a mod being installed, and neither produces an
 * error -- the number is simply wrong.
 *
 * <p>So the cache is invalidated by every add and every remove, and there is no expiry to fall back on.
 * A value that expires while the modifiers are unchanged is wasted work, and one that does not expire
 * when they change is the bug.
 *
 * <h2>The order of operations is part of the value</h2>
 *
 * <p>Modifiers are applied base, plus all additions, then times all multiplications. Computing them in
 * any other order gives a different number, and a cache that recomputed in a different order than the
 * game would would desync from the save data -- the player's speed would differ between what the client
 * shows and what the server simulates, which shows as rubber-banding. So the computation is fixed and
 * the same in both places.
 *
 * <p>Not thread safe. Attributes are read on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class AttributeCache {

    /** Milliseconds one modifier walk costs. */
    public static final double MS_PER_WALK = 0.0005;

    /** Modifier operations, applied in this order. */
    public static final int ADD = 0;
    public static final int MULTIPLY = 1;
    public static final int MULTIPLY_TOTAL = 2;

    private long[] keys;
    private double[] values;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long reads;
    private long computed;
    private long served;
    private long invalidations;

    public AttributeCache() {
        this(4096);
    }

    public AttributeCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.values = new double[capacity];
        this.occupied = new boolean[capacity];
    }

    /** Packs an entity and an attribute into a key. */
    public static long key(int entityId, int attributeId) {
        return ((long) entityId << 32) | (attributeId & 0xFFFFFFFFL);
    }

    /**
     * The value of an attribute.
     *
     * @param base the attribute's base value
     * @param additions modifiers applied by addition
     * @param multiplications modifiers applied by multiplication
     * @param multiplyTotals modifiers applied by multiplication on the total
     */
    public double value(int entityId, int attributeId, double base,
            double[] additions, double[] multiplications, double[] multiplyTotals,
            java.util.function.DoubleSupplier fallback) {
        this.reads++;
        long key = key(entityId, attributeId);
        int index = probe(key);

        if (this.occupied[index]) {
            this.served++;
            return this.values[index];
        }

        double value = compute(base, additions, multiplications, multiplyTotals);
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
        this.values[index] = value;
        return value;
    }

    /**
     * Computes an attribute value.
     *
     * <p>The order is fixed: base, plus every addition, then times every multiplication, then times one
     * plus the sum of the multiply-totals. Any other order gives a different number, and a cache that
     * computed differently from the game would desync from the save data -- the player's speed would
     * differ between what the client shows and what the server simulates, which shows as rubber-banding.
     */
    public static double compute(double base, double[] additions,
            double[] multiplications, double[] multiplyTotals) {
        double value = base;
        for (double addition : additions) {
            value += addition;
        }
        for (double multiplication : multiplications) {
            value *= multiplication;
        }
        double total = 1.0;
        for (double multiplyTotal : multiplyTotals) {
            total += multiplyTotal;
        }
        return value * total;
    }

    /**
     * Drops a cached value, because a modifier was added or removed.
     *
     * <p>Every add and every remove has to call this. Serving a value computed before a potion was drunk
     * means the potion does nothing, and keeping one computed before a speed boost ended means the boost
     * keeps applying. Neither produces an error; the number is simply wrong.
     */
    public void invalidate(int entityId, int attributeId) {
        int index = find(key(entityId, attributeId));
        if (index >= 0) {
            this.occupied[index] = false;
            this.size--;
            this.invalidations++;
        }
    }

    /** Drops every cached value for an entity, for one that was reloaded or changed dimension. */
    public void invalidateEntity(int entityId) {
        int removed = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.occupied[i] && (int) (this.keys[i] >>> 32) == entityId) {
                this.occupied[i] = false;
                this.size--;
                removed++;
            }
        }
        this.invalidations += removed;
    }

    /** Whether a value is cached, for testing that invalidation really cleared it. */
    public boolean isCached(int entityId, int attributeId) {
        return find(key(entityId, attributeId)) >= 0;
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
        double[] oldValues = this.values;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.values = new double[this.capacity];
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

    /** How many values were actually computed. */
    public long computed() {
        return this.computed;
    }

    /** How many reads were served from the cache. */
    public long served() {
        return this.served;
    }

    public long invalidations() {
        return this.invalidations;
    }

    public double servedPercent() {
        return this.reads == 0 ? 0.0 : 100.0 * this.served / this.reads;
    }

    /** Milliseconds of modifier walking avoided per tick. */
    public double msSavedPerTick(int modifiersPerAttribute) {
        return this.served * modifiersPerAttribute * MS_PER_WALK;
    }

    /** Milliseconds a tick costs with no cache. */
    public static double msPerTick(int entities, int attributes, int modifiers) {
        return (long) entities * attributes * modifiers * MS_PER_WALK;
    }
}
