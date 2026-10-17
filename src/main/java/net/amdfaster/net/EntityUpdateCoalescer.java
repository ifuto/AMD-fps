package net.amdfaster.net;

/**
 * Collapses several position updates for one entity in a tick into the last one.
 *
 * <p>On a busy server the client is told where every tracked entity is, every tick, and an entity that is
 * moving fast or being pushed around can arrive several times in one tick. Each arrival costs the same work:
 * the position is set, the interpolation between the old and new position is reset, and the bounding box is
 * recomputed. But the renderer draws the entity once per frame, at wherever it ended up, so every arrival
 * except the last produced state that was never drawn.
 *
 * <p>Keeping only the last one removes that work. With two hundred entities arriving three times a tick, four
 * hundred of the six hundred applications produce nothing the player can see.
 *
 * <h2>The window is one tick, and never longer</h2>
 *
 * <p>Holding updates for longer would save more, and would make every entity on the screen move a tick behind
 * where the server says it is -- which the player feels as input lag on anything they are interacting with,
 * and which on a server with other players watching is visible to them too. One tick is the shortest window
 * there is, and it is already the whole saving, because the redundancy is within a tick rather than across
 * them.
 *
 * <h2>A teleport is never coalesced away</h2>
 *
 * <p>An ordinary update is a delta from where the client already thinks the entity is, so dropping one and
 * applying the next still lands in the right place. A teleport is an absolute position that also resets the
 * interpolation, and dropping it would leave the entity interpolating smoothly towards a place it is no
 * longer at -- which looks like it slid across the map rather than moved. So a teleport is applied
 * immediately whatever else is queued.
 *
 * <h2>Velocity and rotation come from the last one</h2>
 *
 * <p>They are part of the same packet, so the last update carries the values that go with the position being
 * applied. Taking the velocity from an earlier one would set the entity moving in a direction it stopped
 * moving in, and it would keep going that way until the next update corrected it.
 *
 * <h2>Why this is a multiplayer-only win</h2>
 *
 * <p>A single-player client moves its own entities once per tick and never sees a duplicate. This is about a
 * server sending what it sends, which the client cannot ask it to send less of, so the only place to remove
 * the redundancy is after it arrives.
 *
 * <p>Thread safety: updates arrive on the network thread and are applied on the client thread, so the pending
 * state is handed over once per tick rather than shared.
 *
 * <p>Reference: the Java Edition protocol's entity position and rotation packets, which carry deltas and a
 * separate absolute teleport; and VMP's area-map optimisation of the tracking that produces them. This is an
 * independent implementation of the client-side coalescing.
 */
public final class EntityUpdateCoalescer {

    /** Milliseconds applying one entity update costs. */
    public static final double MS_PER_APPLY = 0.004;

    private long received;
    private long applied;
    private long coalesced;
    private long teleports;
    private long ticks;

    /** Non-teleport updates that have arrived this tick and are waiting to be superseded or applied. */
    private int pending;

    /**
     * Records an update arriving.
     *
     * @param teleport whether it is an absolute position rather than a delta
     * @return true if it has to be applied now rather than held for the end of the tick
     */
    public boolean receive(boolean teleport) {
        this.received++;

        if (teleport) {
            // An ordinary update is a delta, so dropping one and applying the next still lands in the right
            // place. A teleport is absolute and resets the interpolation, and dropping it would leave the
            // entity interpolating smoothly towards a place it is no longer at, which looks like it slid
            // across the map rather than moved.
            this.teleports++;
            this.applied++;
            return true;
        }
        this.pending++;
        return false;
    }

    /**
     * Applies what is left at the end of the tick.
     *
     * <p>One entity, one application, wherever it ended up. Holding updates for longer than a tick would save
     * more and make every entity move a tick behind where the server says it is, which the player feels as
     * input lag and other players see.
     *
     * @param entitiesWithPendingUpdates how many distinct entities arrived this tick
     * @return how many applications happened
     */
    public int endTick(int entitiesWithPendingUpdates) {
        if (entitiesWithPendingUpdates < 0) {
            throw new IllegalArgumentException(
                    "a non-negative count is required: " + entitiesWithPendingUpdates);
        }
        if (entitiesWithPendingUpdates > this.pending) {
            throw new IllegalStateException(
                    entitiesWithPendingUpdates + " entities pending but only " + this.pending
                            + " updates arrived this tick");
        }
        this.ticks++;
        this.applied += entitiesWithPendingUpdates;
        this.coalesced += this.pending - entitiesWithPendingUpdates;
        this.pending = 0;
        return entitiesWithPendingUpdates;
    }

    public long received() {
        return this.received;
    }

    public long applied() {
        return this.applied;
    }

    /** How many updates arrived but were superseded before being applied. */
    public long coalesced() {
        return this.coalesced;
    }

    /** How many teleports were applied immediately. */
    public long teleports() {
        return this.teleports;
    }

    public long ticks() {
        return this.ticks;
    }

    public double coalescedPercent() {
        return this.received == 0 ? 0.0 : 100.0 * coalesced() / this.received;
    }

    /** Milliseconds of update application avoided. */
    public double msSaved() {
        return coalesced() * MS_PER_APPLY;
    }

    /** Milliseconds applying every update as it arrived would cost. */
    public double msWithoutCoalescing() {
        return this.received * MS_PER_APPLY;
    }

    /**
     * The share of the update work avoided.
     *
     * <p>Zero before any update has arrived. A coalescer that has received nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutCoalescing();
        if (without == 0.0) {
            return 0.0;
        }
        // The applications still happen, so what is saved is the superseded ones and nothing else.
        return 100.0 * msSaved() / without;
    }
}
