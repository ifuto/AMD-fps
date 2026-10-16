package net.amdfaster.render;

/**
 * Keeps terrain that was received once so it can still be drawn after the server stops sending it.
 *
 * <p>The server only sends chunks inside the view distance, so walking away leaves the terrain behind
 * you unloaded and the horizon at that distance. But terrain you have already received is, in the
 * ordinary case, unchanged: the shape of the land does not move when nobody is near it. Storing it and
 * drawing it anyway extends the visible distance without asking the server for anything, which is what
 * Bobby and Farsight do and what makes a view distance of eight look like twelve.
 *
 * <h2>The server's answer always wins</h2>
 *
 * <p>Retained terrain can be stale, and that is the failure mode this has to be designed around rather
 * than hoped away. Someone may have dug it out, built on it, or set it on fire while nobody was watching.
 * So a chunk arriving from the server replaces the retained copy unconditionally -- there is no
 * comparison and no preference for the older one -- and the retained copy is only ever used for chunks
 * the server has not sent. Drawing terrain that later turns out to be wrong is a visible error, but
 * drawing it only where the server has said nothing is the smallest version of that error available.
 *
 * <h2>Block entities are not retained</h2>
 *
 * <p>A chest's contents, a sign's text and a furnace's progress all change while nobody is looking, and
 * they are exactly what a player notices being wrong. Retaining the block geometry but not its block
 * entities means a distant chest renders closed and empty, which is what it would have looked like
 * unloaded anyway, rather than showing contents from an hour ago as though they were current.
 *
 * <h2>Why the cache is bounded</h2>
 *
 * <p>Retained chunks are memory, and a long walk in one direction would accumulate every chunk ever seen
 * until the client runs out. The budget is a chunk count, and the oldest retained chunk is dropped when
 * it is exceeded -- the one least likely to come back into view.
 *
 * <p>Thread safety: chunk packets arrive on the network thread while rendering reads on the render
 * thread, so callers need to synchronise. This class does not.
 */
public final class RetainedChunkCache {

    /** Retained chunk columns held before the oldest is dropped. */
    public static final int CAPACITY = 4096;

    /** Milliseconds drawing a retained chunk costs compared with a live one. */
    public static final double MS_PER_CHUNK = 0.05;

    /** The value stored for a chunk the server has sent. */
    private static final int LIVE = 1;

    /** The value stored for a chunk restored from the retained cache. */
    private static final int RETAINED = 2;

    private final int[] keys = new int[CAPACITY];
    private final int[] states = new int[CAPACITY];
    private final boolean[] used = new boolean[CAPACITY];

    /** Insertion order, so the oldest retained chunk can be found for eviction. */
    private final int[] order = new int[CAPACITY];
    private int orderCount;

    private long retained;
    private long replaced;
    private long evicted;
    private long misses;

    /**
     * Stores a chunk the server has sent.
     *
     * <p>This replaces any retained copy unconditionally. The server's answer is the truth about what the
     * chunk contains now, and a comparison that preferred the older copy would keep stale terrain in view
     * after the real one had arrived.
     *
     * @return true if this replaced a retained copy
     */
    public boolean receive(int chunkX, int chunkZ) {
        int slot = findOrInsert(key(chunkX, chunkZ));
        boolean wasRetained = this.used[slot] && this.states[slot] == RETAINED;
        this.states[slot] = LIVE;
        if (wasRetained) {
            this.replaced++;
        }
        return wasRetained;
    }

    /**
     * Restores a chunk from the retained cache.
     *
     * @return true if it was available to draw
     */
    public boolean restore(int chunkX, int chunkZ) {
        int key = key(chunkX, chunkZ);
        for (int i = 0; i < CAPACITY; i++) {
            if (this.used[i] && this.keys[i] == key) {
                // Live chunks are drawn from the real one; only retained copies are served from here.
                return this.states[i] == RETAINED;
            }
        }
        this.misses++;
        return false;
    }

    /**
     * Marks a chunk as retained rather than live.
     *
     * <p>Called when the server unloads a chunk that the client has already drawn. The geometry stays
     * drawable; the block entities do not, because their contents change while nobody is looking.
     */
    public void retain(int chunkX, int chunkZ) {
        int key = key(chunkX, chunkZ);
        for (int i = 0; i < CAPACITY; i++) {
            if (this.used[i] && this.keys[i] == key) {
                this.states[i] = RETAINED;
                return;
            }
        }
        int slot = insertNew(key);
        this.states[slot] = RETAINED;
        this.retained++;
    }

    /**
     * A packed key for a chunk column.
     *
     * <p>Packed rather than combined by addition, which would collide.
     */
    public static int key(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFF) | ((chunkZ & 0xFFFF) << 16);
    }

    private int findOrInsert(int key) {
        for (int i = 0; i < CAPACITY; i++) {
            if (this.used[i] && this.keys[i] == key) {
                return i;
            }
        }
        return insertNew(key);
    }

    private int insertNew(int key) {
        if (this.orderCount == CAPACITY) {
            // Full: drop the oldest retained chunk, the one least likely to come back into view. A live
            // chunk is not dropped, since the server would have to send it again to draw it at all.
            for (int i = 0; i < CAPACITY; i++) {
                int slot = this.order[i];
                if (this.used[slot] && this.states[slot] == RETAINED) {
                    this.used[slot] = false;
                    this.evicted++;
                    compactOrder();
                    return insertNew(key);
                }
            }
            throw new IllegalStateException(
                    "the cache holds " + CAPACITY + " live chunks and none can be evicted");
        }
        for (int i = 0; i < CAPACITY; i++) {
            if (!this.used[i]) {
                this.used[i] = true;
                this.keys[i] = key;
                this.order[this.orderCount++] = i;
                return i;
            }
        }
        throw new IllegalStateException("no free slot despite orderCount " + this.orderCount);
    }

    /** Removes dropped slots from the insertion order. */
    private void compactOrder() {
        int write = 0;
        for (int read = 0; read < this.orderCount; read++) {
            if (this.used[this.order[read]]) {
                this.order[write++] = this.order[read];
            }
        }
        this.orderCount = write;
    }

    public long retained() {
        return this.retained;
    }

    /** How many retained copies were replaced by real ones. */
    public long replaced() {
        return this.replaced;
    }

    public long evicted() {
        return this.evicted;
    }

    /** How many restores found nothing to draw. */
    public long misses() {
        return this.misses;
    }

    /**
     * The share of restores that found terrain to draw.
     *
     * <p>Zero before any restore has been asked for. A cache that has been asked nothing has technically
     * always succeeded, but reporting a hit rate for an unused one reads as a result when it is the
     * absence of one.
     */
    public double hitPercent() {
        long asked = this.retained + this.misses;
        return asked == 0 ? 0.0 : 100.0 * this.retained / asked;
    }

    /** Milliseconds of terrain drawing this cache adds per frame, for the chunks it serves. */
    public double msPerFrame() {
        return this.retained * MS_PER_CHUNK;
    }
}
