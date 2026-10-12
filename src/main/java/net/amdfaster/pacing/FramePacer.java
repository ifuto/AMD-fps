package net.amdfaster.pacing;

/**
 * Decides when the next frame's CPU work should start, so that it finishes just in time rather than
 * early.
 *
 * <p>This is the mechanism behind Intel's Xe Low Latency, and the reasoning does not depend on Intel.
 * Most games poll for input at the start of a frame, run their logic, then submit. If the CPU is faster
 * than the GPU, the frame is submitted and then sits in a render queue waiting for the GPU and the
 * display. That wait is pure latency: the input it responded to is older than it needs to be.
 *
 * <p>The fix is to <b>sleep before collecting input</b>, for long enough that the frame completes close
 * to the moment it will be presented. The queue wait disappears, the CPU's work overlaps the GPU's, and
 * the input is sampled closer to the moment it affects. Intel's published figure is up to
 * <b>45 percent lower latency</b>, and the gains are largest exactly where Minecraft lives: GPU-bound
 * scenes, and any scene with VSync on or a frame rate limit set.
 *
 * <h2>What is measured and what is assumed</h2>
 *
 * <p>The sleep is derived from the <em>measured</em> wall span of recent frames rather than from a model
 * of CPU and GPU times. A model would need to know how much of the CPU work overlaps the GPU, which
 * varies with driver queueing and is not something this class can see. The wall span already includes
 * whatever overlap there was.
 *
 * <p>The span is smoothed with an exponential moving average. An unsmoothed value would make the sleep
 * chase individual frame spikes: one slow frame would shorten the next sleep, which would make that
 * frame submit earlier and wait longer, which is an oscillation rather than a correction.
 *
 * <h2>Failing open</h2>
 *
 * <p>Before any frame has been measured, and whenever a measurement is implausible, the sleep is zero
 * and the game runs at full throughput. A pacer that guesses wrong costs frames; a pacer that does
 * nothing costs latency. Latency is the cheaper mistake.
 *
 * <p>Not thread safe. Called from the render thread.
 */
public final class FramePacer {

    /** Whether the pacer is trying to reduce latency or to maximise throughput. */
    public enum Mode {
        /** Never sleep. Queue as many frames as the GPU will take. */
        MAX_THROUGHPUT,
        /** Sleep so the frame completes close to presentation. */
        LOW_LATENCY
    }

    /**
     * How much weight the newest frame carries in the moving average, out of 256.
     *
     * <p>Eight is a window of roughly 32 frames, which is about half a second at 60 fps. Short enough to
     * track a scene change, long enough that a single hitch does not shorten the next sleep.
     */
    public static final int SMOOTHING_NUMERATOR = 8;
    public static final int SMOOTHING_DENOMINATOR = 256;

    /**
     * The longest sleep the pacer will ever request, in nanoseconds.
     *
     * <p>Half of a 30 fps frame. Beyond that something is wrong with the measurements, and it is better
     * to run early than to be visibly late.
     */
    public static final long MAX_SLEEP_NANOS = 16_666_667L;

    private Mode mode;
    private long targetIntervalNanos;
    private long smoothedWallNanos;
    private boolean haveMeasurement;
    private long lastPresentWaitNanos;

    private long framesNoted;
    private long sleepsIssued;
    private long totalSleepNanos;
    private long discardedMeasurements;

    /**
     * Creates a pacer.
     *
     * @param mode initial mode
     * @param targetIntervalNanos the frame interval to aim for; 0 means uncapped, in which case the
     *                            pacer has nothing to pace against and never sleeps
     */
    public FramePacer(Mode mode, long targetIntervalNanos) {
        this.mode = mode;
        this.targetIntervalNanos = Math.max(0L, targetIntervalNanos);
    }

    /** A pacer in low-latency mode with no frame rate target, which is the useful default. */
    public static FramePacer lowLatency() {
        return new FramePacer(Mode.LOW_LATENCY, 0L);
    }

    /**
     * Sets the frame interval to pace against.
     *
     * @param nanos the interval, or 0 for uncapped
     */
    public void setTargetInterval(long nanos) {
        this.targetIntervalNanos = Math.max(0L, nanos);
    }

    /** The interval currently paced against, in nanoseconds. */
    public long targetIntervalNanos() {
        return this.targetIntervalNanos;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * Records one frame.
     *
     * @param wallNanos time from the start of the frame's CPU work to the completion of its submission
     * @param presentWaitNanos how long the frame waited between being ready and being presented; 0 if
     *                         not known
     */
    public void noteFrame(long wallNanos, long presentWaitNanos) {
        this.framesNoted++;
        this.lastPresentWaitNanos = Math.max(0L, presentWaitNanos);

        // A wall time of zero or less means the clock went backwards or the caller measured nothing.
        // Feeding that into the average would drag it toward zero and lengthen every subsequent sleep.
        // A wall time longer than a second means the game was paused or the window was dragged; it says
        // nothing about steady-state pacing.
        if (wallNanos <= 0L || wallNanos > 1_000_000_000L) {
            this.discardedMeasurements++;
            return;
        }

        if (!this.haveMeasurement) {
            this.smoothedWallNanos = wallNanos;
            this.haveMeasurement = true;
            return;
        }
        long delta = wallNanos - this.smoothedWallNanos;
        this.smoothedWallNanos += delta * SMOOTHING_NUMERATOR / SMOOTHING_DENOMINATOR;
    }

    /**
     * How long to sleep before starting the next frame.
     *
     * <p>Call this at the top of the frame loop, before polling input. That ordering is the whole
     * mechanism: sampling input after the sleep means the sample is closer to the moment it affects.
     *
     * @return nanos to sleep, never negative
     */
    public long sleepBeforeFrameNanos() {
        if (this.mode == Mode.MAX_THROUGHPUT) {
            return 0L;
        }
        if (this.targetIntervalNanos <= 0L) {
            // Uncapped. There is no presentation deadline to aim for, so there is nothing to gain from
            // sleeping and something to lose.
            return 0L;
        }
        if (!this.haveMeasurement) {
            return 0L;
        }
        long sleep = this.targetIntervalNanos - this.smoothedWallNanos;
        if (sleep <= 0L) {
            // The frame already takes longer than the interval. Sleeping would only make it later.
            return 0L;
        }
        if (sleep > MAX_SLEEP_NANOS) {
            sleep = MAX_SLEEP_NANOS;
        }
        return sleep;
    }

    /**
     * Records that a sleep was actually taken, so the HUD can show what the pacer is doing.
     *
     * <p>Separated from {@link #sleepBeforeFrameNanos} because asking and sleeping are different events:
     * a caller may skip the sleep when a resize or a world load is in progress.
     */
    public void noteSleepTaken(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        this.sleepsIssued++;
        this.totalSleepNanos += nanos;
    }

    /** The smoothed frame span the pacer is currently pacing against, in nanos. */
    public long smoothedWallNanos() {
        return this.smoothedWallNanos;
    }

    /** Whether at least one usable frame has been measured. */
    public boolean hasMeasurement() {
        return this.haveMeasurement;
    }

    /** The present wait of the most recent frame, in nanos. */
    public long lastPresentWaitNanos() {
        return this.lastPresentWaitNanos;
    }

    public long framesNoted() {
        return this.framesNoted;
    }

    public long sleepsIssued() {
        return this.sleepsIssued;
    }

    public long totalSleepNanos() {
        return this.totalSleepNanos;
    }

    /** Measurements rejected as implausible. */
    public long discardedMeasurements() {
        return this.discardedMeasurements;
    }

    /** Drops the measurement history, for example on a world change. */
    public void reset() {
        this.smoothedWallNanos = 0L;
        this.haveMeasurement = false;
        this.lastPresentWaitNanos = 0L;
        this.framesNoted = 0L;
        this.sleepsIssued = 0L;
        this.totalSleepNanos = 0L;
        this.discardedMeasurements = 0L;
    }
}
