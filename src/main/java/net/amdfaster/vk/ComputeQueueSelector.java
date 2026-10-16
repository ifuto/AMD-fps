package net.amdfaster.vk;

/**
 * Chooses which queue compute work goes on, because on AMD the dedicated one is slower.
 *
 * <p>A Vulkan device usually exposes more than one queue: one that runs graphics, and one or more that run
 * compute only. The obvious choice for compute work is the dedicated queue, and on most hardware that is the
 * right one -- it lets the compute run alongside the frame instead of waiting for a gap in it.
 *
 * <p>On AMD it is not. The driver inserts a memory-fence stall on the dedicated compute queue, which costs
 * more than the overlap gains, so routing the same dispatch through the graphics queue is measurably faster.
 * Choosing by vendor rather than by what looks correct is the whole point.
 *
 * <h2>It is an AMD-specific choice</h2>
 *
 * <p>Applying it everywhere would make Intel and NVIDIA hardware slower, because on those the dedicated queue
 * does what it is supposed to and the overlap is real. A rule that is right for one vendor and wrong for two
 * others has to be keyed on the vendor, and a fallback that assumes the general case is the safe default.
 *
 * <h2>Sharing a queue serialises the two</h2>
 *
 * <p>Work on one queue runs in the order it was submitted, so compute put on the graphics queue cannot run at
 * the same time as the frame -- it runs before or after it. That is the cost of the choice, and it is why the
 * rule is not "always use the graphics queue": it is worth it when the fence stall is bigger than the overlap
 * lost, which is what the measurements say on AMD.
 *
 * <h2>It costs fences, and saves them</h2>
 *
 * <p>Crossing between two queues needs a semaphore or a fence on each side, so a separate compute queue adds
 * synchronisation that a single queue does not. On AMD the driver adds a stall anyway, so the single queue
 * ends up with both fewer fences and less waiting -- which is why the choice is not merely neutral there.
 *
 * <h2>Why this is decided once, not per dispatch</h2>
 *
 * <p>The queue is a property of the device, not of the work. Deciding per dispatch would mean every dispatch
 * paying for a choice that cannot change, and could put two dispatches that share data on different queues,
 * which would need synchronisation between them that sharing a queue would not.
 *
 * <p>Thread safety: the choice is made at device creation, on the thread that creates the device.
 *
 * <p>Reference: llama.cpp's Vulkan backend routes compute dispatches through the graphics queue on AMD to
 * avoid a driver bug that adds a memory-fence stall on the dedicated compute queue. This is an independent
 * implementation of the same reasoning.
 */
public final class ComputeQueueSelector {

    /** Milliseconds the driver's fence stall costs on an AMD dedicated compute queue. */
    public static final double MS_AMD_FENCE_STALL = 0.35;

    /** Milliseconds of overlap lost by putting compute on the graphics queue. */
    public static final double MS_OVERLAP_LOST = 0.20;

    /** Milliseconds one cross-queue synchronisation costs. */
    public static final double MS_CROSS_QUEUE_SYNC = 0.05;

    /** The queues a device may expose. */
    public enum Queue {
        /** Runs graphics and, if asked, compute. */
        GRAPHICS,
        /** Runs compute only. */
        DEDICATED_COMPUTE
    }

    /**
     * Which queue compute should go on.
     *
     * @param amd whether the device is an AMD GPU
     * @param hasDedicatedCompute whether the device exposes a compute-only queue at all
     */
    public static Queue choose(boolean amd, boolean hasDedicatedCompute) {
        if (!hasDedicatedCompute) {
            // There is no choice to make, and assuming there is one would fail at queue creation rather
            // than at the point the decision was made.
            return Queue.GRAPHICS;
        }
        if (amd) {
            // The driver inserts a memory-fence stall on the dedicated compute queue, which costs more than
            // the overlap gains. On other hardware the dedicated queue does what it is supposed to and the
            // overlap is real, so applying this everywhere would make them slower.
            return Queue.GRAPHICS;
        }
        return Queue.DEDICATED_COMPUTE;
    }

    /**
     * The cost of a frame's compute, given where it runs.
     *
     * @param queue where the compute was put
     * @param amd whether the device is an AMD GPU
     * @param dispatches how many dispatches the frame makes
     */
    public static double msCost(Queue queue, boolean amd, int dispatches) {
        if (queue == null) {
            throw new IllegalArgumentException("a queue is required");
        }
        if (dispatches < 0) {
            throw new IllegalArgumentException("a negative dispatch count: " + dispatches);
        }
        if (queue == Queue.GRAPHICS) {
            // Sharing a queue means the compute runs before or after the frame rather than alongside it.
            return dispatches * MS_OVERLAP_LOST;
        }
        double stall = amd ? MS_AMD_FENCE_STALL : 0.0;
        return dispatches * (MS_CROSS_QUEUE_SYNC + stall);
    }

    /**
     * Whether the AMD rule actually helps, given the dispatch count.
     *
     * <p>It is not a matter of principle: with no dispatches there is nothing to save, and the two costs are
     * compared rather than assumed.
     *
     * @param dispatches how many dispatches the frame makes
     */
    public static boolean amdRuleHelps(int dispatches) {
        if (dispatches < 0) {
            throw new IllegalArgumentException("a negative dispatch count: " + dispatches);
        }
        if (dispatches == 0) {
            return false;
        }
        return msCost(Queue.GRAPHICS, true, dispatches)
                < msCost(Queue.DEDICATED_COMPUTE, true, dispatches);
    }

    /**
     * The share of the compute cost the AMD rule removes.
     *
     * <p>Zero when there are no dispatches, so a frame that does no compute is not reported as having saved
     * something.
     */
    public static double savedPercent(int dispatches) {
        if (dispatches <= 0) {
            return 0.0;
        }
        double dedicated = msCost(Queue.DEDICATED_COMPUTE, true, dispatches);
        double graphics = msCost(Queue.GRAPHICS, true, dispatches);
        return 100.0 * Math.max(0.0, dedicated - graphics) / dedicated;
    }
}
