package net.amdfaster.resource;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers where a resource was found, so the search is not repeated every frame.
 *
 * <p>Drawing a block means finding its texture, and finding a texture means resolving an identifier against
 * the resource packs: which pack provides it, in what order they are applied, whether an override exists.
 * That search happens for every block drawn, every frame, and its answer changes only when a resource pack
 * is reloaded -- which is a deliberate action the player takes from the options screen, not something that
 * happens during play.
 *
 * <p>Caching the resolved location turns the search into a map lookup for the rest of the session.
 *
 * <h2>A reload invalidates everything</h2>
 *
 * <p>Reloading resource packs is how a player applies a new texture pack, and a cache that survived it
 * would keep drawing the old textures -- the player would have installed a pack and seen no change, which
 * reads as the pack being broken or the game ignoring the setting. So a reload clears the cache entirely,
 * and the next frame repopulates it from the new packs.
 *
 * <h2>A failed lookup is cached too</h2>
 *
 * <p>A mod may reference a texture it does not ship, and searching for it every frame would repeat the same
 * failed search thousands of times a second. Remembering that it was not found costs one entry and stops
 * the repetition. The game then uses the missing-texture placeholder, which is what it would have used
 * anyway, and does so without paying for the search each time.
 *
 * <h2>The key is the whole identifier</h2>
 *
 * <p>{@code minecraft:block/stone} and {@code somemod:block/stone} are different resources, and a key of
 * the path alone would let one resolve to the other. That would not be a subtle error -- it would be a mod's
 * blocks drawn with vanilla textures, which looks like the mod failed to load.
 *
 * <h2>Why the cache is not bounded</h2>
 *
 * <p>The number of distinct resources a game draws is fixed by what is installed, and a long session does
 * not add to it. Bounding it would evict entries that the next frame needs, so the eviction would cost more
 * than the search it avoided.
 *
 * <p>Not thread safe. Lookups happen on the render and mesh threads, so a caller using this from several
 * needs one instance per thread.
 */
public final class ResourceLookupCache {

    /** Milliseconds resolving an identifier against the resource packs costs. */
    public static final double MS_PER_LOOKUP = 0.0005;

    /** Milliseconds a cached lookup costs. */
    public static final double MS_PER_HIT = 0.000005;

    /** The value stored for an identifier that was not found. */
    private static final String NOT_FOUND = "\u0000missing";

    private final Map<String, String> resolved = new HashMap<>();

    private long lookups;
    private long hits;
    private long resolvedCount;
    private long missing;
    private long reloads;

    /**
     * The key for a resource identifier.
     *
     * <p>The namespace and the path, because {@code minecraft:block/stone} and {@code somemod:block/stone}
     * are different resources and a key of the path alone would let one resolve to the other -- which would
     * be a mod's blocks drawn with vanilla textures, looking like the mod failed to load.
     */
    public static String key(String namespace, String path) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("a namespace is required");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("a path is required");
        }
        return namespace + ":" + path;
    }

    /**
     * Resolves an identifier, using the cached answer if there is one.
     *
     * @return the resolved location, or null if the resource does not exist
     */
    public String resolve(String namespace, String path,
            java.util.function.Supplier<String> search) {
        if (search == null) {
            throw new IllegalArgumentException("a search is required");
        }
        String key = key(namespace, path);
        this.lookups++;

        String cached = this.resolved.get(key);
        if (cached != null) {
            this.hits++;
            if (NOT_FOUND.equals(cached)) {
                // A mod may reference a texture it does not ship. Searching for it every frame would repeat
                // the same failed search thousands of times a second.
                this.missing++;
                return null;
            }
            return cached;
        }

        String found = search.get();
        this.resolved.put(key, found == null ? NOT_FOUND : found);
        if (found == null) {
            this.missing++;
        } else {
            this.resolvedCount++;
        }
        return found;
    }

    /**
     * Clears the cache, because the resource packs have been reloaded.
     *
     * <p>Reloading is how a player applies a new texture pack, and a cache that survived it would keep
     * drawing the old textures -- the player would have installed a pack and seen no change, which reads as
     * the pack being broken or the game ignoring the setting.
     */
    public void invalidate() {
        this.resolved.clear();
        this.reloads++;
    }

    /** How many resources are cached. */
    public int size() {
        return this.resolved.size();
    }

    public long lookups() {
        return this.lookups;
    }

    /** How many lookups were served from the cache. */
    public long hits() {
        return this.hits;
    }

    /** How many distinct resources were resolved. */
    public long resolvedCount() {
        return this.resolvedCount;
    }

    /** How many lookups asked for a resource that does not exist. */
    public long missing() {
        return this.missing;
    }

    /** How many times the packs were reloaded. */
    public long reloads() {
        return this.reloads;
    }

    public double hitPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * this.hits / this.lookups;
    }

    /** Milliseconds spent on lookups. */
    public double msSpent() {
        return this.hits * MS_PER_HIT + (this.lookups - this.hits) * MS_PER_LOOKUP;
    }

    /** Milliseconds resolving every lookup from the packs would cost. */
    public double msWithoutCache() {
        return this.lookups * MS_PER_LOOKUP;
    }

    /**
     * The share of the lookup cost avoided.
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
