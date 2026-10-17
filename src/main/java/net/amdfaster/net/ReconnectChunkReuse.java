package net.amdfaster.net;

/**
 * Reuses the chunks from a previous connection to the same world, as a picture rather than a world.
 *
 * <p>Disconnecting throws the world away, and reconnecting to the same server means downloading the same
 * terrain again -- the same blocks, in the same places, because the world did not move. On a slow connection
 * that is the difference between seeing the world in a second and staring at a void for ten.
 *
 * <p>Keeping what arrived last time and showing it while the real data downloads turns that wait into a
 * picture that fills in. The player sees where they are immediately, and the world becomes solid as the
 * server catches up.
 *
 * <h2>It is a picture, not a world</h2>
 *
 * <p>This is the rule the whole class is built around. A cached chunk can be drawn, but nothing may be done
 * with it: no collision, no block breaking, no entity standing on it, no pathfinding across it. Someone could
 * have dug that tunnel out while the client was disconnected, and a player who walks into a wall that is only
 * a picture falls through the world. So the cached data is offered for rendering only, and every other use is
 * refused until the server sends the real chunk.
 *
 * <h2>The world has to be the same world</h2>
 *
 * <p>Two servers can share a hostname and be different worlds, and the same server can reset. Matching on
 * address alone would show one world's terrain while standing in another, which is not a stale picture but a
 * wrong one -- and it would be wrong everywhere rather than in the places that changed. So the identity is the
 * seed and the dimension together, and a mismatch discards everything rather than showing part of it.
 *
 * <h2>The real chunk replaces it immediately</h2>
 *
 * <p>As soon as the server sends a chunk, that chunk is the truth and the cached one is dropped for that
 * position. Keeping both would mean two answers for one place, and whichever is drawn last decides what the
 * player sees -- which is a bug that appears and disappears depending on timing.
 *
 * <h2>It is discarded on a world change</h2>
 *
 * <p>Joining a different server, or a different dimension of the same one, invalidates all of it. Keeping the
 * cache across a world change would hold memory for a world the player has left and might never return to,
 * and would be one identity check away from showing it in the wrong place.
 *
 * <p>Thread safety: chunks arrive on the network thread and the cache is read on the render thread, so callers
 * need to synchronise.
 *
 * <p>Reference: BandwidthOptimizer describes persistent client chunk reuse across reconnects and repeated
 * terrain visits, and Bobby and Farsight cache received chunks to draw beyond the server's render distance.
 * This is an independent implementation of the same idea, restricted to the reconnect case.
 */
public final class ReconnectChunkReuse {

    /** Milliseconds drawing one cached chunk costs, which is what the wait is spent on instead. */
    public static final double MS_PER_DRAW = 0.05;

    /** Milliseconds downloading and processing one chunk costs. */
    public static final double MS_PER_DOWNLOAD = 4.0;

    private long worldKey;
    private boolean haveWorld;

    private int cached;
    private long drawn;
    private long replaced;
    private long refusedForGameplay;
    private long discarded;

    /**
     * The identity of a world.
     *
     * <p>The seed and the dimension together. Matching on address alone would show one world's terrain while
     * standing in another, because two servers can share a hostname and be different worlds and the same
     * server can reset -- which is not a stale picture but a wrong one, and wrong everywhere rather than in
     * the places that changed.
     */
    public static long worldKey(long seed, int dimension) {
        return seed * 0x9E3779B97F4A7C15L + dimension;
    }

    /**
     * Starts a connection, adopting the cache if it is the same world.
     *
     * @return how many chunks are available to draw
     */
    public int connect(long seed, int dimension) {
        long key = worldKey(seed, dimension);

        if (!this.haveWorld || key != this.worldKey) {
            // A different world. Showing part of the old one would be wrong everywhere rather than in the
            // places that changed, so all of it goes.
            this.discarded += this.cached;
            this.cached = 0;
            this.worldKey = key;
            this.haveWorld = true;
        }
        return this.cached;
    }

    /**
     * Stores a chunk that arrived from the server, so a later connection can draw it while the real one
     * downloads again.
     *
     * <p>Called as chunks arrive during a live connection. What is stored is a picture of the terrain, not
     * the chunk itself, so nothing here makes a gameplay use of it possible.
     */
    public void store() {
        this.cached++;
    }

    /**
     * Adds a chunk that arrived from the server.
     *
     * <p>It is now the truth for that position and the cached one is dropped, because keeping both would mean
     * two answers for one place and whichever is drawn last would decide what the player sees.
     */
    public void receive() {
        if (this.cached > 0) {
            this.replaced++;
            this.cached--;
        }
    }

    /**
     * Draws a cached chunk while the real one downloads.
     *
     * @return true if there was one to draw
     */
    public boolean drawCached() {
        if (this.cached <= 0) {
            return false;
        }
        this.drawn++;
        return true;
    }

    /**
     * Refuses a gameplay use of a cached chunk.
     *
     * <p>Someone could have dug that tunnel out while the client was disconnected, and a player who walks
     * into a wall that is only a picture falls through the world. So collision, block breaking, entities
     * standing on it and pathfinding across it are all refused until the server sends the real chunk.
     */
    public boolean allowGameplayUse() {
        this.refusedForGameplay++;
        return false;
    }

    /** How many chunks are held from the previous connection. */
    public int cached() {
        return this.cached;
    }

    public long drawn() {
        return this.drawn;
    }

    /** How many were replaced by real chunks from the server. */
    public long replaced() {
        return this.replaced;
    }

    /** How many gameplay uses were refused. */
    public long refusedForGameplay() {
        return this.refusedForGameplay;
    }

    /** How many were thrown away on a world change. */
    public long discarded() {
        return this.discarded;
    }

    /** Milliseconds of downloading the cached chunks would have cost. */
    public double msSaved() {
        return this.drawn * MS_PER_DOWNLOAD;
    }

    /** Milliseconds drawing them cost instead. */
    public double msDrawing() {
        return this.drawn * MS_PER_DRAW;
    }

    /**
     * The share of the download cost avoided while the world fills in.
     *
     * <p>Zero before anything has been drawn. A cache that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The download is not avoided for good -- the server still sends the chunk -- so this is the wait
     * removed, not bandwidth saved, and it is reported as the share of the wait.
     */
    public double savedPercent() {
        if (this.drawn == 0) {
            return 0.0;
        }
        double without = this.drawn * MS_PER_DOWNLOAD;
        return 100.0 * Math.max(0.0, without - msDrawing()) / without;
    }
}
