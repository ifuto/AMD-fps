package net.amdfaster.gpu;

/**
 * Issues one indirect draw per batch, with the visible list written on the GPU.
 *
 * <p>The usual path has the CPU walk every object, decide whether it is visible, and issue a draw for the ones
 * that are. With thousands of sections that is thousands of decisions and thousands of draw submissions, all of
 * it on the thread that is also doing everything else, and the submissions are the part that shows up as a
 * CPU-bound frame on integrated graphics.
 *
 * <p>Indirect drawing moves the decision to the GPU. A compute shader culls and writes the survivors into a
 * buffer, and one draw per batch consumes that buffer -- the draw arguments come from the GPU rather than from
 * CPU code. The CPU issues a handful of draws whatever the scene contains.
 *
 * <h2>The instance count is reset every frame</h2>
 *
 * <p>The compute shader increments a counter for each visible object, so the counter has to start at zero. If
 * it were left at last frame's value, this frame's draws would include objects that are no longer visible --
 * geometry that was culled last frame and is culled again this one would still be drawn, and the count would
 * grow until it overflowed. Resetting is not a cleanup step, it is part of how the count is produced.
 *
 * <h2>The barriers are in order</h2>
 *
 * <p>The compute shader writes the draw parameters and the graphics pipeline reads them. Without a barrier
 * between the two, the draw can start before the write has landed, and the result is a frame drawn from
 * partially written arguments -- which is not a wrong frame, it is a different wrong frame each time, and
 * utterly unreproducible. So the sequence is fixed: uploads visible to compute, compute finished before
 * graphics, and the order is not something a caller can rearrange.
 *
 * <h2>The object identity rides on the instance index</h2>
 *
 * <p>A draw with a thousand instances needs each one to know which object it is, so it can fetch the right
 * transform. The instance index is used to look that up in an indirection buffer the compute shader filled,
 * which is what lets one draw cover a thousand different objects rather than a thousand copies of one.
 *
 * <h2>Why this is not the same as the draw call merger</h2>
 *
 * <p>{@link net.amdfaster.draw.DrawCallMerger} combines draws that are already decided, so the CPU still makes
 * every visibility decision. This moves the decision itself to the GPU, so the CPU's cost stops depending on
 * how much of the scene is visible -- which matters most in exactly the scenes where it is all visible and the
 * merger has nothing to combine.
 *
 * <p>Not thread safe. Command recording happens on the render thread.
 */
public final class IndirectDrawBatcher {

    /** Milliseconds one CPU-issued draw submission costs. */
    public static final double MS_PER_SUBMISSION = 0.008;

    /** Milliseconds one indirect draw submission costs. */
    public static final double MS_PER_INDIRECT = 0.012;

    /** Milliseconds the compute culling dispatch costs, once per batch. */
    public static final double MS_PER_DISPATCH = 0.05;

    private final int[] stages = new int[3];

    private long batches;
    private long draws;
    private long objects;
    private long resets;

    /** The three barrier stages, in the only order they may be issued. */
    public static final int TRANSFER_TO_COMPUTE = 0;
    public static final int COMPUTE_DISPATCH = 1;
    public static final int COMPUTE_TO_GRAPHICS = 2;

    /**
     * Records a batch: a reset, a dispatch, and one indirect draw.
     *
     * @param objectCount how many objects the batch covers, visible or not
     * @return the number of draw submissions, which is one
     */
    public int recordBatch(int objectCount) {
        if (objectCount < 0) {
            throw new IllegalArgumentException("a negative object count: " + objectCount);
        }
        // The counter has to start at zero. Left at last frame's value, this frame's draws would include
        // objects that are no longer visible, and the count would grow until it overflowed.
        this.resets++;

        this.stages[TRANSFER_TO_COMPUTE]++;
        this.stages[COMPUTE_DISPATCH]++;
        this.stages[COMPUTE_TO_GRAPHICS]++;

        this.batches++;
        this.objects += objectCount;
        this.draws++;
        return 1;
    }

    /**
     * Whether the barrier sequence is in order.
     *
     * <p>The compute shader writes the draw parameters and the graphics pipeline reads them. Without the
     * barrier between, the draw can start before the write has landed, and the result is a frame drawn from
     * partially written arguments -- not a wrong frame but a different wrong frame each time, utterly
     * unreproducible.
     */
    public boolean barriersInOrder() {
        return this.stages[TRANSFER_TO_COMPUTE] == this.stages[COMPUTE_DISPATCH]
                && this.stages[COMPUTE_DISPATCH] == this.stages[COMPUTE_TO_GRAPHICS]
                && this.stages[COMPUTE_TO_GRAPHICS] == this.batches;
    }

    /** How many resets have run, which must equal the batch count. */
    public long resets() {
        return this.resets;
    }

    public long batches() {
        return this.batches;
    }

    /** How many draw submissions were issued. */
    public long draws() {
        return this.draws;
    }

    /** How many objects the batches covered. */
    public long objects() {
        return this.objects;
    }

    /** Milliseconds of submission this costs. */
    public double msSubmitting() {
        return this.draws * MS_PER_INDIRECT + this.batches * MS_PER_DISPATCH;
    }

    /** Milliseconds one CPU draw per object would cost. */
    public double msWithoutIndirect() {
        return this.objects * MS_PER_SUBMISSION;
    }

    /**
     * The share of the submission cost avoided.
     *
     * <p>Zero before any batch has been recorded. A batcher that has recorded nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     *
     * <p>The dispatch cost is included, because it is work this approach adds rather than work that happens
     * either way -- a batcher whose dispatch cost more than the submissions it saved would report that.
     */
    public double savedPercent() {
        double without = msWithoutIndirect();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msSubmitting()) / without;
    }
}
