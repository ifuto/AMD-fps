package net.amdfaster.perf;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Times section mesh builds, which is the "chunk rebuild time" figure the benchmark has to report.
 *
 * <h2>Why this is not a {@link StageTimer} stage</h2>
 *
 * <p>{@code StageTimer} is explicitly not thread safe, and its own note says parallel meshing needs one
 * timer per worker merged with {@code addFrom}. Mesh builds run on the worker pool's threads, several at
 * once, so recording into one shared {@code StageTimer} would corrupt its arrays -- and the corruption
 * would show up as a rebuild time that is wrong in a way that looks plausible.
 *
 * <p>The alternative of one {@code StageTimer} per worker and merging them has a different problem: the
 * merge would have to happen on a schedule, and between merges the reported number would be missing the
 * work in flight. For a figure whose whole purpose is to say whether meshing is the bottleneck, a number
 * that lags by an unknown interval is not useful.
 *
 * <p>So the accumulation here is done with counters that are correct under concurrency rather than with a
 * timer that is not. {@link LongAdder} for the total and count, because they are written from every worker
 * on every build and are built for exactly that; {@link AtomicLong} with a compare-and-set loop for the
 * worst, because a maximum cannot be accumulated -- it has to be contended for.
 *
 * <h2>Mean and worst, not just mean</h2>
 *
 * <p>A mean rebuild time hides the case that actually matters. Meshing twenty sections at 1 ms and one at
 * 40 ms averages 2.9 ms and looks fine, but the 40 ms one is a hitch the player felt. Both numbers are
 * kept because they answer different questions: the mean says what meshing costs in aggregate, the worst
 * says what the single worst chunk did.
 *
 * <p>Thread safe.
 */
public final class MeshBuildTimer {

    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder builds = new LongAdder();
    private final AtomicLong worstNanos = new AtomicLong();

    /**
     * Times one build.
     *
     * <p>Wraps the build rather than being handed a duration, because a duration passed in by the caller is
     * a duration the caller measured, and the point of this figure is that it measures the build itself.
     *
     * @param action the build to time
     */
    public void time(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("an action is required");
        }
        long startedAt = System.nanoTime();
        try {
            action.run();
        } finally {
            // Recorded even when the build throws. A build that fails still cost the time it took, and
            // dropping it would make a crashing mesh look free -- which is the one case where the timing
            // matters most, because that is the case where something is wrong.
            record(System.nanoTime() - startedAt);
        }
    }

    /**
     * Records a duration measured elsewhere.
     *
     * <p>Kept public so a build timed by a worker's own clock can go in the same figure, and so the
     * accumulation can be tested without depending on how long a real build takes.
     *
     * @param nanos the build duration; negative values are rejected rather than silently skewing the mean
     */
    public void record(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("a build cannot take " + nanos + " ns");
        }
        this.totalNanos.add(nanos);
        this.builds.increment();
        // A maximum is contended for, not accumulated: two workers finishing at once must not lose the
        // larger of the two, which is what a plain read-then-write would do.
        long seen = this.worstNanos.get();
        while (nanos > seen && !this.worstNanos.compareAndSet(seen, nanos)) {
            seen = this.worstNanos.get();
        }
    }

    /** How many builds have been timed. */
    public long builds() {
        return this.builds.sum();
    }

    /** Total time spent building, in nanoseconds. */
    public long totalNanos() {
        return this.totalNanos.sum();
    }

    /**
     * Mean build time in nanoseconds.
     *
     * <p>Zero when nothing has been built, rather than a division by zero. "No rebuilds yet" and "rebuilds
     * are free" are different statements and only the first one is true.
     */
    public long meanNanos() {
        long count = this.builds.sum();
        return count == 0 ? 0L : this.totalNanos.sum() / count;
    }

    /** The slowest single build, in nanoseconds. */
    public long worstNanos() {
        return this.worstNanos.get();
    }

    /** Drops the accumulated figures, for a world change. */
    public void clear() {
        this.totalNanos.reset();
        this.builds.reset();
        this.worstNanos.set(0L);
    }
}
