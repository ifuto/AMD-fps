package net.amdfaster.mc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Block models loaded on first use and held while anything still refers to them.
 *
 * <p>Two problems, and they pull in opposite directions, which is why the cache is reference counted
 * rather than simply bounded.
 *
 * <p>Loading eagerly is wrong because a world uses a small fraction of the block registry. A plains
 * biome touches grass, dirt, stone, oak and air; loading every model in the game at startup spends
 * startup time and memory on blocks the player will never see in this world, and on a modded install
 * the registry is thousands of entries.
 *
 * <p>Evicting by size alone is also wrong, and worse, because a model in use is not a model that can be
 * dropped. Every loaded section holding that block has geometry referring to its quads and sprite
 * indices. Evicting it under them means either a null dereference during meshing or a rebuild of every
 * section that used it, which is more expensive than the memory the eviction saved.
 *
 * <p>So entries are loaded lazily, acquire a reference when a section starts using them, release it when
 * that section is rebuilt or unloaded, and are only eligible for eviction at zero references. That is
 * the combination that makes lazy loading safe: nothing is paid for until it is needed, and nothing
 * needed is ever dropped.
 *
 * <p>Keyed by block id in a map rather than mapped to slots by modulo. A direct-mapped table would let
 * two ids collide, and resolving that collision means displacing whichever model was already there --
 * which is exactly the eviction of a referenced model this class exists to refuse. A hash map has no
 * collisions to resolve, so capacity is the only pressure and reference count the only veto.
 *
 * <p>Not thread safe.
 *
 * @param <T> the model type
 */
public final class ModelCache<T> {

    /** Entries the cache tries to keep resident. Unreferenced entries beyond this are dropped. */
    public static final int DEFAULT_CAPACITY = 512;

    private static final class Entry<T> {
        T model;
        int references;

        Entry(T model) {
            this.model = model;
        }
    }

    private final int capacity;
    private final Map<Integer, Entry<T>> entries = new HashMap<>();

    private long loads;
    private long reused;
    private long evictions;
    private long evictionRefusals;

    public ModelCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    public static <T> ModelCache<T> withDefaultCapacity() {
        return new ModelCache<>(DEFAULT_CAPACITY);
    }

    /** Produces a model for a block id. Called once per block id per residency. */
    public interface Loader<T> {
        T load(int blockId);
    }

    public int capacity() {
        return this.capacity;
    }

    public long loads() {
        return this.loads;
    }

    /** Lookups served from a model already resident. */
    public long reused() {
        return this.reused;
    }

    public long evictions() {
        return this.evictions;
    }

    /** Evictions declined because the entry was still referenced. */
    public long evictionRefusals() {
        return this.evictionRefusals;
    }

    /** Entries currently resident, referenced or not. */
    public int residentCount() {
        return this.entries.size();
    }

    /**
     * Returns the model for a block, loading it if necessary, and takes a reference.
     *
     * <p>The caller must {@link #release} it when the section holding it is rebuilt or unloaded. A
     * reference that is never released is not a leak in the usual sense -- the entry stays correct --
     * but it pins the entry so it can never be evicted, which reaches the same outcome by another route.
     */
    public T acquire(int blockId, Loader<T> loader) {
        Entry<T> entry = this.entries.get(blockId);
        if (entry == null) {
            trimToCapacity();
            entry = new Entry<>(loader.load(blockId));
            this.entries.put(blockId, entry);
            this.loads++;
        } else {
            this.reused++;
        }
        entry.references++;
        return entry.model;
    }

    /**
     * Drops a reference.
     *
     * @return true if the entry is now unreferenced and eligible for eviction
     */
    public boolean release(int blockId) {
        Entry<T> entry = this.entries.get(blockId);
        if (entry == null || entry.references == 0) {
            return false;
        }
        entry.references--;
        return entry.references == 0;
    }

    public boolean isResident(int blockId) {
        return this.entries.containsKey(blockId);
    }

    public int referenceCount(int blockId) {
        Entry<T> entry = this.entries.get(blockId);
        return entry == null ? 0 : entry.references;
    }

    /**
     * Makes room for one more entry by dropping unreferenced ones.
     *
     * <p>If everything resident is referenced, nothing is dropped and the cache simply grows past its
     * capacity. That is the right answer: the capacity is a target for reclaimable memory, not a hard
     * limit that justifies discarding a model loaded sections are using. Refusing and counting the
     * refusal is what makes the situation visible rather than silent.
     */
    private void trimToCapacity() {
        if (this.entries.size() < this.capacity) {
            return;
        }
        Iterator<Map.Entry<Integer, Entry<T>>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext() && this.entries.size() >= this.capacity) {
            Map.Entry<Integer, Entry<T>> candidate = iterator.next();
            if (candidate.getValue().references > 0) {
                this.evictionRefusals++;
                continue;
            }
            iterator.remove();
            this.evictions++;
        }
    }

    /**
     * Drops every unreferenced entry.
     *
     * @return how many were dropped
     */
    public int evictUnreferenced() {
        int dropped = 0;
        Iterator<Map.Entry<Integer, Entry<T>>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().references == 0) {
                iterator.remove();
                this.evictions++;
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Drops every unreferenced entry and leaves referenced ones alone.
     *
     * <p>Not a full wipe, and deliberately: a referenced entry belongs to loaded sections, and clearing
     * it would be the same mistake an eviction would be. A caller that genuinely wants everything gone
     * has to release the references first, which means unloading the sections -- the honest order.
     */
    public void clear() {
        Iterator<Map.Entry<Integer, Entry<T>>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Entry<T>> candidate = iterator.next();
            if (candidate.getValue().references > 0) {
                this.evictionRefusals++;
                continue;
            }
            iterator.remove();
        }
        this.loads = 0;
        this.reused = 0;
    }
}
