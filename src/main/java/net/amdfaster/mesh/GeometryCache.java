package net.amdfaster.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

import net.amdfaster.dirty.SectionCoord;

/**
 * A bounded cache of built section geometry, so a section is not rebuilt when nothing about its
 * geometry changed.
 *
 * <p>What it saves. A section gets rebuilt for two unrelated reasons: its blocks changed, or its light
 * changed. Only the first changes geometry. When a torch is placed near a chunk border, the light
 * update touches every section within fifteen blocks, and without this cache each of those sections
 * regenerates its vertex data -- tens of thousands of quads -- to produce byte-identical geometry with
 * new light values baked in. Placing one block in Minecraft causes on the order of fifty chunk rebuilds
 * for exactly this reason, and rebuild is the dominant client stutter there is.
 *
 * <p>Why bounded rather than "keep everything built". At render distance 16 there are thousands of
 * loaded sections, and holding mesh data for all of them is more memory than the sections themselves.
 * The cache holds what is near the camera, which is what gets re-requested; a section at the edge of
 * render distance is far more likely to be unloaded than re-meshed.
 *
 * <p>Invalidation is by section and by column. By section for a block change. By column for a light
 * change that crosses a section boundary vertically, which is the common case for anything tall.
 *
 * <p>Not thread safe. Mesh builds run on worker threads but the cache itself is touched from the thread
 * that consumes finished builds.
 *
 * @param <M> the built mesh type
 */
public final class GeometryCache<M> {

    /** Sections held by default. */
    public static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final LinkedHashMap<Long, M> entries;

    private long hits;
    private long misses;
    private long evictions;
    private long invalidations;

    public GeometryCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        // Access order, so a get promotes the entry and eviction takes the least recently used. Insertion
        // order would evict by age, which is wrong here: a section the player keeps looking at is used
        // every frame and is exactly the one that must not be dropped.
        this.entries = new LinkedHashMap<Long, M>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, M> eldest) {
                if (size() > GeometryCache.this.capacity) {
                    GeometryCache.this.evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    public static <M> GeometryCache<M> withDefaultCapacity() {
        return new GeometryCache<>(DEFAULT_CAPACITY);
    }

    public int capacity() {
        return this.capacity;
    }

    public int size() {
        return this.entries.size();
    }

    public long hits() {
        return this.hits;
    }

    public long misses() {
        return this.misses;
    }

    public long evictions() {
        return this.evictions;
    }

    public long invalidations() {
        return this.invalidations;
    }

    /**
     * Fraction of lookups that found geometry already built.
     *
     * <p>Reported because whether the cache earns its memory is a measured question, not an assumed one.
     * A low rate means the invalidation is too aggressive and the cache is costing memory for nothing.
     */
    public double hitRate() {
        long total = this.hits + this.misses;
        return total == 0 ? 0.0 : (double) this.hits / total;
    }

    /** The cached mesh for a section, or null if it has to be built. */
    public M get(int sectionX, int sectionY, int sectionZ) {
        M mesh = this.entries.get(SectionCoord.key(sectionX, sectionY, sectionZ));
        if (mesh == null) {
            this.misses++;
        } else {
            this.hits++;
        }
        return mesh;
    }

    public boolean contains(int sectionX, int sectionY, int sectionZ) {
        return this.entries.containsKey(SectionCoord.key(sectionX, sectionY, sectionZ));
    }

    /** Stores a finished build. */
    public void put(int sectionX, int sectionY, int sectionZ, M mesh) {
        this.entries.put(SectionCoord.key(sectionX, sectionY, sectionZ), mesh);
    }

    /**
     * Drops one section's geometry.
     *
     * @return true if there was something to drop
     */
    public boolean invalidateSection(int sectionX, int sectionY, int sectionZ) {
        boolean removed = this.entries.remove(SectionCoord.key(sectionX, sectionY, sectionZ)) != null;
        if (removed) {
            this.invalidations++;
        }
        return removed;
    }

    /**
     * Drops every cached section in one column.
     *
     * <p>For a light change, which spreads vertically without bound: a torch at the bottom of a shaft
     * lights sections all the way up it, and each of those has stale baked light even though not one of
     * their blocks moved.
     *
     * @return how many sections were dropped
     */
    public int invalidateColumn(int sectionX, int sectionZ) {
        int removed = 0;
        var iterator = this.entries.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            if (SectionCoord.x(key) == sectionX && SectionCoord.z(key) == sectionZ) {
                iterator.remove();
                removed++;
            }
        }
        this.invalidations += removed;
        return removed;
    }

    public void clear() {
        this.entries.clear();
    }
}
