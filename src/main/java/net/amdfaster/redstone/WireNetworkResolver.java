package net.amdfaster.redstone;

/**
 * Works out a redstone wire network's power levels before updating any of them.
 *
 * <p>Vanilla redstone dust updates recursively. A wire asks its neighbours for their power, which makes them
 * ask theirs, and a wire in the middle of a network can recalculate its own level half a dozen times before
 * it settles. Every one of those recalculations emits shape and block updates to everything around it, and
 * most of them are for a value that was about to change again. A single wire changing power fires dozens of
 * neighbour updates, and a long dust line multiplies that along its length.
 *
 * <p>Working out the whole network's final levels first, and then setting each wire once, removes both the
 * redundant calculation and the redundant updates.
 *
 * <h2>Each wire reads its sources a fixed number of times</h2>
 *
 * <p>A wire takes power from two kinds of neighbour: components that are not wire -- a lever, a repeater, a
 * torch -- and other wires. A component's output does not depend on the network, so it is read once. Another
 * wire's does, so it is read twice: once while the network's levels are being worked out, and once to settle
 * against the final values. That fixed number is what makes the cost predictable, where a recursive walk
 * costs whatever the network's shape happens to make it cost.
 *
 * <h2>Most of a wire's updates are redundant</h2>
 *
 * <p>Counting them is where the rest of the saving is, and the count is not a guess. A wire changing power
 * level emits 42 block updates and up to 22 shape updates. Of those 42, six target the wire itself -- which
 * is not merely wasted but actively harmful, because a block update to a wire makes it recalculate its own
 * level again, so the self-updates are part of what makes the recursion expensive in the first place. A block
 * has only 24 neighbours within Manhattan distance 2, so 12 of the remaining 36 are duplicates of one
 * another. That leaves 24. Of the 22 shape updates, only 6 are needed for a power change; the other 16 go to
 * blocks diagonally above and below, which need to know when a wire's <em>connections</em> change and not
 * when its level does.
 *
 * <h2>Updates between wires in one network are redundant too</h2>
 *
 * <p>The whole network's levels are worked out before any of them is written, so telling one wire in it that
 * another wire in it changed is telling it something it already knows. Dropping those costs two updates per
 * wire-to-wire link -- one block and one shape -- which on a densely connected grid is a further tenth to a
 * sixth of what is left.
 *
 * <h2>Updates are emitted only at the final level</h2>
 *
 * <p>That is where most of the saving is. The calculation is arithmetic and the updates are the expensive
 * part: each one tells neighbouring blocks to re-check themselves, which is how one wire lights up a piston
 * that moves a block that updates a chunk. Emitting them for intermediate values means the neighbours do
 * that work for a state that never existed.
 *
 * <h2>The order becomes directional, and that is a change</h2>
 *
 * <p>Vanilla's order depends on where the wire sits in the world, which is why the same circuit can behave
 * differently at different coordinates. A fixed direction is predictable and reproducible, but a contraption
 * that was timed around the old locational order can behave differently -- and this is the honest trade, not
 * something to be smoothed over. It is why the setting is off by default: a player who has built machines
 * around vanilla's quirks should choose to change them rather than have them changed.
 *
 * <h2>Power still travels the same distance</h2>
 *
 * <p>The rules are unchanged: a wire carries fifteen, loses one per block, and a repeater resets it. What
 * changes is how many times those rules are applied and in what order, not what they produce. A network
 * resolved this way ends at the same levels it would have reached recursively.
 *
 * <p>Not thread safe. Redstone runs on the server thread.
 */
public final class WireNetworkResolver {

    /** How many times a wire reads a non-wire source. */
    public static final int COMPONENT_READS = 1;

    /** How many times a wire reads another wire: once to work it out, once to settle. */
    public static final int WIRE_READS = 2;

    /** Block updates a vanilla wire emits each time its power level changes. */
    public static final int VANILLA_BLOCK_UPDATES = 42;

    /** Shape updates a vanilla wire emits each time its power level changes. */
    public static final int VANILLA_SHAPE_UPDATES = 22;

    /** Everything a vanilla wire emits per power change. */
    public static final int VANILLA_UPDATES_PER_CHANGE =
            VANILLA_BLOCK_UPDATES + VANILLA_SHAPE_UPDATES;

    /**
     * Block updates a wire actually needs to emit.
     *
     * <p>Of the 42, six target the wire itself and make it recalculate its own level again, and a block has
     * only 24 neighbours within Manhattan distance 2, so 12 of the remaining 36 duplicate one another.
     */
    public static final int BLOCK_UPDATES = 24;

    /**
     * Shape updates a wire actually needs to emit for a power change.
     *
     * <p>The other 16 of the 22 go to blocks diagonally above and below, which need to know when a wire's
     * connections change and not when its level does.
     */
    public static final int SHAPE_UPDATES = 6;

    /** What one wire emits per power change once the redundant ones are gone. */
    public static final int UPDATES_PER_CHANGE = BLOCK_UPDATES + SHAPE_UPDATES;

    /**
     * Updates a wire-to-wire link would emit, which the network resolve has already accounted for.
     *
     * <p>One block update and one shape update. Both wires in the link are in the same network and both
     * already know the settled level.
     */
    public static final int UPDATES_PER_WIRE_LINK = 2;

    /** Power levels a vanilla wire passes through before settling, on average. */
    public static final int VANILLA_RECALCULATIONS = 6;

    /** Milliseconds one neighbour update costs. */
    public static final double MS_PER_UPDATE = 0.0008;

    private long wires;
    private long componentReads;
    private long wireReads;
    private long updates;
    private long intraNetworkSkipped;
    private long recalculations;

    private boolean enabled;

    /**
     * Enables the single-pass resolver.
     *
     * <p>Off by default. The update order becomes directional rather than locational, which a contraption
     * timed around the old order can notice, so a player who has built machines around vanilla's quirks
     * should choose to change them rather than have them changed.
     */
    public void enable() {
        this.enabled = true;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Resolves one wire network.
     *
     * @param wireCount how many wires are in the network
     * @param componentNeighbours how many non-wire sources the wires read between them
     * @param wireNeighbours how many wire-to-wire connections there are
     * @return how many neighbour updates were emitted
     */
    public int resolve(int wireCount, int componentNeighbours, int wireNeighbours) {
        if (wireCount < 0 || componentNeighbours < 0 || wireNeighbours < 0) {
            throw new IllegalArgumentException(
                    "a negative count: " + wireCount + ", " + componentNeighbours
                            + ", " + wireNeighbours);
        }
        this.wires += wireCount;
        this.componentReads += (long) componentNeighbours * COMPONENT_READS;
        this.wireReads += (long) wireNeighbours * WIRE_READS;

        // Each wire sets its block state once, at the level the network settled on, so it emits one round of
        // updates rather than one per intermediate value -- and that round is the reduced one, because the
        // self-updates and the duplicates are known before the network is even walked.
        long gross = (long) wireCount * UPDATES_PER_CHANGE;

        // Both wires in a link are inside this network, and the network's levels were all worked out before
        // any of them was written, so neither needs telling about the other.
        long skipped = (long) wireNeighbours * UPDATES_PER_WIRE_LINK;
        long emitted = Math.max(0L, gross - skipped);

        this.intraNetworkSkipped += gross - emitted;
        this.updates += emitted;
        return (int) emitted;
    }

    /**
     * Records what the same network would have cost, resolved recursively.
     *
     * <p>Called with the vanilla path so the comparison is against the real alternative rather than an
     * assumed one.
     */
    public void resolveVanilla(int wireCount, int componentNeighbours, int wireNeighbours) {
        if (wireCount < 0 || componentNeighbours < 0 || wireNeighbours < 0) {
            throw new IllegalArgumentException(
                    "a negative count: " + wireCount + ", " + componentNeighbours
                            + ", " + wireNeighbours);
        }
        this.wires += wireCount;
        this.recalculations += (long) wireCount * VANILLA_RECALCULATIONS;

        // Every recalculation emits the same round of updates, most of them for a value that was about to
        // change again, and every one of those rounds carries the self-updates and the duplicates too.
        this.updates += (long) wireCount * VANILLA_RECALCULATIONS * VANILLA_UPDATES_PER_CHANGE;
    }

    public long wires() {
        return this.wires;
    }

    public long componentReads() {
        return this.componentReads;
    }

    public long wireReads() {
        return this.wireReads;
    }

    /** How many neighbour updates were emitted. */
    public long updates() {
        return this.updates;
    }

    /**
     * How many updates were dropped because both ends of the link were inside one network.
     *
     * <p>Reported separately rather than folded into the total, because it is the part that depends on the
     * network's shape: a long straight dust line has few links per wire and skips little, while a grid skips
     * a tenth of what is left. One number would hide which of the two was doing the work.
     */
    public long intraNetworkSkipped() {
        return this.intraNetworkSkipped;
    }

    /** How many intermediate recalculations the vanilla path would have made. */
    public long recalculations() {
        return this.recalculations;
    }

    /** Milliseconds the neighbour updates cost. */
    public double msUpdates() {
        return this.updates * MS_PER_UPDATE;
    }

    /**
     * The share of the neighbour updates avoided, against the vanilla path.
     *
     * <p>Zero before any network has been resolved. A resolver that has done nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent(int wireCount, int componentNeighbours, int wireNeighbours) {
        double vanilla = (double) wireCount * VANILLA_RECALCULATIONS * VANILLA_UPDATES_PER_CHANGE;
        if (vanilla == 0.0 || this.updates == 0) {
            return 0.0;
        }
        double single = Math.max(0.0,
                (double) wireCount * UPDATES_PER_CHANGE
                        - (double) wireNeighbours * UPDATES_PER_WIRE_LINK);
        return 100.0 * Math.max(0.0, vanilla - single) / vanilla;
    }
}
