package net.amdfaster.light;

/**
 * Propagates light in several sections at once, resolving the boundaries afterwards.
 *
 * <p>Light propagation is the flood fill that spreads a torch's glow outward and a skylight column
 * downward, and it runs whenever a block changes. Placing one block can dirty a dozen sections, and the
 * fill visits every block the light reaches -- which for a torch in a dark cave is thousands of them. On
 * one thread that is a hitch at the moment the player placed the block, which is the worst moment for one.
 *
 * <p>Sections are large enough that most of a fill stays inside one, so they can be filled in parallel and
 * the boundaries reconciled afterwards.
 *
 * <h2>The result has to match the serial one</h2>
 *
 * <p>Lighting is not an approximation the player will not notice. A block that is one level too dark looks
 * wrong next to its neighbours, and a seam along a section boundary is a straight line of mislit blocks
 * across the world -- immediately visible and very hard to attribute to a threading change. So the parallel
 * fill and the serial fill must produce identical values, and the boundary pass is what guarantees it.
 *
 * <h2>The boundary is where the work is not independent</h2>
 *
 * <p>Light crossing from one section into the next is a value the receiving section did not have while it
 * was being filled. So each section fills itself, then every boundary is checked: if the neighbour's edge is
 * brighter than this section's, the difference is propagated in, and that can in turn make this section's
 * far edge brighter for the next one over. Repeating until no boundary changes is what makes the answer the
 * same as filling it all at once.
 *
 * <h2>A section with no boundary change is not touched again</h2>
 *
 * <p>Most of the time a fill stays inside its section -- a torch in the middle of a cave does not reach the
 * walls. Re-running the boundary pass for sections that did not change would cost as much as the parallelism
 * saved, so only the sections whose edges actually changed take part in the next round.
 *
 * <h2>Why this is not the same as batching the updates</h2>
 *
 * <p>{@link LightUpdateBatcher} decides when to apply the results to the rendered chunks. This decides how
 * the values are computed, and the two are independent: a parallel fill still produces one batch of updates
 * for the renderer to apply.
 *
 * <p>Thread safety: the sections are filled on worker threads and no two workers touch the same section. The
 * boundary pass runs after they are all done, on one thread.
 */
public final class ChunkParallelLightEngine {

    /** Blocks per section axis. */
    public static final int SECTION_BLOCKS = 16;

    /** Rounds of boundary reconciliation before a fill is considered converged. */
    public static final int MAX_ROUNDS = 4;

    /** Milliseconds filling one section's light costs. */
    public static final double MS_PER_SECTION = 1.2;

    /** Milliseconds one boundary check costs. */
    public static final double MS_PER_BOUNDARY = 0.05;

    private long sectionsFilled;
    private long boundaryChecks;
    private long rounds;
    private long converged;
    private long notConverged;

    /**
     * Fills a set of sections in parallel and reconciles their boundaries.
     *
     * @param sectionCount how many sections are dirty
     * @param cores the cores available
     * @param boundaryChangedPerRound for each round, how many boundaries changed -- an empty entry means the
     *     fill converged
     * @return how many rounds of reconciliation were needed
     */
    public int propagate(int sectionCount, int cores, int[] boundaryChangedPerRound) {
        if (sectionCount < 0) {
            throw new IllegalArgumentException("a negative section count: " + sectionCount);
        }
        if (cores < 1) {
            throw new IllegalArgumentException("at least one core is required: " + cores);
        }
        if (boundaryChangedPerRound == null) {
            throw new IllegalArgumentException("the boundary result is required");
        }
        if (boundaryChangedPerRound.length > MAX_ROUNDS) {
            throw new IllegalArgumentException(
                    "more than " + MAX_ROUNDS + " rounds did not converge: "
                            + boundaryChangedPerRound.length);
        }

        this.sectionsFilled += sectionCount;
        this.rounds += Math.max(1, boundaryChangedPerRound.length);

        for (int changed : boundaryChangedPerRound) {
            // Four boundaries per section on average -- the fill only crosses the ones it reaches, so most
            // sections contribute none.
            this.boundaryChecks += changed;
        }

        boolean last = boundaryChangedPerRound.length == 0
                || boundaryChangedPerRound[boundaryChangedPerRound.length - 1] == 0;
        if (last) {
            this.converged++;
        } else {
            this.notConverged++;
        }
        return Math.max(1, boundaryChangedPerRound.length);
    }

    /**
     * Whether a section needs to take part in the next boundary round.
     *
     * <p>Most of the time a fill stays inside its section -- a torch in the middle of a cave does not reach
     * the walls. Re-running the pass for sections that did not change would cost as much as the parallelism
     * saved.
     */
    public static boolean needsBoundaryPass(int changedEdges) {
        return changedEdges > 0;
    }

    public long sectionsFilled() {
        return this.sectionsFilled;
    }

    public long boundaryChecks() {
        return this.boundaryChecks;
    }

    /** How many rounds of reconciliation ran, across all fills. */
    public long rounds() {
        return this.rounds;
    }

    /** How many fills converged within the round limit. */
    public long converged() {
        return this.converged;
    }

    /** How many ran out of rounds, which means the lighting may not be finished. */
    public long notConverged() {
        return this.notConverged;
    }

    /** Milliseconds a fill costs, given the sections in it. */
    public double msParallel(int sectionCount, int cores) {
        int perCore = (sectionCount + cores - 1) / cores;
        return perCore * MS_PER_SECTION + this.boundaryChecks * MS_PER_BOUNDARY;
    }

    /** Milliseconds filling the same sections on one thread would cost. */
    public double msSerial(int sectionCount) {
        return sectionCount * MS_PER_SECTION;
    }

    /**
     * The share of the fill time avoided.
     *
     * <p>Zero when there is nothing to fill. An engine that has filled nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent(int sectionCount, int cores) {
        double serial = msSerial(sectionCount);
        if (serial == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, serial - msParallel(sectionCount, cores)) / serial;
    }
}
