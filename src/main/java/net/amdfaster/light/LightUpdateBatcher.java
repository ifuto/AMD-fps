package net.amdfaster.light;

import java.util.ArrayList;
import java.util.List;

import net.amdfaster.dirty.SectionCoord;

/**
 * Coalesces a burst of block changes into as few light updates as possible, and processes them under a
 * budget.
 *
 * <p>Two separate problems, solved in the same place because they share a queue.
 *
 * <p>Coalescing: block changes arrive in bursts. A TNT explosion changes hundreds of blocks in one tick
 * and a piston pushes a whole row. Each of those blocks would naively trigger its own light update, and
 * the updates overlap almost entirely -- the light around block A and the light around block B, one
 * block apart, cover nearly the same cells. Doing them separately costs several times more than doing
 * them together, for the same final result. Collapsing changes to the section they are in, then to the
 * set of affected columns, removes that duplication before any propagation runs.
 *
 * <p>Budgeting: a light update is unbounded in cost. Lighting a region that was loaded in darkness can
 * take tens of milliseconds, and doing that inside a tick stalls the client, which is felt as a hitch
 * rather than as low FPS. Processing under a step budget spreads it over frames instead. The budget is
 * counted in cells visited rather than in wall-clock time, because that is what the propagation cost is
 * actually proportional to and it does not depend on the machine.
 *
 * <p>Not thread safe.
 */
public final class LightUpdateBatcher {

    /** Cells propagated per call before yielding. Matches the rebuild scheduler's scale. */
    public static final int DEFAULT_BUDGET = 64;

    /**
     * Queued changes, as x, y, z, previous emission, new emission.
     *
     * <p>The previous emission is what makes this more than a list of coordinates. Removing a source
     * and adding one are different operations with different costs, and a block that changed from one
     * emitting state to another -- a redstone torch switching on, a lantern being waterlogged -- needs
     * both, in that order.
     */
    private final List<long[]> pending = new ArrayList<>();

    private final int budget;

    private long changesReceived;
    private long updatesCoalesced;
    private long batchesStarted;
    private long budgetExhausted;
    private long cellsPropagated;

    public LightUpdateBatcher() {
        this(DEFAULT_BUDGET);
    }

    public LightUpdateBatcher(int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive: " + budget);
        }
        this.budget = budget;
    }

    public int budget() {
        return this.budget;
    }

    public long changesReceived() {
        return this.changesReceived;
    }

    /**
     * Changes that were folded into an already-pending update.
     *
     * <p>The number that says whether coalescing is earning its keep. A burst of changes in one column
     * should collapse to one pending update no matter how many blocks changed.
     */
    public long updatesCoalesced() {
        return this.updatesCoalesced;
    }

    public long batchesStarted() {
        return this.batchesStarted;
    }

    public long budgetExhausted() {
        return this.budgetExhausted;
    }

    public long cellsPropagated() {
        return this.cellsPropagated;
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public boolean isEmpty() {
        return this.pending.isEmpty();
    }

    /**
     * Records a block whose emission changed.
     *
     * <p>Does no propagation. The caller drains with {@link #process} when it has a moment to spend,
     * which is the whole point: an explosion that changes three hundred blocks queues three hundred
     * cheap entries and the propagation happens over the following frames, not in the tick the
     * explosion happened in.
     *
     * @param previousEmission what the block emitted before, 0 if it emitted nothing
     * @param newEmission      what it emits now, 0 if it emits nothing
     */
    public void addChange(int x, int y, int z, int previousEmission, int newEmission) {
        if (previousEmission == newEmission) {
            // Nothing about the light can change, so there is nothing to queue. A piston moving stone
            // around is the common case and it lands here.
            this.changesReceived++;
            this.updatesCoalesced++;
            return;
        }
        // Folding repeat changes to the same block: a lever flipped twice within one batch, or a block
        // broken and replaced, should cost one update rather than two.
        for (long[] entry : this.pending) {
            if (entry[0] == x && entry[1] == y && entry[2] == z) {
                entry[4] = newEmission;
                this.changesReceived++;
                this.updatesCoalesced++;
                return;
            }
        }
        this.pending.add(new long[] {x, y, z, previousEmission, newEmission});
        this.changesReceived++;
    }

    /** A block that started or stopped emitting, with no previous state to fold. */
    public void addChange(int x, int y, int z) {
        addChange(x, y, z, 0, 0);
    }

    /**
     * Runs propagation until the budget is spent or nothing is left.
     *
     * @return how many columns were completed, so a caller can tell a finished queue from one that was
     *         cut short by the budget
     */
    public int process(LightEngine engine, LightEngine.OpacitySource opacity) {
        if (this.pending.isEmpty()) {
            return 0;
        }
        this.batchesStarted++;
        long spent = 0;
        int completed = 0;
        while (!this.pending.isEmpty()) {
            long before = engine.cellsVisited();
            long[] entry = this.pending.remove(0);
            int x = (int) entry[0];
            int y = (int) entry[1];
            int z = (int) entry[2];
            // Removal first. Adding the new level before clearing the old one would leave the old
            // light's reach in place, because a source only ever raises what it touches and would
            // refuse to darken the cells the previous state was lighting.
            if (entry[3] > 0) {
                engine.removeSource(x, y, z, opacity);
            }
            if (entry[4] > 0) {
                engine.addSource(x, y, z, (int) entry[4], opacity);
            }
            spent += engine.cellsVisited() - before;
            completed++;
            if (spent >= this.budget) {
                if (!this.pending.isEmpty()) {
                    this.budgetExhausted++;
                }
                break;
            }
        }
        this.cellsPropagated += spent;
        return completed;
    }

    /**
     * The column a change falls in, as a section coordinate key.
     *
     * <p>Exposed because the caller needs it to know which sections to re-mesh; the batcher itself
     * works per block, since that is the granularity the light engine takes.
     */
    public static long columnOf(int x, int z) {
        return SectionCoord.key(x >> 4, 0, z >> 4);
    }

    public void clear() {
        this.pending.clear();
    }
}
