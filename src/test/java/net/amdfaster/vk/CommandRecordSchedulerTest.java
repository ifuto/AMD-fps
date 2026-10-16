package net.amdfaster.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRecordSchedulerTest {

    @Test
    void theBatchCountIsTheCoreCount() {
        // Too few batches and the slowest one decides the frame time, because the others finish and wait.
        // Too many and the cost of allocating and submitting the buffers outweighs the recording saved.
        assertEquals(8, new CommandRecordScheduler(8).batchCount());
        assertEquals(1, new CommandRecordScheduler(1).batchCount());
    }

    @Test
    void sectionsAreSpreadEvenlyAcrossTheBatches() {
        CommandRecordScheduler scheduler = new CommandRecordScheduler(4);

        assertEquals(250, scheduler.sectionsPerBatch(1000));
        assertEquals(251, scheduler.sectionsPerBatch(1001), "the remainder goes in the first batches");
        assertEquals(1, scheduler.sectionsPerBatch(1), "fewer sections than cores");
    }

    @Test
    void theFrameWaitsForTheSlowestBatchNotAllOfThem() {
        // The batches run at the same time, so the frame waits for the slowest one, not for the sum.
        CommandRecordScheduler parallel = new CommandRecordScheduler(8);

        assertEquals(250 * CommandRecordScheduler.MS_PER_SECTION
                + CommandRecordScheduler.MS_PER_BUFFER, parallel.msRecording(2000), 1e-9);
        assertEquals(2000 * CommandRecordScheduler.MS_PER_SECTION,
                parallel.msRecordingSerially(2000), 1e-9);
    }

    @Test
    void parallelRecordingSavesMostOfTheTime() {
        CommandRecordScheduler scheduler = new CommandRecordScheduler(8);

        assertTrue(scheduler.recordBatch(250));
        scheduler.endFrame();

        assertEquals(250L, scheduler.recorded());
        assertEquals(1L, scheduler.frames());
        assertEquals(86.25, scheduler.savedPercent(2000), 0.01,
                "0.55 ms for the slowest batch against 4.0 recorded serially");
    }

    @Test
    void oneCoreSavesNothing() {
        // The honest case: parallelism with one core is just the extra buffer cost.
        CommandRecordScheduler scheduler = new CommandRecordScheduler(1);

        assertEquals(0.0, scheduler.savedPercent(2000), 1e-9,
                "the whole frame on one batch, plus the buffer");
    }

    @Test
    void aFailedBatchIsRecordedRatherThanThrown() {
        // If recording one batch throws, the rest are still valid and the frame can be drawn without it,
        // which is a section missing for one frame rather than a crash.
        CommandRecordScheduler scheduler = new CommandRecordScheduler(4);

        scheduler.recordBatch(250);
        scheduler.recordFailure();
        scheduler.recordBatch(250);
        scheduler.endFrame();

        assertEquals(1L, scheduler.failedBatches());
        assertEquals(500L, scheduler.recorded(), "the other batches still went in");
        assertEquals(1L, scheduler.frames(), "and the frame still completed");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CommandRecordScheduler(0));
        assertThrows(IllegalArgumentException.class, () -> new CommandRecordScheduler(-1));

        CommandRecordScheduler scheduler = new CommandRecordScheduler(4);
        assertThrows(IllegalArgumentException.class, () -> scheduler.sectionsPerBatch(-1));
        assertThrows(IllegalArgumentException.class, () -> scheduler.recordBatch(-1));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has recorded no sections has technically avoided everything, but reporting that
        // as a saving would describe an unused one as though it had done something.
        CommandRecordScheduler scheduler = new CommandRecordScheduler(8);

        assertEquals(0.0, scheduler.savedPercent(0));
        assertEquals(0.0, scheduler.msRecordingSerially(0), 1e-12);
        assertEquals(0L, scheduler.recorded());
        assertEquals(0L, scheduler.frames());
        assertEquals(0L, scheduler.failedBatches());
    }
}
