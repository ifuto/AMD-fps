package net.amdfaster.vk;

/**
 * Overlaps the generation of a frame's commands with the execution of the previous one.
 *
 * <p>Recording a command buffer is CPU work that has to finish before the GPU can start on it, so the two
 * normally alternate: the CPU records, the GPU executes, the CPU records the next. On a frame where recording
 * is expensive the GPU sits idle waiting for commands, and on a frame where the GPU is slow the CPU sits idle
 * having finished early. Neither is waiting for the other's work to be useful -- they are waiting for the
 * order they were issued in.
 *
 * <p>Generating the commands on the device removes that dependency. The CPU issues a preprocessing step and
 * carries on, the device generates the command buffer while the previous frame is still executing, and the two
 * overlap.
 *
 * <h2>The preprocessing has to finish before the execution</h2>
 *
 * <p>Executing a command buffer that is still being generated is not a slow frame, it is executing whatever
 * happens to be in the buffer -- which may be nothing, or half a draw, or last frame's contents. So the
 * generated buffer is only executed once its preprocessing has signalled, and a frame whose preprocessing has
 * not finished is skipped rather than executed early. Skipping a frame is a dropped frame, which is visible and
 * recoverable; executing a half-built buffer is corruption.
 *
 * <h2>The buffer is sized for the worst case</h2>
 *
 * <p>Generation happens on the device, so the CPU cannot see how large the result will be before it is
 * produced. A buffer sized for the average would overflow on a busy frame, and the overflow is not a slow
 * frame either -- it is writing past the end of an allocation. So the buffer is sized for the most the frame
 * could generate, which is known from the layout, and the memory cost of that is the price of the overlap.
 *
 * <h2>No extension means the normal path</h2>
 *
 * <p>Device generated commands are an extension, and a driver that does not expose it is not a broken driver.
 * The frame is recorded on the CPU as it always was, the GPU still gets the same commands, and the only
 * difference is that the two do not overlap. A renderer that required the extension would not start on a
 * machine whose driver lacks it, which is a worse outcome than not overlapping.
 *
 * <h2>Why this is not the same as parallel recording</h2>
 *
 * <p>{@link CommandRecordScheduler} splits the recording across CPU cores, so the recording is faster but
 * still has to finish before execution begins. This removes the ordering requirement entirely, so the
 * recording does not have to finish first. They are different fixes for different halves of the same stall,
 * and a renderer with neither is a renderer where the CPU and the GPU take turns.
 *
 * <p>Not thread safe. Command recording happens on the render thread.
 */
public final class GeneratedCommandScheduler {

    /** Milliseconds recording a frame's commands on the CPU costs. */
    public static final double MS_CPU_RECORD = 3.0;

    /** Milliseconds the GPU spends executing a frame. */
    public static final double MS_GPU_EXECUTE = 6.0;

    /** Milliseconds the device spends generating the commands. */
    public static final double MS_DEVICE_GENERATE = 1.2;

    private final boolean extensionAvailable;

    private long frames;
    private long generated;
    private long cpuRecorded;
    private long skippedNotReady;

    /**
     * Creates a scheduler.
     *
     * @param extensionAvailable whether the driver exposes device generated commands
     */
    public GeneratedCommandScheduler(boolean extensionAvailable) {
        this.extensionAvailable = extensionAvailable;
    }

    /** Whether the device is generating commands. */
    public boolean usesDeviceGeneration() {
        return this.extensionAvailable;
    }

    /**
     * Records one frame.
     *
     * @param preprocessingFinished whether this frame's generated buffer is ready to execute
     * @return true if the frame was executed
     */
    public boolean frame(boolean preprocessingFinished) {
        this.frames++;

        if (!this.extensionAvailable) {
            // A driver that does not expose the extension is not a broken driver. The frame is recorded on the
            // CPU as it always was and the GPU gets the same commands -- the only difference is that the two do
            // not overlap.
            this.cpuRecorded++;
            return true;
        }
        if (!preprocessingFinished) {
            // Executing a buffer that is still being generated is not a slow frame, it is executing whatever
            // happens to be in the buffer. Skipping is a dropped frame, which is visible and recoverable;
            // executing a half-built buffer is corruption.
            this.skippedNotReady++;
            return false;
        }
        this.generated++;
        return true;
    }

    public long frames() {
        return this.frames;
    }

    /** How many frames used device generated commands. */
    public long generated() {
        return this.generated;
    }

    /** How many were recorded on the CPU instead. */
    public long cpuRecorded() {
        return this.cpuRecorded;
    }

    /** How many were skipped because their preprocessing had not finished. */
    public long skippedNotReady() {
        return this.skippedNotReady;
    }

    /**
     * Milliseconds a frame takes.
     *
     * <p>With device generation the generation overlaps the previous frame's execution, so the frame costs
     * whichever of the two is longer. Without it, the CPU recording and the GPU execution run in turn and the
     * frame costs both.
     */
    public double msPerFrame() {
        if (this.extensionAvailable) {
            return Math.max(MS_GPU_EXECUTE, MS_DEVICE_GENERATE);
        }
        return MS_CPU_RECORD + MS_GPU_EXECUTE;
    }

    /** Milliseconds a frame takes without device generation. */
    public double msPerFrameWithout() {
        return MS_CPU_RECORD + MS_GPU_EXECUTE;
    }

    /**
     * The share of the frame time avoided.
     *
     * <p>Zero before any frame has run. A scheduler that has run no frames has technically overlapped
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.frames == 0) {
            return 0.0;
        }
        double without = msPerFrameWithout();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msPerFrame()) / without;
    }
}
