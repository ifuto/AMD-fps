package net.amdfaster.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTimeRecorderTest {

    private static final long MS = 1_000_000L;

    @Test
    void aSteadyFramerateReportsTheSameNumberEveryWay() {
        // The baseline case. If the mean, the percentiles, the 1% low and the worst frame disagree
        // on a perfectly steady series, the statistics are wrong before they are interesting.
        FrameTimeRecorder recorder = new FrameTimeRecorder(1000);
        for (int i = 0; i < 1000; i++) {
            recorder.record(16 * MS);
        }

        assertEquals(1000, recorder.sampleCount());
        assertEquals(16 * MS, recorder.meanFrameNanos());
        assertEquals(62.5, recorder.fps(), 1e-9);
        assertEquals(62.5, recorder.onePercentLowFps(), 1e-9, "a steady series has no bad tail");
        assertEquals(16 * MS, recorder.percentileFrameNanos(50));
        assertEquals(16 * MS, recorder.percentileFrameNanos(99));
        assertEquals(16 * MS, recorder.worstFrameNanos());
        assertEquals(0, recorder.stdDevFrameNanos());
    }

    @Test
    void hitchesAreInvisibleToTheAverageAndThePercentilesButNotToTheOnePercentLow() {
        // The reason this class reports more than a mean. Ten 200 ms hitches in a thousand frames:
        // the average barely moves, and the 95th and 99th percentiles do not move at all, because
        // with nearest-rank on a thousand samples the 99th percentile is the 990th frame and the
        // hitches are frames 991 to 1000. Only the 1% low -- which averages exactly those ten --
        // reports the stutter, and it reports 5 fps against a headline of 56.
        //
        // Minecraft is chunk rebuilds and GC pauses, so this is not a synthetic worry: a benchmark
        // that only reported an average would favour whichever implementation allocated most freely.
        FrameTimeRecorder recorder = new FrameTimeRecorder(1000);
        for (int i = 0; i < 990; i++) {
            recorder.record(16 * MS);
        }
        for (int i = 0; i < 10; i++) {
            recorder.record(200 * MS);
        }

        assertEquals(17_840_000L, recorder.meanFrameNanos());
        assertEquals(56.0538, recorder.fps(), 0.001, "the average barely moves");
        assertEquals(16 * MS, recorder.percentileFrameNanos(95), "p95 does not see it");
        assertEquals(16 * MS, recorder.percentileFrameNanos(99), "p99 does not see it either");
        assertEquals(5.0, recorder.onePercentLowFps(), 1e-9, "the 1% low sees exactly this");
        assertEquals(200 * MS, recorder.worstFrameNanos());
        assertTrue(recorder.stdDevFrameNanos() > 10 * MS, "and so does the spread");
    }

    @Test
    void theRingKeepsTheMostRecentFramesAndNothingElse() {
        // Capacity 8, twenty frames recorded: only the last eight survive. Getting this wrong in
        // either direction is bad -- keeping everything grows the heap over a long session, and
        // keeping the wrong ones makes the statistics describe a frame rate from minutes ago.
        FrameTimeRecorder recorder = new FrameTimeRecorder(8);
        for (int i = 0; i < 20; i++) {
            recorder.record(i * 1000L);
        }

        assertEquals(8, recorder.sampleCount(), "the ring is full and stays full");
        assertEquals(20, recorder.totalRecorded(), "but it remembers how much it saw");
        assertEquals(8, recorder.capacity());

        long[] sorted = recorder.sortedSnapshot();
        for (int i = 0; i < 8; i++) {
            assertEquals((12 + i) * 1000L, sorted[i], "slot " + i + " of the surviving window");
        }
    }

    @Test
    void aPartiallyFilledRingReportsTheFramesItActuallyHolds() {
        // The case that caught a real bug. Before the ring fills, head points one past the newest
        // sample, so walking the window from head read uninitialised zeros and skipped the real
        // frames. It was silent in exactly the way these things usually are: the zeros sort to the
        // front, every statistic still returned a number, and the mean -- which sums the backing
        // array directly rather than walking the window -- was unaffected, so the two disagreed
        // without either looking wrong.
        //
        // The ring-wrap test above could not catch it, because once the ring is full head really is
        // the oldest slot and the walk was correct.
        FrameTimeRecorder recorder = new FrameTimeRecorder(8);
        recorder.record(30 * MS);
        recorder.record(10 * MS);
        recorder.record(20 * MS);

        assertEquals(3, recorder.sampleCount());
        assertEquals(20 * MS, recorder.meanFrameNanos());
        assertEquals(10 * MS, recorder.percentileFrameNanos(50), "the median of 10, 20, 30");
        assertEquals(30 * MS, recorder.worstFrameNanos());
        // The worst one percent of three frames rounds up to one frame: the 30 ms one.
        assertEquals(1_000_000_000.0 / (30 * MS), recorder.onePercentLowFps(), 1e-9);

        long[] sorted = recorder.sortedSnapshot();
        assertEquals(3, sorted.length, "the snapshot holds the samples, not the whole ring");
        assertEquals(10 * MS, sorted[0]);
        assertEquals(20 * MS, sorted[1]);
        assertEquals(30 * MS, sorted[2]);
    }

    @Test
    void anEmptyRecorderAnswersZeroRatherThanDividingByZero() {
        FrameTimeRecorder recorder = new FrameTimeRecorder();
        assertEquals(0, recorder.sampleCount());
        assertEquals(0, recorder.meanFrameNanos());
        assertEquals(0.0, recorder.fps(), 0.0);
        assertEquals(0.0, recorder.onePercentLowFps(), 0.0);
        assertEquals(0, recorder.percentileFrameNanos(99));
        assertEquals(0, recorder.stdDevFrameNanos());
        assertEquals(0, recorder.worstFrameNanos());
        assertEquals(0, recorder.totalRecorded());
    }

    @Test
    void aSingleFrameIsItsOwnOnePercentLow() {
        // With fewer than a hundred frames the worst-one-percent bucket rounds up to one frame.
        // That is the honest reading at that sample size, and sampleCount is what tells the reader
        // how much weight to give it.
        FrameTimeRecorder recorder = new FrameTimeRecorder(4);
        recorder.record(20 * MS);
        assertEquals(1, recorder.sampleCount());
        assertEquals(50.0, recorder.onePercentLowFps(), 1e-9);
        assertEquals(20 * MS, recorder.percentileFrameNanos(50));
        assertEquals(20 * MS, recorder.percentileFrameNanos(100));
    }

    @Test
    void percentilesUseNearestRankAndAlwaysReturnAFrameThatHappened() {
        // Nearest-rank rather than interpolation, so the answer is always a real sample. An
        // interpolated percentile can report a duration no frame ever took.
        FrameTimeRecorder recorder = new FrameTimeRecorder(10);
        for (int i = 1; i <= 10; i++) {
            recorder.record(i * MS);
        }

        assertEquals(1 * MS, recorder.percentileFrameNanos(0), "p0 clamps to the best frame");
        assertEquals(1 * MS, recorder.percentileFrameNanos(1));
        assertEquals(5 * MS, recorder.percentileFrameNanos(50));
        assertEquals(9 * MS, recorder.percentileFrameNanos(90));
        assertEquals(10 * MS, recorder.percentileFrameNanos(99), "p99 rounds up to the worst");
        assertEquals(10 * MS, recorder.percentileFrameNanos(100));

        long[] sorted = recorder.sortedSnapshot();
        for (long sample : sorted) {
            boolean real = false;
            for (int i = 1; i <= 10; i++) {
                real |= sample == i * MS;
            }
            assertTrue(real, "every reported value is a frame that was actually recorded");
        }
    }

    @Test
    void lowFpsGeneralisesToAnyWorstCasePercentage() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(10);
        for (int i = 1; i <= 10; i++) {
            recorder.record(i * MS);
        }
        // Worst 10% of ten frames is the single 10 ms frame.
        assertEquals(100.0, recorder.lowFps(10), 1e-9);
        // Worst 100% is the whole set, mean 5.5 ms.
        assertEquals(1_000_000_000.0 / (5.5 * MS), recorder.lowFps(100), 1e-6);
        assertTrue(recorder.lowFps(10) <= recorder.fps(), "a worst-case figure cannot beat the mean");
    }

    @Test
    void clearResetsTheWindowAndTheCounters() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(4);
        for (int i = 0; i < 10; i++) {
            recorder.record(16 * MS);
        }
        recorder.clear();
        assertEquals(0, recorder.sampleCount());
        assertEquals(0, recorder.totalRecorded());
        assertEquals(0, recorder.meanFrameNanos());
        assertEquals(0.0, recorder.fps(), 0.0);

        recorder.record(10 * MS);
        assertEquals(1, recorder.sampleCount(), "and it records normally afterwards");
        assertEquals(100.0, recorder.fps(), 1e-9);
    }

    @Test
    void nonsenseInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrameTimeRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameTimeRecorder(-1));

        FrameTimeRecorder recorder = new FrameTimeRecorder(4);
        assertThrows(IllegalArgumentException.class, () -> recorder.record(-1),
                "a negative frame time would corrupt the mean and the percentiles");
        assertThrows(IllegalArgumentException.class, () -> recorder.percentileFrameNanos(-1));
        assertThrows(IllegalArgumentException.class, () -> recorder.percentileFrameNanos(101));
        assertThrows(IllegalArgumentException.class, () -> recorder.lowFps(0));
        assertThrows(IllegalArgumentException.class, () -> recorder.lowFps(101));
    }
}
