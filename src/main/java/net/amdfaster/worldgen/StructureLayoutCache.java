package net.amdfaster.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remembers where a structure's pieces were placed, so the search is not run again.
 *
 * <p>Placing a village means choosing where each house goes: pick a position, check it does not overlap the
 * last one, check the ground is level enough, and if it fails try somewhere else. That search is repeated
 * for every structure in every chunk the world generates, and it is the most expensive part of generating a
 * chunk that contains one.
 *
 * <p>The result depends only on the seed and the position, so a structure that has been laid out once can be
 * laid out from memory every time after.
 *
 * <h2>The key is the seed and the position, not just one</h2>
 *
 * <p>Two worlds with different seeds have different villages in the same chunk, and one world has different
 * villages in different chunks. A key of either alone would serve one world's layout to the other, and the
 * player would see a village that does not match the terrain it is standing on -- houses floating, roads
 * ending in hills. Both are in the key.
 *
 * <h2>The structure type is in the key too</h2>
 *
 * <p>A village and a stronghold in the same chunk are different layouts, and a key without the type would
 * let one be served for the other. That would not be a subtle error: it would be a stronghold where the
 * village should be.
 *
 * <h2>A failed search is cached as well</h2>
 *
 * <p>Most chunks have no structure, and the search that establishes that is the same work as the one that
 * finds something. Repeating it for every chunk the player walks past would cost as much as generating
 * structures that are not there. Remembering that a chunk has none is the common case, not the exception.
 *
 * <h2>Why this is not the same as the piece cache</h2>
 *
 * <p>{@link StructurePieceCache} caches the templates -- what a house is made of. This caches where the
 * houses go. A layout can be reused with a template that was built separately, and a template can be shared
 * between layouts in different chunks. The two are independent and both are needed.
 *
 * <p>Thread safety: generation runs on worker threads and no two workers generate the same chunk, so the map
 * is only written from one thread at a time per key. Callers generating different chunks concurrently need
 * to synchronise on the map itself.
 */
public final class StructureLayoutCache {

    /** Milliseconds searching for one structure's layout costs. */
    public static final double MS_PER_SEARCH = 8.0;

    /** Milliseconds reading a cached layout costs. */
    public static final double MS_PER_HIT = 0.01;

    /** Structures attempted per chunk on average. */
    public static final int TYPES_PER_CHUNK = 4;

    /** The value stored for a chunk that has no structure of a given type. */
    private static final int[] NONE = new int[0];

    private final Map<Long, int[]> layouts = new HashMap<>();

    private long lookups;
    private long hits;
    private long searches;
    private long empty;

    /**
     * The key for a structure's layout.
     *
     * <p>The seed, the chunk position and the structure type. Two worlds with different seeds have different
     * villages in the same chunk, one world has different villages in different chunks, and a village and a
     * stronghold in the same chunk are different layouts.
     */
    public static long key(long seed, int chunkX, int chunkZ, int structureType) {
        long mixed = seed * 0x9E3779B97F4A7C15L;
        mixed ^= (long) chunkX * 0xC2B2AE3D27D4EB4FL;
        mixed ^= (long) chunkZ * 0x165667B19E3779F9L;
        mixed ^= (long) structureType * 0x27D4EB2F165667C5L;
        return mixed;
    }

    /**
     * Returns a structure's layout, searching for it if it has not been found before.
     *
     * @return the piece positions, or an empty array if the chunk has no structure of that type
     */
    public int[] layout(long seed, int chunkX, int chunkZ, int structureType,
            Supplier<int[]> search) {
        if (search == null) {
            throw new IllegalArgumentException("a search is required");
        }
        long key = key(seed, chunkX, chunkZ, structureType);
        this.lookups++;

        int[] cached = this.layouts.get(key);
        if (cached != null) {
            this.hits++;
            if (cached.length == 0) {
                // Most chunks have no structure, and the search that establishes that is the same work as
                // the one that finds something.
                this.empty++;
            }
            return cached;
        }

        int[] found = search.get();
        if (found == null) {
            throw new IllegalStateException("the search for " + key + " produced no result");
        }
        this.layouts.put(key, found);
        this.searches++;
        if (found.length == 0) {
            this.empty++;
        }
        return found;
    }

    /** How many layouts are cached. */
    public int size() {
        return this.layouts.size();
    }

    /** Drops every cached layout, when the world changes. */
    public void clear() {
        this.layouts.clear();
    }

    public long lookups() {
        return this.lookups;
    }

    public long hits() {
        return this.hits;
    }

    /** How many searches were actually run. */
    public long searches() {
        return this.searches;
    }

    /** How many lookups found that a chunk has no structure of that type. */
    public long empty() {
        return this.empty;
    }

    public double hitPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.hits / this.lookups;
    }

    /** Milliseconds spent on layout work. */
    public double msSpent() {
        return this.searches * MS_PER_SEARCH + this.hits * MS_PER_HIT;
    }

    /** Milliseconds searching for every layout would cost. */
    public double msWithoutCache() {
        return this.lookups * MS_PER_SEARCH;
    }

    /**
     * The share of the layout search avoided.
     *
     * <p>Zero before any lookup has been made. A cache that has been asked nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutCache();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * (without - msSpent()) / without;
    }
}
