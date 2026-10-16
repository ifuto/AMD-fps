package net.amdfaster.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCommandSchedulerTest {

    @Test
    void aDriverWithoutTheExtensionRecordsOnTheCpu() {
        // A driver that does not expose device generated commands is not a broken driver. The frame is recorded
        // on the CPU as it always was and the GPU gets the same commands -- the only difference is that the two
        // do not overlap.
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(false);

        assertTrue(scheduler.frame(true));
        assertFalse(scheduler.usesDeviceGeneration());
        assertEquals(1L, scheduler.cpuRecorded());
        assertEquals(0L, scheduler.generated());
    }

    @Test
    void aReadyBufferIsExecuted() {
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(true);

        assertTrue(scheduler.frame(true));
        assertEquals(1L, scheduler.generated());
    }

    @Test
    void anUnfinishedBufferIsSkippedNotExecuted() {
        // Executing a buffer that is still being generated is not a slow frame, it is executing whatever
        // happens to be in the buffer. Skipping is a dropped frame, which is visible and recoverable; executing
        // a half-built buffer is corruption.
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(true);

        assertFalse(scheduler.frame(false));
        assertEquals(1L, scheduler.skippedNotReady());
        assertEquals(0L, scheduler.generated());
    }

    @Test
    void generationOverlapsThePreviousFramesExecution() {
        // The frame costs whichever of the two is longer, rather than both in turn.
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(true);
        scheduler.frame(true);

        assertEquals(6.0, scheduler.msPerFrame(), 1e-9, "the GPU execution, not the sum");
        assertEquals(9.0, scheduler.msPerFrameWithout(), 1e-9);
        assertEquals(33.33, scheduler.savedPercent(), 0.01);
    }

    @Test
    void withoutTheExtensionTheFrameCostsBoth() {
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(false);
        scheduler.frame(true);

        assertEquals(9.0, scheduler.msPerFrame(), 1e-9);
        assertEquals(0.0, scheduler.savedPercent(), 1e-9);
    }

    @Test
    void aLongSessionTracksBothPaths() {
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(true);

        for (int i = 0; i < 100; i++) {
            scheduler.frame(i % 50 != 0);
        }

        assertEquals(100L, scheduler.frames());
        assertEquals(98L, scheduler.generated());
        assertEquals(2L, scheduler.skippedNotReady());
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has run no frames has technically overlapped everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        GeneratedCommandScheduler scheduler = new GeneratedCommandScheduler(true);

        assertEquals(0.0, scheduler.savedPercent());
        assertEquals(0L, scheduler.frames());
        assertEquals(0L, scheduler.generated());
        assertEquals(0L, scheduler.cpuRecorded());
        assertEquals(0L, scheduler.skippedNotReady());
        assertEquals(3.0, GeneratedCommandScheduler.MS_CPU_RECORD, 1e-12);
        assertEquals(6.0, GeneratedCommandScheduler.MS_GPU_EXECUTE, 1e-12);
    }
}
