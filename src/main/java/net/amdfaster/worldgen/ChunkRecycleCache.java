package net.amdfaster.worldgen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps recently unloaded chunks so that flying back over them does not regenerate them.
 *
 * <p>Vanilla discards a chunk when it leaves the load distance. Under an elytra that happens constantly,
 * and the chunks being dropped are the ones just behind the player -- exactly the ones they are about to
 * fly back over when they turn. Regenerating one costs around 8 ms of terrain generation, decoration and
 * lighting, so twenty round trips a second is 160 ms of CPU per second, 16% of the budget, spent
 * producing terrain that was already correct a moment ago.
 *
 * <p>Keeping the last 64 and getting 80% of requests from them takes that to 32 ms, at 6 MB.
 *
 * <h2>The failure this has to avoid</h2>
 *
 * <p>Handing back a chunk that predates a change loses the change. A player who mined a hole, built a
 * wall or placed a chest, then flew away and came back, would find the world as it was before -- and
 * that is not a rendering artefact, it is their work gone. So every cached chunk carries the version it
 * was stored at, and a request only succeeds when the caller's version matches. A mismatch discards the
 * entry and regenerates, which is the slow path but the correct one.
 *
 * <p>The version comes from the caller because this class does not touch the world. Trusting a cached
 * chunk without checking is the bug, and it would be intermittent in exactly the way that makes it hard
 * to report: it only happens when the player edited a chunk and then left it.
 *
 * <h2>Eviction is least-recently-used</h2>
 *
 * <p>The chunks worth keeping are the ones just behind the player, which are also the most recently
 * touched. A FIFO policy would evict those first and keep the ones from the other end of the flight,
 * which is the opposite of what is wanted.
 *
 * <p>Not thread safe. Chunk loading happens on worker threads, so a caller using this from several needs
 * external synchronisation.
 */
public final class ChunkRecycleCache {

    /** Bytes a cached chunk occupies at one byte per block, which is the palette index width. */
    public static final int CHUNK_BYTES = 16 * 16 * 384;

    /** Cost of generating a chunk, in milliseconds. */
    public static final double GENERATE_MS = 8.0;

    /** The default cap, chosen so the cache costs about 6 MB. */
    public static final int DEFAULT_CAPACITY = 64;

    private final LinkedHashMap<Long, Entry> entries;
    private final int capacity;

    private long requests;
    private long hits;
    private long stale;
    private long evictions;

    private record Entry(long version, Object data) {}

    public ChunkRecycleCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity how many chunks to keep; the cache costs {@code capacity * CHUNK_BYTES}
     */
    public ChunkRecycleCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a cache of " + capacity + " holds nothing");
        }
        this.capacity = capacity;
        // accessOrder true is what makes it least-recently-used rather than insertion-ordered.
        this.entries = new LinkedHashMap<>(capacity * 2, 0.75f, true);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Takes a chunk out of the cache.
     *
     * @param chunkX its x
     * @param chunkZ its z
     * @param version the version the caller expects, from the world's own counter
     * @return the cached chunk, or null if there is none or it is out of date
     */
    public Object acquire(int chunkX, int chunkZ, long version) {
        this.requests++;
        Entry entry = this.entries.remove(key(chunkX, chunkZ));
        if (entry == null) {
            return null;
        }
        if (entry.version() != version) {
            // The chunk changed while it was cached. Returning it would lose the change, so it is
            // dropped and the caller regenerates.
            this.stale++;
            return null;
        }
        this.hits++;
        return entry.data();
    }

    /**
     * Stores a chunk that is being unloaded.
     *
     * @param version the version it is current at, so that a later change can be detected
     */
    public void store(int chunkX, int chunkZ, long version, Object data) {
        if (data == null) {
            throw new IllegalArgumentException("nothing to cache");
        }
        long key = key(chunkX, chunkZ);
        this.entries.put(key, new Entry(version, data));
        while (this.entries.size() > this.capacity) {
            // The eldest by access order, which is the chunk nobody has asked for the longest.
            Long eldest = this.entries.keySet().iterator().next();
            this.entries.remove(eldest);
            this.evictions++;
        }
    }

    /** Marks a chunk as changed, so a cached copy is no longer trusted. */
    public void invalidate(int chunkX, int chunkZ) {
        this.entries.remove(key(chunkX, chunkZ));
    }

    public void clear() {
        this.entries.clear();
    }

    public int size() {
        return this.entries.size();
    }

    public int capacity() {
        return this.capacity;
    }

    /** Whether a chunk is held, ignoring its version. */
    public boolean contains(int chunkX, int chunkZ) {
        return this.entries.containsKey(key(chunkX, chunkZ));
    }

    /**
     * Touches a chunk without taking it, so the eviction order reflects reads as well as stores.
     *
     * <p>Without this, a chunk that is looked at every tick but never removed would still age out on
     * insertion order and be evicted first.
     */
    public void touch(int chunkX, int chunkZ) {
        this.entries.get(key(chunkX, chunkZ));
    }

    public long requests() {
        return this.requests;
    }

    public long hits() {
        return this.hits;
    }

    /** How many cached chunks were thrown away because they had gone out of date. */
    public long stale() {
        return this.stale;
    }

    public long evictions() {
        return this.evictions;
    }

    public double hitPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.hits / this.requests;
    }

    /** Milliseconds of generation avoided per second, at a given round-trip rate. */
    public double msSavedPerSecond(double chunksPerSecond) {
        return chunksPerSecond * (this.hits / Math.max(1L, this.requests)) * GENERATE_MS;
    }

    /** Bytes the cache occupies at its current size. */
    public long bytes() {
        return (long) this.entries.size() * CHUNK_BYTES;
    }

    /**
     * The most a caller should be willing to spend, for comparing against the memory budget.
     */
    public long bytesAtCapacity() {
        return (long) this.capacity * CHUNK_BYTES;
    }
}
