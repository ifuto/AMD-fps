package net.amdfaster.runtime;

import net.amdfaster.net.ChunkBatchAckPacer;
import net.amdfaster.net.EntityUpdateCoalescer;
import net.amdfaster.net.ReconnectChunkReuse;

/**
 * Runs the multiplayer network decisions in one place, driven by the connection's lifecycle.
 *
 * <p>Three of the network components only mean anything while connected to a server, and each of them has a
 * moment it has to be told about: the pacer has to know when a connection starts so it can begin from the
 * conservative end, the coalescer has to know when a tick ends so it can apply what it held, and the chunk
 * reuse has to know whether the world it is reconnecting to is the one it cached.
 *
 * <p>None of that is a decision the components can make themselves, and getting any of it wrong is not a
 * missed optimisation but a broken connection: a pacer that never resets floods the client on every join, a
 * coalescer whose tick never ends never applies anything, and a chunk reuse that cannot tell two worlds apart
 * shows the wrong terrain.
 *
 * <h2>The three are independent and none can substitute for another</h2>
 *
 * <p>The pacer controls how fast the server sends chunks, the coalescer removes redundant entity updates
 * after they arrive, and the chunk reuse fills the screen while the first batch downloads. They act at
 * different points in the same pipeline -- before the data, after the data, and instead of the data -- so
 * they compose rather than overlap.
 *
 * <h2>Everything resets on disconnect, not on connect</h2>
 *
 * <p>A connection that ends leaves state behind: a half-filled coalescer, a pacer holding a measurement from
 * a server the player has left, a chunk cache for a world that may not be the next one. Clearing on the way
 * out means the next connection starts clean whatever it is, and clearing on the way in would leave the state
 * of the previous session live until the moment a new one begins, which is exactly when it would be read.
 *
 * <h2>Single player is a no-op, not a special case</h2>
 *
 * <p>An integrated server has no chunk batches to acknowledge and no duplicate entity updates, so the
 * components simply do nothing rather than being bypassed by a flag. A flag would be a second way to be
 * wrong, and the honest behaviour is that these answer questions a single-player client is never asked.
 *
 * <p>Not thread safe. Connection events and the client tick all run on the client thread.
 */
public final class NetworkRuntime {

    private final ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();
    private final EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();
    private final ReconnectChunkReuse chunks = new ReconnectChunkReuse();

    /**
     * The entities with an update pending this tick, by id.
     *
     * <p>A set rather than a count, because the coalescer applies one update per entity and refuses a count
     * larger than the updates it received. Counting updates instead of entities would pass a number equal to
     * the updates, and the coalescer would apply all of them and record nothing coalesced -- the pipeline
     * would run and save nothing, which is worse than not running because it looks like it is working.
     */
    private final java.util.Set<Integer> pendingEntities = new java.util.HashSet<>();

    private boolean connected;
    private long connections;

    /**
     * Starts a connection.
     *
     * @param seed the world's seed
     * @param dimension the dimension being joined
     * @return how many chunks are available to draw while the real ones download
     */
    public int connect(long seed, int dimension) {
        this.connected = true;
        this.connections++;
        // The pacer starts conservative on its own, because the first measurement is of a quiet moment that
        // is about to stop being quiet.
        return this.chunks.connect(seed, dimension);
    }

    /**
     * Ends a connection and clears everything.
     *
     * <p>Clearing on the way out rather than on the way in means the next connection starts clean whatever it
     * is. Clearing on the way in would leave the previous session's state live until the moment a new one
     * begins, which is exactly when it would be read.
     */
    public void disconnect() {
        this.connected = false;
        this.pendingEntities.clear();
    }

    /** Whether a connection is live. */
    public boolean isConnected() {
        return this.connected;
    }

    /** How many connections have been made this session. */
    public long connections() {
        return this.connections;
    }

    /** How many distinct entities have an update pending this tick. */
    public int pendingEntities() {
        return this.pendingEntities.size();
    }

    /**
     * Reports an entity update arriving from the server.
     *
     * @param entityId which entity it is for, so several updates in one tick collapse to one application
     * @param teleport whether it is an absolute position rather than a delta
     * @return true if it has to be applied now
     */
    public boolean entityUpdate(int entityId, boolean teleport) {
        boolean applyNow = this.coalescer.receive(teleport);
        if (!teleport) {
            this.pendingEntities.add(entityId);
        }
        return applyNow;
    }

    /**
     * Ends a tick, applying what the coalescer held.
     *
     * <p>One entity, one application, wherever it ended up. Holding updates for longer than a tick would make
     * every entity move a tick behind where the server says it is, which the player feels as input lag and
     * other players see.
     */
    public void endTick() {
        if (!this.pendingEntities.isEmpty()) {
            this.coalescer.endTick(this.pendingEntities.size());
            this.pendingEntities.clear();
        }
    }

    /**
     * Reports a chunk batch processed, and returns what to acknowledge with.
     *
     * @param chunkCount how many chunks were in the batch
     * @param millis how long processing them took
     */
    public double acknowledgeChunks(int chunkCount, double millis) {
        return this.pacer.acknowledge(chunkCount, millis);
    }

    /** Reports a teleport, so the pacer starts again from the conservative end. */
    public void teleported() {
        this.pacer.resetForTeleport();
    }

    /** Reports a chunk arriving, which replaces any cached copy of it. */
    public void chunkReceived() {
        this.chunks.receive();
    }

    /** Reports a chunk stored, so a later connection can draw it while the real one downloads. */
    public void chunkStored() {
        this.chunks.store();
    }

    /** Whether a cached chunk is available to draw. */
    public boolean drawCachedChunk() {
        return this.chunks.drawCached();
    }

    /** Refuses a gameplay use of a cached chunk, which is always. */
    public boolean allowGameplayUseOfCachedChunk() {
        return this.chunks.allowGameplayUse();
    }

    public ChunkBatchAckPacer pacer() {
        return this.pacer;
    }

    public EntityUpdateCoalescer coalescer() {
        return this.coalescer;
    }

    public ReconnectChunkReuse chunks() {
        return this.chunks;
    }

    /** Milliseconds of entity update work the coalescer removed. */
    public double msSavedOnEntityUpdates() {
        return this.coalescer.msSaved();
    }

    /** Milliseconds of waiting the chunk reuse removed. */
    public double msSavedOnChunkDownload() {
        return this.chunks.msSaved();
    }

    /**
     * The share of entity update work removed.
     *
     * <p>Zero before any update has arrived. A runtime that has received nothing has technically removed
     * everything, but reporting that as a saving would describe an unused one as though it had measured
     * something.
     */
    public double entityUpdateSavedPercent() {
        return this.coalescer.savedPercent();
    }
}
