package net.amdfaster.mem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reuses repeated short NBT strings instead of allocating a new one each time.
 *
 * <p>Every chunk, item stack and entity carries NBT data, and much of it is strings: the name of a block
 * entity's id, a custom name, a list of enchantment ids. The same handful of values appears thousands of
 * times -- {@code minecraft:chest} once per chest in the world, {@code id} and {@code Count} in every item
 * stack -- and each occurrence is a separate String object with its own array of characters.
 *
 * <p>Reusing one object per distinct value removes the duplicates. In a large modpack with thousands of
 * blockstates and item stacks that is a meaningful part of the heap, and a smaller heap means the collector
 * runs less often and finishes sooner, which is what the periodic micro-freeze during chunk loading actually
 * is.
 *
 * <h2>Only short strings</h2>
 *
 * <p>The cache exists to remove duplicates of the values that repeat. A written book's text appears once, so
 * caching it costs a map entry and saves nothing, and it would evict something that does repeat. The length
 * limit keeps the cache full of the values it is for.
 *
 * <h2>Bounded, and the least recently used leaves</h2>
 *
 * <p>An unbounded cache would grow for the whole session and become its own memory problem, which is the
 * opposite of the point. The bound is a few thousand entries, which holds every distinct short value a
 * modpack uses with room to spare, and the least recently used entry leaves when it is exceeded -- the one
 * least likely to be asked for again.
 *
 * <h2>Strings are immutable, so sharing is safe</h2>
 *
 * <p>Two NBT tags holding the same String object cannot interfere, because neither can change it. That is
 * what makes this more than a performance trick: there is no case where sharing produces a different value
 * than not sharing.
 *
 * <h2>Why this never calls System.gc()</h2>
 *
 * <p>A mod that triggers collection is trading a heap that fills gradually for a pause it chose the timing
 * of, and the pause is not shorter for having been scheduled -- it is the same work, done at a moment the
 * mod picked rather than the runtime. Reducing what is allocated means the runtime collects less often on
 * its own, which is a smaller total cost rather than a rearranged one.
 *
 * <p>Thread safety: NBT is read on the server thread and on worker threads during chunk loading, so callers
 * need to synchronise.
 */
public final class NbtStringCache {

    /** Entries held before the least recently used leaves. */
    public static final int CAPACITY = 2048;

    /** Characters above which a string is not worth caching. */
    public static final int MAX_LENGTH = 64;

    /** Bytes a String object and its array occupy, beyond the characters. */
    public static final int BYTES_PER_STRING = 40;

    /**
     * Access-ordered, so the least recently used entry is the one to evict.
     *
     * <p>{@code LinkedHashMap} with the last flag set orders by access rather than insertion, which is what
     * "least recently used" means; insertion order would evict the oldest entry even if it is used every
     * tick.
     */
    private final LinkedHashMap<String, String> cache =
            new LinkedHashMap<>(CAPACITY, 0.75f, true);

    private long requests;
    private long reused;
    private long cached;
    private long evicted;
    private long skippedTooLong;

    /**
     * Returns a shared instance of a string.
     *
     * @return the same object for equal values
     */
    public String intern(String value) {
        if (value == null) {
            throw new IllegalArgumentException("a value is required");
        }
        this.requests++;

        if (value.length() > MAX_LENGTH) {
            // A written book's text appears once, so caching it costs a map entry and saves nothing, and it
            // would evict something that does repeat.
            this.skippedTooLong++;
            return value;
        }

        String existing = this.cache.get(value);
        if (existing != null) {
            this.reused++;
            return existing;
        }
        if (this.cache.size() >= CAPACITY) {
            // An unbounded cache would grow for the whole session and become its own memory problem, which
            // is the opposite of the point.
            this.cache.remove(this.cache.keySet().iterator().next());
            this.evicted++;
        }
        this.cache.put(value, value);
        this.cached++;
        return value;
    }

    /** How many distinct strings are held. */
    public int size() {
        return this.cache.size();
    }

    /** Drops every cached string. */
    public void clear() {
        this.cache.clear();
    }

    public long requests() {
        return this.requests;
    }

    /** How many requests were served an object that already existed. */
    public long reused() {
        return this.reused;
    }

    /** How many distinct strings were cached. */
    public long cached() {
        return this.cached;
    }

    /** How many were dropped to make room. */
    public long evicted() {
        return this.evicted;
    }

    /** How many were left alone for being too long to be worth caching. */
    public long skippedTooLong() {
        return this.skippedTooLong;
    }

    public double reusePercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.reused / this.requests;
    }

    /**
     * Bytes of duplicate strings avoided.
     *
     * <p>Every reuse is one fewer String object and one fewer character array.
     */
    public long bytesSaved() {
        return this.reused * (BYTES_PER_STRING + 2L * (MAX_LENGTH / 2));
    }

    /**
     * The share of the allocations avoided.
     *
     * <p>Zero before any string has been requested. A cache that has been asked nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        if (this.requests == 0) {
            return 0.0;
        }
        return 100.0 * this.reused / this.requests;
    }
}
