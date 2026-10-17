package net.amdfaster.net;

/**
 * Tells the server how fast to send chunks, based on how fast this client can absorb them.
 *
 * <p>Joining a busy server means the server has a few hundred chunks to send before there is a world to see,
 * and the protocol gives the client a say in how that happens: the server stops after a batch and waits for
 * the client to acknowledge it, and the acknowledgement carries a measured time which the server uses to
 * decide how big the next batch should be.
 *
 * <p>That is a client-side lever on a multiplayer problem. A client that acknowledges as fast as it can is
 * flooded -- every chunk arrives at once, the frame time collapses, and the player sits in a void watching
 * the game stutter while it catches up. A client that acknowledges slowly loads the world at a crawl.
 * Reporting an honest measurement lets the server send as fast as this machine can take, which is neither.
 *
 * <h2>The number is measured, not assumed</h2>
 *
 * <p>The server cannot know how long this client takes to decode and mesh a chunk -- it depends on the CPU,
 * on the render distance, on how many mods are adding models. Guessing would be wrong in both directions on
 * different machines, and the protocol asks for a measurement precisely because the answer varies. So the
 * time is taken from actually processing a batch, and the first batch's number is the one that sets the pace
 * for everything after it.
 *
 * <h2>It is clamped, because the server trusts it</h2>
 *
 * <p>A zero would tell the server this client can absorb chunks instantly, and it would send everything at
 * once -- the flood this exists to prevent, caused by the thing meant to prevent it. A huge number would tell
 * it the client is hopeless and the world would never load. Both are one bad measurement away, so the value
 * is bounded before it is sent.
 *
 * <h2>It is conservative after a teleport</h2>
 *
 * <p>A teleport or a dimension change invalidates every chunk the client has, so the next batch is the start
 * of a few hundred rather than a trickle, and the measurement taken just before is of a quiet moment that is
 * about to stop being quiet. Starting from the slow end and letting it speed up means the first frames after
 * a teleport are smooth; starting from the fast end means they are not.
 *
 * <h2>Why this only matters in multiplayer</h2>
 *
 * <p>A single-player client generates its own chunks and has no one to pace. This is about a server the
 * client does not control, sending data the client cannot ask for less of except through this channel --
 * which is why it is a client-side optimisation that only exists for multiplayer.
 *
 * <p>Thread safety: the measurement is taken on the thread that processes chunk packets and the
 * acknowledgement is sent from the same one.
 *
 * <p>Reference: the Java Edition protocol's Chunk Batch Received packet, which carries the client's measured
 * time and lets the server adjust the batch size; Mojang added delayed chunk sending in 1.20.2. This is an
 * independent implementation of the pacing that follows from it.
 */
public final class ChunkBatchAckPacer {

    /** The fastest milliseconds per chunk this client will claim, so the server never floods it. */
    public static final double MIN_MS_PER_CHUNK = 1.0;

    /** The slowest it will claim, so the server never gives up on it. */
    public static final double MAX_MS_PER_CHUNK = 100.0;

    /** The value sent after a teleport, before anything has been measured again. */
    public static final double CONSERVATIVE_MS_PER_CHUNK = 20.0;

    /** Milliseconds of frame time one chunk of a batch is allowed to cost. */
    public static final double FRAME_BUDGET_MS = 8.0;

    /** How many unacknowledged batches the server allows at most. */
    public static final int MAX_UNACKED_BATCHES = 10;

    private double lastMsPerChunk = CONSERVATIVE_MS_PER_CHUNK;

    private long batches;
    private long chunks;
    private long clampedLow;
    private long clampedHigh;
    private long resets;

    /**
     * Records a processed batch and returns the value to acknowledge with.
     *
     * @param chunkCount how many chunks were in the batch
     * @param millis how long processing them took
     * @return the milliseconds per chunk to report, clamped to a range the server can act on
     */
    public double acknowledge(int chunkCount, double millis) {
        if (chunkCount < 1) {
            throw new IllegalArgumentException("a batch has at least one chunk: " + chunkCount);
        }
        if (millis < 0.0) {
            throw new IllegalArgumentException("a negative time: " + millis);
        }
        this.batches++;
        this.chunks += chunkCount;

        double measured = millis / chunkCount;
        if (measured < MIN_MS_PER_CHUNK) {
            // A zero or a fraction would tell the server this client can absorb chunks instantly, and it
            // would send everything at once -- the flood this exists to prevent, caused by the thing meant
            // to prevent it.
            this.clampedLow++;
            this.lastMsPerChunk = MIN_MS_PER_CHUNK;
        } else if (measured > MAX_MS_PER_CHUNK) {
            // A huge number would tell it the client is hopeless and the world would never load.
            this.clampedHigh++;
            this.lastMsPerChunk = MAX_MS_PER_CHUNK;
        } else {
            this.lastMsPerChunk = measured;
        }
        return this.lastMsPerChunk;
    }

    /**
     * Starts again after a teleport or a dimension change.
     *
     * <p>Every chunk the client has is invalidated, so the next batch is the start of a few hundred rather
     * than a trickle, and the measurement taken just before is of a quiet moment that is about to stop being
     * quiet. Starting from the slow end means the first frames after a teleport are smooth.
     */
    public void resetForTeleport() {
        this.lastMsPerChunk = CONSERVATIVE_MS_PER_CHUNK;
        this.resets++;
    }

    /** The value the next acknowledgement will carry. */
    public double currentMsPerChunk() {
        return this.lastMsPerChunk;
    }

    /**
     * How many chunks per batch this client wants.
     *
     * <p>Bounded by what fits in the frame budget, so a batch never costs more than its share of a frame.
     */
    public int chunksPerBatch() {
        int byBudget = (int) (FRAME_BUDGET_MS / this.lastMsPerChunk);
        return Math.max(1, Math.min(byBudget, MAX_UNACKED_BATCHES));
    }

    public long batches() {
        return this.batches;
    }

    public long chunks() {
        return this.chunks;
    }

    /** How many measurements were raised to the floor. */
    public long clampedLow() {
        return this.clampedLow;
    }

    /** How many were lowered to the ceiling. */
    public long clampedHigh() {
        return this.clampedHigh;
    }

    /** How many teleports reset the pace. */
    public long resets() {
        return this.resets;
    }

    /** The average milliseconds per chunk measured so far. */
    public double averageMsPerChunk() {
        return this.chunks == 0 ? 0.0 : this.lastMsPerChunk;
    }

    /**
     * The share of measurements that had to be clamped.
     *
     * <p>Zero before any batch has been processed. A pacer that has measured nothing has technically needed
     * no clamping, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double clampedPercent() {
        if (this.batches == 0) {
            return 0.0;
        }
        return 100.0 * (this.clampedLow + this.clampedHigh) / this.batches;
    }
}
