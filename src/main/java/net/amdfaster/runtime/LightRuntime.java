package net.amdfaster.runtime;

import net.amdfaster.light.BlockLightProperties;
import net.amdfaster.light.ChunkParallelLightEngine;
import net.amdfaster.light.LightCache;
import net.amdfaster.light.LightDirtyTracker;
import net.amdfaster.light.LightEngine;
import net.amdfaster.light.LightUpdateBatcher;
import net.amdfaster.light.SectionLightStore;

/**
 * Runs the lighting decisions in one place, and rebuilds only the sections that changed.
 *
 * <p>Lighting is the expensive part of a block change. Placing one block does not change one value -- it changes
 * every block the light reaches, which for a torch in a dark cave is thousands of them, and each of those changes
 * has to reach the meshes that draw those blocks. Doing all of it on the frame the block was placed is a hitch
 * exactly when the player is interacting.
 *
 * <p>This spreads it: the propagation runs across cores, the updates are batched into a budget, and only the
 * sections whose light actually changed are rebuilt.
 *
 * <h2>Only the sections that changed are rebuilt</h2>
 *
 * <p>A light change that reaches a section but leaves every value the same still needs no rebuild, and rebuilding
 * it would cost a mesh build for nothing. The dirty tracker compares a checksum before and after, so a section
 * whose light did not actually change is skipped -- which is the common case at the edge of a light's reach, where
 * the propagation arrives and finds nothing left to do.
 *
 * <h2>The updates are batched into a budget</h2>
 *
 * <p>Applying every pending light update on one frame is the hitch this exists to remove. So the batcher applies
 * as many as its budget allows and the rest wait for the next frame, which spreads the cost over the frames the
 * player is watching rather than concentrating it on the one where they clicked.
 *
 * <h2>The propagation runs across cores</h2>
 *
 * <p>Sections are independent except at their boundaries, so most of the work parallelises and only the boundaries
 * need a second pass. The result has to match what a single-threaded propagation would produce, because lighting
 * that differs by a level is visible as a seam, and a seam along a section boundary is a straight line across the
 * world.
 *
 * <h2>The properties table is shared, not per section</h2>
 *
 * <p>How opaque a block is and how much light it emits are properties of the block, not of where it is, so one
 * table serves every section. A table per section would be two hundred copies of the same numbers, which is the
 * duplication the memory work exists to remove.
 *
 * <p>Not thread safe at this level. The propagation inside is designed for worker threads, but the batching and
 * the dirty tracking happen on the client thread.
 */
public final class LightRuntime {

    private final BlockLightProperties properties = BlockLightProperties.forMinecraft();
    private final SectionLightStore store = new SectionLightStore();
    private final LightUpdateBatcher batcher = new LightUpdateBatcher();
    private final ChunkParallelLightEngine parallel = new ChunkParallelLightEngine();
    private final LightDirtyTracker dirty = new LightDirtyTracker();

    private long sectionsPropagated;
    private long rebuilds;
    private long rebuildsSkipped;

    /** The shared block light properties table. */
    public BlockLightProperties properties() {
        return this.properties;
    }

    /** The per-section light store. */
    public SectionLightStore store() {
        return this.store;
    }

    /** The update batcher. */
    public LightUpdateBatcher batcher() {
        return this.batcher;
    }

    /** The parallel propagation engine. */
    public ChunkParallelLightEngine parallel() {
        return this.parallel;
    }

    /** The dirty tracker. */
    public LightDirtyTracker dirty() {
        return this.dirty;
    }

    /**
     * Propagates light across a number of sections.
     *
     * @param sectionCount how many sections to propagate
     * @param cores how many cores to use
     * @param boundaryChangedPerRound how many boundaries changed in each round, from the propagation
     * @return how many rounds the boundaries needed
     */
    public int propagate(int sectionCount, int cores, int[] boundaryChangedPerRound) {
        this.sectionsPropagated += sectionCount;
        return this.parallel.propagate(sectionCount, cores, boundaryChangedPerRound);
    }

    /**
     * Decides whether a section needs its mesh rebuilt after a light change.
     *
     * <p>A light change that reaches a section but leaves every value the same still needs no rebuild, and
     * rebuilding it would cost a mesh build for nothing -- which is the common case at the edge of a light's
     * reach, where the propagation arrives and finds nothing left to do.
     *
     * @param checksumBefore the section's light checksum before the change
     * @param checksumAfter the checksum after
     * @return true if the mesh needs rebuilding
     */
    public boolean needsRebuild(long checksumBefore, long checksumAfter) {
        if (checksumBefore == checksumAfter) {
            this.rebuildsSkipped++;
            return false;
        }
        this.rebuilds++;
        return true;
    }

    /**
     * Reports a light change at a position, which the batcher will apply within its budget.
     *
     * <p>Applying every pending light update on one frame is the hitch this exists to remove, so the batcher
     * holds the change and applies as many as its budget allows when it is processed.
     */
    public void onLightChange(int x, int y, int z) {
        this.batcher.addChange(x, y, z);
    }

    /** How many light changes are waiting to be applied. */
    public int pendingLightChanges() {
        return this.batcher.pendingCount();
    }

    /** How many sections have been propagated this session. */
    public long sectionsPropagated() {
        return this.sectionsPropagated;
    }

    /** How many sections needed a rebuild. */
    public long rebuilds() {
        return this.rebuilds;
    }

    /** How many were skipped because their light did not actually change. */
    public long rebuildsSkipped() {
        return this.rebuildsSkipped;
    }

    /**
     * The share of the sections reached by a light change that needed no rebuild.
     *
     * <p>Zero before any section has been checked. A runtime that has checked nothing has technically skipped
     * everything, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double rebuildsSkippedPercent() {
        long total = this.rebuilds + this.rebuildsSkipped;
        return total == 0 ? 0.0 : 100.0 * this.rebuildsSkipped / total;
    }

    /** Clears everything, on a world change. */
    public void onWorldChange() {
        this.sectionsPropagated = 0;
        this.rebuilds = 0;
        this.rebuildsSkipped = 0;
    }
}
