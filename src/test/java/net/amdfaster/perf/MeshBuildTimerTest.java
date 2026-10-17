package net.amdfaster.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Every figure here was printed by a model of the same accumulation before the test was written.
 */
class MeshBuildTimerTest {

    @Test
    void totalMeanAndWorstAgree() {
        MeshBuildTimer timer = new MeshBuildTimer();

        timer.record(1_000_000L);
        timer.record(3_000_000L);
        timer.record(2_000_000L);

        assertEquals(3, timer.builds());
        assertEquals(6_000_000L, timer.totalNanos());
        assertEquals(2_000_000L, timer.meanNanos(), "6 ms over three builds");
        assertEquals(3_000_000L, timer.worstNanos());
    }

    @Test
    void theMeanHidesTheHitchAndTheWorstDoesNot() {
        MeshBuildTimer timer = new MeshBuildTimer();

        // The case the class exists for: twenty ordinary builds and one that stalls.
        for (int i = 0; i < 20; i++) {
            timer.record(1_000_000L);
        }
        timer.record(40_000_000L);

        assertEquals(21, timer.builds());
        assertEquals(2_857_142L, timer.meanNanos(), "2.86 ms, which reads as fine");
        assertEquals(40_000_000L, timer.worstNanos(), "and the 40 ms build is the one that was felt");
    }

    @Test
    void nothingBuiltMeansZeroAndNotADivisionByZero() {
        MeshBuildTimer timer = new MeshBuildTimer();

        assertEquals(0, timer.builds());
        assertEquals(0L, timer.totalNanos());
        assertEquals(0L, timer.meanNanos(), "'no rebuilds yet' is not 'rebuilds are free'");
        assertEquals(0L, timer.worstNanos());
    }

    @Test
    void aBuildThatThrowsIsStillTimed() {
        MeshBuildTimer timer = new MeshBuildTimer();

        assertThrows(IllegalStateException.class,
                () -> timer.time(() -> {
                    throw new IllegalStateException("the build failed");
                }));

        assertEquals(1, timer.builds(),
                "a build that fails still cost the time it took; dropping it would make a crashing mesh free");
    }

    @Test
    void timingWrapsTheAction() {
        MeshBuildTimer timer = new MeshBuildTimer();
        AtomicInteger ran = new AtomicInteger();

        timer.time(ran::incrementAndGet);
        timer.time(ran::incrementAndGet);

        assertEquals(2, ran.get(), "the action ran");
        assertEquals(2, timer.builds(), "and both were timed");
        assertTrue(timer.totalNanos() >= 0, "a duration was recorded");
    }

    @Test
    void clearDropsEverything() {
        MeshBuildTimer timer = new MeshBuildTimer();
        timer.record(5_000_000L);

        timer.clear();

        assertEquals(0, timer.builds());
        assertEquals(0L, timer.totalNanos());
        assertEquals(0L, timer.meanNanos());
        assertEquals(0L, timer.worstNanos(), "the worst goes too, or the next world inherits it");
    }

    @Test
    void concurrentBuildsDoNotLoseTheWorst() {
        MeshBuildTimer timer = new MeshBuildTimer();
        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final long nanos = (t + 1) * 1_000_000L;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        timer.record(nanos);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the workers", e);
        }

        assertEquals((long) threads * perThread, timer.builds(), "no count lost");
        // The largest value any thread wrote is 8 ms. A read-then-write maximum would lose it under
        // contention, which is the whole reason this is a compare-and-set loop.
        assertEquals(8_000_000L, timer.worstNanos(), "the worst survived the contention");
        // Each thread wrote 1..8 ms, so the mean is the average of 1 through 8.
        assertEquals(4_500_000L, timer.meanNanos(), "the mean of 1..8 ms is 4.5 ms");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshBuildTimer timer = new MeshBuildTimer();

        assertThrows(IllegalArgumentException.class, () -> timer.record(-1L),
                "a negative duration would skew the mean downwards");
        assertThrows(IllegalArgumentException.class, () -> timer.time(null), "an action is required");
    }
}
