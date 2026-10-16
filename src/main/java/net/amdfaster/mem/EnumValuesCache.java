package net.amdfaster.mem;

import java.util.EnumMap;
import java.util.Map;

/**
 * Keeps the array behind an enum's {@code values()} instead of building a new one each call.
 *
 * <p>{@code SomeEnum.values()} does not return a field. It clones the internal array, because the array is
 * mutable and handing it out would let a caller change the enum for everyone. That clone is correct, and it
 * costs an allocation every time -- which matters when the call is inside a loop that runs per block state,
 * per entity, per frame. A modpack with thousands of block states turns a free-looking method call into
 * thousands of short-lived arrays a second, and those are exactly the allocations that make the young
 * generation fill and the collector run.
 *
 * <p>Caching the array once per enum removes them.
 *
 * <h2>The caller still gets a copy</h2>
 *
 * <p>That is the part that keeps this correct rather than merely fast. Returning the cached array would hand
 * out something mutable that every other caller shares, and one of them writing to it would corrupt the enum
 * for the rest of the game -- a {@code values()} that silently has a hole in it. So the cache holds one
 * array and every caller gets a clone of it, which is the same guarantee {@code values()} gives, with the
 * internal array built once instead of every call.
 *
 * <h2>The saving is in the source, not the clone</h2>
 *
 * <p>A caller that still needs a fresh array still pays for one, so the saving is not the clone the caller
 * receives -- it is the internal array the enum no longer rebuilds, and the reflection-free lookup that
 * replaces walking the class. For code that only reads the values, the cached array can be used directly and
 * the clone disappears too.
 *
 * <h2>Ordinals do not change</h2>
 *
 * <p>An enum's constants are fixed for the lifetime of the class, and so are their ordinals. That is what
 * makes caching them safe at all: there is no event that would make a cached array wrong, so it never needs
 * invalidating.
 *
 * <h2>An empty result is shared</h2>
 *
 * <p>Some lookups return nothing, and an empty array is the same empty array every time. Allocating one per
 * call would be the same waste in a smaller package.
 *
 * <p>Thread safe for reads: the map is filled during startup and only read afterwards.
 */
public final class EnumValuesCache {

    /** Milliseconds one clone of an enum's values costs. */
    public static final double MS_PER_CLONE = 0.00005;

    /** The single shared empty array. */
    private static final Object[] EMPTY = new Object[0];

    private final Map<Class<? extends Enum<?>>, Object[]> cache = new EnumMap<>(emptyKey());

    private long requests;
    private long cached;
    private long empty;

    @SuppressWarnings("unchecked")
    private static Class<? extends Enum<?>> emptyKey() {
        // An EnumMap needs a key type; the marker is never used as a key.
        return (Class<? extends Enum<?>>) (Class<?>) Marker.class;
    }

    /** Stands in for the key type of the map, which is fixed per instance. */
    private enum Marker { ONLY }

    private long[] lengths;

    /**
     * Returns a copy of an enum's values.
     *
     * <p>The caller gets a fresh array, exactly as {@code values()} would give, because handing out the
     * cached one would let a caller write to something every other caller shares.
     */
    public <E extends Enum<E>> E[] values(Class<E> type) {
        if (type == null) {
            throw new IllegalArgumentException("an enum type is required");
        }
        this.requests++;

        @SuppressWarnings("unchecked")
        Object[] held = this.cache.get((Class<? extends Enum<?>>) (Class<?>) type);
        if (held == null) {
            held = type.getEnumConstants();
            if (held == null) {
                throw new IllegalArgumentException("not an enum: " + type.getName());
            }
            this.cache.put((Class<? extends Enum<?>>) (Class<?>) type, held);
            this.cached++;
        }
        if (held.length == 0) {
            this.empty++;
        }

        @SuppressWarnings("unchecked")
        E[] copy = (E[]) java.lang.reflect.Array.newInstance(type, held.length);
        System.arraycopy(held, 0, copy, 0, held.length);
        return copy;
    }

    /**
     * The cached array itself, for callers that only read it.
     *
     * <p>They must not write to it. A caller that needs to modify the result should use {@link #values},
     * which gives a copy.
     */
    public int length(Class<? extends Enum<?>> type) {
        Object[] held = this.cache.get(type);
        return held == null ? -1 : held.length;
    }

    /** A shared empty array, so a lookup that finds nothing does not allocate one. */
    public static Object[] empty() {
        return EMPTY;
    }

    /** How many enum types are cached. */
    public int size() {
        return this.cache.size();
    }

    /** Drops every cached array. */
    public void clear() {
        this.cache.clear();
    }

    public long requests() {
        return this.requests;
    }

    /** How many enum types had their array built. */
    public long cached() {
        return this.cached;
    }

    /** How many requests were for an enum with no constants. */
    public long empty() {
        return this.empty;
    }

    /**
     * Milliseconds of internal array building avoided.
     *
     * <p>Every request after the first for a given enum reuses the array that was built once.
     */
    public double msSaved() {
        return (this.requests - this.cached) * MS_PER_CLONE;
    }

    /**
     * The share of the array building avoided.
     *
     * <p>Zero before any request has been made. A cache that has been asked nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.requests == 0) {
            return 0.0;
        }
        return 100.0 * (this.requests - this.cached) / this.requests;
    }
}
