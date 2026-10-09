package net.amdfaster.perf;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the frame time actually goes, by named stage.
 *
 * <p>{@link FrameTimeRecorder} says how bad a frame was. This says which part of it was
 * responsible, which is the difference between knowing there is a problem and knowing what to
 * change. Minecraft's client cost splits into a handful of stages -- meshing, light sampling,
 * culling, upload, draw -- and they are bottlenecked by different things: meshing is CPU and
 * allocation, upload is PCIe and write-combining, draw is command submission. Optimising the wrong
 * one is the most common way to spend a week and move nothing.
 *
 * <p>Stage names are interned at registration so recording is an array index and not a hash lookup.
 * A timer that costs more than the code it measures is worse than no timer, and this one runs once
 * per stage per frame, which for meshing means once per section rebuilt -- not often enough to need
 * heroics, but often enough that a map lookup per call would show up.
 *
 * <p>Usage is {@code begin(stage)} / {@code end(stage)}, or {@link #run} for a lambda. Nesting is
 * allowed and is the point: a "rebuild" stage can contain "mesh" and "light" stages, and the
 * breakdown then shows both the total and its parts. What this does not do is subtract children from
 * parents -- the numbers overlap by design, and reading them as a partition would be wrong.
 *
 * <p>Not thread safe. Parallel meshing needs one timer per worker, merged with {@link #addFrom}.
 */
public final class StageTimer {

    private static final int INITIAL_STAGES = 16;

    private String[] names = new String[INITIAL_STAGES];
    private long[] totals = new long[INITIAL_STAGES];
    private long[] counts = new long[INITIAL_STAGES];
    private long[] worst = new long[INITIAL_STAGES];
    private long[] openSince = new long[INITIAL_STAGES];
    private int stageCount;

    /** Registers a stage and returns its index. Registering the same name twice returns the same index. */
    public int stage(String name) {
        for (int i = 0; i < this.stageCount; i++) {
            if (this.names[i].equals(name)) {
                return i;
            }
        }
        if (this.stageCount == this.names.length) {
            int next = this.stageCount * 2;
            this.names = Arrays.copyOf(this.names, next);
            this.totals = Arrays.copyOf(this.totals, next);
            this.counts = Arrays.copyOf(this.counts, next);
            this.worst = Arrays.copyOf(this.worst, next);
            this.openSince = Arrays.copyOf(this.openSince, next);
        }
        this.names[this.stageCount] = name;
        return this.stageCount++;
    }

    /** Starts timing a stage. Uses {@link System#nanoTime()}, whose epoch is arbitrary. */
    public void begin(int stage) {
        this.openSince[stage] = System.nanoTime();
    }

    /** Stops timing a stage and adds the elapsed time. Returns the elapsed nanoseconds. */
    public long end(int stage) {
        long elapsed = System.nanoTime() - this.openSince[stage];
        return add(stage, elapsed);
    }

    /**
     * Records a duration measured elsewhere.
     *
     * <p>Separate from begin/end so a duration that came from somewhere else -- a GPU timestamp
     * query, a worker thread's own clock -- can go in the same breakdown. GPU time cannot be
     * measured by wrapping a call in nanoTime; it needs
     * {@code VK_QUERY_TYPE_TIMESTAMP}, and this is where that number lands.
     */
    public long add(int stage, long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("negative duration for " + this.names[stage] + ": " + nanos);
        }
        this.totals[stage] += nanos;
        this.counts[stage]++;
        if (nanos > this.worst[stage]) {
            this.worst[stage] = nanos;
        }
        return nanos;
    }

    /** Runs an action with the stage timed around it. */
    public void run(int stage, Runnable action) {
        begin(stage);
        try {
            action.run();
        } finally {
            // Timed even when the action throws: a stage that fails is still a stage that cost
            // something, and losing the sample would bias the average towards the fast runs.
            end(stage);
        }
    }

    public int stageCount() {
        return this.stageCount;
    }

    public String name(int stage) {
        return this.names[stage];
    }

    public long totalNanos(int stage) {
        return this.totals[stage];
    }

    public long count(int stage) {
        return this.counts[stage];
    }

    /** Mean duration per invocation, or 0 when the stage has never run. */
    public long meanNanos(int stage) {
        return this.counts[stage] == 0 ? 0 : this.totals[stage] / this.counts[stage];
    }

    /** Slowest single invocation recorded for the stage. */
    public long worstNanos(int stage) {
        return this.worst[stage];
    }

    /** Sums another timer's samples in, for merging per-thread timers after parallel work. */
    public void addFrom(StageTimer other) {
        for (int i = 0; i < other.stageCount; i++) {
            if (other.counts[i] == 0) {
                continue;
            }
            int stage = stage(other.names[i]);
            this.totals[stage] += other.totals[i];
            this.counts[stage] += other.counts[i];
            if (other.worst[i] > this.worst[stage]) {
                this.worst[stage] = other.worst[i];
            }
        }
    }

    public void clear() {
        Arrays.fill(this.totals, 0, this.stageCount, 0L);
        Arrays.fill(this.counts, 0, this.stageCount, 0L);
        Arrays.fill(this.worst, 0, this.stageCount, 0L);
        Arrays.fill(this.openSince, 0, this.stageCount, 0L);
    }

    /**
     * A multi-line breakdown ordered by total time, largest first.
     *
     * <p>Ordered by total rather than by registration because the question this answers is "what
     * should I look at first", and the answer is whatever dominates.
     *
     * @param spanNanos the interval the totals are a fraction of -- a frame, or the whole session.
     *                  Zero or negative omits the percentage column rather than dividing by zero.
     */
    public String report(long spanNanos) {
        Integer[] order = new Integer[this.stageCount];
        for (int i = 0; i < this.stageCount; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(this.totals[b], this.totals[a]));

        StringBuilder out = new StringBuilder();
        for (int rank = 0; rank < order.length; rank++) {
            int i = order[rank];
            out.append(this.names[i])
                    .append(": total ").append(this.totals[i] / 1_000).append(" us")
                    .append(", n=").append(this.counts[i])
                    .append(", mean ").append(this.counts[i] == 0 ? 0 : this.totals[i] / this.counts[i] / 1_000)
                    .append(" us, worst ").append(this.worst[i] / 1_000).append(" us");
            if (spanNanos > 0) {
                out.append(String.format(", %.1f%% of span", 100.0 * this.totals[i] / spanNanos));
            }
            if (rank < order.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Stage name to total nanoseconds, in registration order. For assertions and for the command output. */
    public Map<String, Long> totalsByName() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < this.stageCount; i++) {
            out.put(this.names[i], this.totals[i]);
        }
        return out;
    }
}
