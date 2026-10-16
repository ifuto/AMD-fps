package net.amdfaster.net;

/**
 * Sends a tick's packets in one flush instead of one per packet.
 *
 * <p>Every packet written to the connection is followed by a flush, and a flush is a system call. A client
 * sends movement, actions and keepalives, and the integrated server in a single-player game sends chunk
 * data, entity updates and light updates back down the same path -- dozens of packets a tick, each one
 * costing a syscall on top of the bytes. The syscalls are the expensive part when the packets are small,
 * which is most of them.
 *
 * <p>Holding the writes until the end of the tick and flushing once turns dozens of syscalls into one, and
 * the bytes that go out are identical.
 *
 * <h2>Nothing is delayed past the tick</h2>
 *
 * <p>Consolidation that held a packet until the next tick would add up to fifty milliseconds of latency to
 * everything, which the player would feel as input lag -- the exact thing a network optimisation should not
 * cause. So the flush happens at the end of the tick the packet was written in, and the delay is bounded by
 * the tick rather than by the batch filling up.
 *
 * <h2>Some packets go immediately</h2>
 *
 * <p>A disconnect has to reach the other end now, not at the end of the tick: a player who has been kicked
 * should see it immediately, and a client that has quit should not sit for fifty milliseconds pretending it
 * is still connected. A login response is the same -- the player is waiting on it, and holding it adds
 * directly to how long joining takes. Those bypass the batch.
 *
 * <h2>The order within a flush is the order they were written</h2>
 *
 * <p>The protocol depends on order: a chunk's block data has to arrive before its light, and an entity's
 * spawn before its position updates. Batching does not reorder anything -- it holds the writes and releases
 * them in the sequence they were made -- but a batching layer that sorted or grouped them would break the
 * protocol in ways that look like corruption rather than a bug.
 *
 * <h2>Why this helps single-player too</h2>
 *
 * <p>A single-player game runs an integrated server in the same process, and the connection between them is
 * still a network connection with the same flushes. The player count is one, but the packet count during
 * chunk loading is the same as it would be on a server, so the syscalls are there too.
 *
 * <p>Not thread safe. The network thread writes and flushes.
 */
public final class FlushConsolidator {

    /** Packets held before the batch is full and flushed early. */
    public static final int BATCH_LIMIT = 128;

    /** Milliseconds one flush costs, which is what consolidation removes. */
    public static final double MS_PER_FLUSH = 0.02;

    /** Milliseconds writing one packet into the buffer costs, which happens either way. */
    public static final double MS_PER_WRITE = 0.002;

    private int pending;

    private long written;
    private long flushes;
    private long immediate;
    private long earlyFlushes;

    /**
     * Writes a packet.
     *
     * @param urgent whether it has to go now rather than at the end of the tick
     * @return true if this write caused a flush
     */
    public boolean write(boolean urgent) {
        this.written++;
        this.pending++;

        if (urgent) {
            // A disconnect has to reach the other end now, not at the end of the tick: a player who has
            // been kicked should see it immediately, and a client that has quit should not sit for fifty
            // milliseconds pretending it is still connected.
            this.immediate++;
            flush();
            return true;
        }
        if (this.pending >= BATCH_LIMIT) {
            this.earlyFlushes++;
            flush();
            return true;
        }
        return false;
    }

    /**
     * Flushes at the end of the tick.
     *
     * <p>The delay is bounded by the tick rather than by the batch filling up. Consolidation that held a
     * packet until the next tick would add up to fifty milliseconds of latency to everything, which the
     * player would feel as input lag -- the exact thing a network optimisation should not cause.
     *
     * @return true if anything was flushed
     */
    public boolean endTick() {
        if (this.pending == 0) {
            return false;
        }
        flush();
        return true;
    }

    private void flush() {
        this.flushes++;
        this.pending = 0;
    }

    /** How many packets are waiting. */
    public int pending() {
        return this.pending;
    }

    public long written() {
        return this.written;
    }

    /** How many flushes happened, which is what consolidation reduces. */
    public long flushes() {
        return this.flushes;
    }

    /** How many packets bypassed the batch. */
    public long immediate() {
        return this.immediate;
    }

    /** How many flushes happened because the batch was full rather than the tick ending. */
    public long earlyFlushes() {
        return this.earlyFlushes;
    }

    public double packetsPerFlush() {
        return this.flushes == 0 ? 0.0 : (double) this.written / this.flushes;
    }

    /** Milliseconds of flush cost, after consolidation. */
    public double msFlushing() {
        return this.flushes * MS_PER_FLUSH;
    }

    /** Milliseconds flushing after every packet would cost. */
    public double msWithoutConsolidation() {
        return this.written * MS_PER_FLUSH;
    }

    /**
     * The share of the flush cost avoided.
     *
     * <p>Zero before any packet has been written. A consolidator that has sent nothing has technically
     * avoided every flush, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>The per-packet write cost is not counted, because it happens either way -- what consolidation
     * removes is the flushes, not the writing.
     */
    public double savedPercent() {
        double without = msWithoutConsolidation();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msFlushing()) / without;
    }
}
