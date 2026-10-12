package net.amdfaster.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramePacerTest {

    private static final long MS = 1_000_000L;
    private static final long FPS_60 = 16_666_667L;

    @Test
    void aFastFrameUnderACapSleepsForTheDifference() {
        // This is the whole mechanism. The CPU finished in 10 ms with a 60 fps cap, so the frame would
        // otherwise sit in a render queue for the remaining 6.67 ms. Sleeping instead means the input is
        // sampled 6.67 ms later, which is 6.67 ms closer to the moment it affects.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);

        assertEquals(6_666_667L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void aFrameThatOverrunsTheCapNeverSleeps() {
        // Sleeping would only make it later. The pacer's job is to remove queue wait, not to add delay.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(20 * MS, 0);

        assertEquals(0L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void aFrameExactlyAtTheCapSleepsAlmostNothing() {
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(16 * MS, 0);

        assertEquals(666_667L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void anUncappedGameNeverSleeps() {
        // With no presentation deadline there is nothing to aim for and something to lose.
        FramePacer pacer = FramePacer.lowLatency();
        pacer.noteFrame(10 * MS, 0);

        assertEquals(0L, pacer.targetIntervalNanos());
        assertEquals(0L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void maxThroughputModeNeverSleeps() {
        FramePacer pacer = new FramePacer(FramePacer.Mode.MAX_THROUGHPUT, FPS_60);
        pacer.noteFrame(1 * MS, 0);

        assertEquals(0L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void beforeAnyFrameHasBeenMeasuredThePacerDoesNothing() {
        // A guess here costs frames; doing nothing costs latency. Latency is the cheaper mistake.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);

        assertFalse(pacer.hasMeasurement());
        assertEquals(0L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void theAverageTracksAStepChangeWithTheExpectedIntegerSteps() {
        // 8/256 is a window of about 32 frames, roughly half a second at 60 fps. Short enough to follow a
        // scene change, long enough that one hitch does not shorten the next sleep. The arithmetic is
        // integer throughout, so the steps are exact and checkable.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);
        assertEquals(10_000_000L, pacer.smoothedWallNanos(), "the first sample is taken as-is");

        pacer.noteFrame(20 * MS, 0);
        assertEquals(10_312_500L, pacer.smoothedWallNanos(),
                "delta 10ms times 8 over 256 is exactly 312500ns");

        pacer.noteFrame(20 * MS, 0);
        assertEquals(10_615_234L, pacer.smoothedWallNanos(),
                "delta 9687500 times 8 over 256 truncates to 302734");
    }

    @Test
    void aSteadyFrameLeavesTheAverageUnchanged() {
        // If a constant input moved the average, the sleep would drift and the pacer would oscillate
        // against the cap rather than settling on it.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);
        long first = pacer.smoothedWallNanos();
        for (int i = 0; i < 100; i++) {
            pacer.noteFrame(10 * MS, 0);
        }

        assertEquals(first, pacer.smoothedWallNanos());
        assertEquals(6_666_667L, pacer.sleepBeforeFrameNanos());
        assertEquals(101L, pacer.framesNoted());
    }

    @Test
    void implausibleMeasurementsAreDiscardedRatherThanAveraged() {
        // Zero or negative means the clock went backwards or nothing was measured; feeding that in would
        // drag the average toward zero and lengthen every later sleep. Over a second means the window was
        // dragged or the game paused, which says nothing about steady-state pacing.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);

        pacer.noteFrame(0, 0);
        pacer.noteFrame(-1, 0);
        pacer.noteFrame(1_000_000_001L, 0);

        assertEquals(3L, pacer.discardedMeasurements());
        assertEquals(10_000_000L, pacer.smoothedWallNanos(), "the average is untouched");
        assertEquals(4L, pacer.framesNoted(),
                "all four calls are frames seen -- the one usable sample plus the three rejected");
    }

    @Test
    void aSleepLongerThanHalfA30fpsFrameIsClamped() {
        // Past that, something is wrong with the measurements, and running early is better than being
        // visibly late.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, 1_000_000_000L);
        pacer.noteFrame(1, 0);

        assertEquals(FramePacer.MAX_SLEEP_NANOS, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void changingTheCapTakesEffectImmediately() {
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);
        assertEquals(6_666_667L, pacer.sleepBeforeFrameNanos());

        pacer.setTargetInterval(33_333_333L);
        assertEquals(FramePacer.MAX_SLEEP_NANOS, pacer.sleepBeforeFrameNanos(),
                "a 30 fps cap against a 10 ms frame asks for 23.33 ms, which the clamp cuts to 16.67");
        assertTrue(33_333_333L - 10_000_000L > FramePacer.MAX_SLEEP_NANOS,
                "which is only a clamp because the raw request does exceed the limit");

        pacer.setTargetInterval(0L);
        assertEquals(0L, pacer.sleepBeforeFrameNanos(), "and uncapping stops the sleeping");
    }

    @Test
    void aNegativeCapIsTreatedAsUncapped() {
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, -5L);
        pacer.noteFrame(10 * MS, 0);

        assertEquals(0L, pacer.targetIntervalNanos());
        assertEquals(0L, pacer.sleepBeforeFrameNanos());
    }

    @Test
    void askingAndSleepingAreCountedSeparately() {
        // A caller may skip the sleep while a resize or a world load is in progress, so the number of
        // sleeps actually taken is not the number asked for.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 0);

        long asked = pacer.sleepBeforeFrameNanos();
        assertEquals(0L, pacer.sleepsIssued(), "asking is not sleeping");

        pacer.noteSleepTaken(asked);
        assertEquals(1L, pacer.sleepsIssued());
        assertEquals(asked, pacer.totalSleepNanos());

        pacer.noteSleepTaken(0L);
        assertEquals(1L, pacer.sleepsIssued(), "a zero sleep is not a sleep");
    }

    @Test
    void thePresentWaitIsRecordedForDiagnostics() {
        // The wait is what the pacer is trying to drive to zero, so it has to be readable.
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        assertEquals(0L, pacer.lastPresentWaitNanos());

        pacer.noteFrame(10 * MS, 4 * MS);
        assertEquals(4 * MS, pacer.lastPresentWaitNanos());

        pacer.noteFrame(10 * MS, -3L);
        assertEquals(0L, pacer.lastPresentWaitNanos(), "a negative wait is clamped, not stored");
    }

    @Test
    void resetDropsTheHistorySoAWorldChangeDoesNotPaceAgainstTheOldOne() {
        FramePacer pacer = new FramePacer(FramePacer.Mode.LOW_LATENCY, FPS_60);
        pacer.noteFrame(10 * MS, 3 * MS);
        pacer.noteSleepTaken(1 * MS);

        pacer.reset();

        assertFalse(pacer.hasMeasurement());
        assertEquals(0L, pacer.smoothedWallNanos());
        assertEquals(0L, pacer.lastPresentWaitNanos());
        assertEquals(0L, pacer.framesNoted());
        assertEquals(0L, pacer.sleepsIssued());
        assertEquals(0L, pacer.totalSleepNanos());
        assertEquals(0L, pacer.discardedMeasurements());
        assertTrue(pacer.sleepBeforeFrameNanos() == 0L);
    }
}
