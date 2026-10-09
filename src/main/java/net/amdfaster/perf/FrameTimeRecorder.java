package net.amdfaster.perf;

import java.util.Arrays;

/**
 * A fixed-capacity ring of frame times, and the statistics that decide whether a change actually
 * helped.
 *
 * <p>Why this exists before anything else. Every optimisation in this mod has so far been argued
 * from a paper's measurement on someone else's workload, because there was nothing here that could
 * measure this one. That is not a way to choose between two implementations, and it is not a way to
 * notice that one of them made things worse.
 *
 * <p><b>The average is the wrong headline number</b>, and that is the whole reason this class does
 * more than divide. A frame time series with ten 200 ms hitches in a thousand frames averages
 * 54 fps and reads as fine; its 95th and 99th percentiles both read 16.7 ms, because the hitches
 * are exactly the tail those percentiles stop short of. Only the 1% low -- the average of the worst
 * one percent of frames -- reports it, and it reports 5 fps. Minecraft is a game of chunk rebuilds
 * and garbage collection pauses, which is to say a game of hitches, so a benchmark that only
 * reports an average will systematically favour whatever allocates most freely.
 *
 * <p>Capacity is fixed and old frames are overwritten, so a long session cannot grow the heap and
 * the statistics stay about recent behaviour rather than about the whole play session. The window
 * is a size, not a duration: at 30 fps and at 300 fps the same capacity covers different spans of
 * time, which is the right trade for "how does this feel now" and the wrong one for comparing two
 * runs of different length. Comparisons must therefore use the same frame count, and
 * {@link #sampleCount()} is exposed so that can be checked rather than assumed.
 *
 * <p>Not thread safe. Frame times arrive from the render thread; a stage timer on another thread
 * gets its own instance.
 */
public final class FrameTimeRecorder {

    /** Frames kept by default: about four seconds at 60 fps, twenty at 300. */
    public static final int DEFAULT_CAPACITY = 240;

    private final long[] nanos;
    private final int capacity;
    private int head;
    private int count;

    /** Total frames ever recorded, including the ones the ring has overwritten. */
    private long totalRecorded;

    public FrameTimeRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public FrameTimeRecorder(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.nanos = new long[capacity];
    }

    /** Records one frame's duration in nanoseconds. */
    public void record(long frameNanos) {
        if (frameNanos < 0) {
            throw new IllegalArgumentException("negative frame time: " + frameNanos);
        }
        this.nanos[this.head] = frameNanos;
        this.head = (this.head + 1) % this.capacity;
        if (this.count < this.capacity) {
            this.count++;
        }
        this.totalRecorded++;
    }

    /** Frames currently held. Less than the capacity until the ring has filled once. */
    public int sampleCount() {
        return this.count;
    }

    /** Frames ever recorded, including overwritten ones. */
    public long totalRecorded() {
        return this.totalRecorded;
    }

    public int capacity() {
        return this.capacity;
    }

    public void clear() {
        Arrays.fill(this.nanos, 0, this.count, 0L);
        this.head = 0;
        this.count = 0;
        this.totalRecorded = 0;
    }

    /** The held frame times in ascending order. Allocated per call; do not call it in a hot path. */
    public long[] sortedSnapshot() {
        long[] sorted = new long[this.count];
        for (int i = 0; i < this.count; i++) {
            sorted[i] = this.nanos[(this.head + i) % this.capacity];
        }
        Arrays.sort(sorted);
        return sorted;
    }

    /** Mean frame time in nanoseconds, or 0 when nothing has been recorded. */
    public long meanFrameNanos() {
        if (this.count == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < this.count; i++) {
            sum += this.nanos[i];
        }
        return sum / this.count;
    }

    /** Frames per second from the mean frame time, or 0 when nothing has been recorded. */
    public double fps() {
        long mean = meanFrameNanos();
        return mean == 0 ? 0.0 : 1_000_000_000.0 / mean;
    }

    /**
     * The frame time at a percentile, using the nearest-rank definition: the smallest sample such
     * that at least {@code p} percent of the held frames are at or below it.
     *
     * <p>Nearest-rank rather than interpolation because it always returns a frame time that really
     * happened. An interpolated percentile can report a duration no frame ever took, which is a
     * confusing thing to put in front of someone trying to reproduce a stutter.
     *
     * @param p 0 to 100 inclusive
     */
    public long percentileFrameNanos(double p) {
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException("percentile outside 0..100: " + p);
        }
        if (this.count == 0) {
            return 0;
        }
        long[] sorted = sortedSnapshot();
        int rank = (int) Math.ceil(p / 100.0 * this.count);
        return sorted[Math.min(Math.max(rank, 1), this.count) - 1];
    }

    /**
     * Frames per second computed from the average of the slowest one percent of held frames.
     *
     * <p>At least one frame is always used, so a recorder holding fewer than a hundred frames still
     * answers -- it answers with its single worst frame, which is the honest reading of "1% low" at
     * that sample size, and {@link #sampleCount()} says how much weight to give it.
     */
    public double onePercentLowFps() {
        return lowFps(1.0);
    }

    /** The same measure over an arbitrary worst-case percentage. */
    public double lowFps(double worstPercent) {
        if (worstPercent <= 0 || worstPercent > 100) {
            throw new IllegalArgumentException("worst percent outside (0,100]: " + worstPercent);
        }
        if (this.count == 0) {
            return 0.0;
        }
        long[] sorted = sortedSnapshot();
        int bucket = (int) Math.ceil(worstPercent / 100.0 * this.count);
        bucket = Math.min(Math.max(bucket, 1), this.count);
        long sum = 0;
        for (int i = this.count - bucket; i < this.count; i++) {
            sum += sorted[i];
        }
        long mean = sum / bucket;
        return mean == 0 ? 0.0 : 1_000_000_000.0 / mean;
    }

    /**
     * Population standard deviation of the held frame times, in nanoseconds.
     *
     * <p>Reported alongside the percentiles rather than instead of them. Two runs can share a p99
     * and differ completely in how often they miss it, and the spread is what separates a steady
     * 60 from a 60 that oscillates.
     */
    public long stdDevFrameNanos() {
        if (this.count == 0) {
            return 0;
        }
        double mean = meanFrameNanos();
        double sum = 0;
        for (int i = 0; i < this.count; i++) {
            double d = this.nanos[i] - mean;
            sum += d * d;
        }
        return (long) Math.sqrt(sum / this.count);
    }

    /**
     * Worst frame held, in nanoseconds.
     *
     * <p>Kept separate from the percentiles because a single pathological frame -- a shader compile,
     * a chunk allocation, a garbage collection pause -- is exactly what a percentile is designed to
     * ignore and exactly what a player reports as a stutter.
     */
    public long worstFrameNanos() {
        long worst = 0;
        for (int i = 0; i < this.count; i++) {
            if (this.nanos[i] > worst) {
                worst = this.nanos[i];
            }
        }
        return worst;
    }
}
