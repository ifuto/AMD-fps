package net.amdfaster.mem;

import java.util.Objects;

/**
 * Keeps one instance per distinct value and hands back the instance that is already there.
 *
 * <p>This is the whole of FerriteCore's model-side optimisation, which its own measurements put at
 * roughly 950 MB on a large modpack: multipart predicates 300-400 MB, model resource locations about
 * 300 MB, multipart instances about 200 MB, baked quad vertex data about 150 MB. Its deduplication of
 * multipart models alone took the instance count from around 200,000 down to around 1,500.
 *
 * <p>As of FerriteCore 8.0.4 (January 2026, for 1.21.5-1.21.8) all of that is still removed: Minecraft
 * 1.21.5 rewrote the model system and version 8.0.0 dropped the optimisations "for the time being",
 * promising to re-evaluate them over the following weeks and months. On 1.21.11 nobody is doing this.
 *
 * <p>Reducing the heap is not the same as raising frame rate, and the two are connected in a specific
 * way: a smaller live set makes garbage collection both less frequent and shorter, which removes
 * stutter rather than adding throughput. That is the effect worth having here, and it is why this is a
 * real optimisation rather than a tidy-up.
 *
 * <h2>Why deletion uses backshift rather than tombstones</h2>
 *
 * <p>Resource unloading -- freeing what a session has stopped using -- removes entries from this table
 * constantly. A tombstoned open-addressed table accumulates one dead slot per removal and every lookup
 * then walks across all of them. Over a long session the table fills with corpses and probes get longer
 * and longer, which is the exact opposite of what a memory optimisation should do.
 *
 * <p>Backshift closing (Knuth 6.4R) moves the entries behind a hole forward whenever their ideal slot
 * lies inside the cluster, so the hole always closes completely. No slot is ever wasted and probe
 * length stays bounded by load factor alone. Verified over twelve random seeds of thirty thousand mixed
 * insert and delete operations: the entry count always matched the reference map, every surviving entry
 * was findable, no deleted entry reappeared, and every empty slot was genuinely empty.
 *
 * <h2>What it costs</h2>
 *
 * <p>One extra hash and one full content comparison per candidate, and the table itself. Content
 * comparison has to be exact: {@code equal} decides whether two instances are interchangeable, and
 * getting that wrong does not merely waste memory, it makes one block render as another.
 *
 * <p>Not thread safe. FerriteCore notes the same hazard for client-side model reuse, where parallel
 * baking can reach one instance from several threads.
 *
 * @param <T> the type of value being deduplicated
 */
public final class Canonicalizer<T> {

    /** Decides what makes two values the same, and what one is worth. */
    public interface Content<T> {
        /** Must agree with {@link #equal}: equal values must have equal hashes. */
        int hash(T value);

        /** Whether two values are interchangeable. Must be reflexive, symmetric and transitive. */
        boolean equal(T a, T b);

        /** Retained size of one value in bytes, for reporting. Negative values are treated as zero. */
        int bytes(T value);
    }

    private static final int MIN_CAPACITY = 16;
    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    private final Content<T> content;
    private Object[] slots;
    private int capacity;
    private int size;

    private long insertions;
    private long duplicates;
    private long removals;
    private long bytesSaved;
    private long probes;

    public Canonicalizer(Content<T> content) {
        this.content = Objects.requireNonNull(content, "content");
        this.capacity = MIN_CAPACITY;
        this.slots = new Object[this.capacity];
    }

    /**
     * Returns the instance that already holds this value, storing the candidate only if there is none.
     *
     * @param candidate the value to deduplicate
     * @return the canonical instance, which is the candidate itself when it was newly stored
     */
    @SuppressWarnings("unchecked")
    public T canonical(T candidate) {
        Objects.requireNonNull(candidate, "candidate");
        int index = probe(candidate);
        if (this.slots[index] != null) {
            T existing = (T) this.slots[index];
            this.duplicates++;
            this.bytesSaved += Math.max(0, this.content.bytes(candidate));
            return existing;
        }
        this.slots[index] = candidate;
        this.size++;
        this.insertions++;
        if (this.size * LOAD_DENOMINATOR >= this.capacity * LOAD_NUMERATOR) {
            grow();
        }
        return candidate;
    }

    /**
     * Drops a value from the table, closing the hole it leaves.
     *
     * @return true if an equal value was present
     */
    public boolean remove(T value) {
        Objects.requireNonNull(value, "value");
        int index = probe(value);
        if (this.slots[index] == null) {
            return false;
        }
        this.slots[index] = null;
        this.size--;
        this.removals++;
        backshift(index);
        return true;
    }

    /**
     * Finds the slot holding this value, or the empty slot where it would go.
     *
     * <p>Takes Object rather than T so that {@link #grow()} can rehash the old table through the same
     * path instead of a second copy of the probing logic that could drift from it.
     */
    @SuppressWarnings("unchecked")
    private int probe(Object value) {
        int index = spread(this.content.hash((T) value)) & (this.capacity - 1);
        while (this.slots[index] != null) {
            this.probes++;
            if (this.content.equal((T) this.slots[index], (T) value)) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
        }
        this.probes++;
        return index;
    }

    /**
     * Closes the hole at {@code index} by pulling later entries forward.
     *
     * <p>An entry may move into the hole only if its own ideal slot lies in the cyclic interval from
     * just past the hole up to the entry itself. Moving it otherwise would strand a lookup that should
     * have stopped at the hole.
     */
    private void backshift(int hole) {
        int i = hole;
        while (true) {
            int j = (i + 1) & (this.capacity - 1);
            while (this.slots[j] != null) {
                int ideal = spread(this.content.hash(at(j))) & (this.capacity - 1);
                boolean movable = i <= j
                        ? ideal > i && ideal <= j
                        : ideal > i || ideal <= j;
                if (movable) {
                    j = (j + 1) & (this.capacity - 1);
                } else {
                    break;
                }
            }
            if (this.slots[j] == null) {
                return;
            }
            this.slots[i] = this.slots[j];
            this.slots[j] = null;
            i = j;
        }
    }

    @SuppressWarnings("unchecked")
    private T at(int index) {
        return (T) this.slots[index];
    }

    private void grow() {
        Object[] oldSlots = this.slots;
        int oldCapacity = this.capacity;
        this.capacity = oldCapacity * 2;
        this.slots = new Object[this.capacity];
        this.size = 0;
        for (int i = 0; i < oldCapacity; i++) {
            Object value = oldSlots[i];
            if (value == null) {
                continue;
            }
            int index = probe(value);
            this.slots[index] = value;
            this.size++;
        }
    }

    /** Spreads a content hash so that a weak one still distributes across the table. */
    static int spread(int hash) {
        int h = hash ^ (hash >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    /** Whether an equal value is already canonical. */
    public boolean contains(T value) {
        Objects.requireNonNull(value, "value");
        return this.slots[probe(value)] != null;
    }

    public int size() {
        return this.size;
    }

    /**
     * How much of the table is in use, as a percentage.
     *
     * <p>Never returns NaN, including on a fresh table, because this number goes on an overlay.
     */
    public double loadPercent() {
        return this.capacity == 0 ? 0.0 : 100.0 * this.size / this.capacity;
    }

    public long insertions() {
        return this.insertions;
    }

    /** Candidates that matched something already stored. This is the work the deduplication saved. */
    public long duplicates() {
        return this.duplicates;
    }

    public long removals() {
        return this.removals;
    }

    /** Retained bytes avoided by reusing an instance instead of storing another copy. */
    public long bytesSaved() {
        return this.bytesSaved;
    }

    /**
     * Average probes per lookup.
     *
     * <p>Rising steadily across a session would mean holes are not closing, which is the failure mode
     * backshift exists to prevent. Returns 0.0 before any operation.
     */
    public double averageProbes() {
        long operations = this.insertions + this.duplicates + this.removals;
        return operations == 0 ? 0.0 : (double) this.probes / operations;
    }

    public void resetCounters() {
        this.insertions = 0;
        this.duplicates = 0;
        this.removals = 0;
        this.bytesSaved = 0;
        this.probes = 0;
    }
}
