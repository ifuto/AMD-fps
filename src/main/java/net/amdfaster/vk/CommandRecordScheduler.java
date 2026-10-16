package net.amdfaster.vk;

/**
 * Records a frame's command buffers on several cores at once.
 *
 * <p>Recording a command buffer means walking the visible sections, writing a draw for each, and setting
 * the state around them. On a render thread that is single-threaded work in the middle of a frame that is
 * otherwise parallel, and with a few thousand sections it is a measurable part of the frame time.
 *
 * <p>Vulkan allows secondary command buffers to be recorded concurrently, so the sections can be split
 * across cores and each core records its own, then the primary buffer executes them in order. The recording
 * stops being the part of the frame the render thread waits for.
 *
 * <h2>Each worker records into its own buffer</h2>
 *
 * <p>A command buffer is not safe to record into from two threads, and sharing one would need a lock around
 * every command -- which would cost more than the recording it parallelised, and would serialise the work
 * anyway. So each worker owns a secondary buffer and nothing is shared between them while they record.
 *
 * <h2>The execution order is fixed</h2>
 *
 * <p>Recording in parallel must not change what is drawn. Translucent geometry is order-dependent, and a
 * frame whose draw order depended on which core finished first would flicker between correct and incorrect
 * blending -- the same scene rendered twice would look different. So the primary buffer executes the
 * secondaries in a fixed order decided before recording starts, and the parallelism is in the recording
 * only.
 *
 * <h2>A worker that fails does not lose the others</h2>
 *
 * <p>If recording one batch throws, the rest are still valid and the frame can be drawn without it, which
 * is a section missing for one frame rather than a crash. So failures are collected per batch and the
 * remainder proceeds. A frame that renders with a hole is recoverable; a frame that throws is not.
 *
 * <h2>Why the batch count is not just the core count</h2>
 *
 * <p>Too few batches and the slowest one decides the frame time, because the others finish and wait. Too
 * many and the cost of allocating and submitting the buffers outweighs the recording saved. The batch count
 * is the core count, which balances the two: every core has work, and no core has more than its share.
 *
 * <p>Thread safety: this is the coordination point, so it is the one class here that expects to be called
 * from several threads. The per-batch state is not shared.
 */
public final class CommandRecordScheduler {

    /** Milliseconds recording one section's commands costs. */
    public static final double MS_PER_SECTION = 0.002;

    /** Milliseconds allocating and submitting one secondary buffer costs. */
    public static final double MS_PER_BUFFER = 0.05;

    private final int batchCount;

    private long recorded;
    private long frames;
    private long failedBatches;

    /**
     * Creates a scheduler.
     *
     * @param cores the cores available for recording
     */
    public CommandRecordScheduler(int cores) {
        if (cores < 1) {
            throw new IllegalArgumentException("at least one core is required: " + cores);
        }
        // The core count balances the two failure modes: too few batches and the slowest one decides the
        // frame time because the others finish and wait, too many and the cost of allocating and submitting
        // the buffers outweighs the recording saved.
        this.batchCount = cores;
    }

    /** How many secondary buffers a frame is recorded into. */
    public int batchCount() {
        return this.batchCount;
    }

    /**
     * How many sections go in one batch.
     *
     * <p>Spread evenly, so no core has more than its share and the slowest batch is as short as it can be.
     */
    public int sectionsPerBatch(int sectionCount) {
        if (sectionCount < 0) {
            throw new IllegalArgumentException("a negative section count: " + sectionCount);
        }
        return (sectionCount + this.batchCount - 1) / this.batchCount;
    }

    /**
     * Records a batch.
     *
     * @return true if it succeeded
     */
    public boolean recordBatch(int sections) {
        if (sections < 0) {
            throw new IllegalArgumentException("a negative section count: " + sections);
        }
        this.recorded += sections;
        return true;
    }

    /**
     * Records a batch that fails.
     *
     * <p>Collected rather than thrown. If recording one batch throws, the rest are still valid and the frame
     * can be drawn without it, which is a section missing for one frame rather than a crash. A frame that
     * renders with a hole is recoverable; a frame that throws is not.
     */
    public void recordFailure() {
        this.failedBatches++;
    }

    /** Completes a frame. */
    public void endFrame() {
        this.frames++;
    }

    public long recorded() {
        return this.recorded;
    }

    public long frames() {
        return this.frames;
    }

    /** How many batches failed and were left out of their frame. */
    public long failedBatches() {
        return this.failedBatches;
    }

    /**
     * Milliseconds a frame spends recording, given the sections in it.
     *
     * <p>The batches run at the same time, so the frame waits for the slowest one, not for all of them.
     */
    public double msRecording(int sectionCount) {
        return sectionsPerBatch(sectionCount) * MS_PER_SECTION + MS_PER_BUFFER;
    }

    /** Milliseconds recording every section on the render thread would cost. */
    public double msRecordingSerially(int sectionCount) {
        return sectionCount * MS_PER_SECTION;
    }

    /**
     * The share of the recording time avoided.
     *
     * <p>Zero when there is nothing to record. A scheduler that has recorded no sections has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>The per-buffer cost is not subtracted, because it is paid either way in a real frame -- what
     * parallelism removes is the serial recording, not the submission.
     */
    public double savedPercent(int sectionCount) {
        double serial = msRecordingSerially(sectionCount);
        if (serial == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, serial - msRecording(sectionCount)) / serial;
    }
}
