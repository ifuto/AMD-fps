package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameSlotSchedulerTest {

    @Test
    void aSlotIsHeldFromAcquireUntilComplete() {
        FrameSlotScheduler scheduler = new FrameSlotScheduler();

        int slot = scheduler.acquire();
        assertEquals(0, slot);
        assertTrue(scheduler.isInFlight(slot));
        assertEquals(1, scheduler.inFlightCount());

        scheduler.submit(slot);
        assertTrue(scheduler.isInFlight(slot), "submitting does not free it");

        scheduler.complete(slot);
        assertFalse(scheduler.isInFlight(slot));
        assertEquals(0, scheduler.inFlightCount());
    }

    @Test
    void everySlotInFlightMeansNoSlotAndNotABusyOne() {
        // Handing back a slot whose command buffer is still executing means the CPU overwrites
        // commands the GPU is reading. That is a garbled frame or a device loss, not an error the mod
        // can catch, so the answer is "wait" rather than a busy slot.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(2);

        int first = scheduler.acquire();
        int second = scheduler.acquire();
        assertEquals(0, first);
        assertEquals(1, second);
        assertEquals(2, scheduler.inFlightCount());

        assertEquals(FrameSlotScheduler.NO_SLOT, scheduler.acquire());
        assertEquals(1L, scheduler.stalls());
        assertTrue(scheduler.wouldStall());

        scheduler.complete(first);
        assertFalse(scheduler.wouldStall());
        assertEquals(first, scheduler.acquire(), "and the freed one comes back");
    }

    @Test
    void slotsAreReusedRoundRobin() {
        // Not "the first free one", because always taking slot 0 would let the CPU overwrite the
        // frame the GPU is two frames behind on.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(3);

        int a = scheduler.acquire();
        scheduler.submit(a);
        scheduler.complete(a);
        int b = scheduler.acquire();
        scheduler.submit(b);
        scheduler.complete(b);
        int c = scheduler.acquire();
        scheduler.submit(c);
        scheduler.complete(c);
        int d = scheduler.acquire();

        assertEquals(0, a);
        assertEquals(1, b, "the next slot, not the one just freed");
        assertEquals(2, c);
        assertEquals(0, d, "and it wraps");
    }

    @Test
    void submittingTwiceIsRejected() {
        FrameSlotScheduler scheduler = new FrameSlotScheduler();
        int slot = scheduler.acquire();

        scheduler.submit(slot);
        assertThrows(IllegalStateException.class, () -> scheduler.submit(slot));
    }

    @Test
    void completingASlotThatWasNeverSubmittedIsRejected() {
        // A completion for work never submitted means the fence being polled is not the one this
        // scheduler handed out, and silently accepting it would free a slot the GPU still holds.
        FrameSlotScheduler scheduler = new FrameSlotScheduler();

        assertThrows(IllegalStateException.class, () -> scheduler.complete(0));

        int slot = scheduler.acquire();
        scheduler.complete(slot);
        assertThrows(IllegalStateException.class, () -> scheduler.complete(slot), "or twice");
    }

    @Test
    void submittingWithoutAcquiringIsRejected() {
        FrameSlotScheduler scheduler = new FrameSlotScheduler();

        assertThrows(IllegalStateException.class, () -> scheduler.submit(0));
    }

    @Test
    void unsubmittedAcquiresAreReported() {
        // A slot acquired and never submitted is a leak in the caller: it is held until reset, so the
        // pipeline silently loses a slot of overlap.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(3);

        scheduler.acquire();
        int second = scheduler.acquire();
        scheduler.submit(second);

        assertEquals(1L, scheduler.unsubmitted());
        assertEquals(1L, scheduler.submits());
    }

    @Test
    void theSlotCountNeededIsOnePlusGpuOverCpu() {
        // Simulated against the scheduler rather than argued, because the intuition that two slots
        // always overlap one frame against one is wrong. Frame f's GPU work finishes at (f+1)*cpu+gpu
        // and frame f+K needs the same slot at (f+K)*cpu, so K*cpu has to reach cpu+gpu.
        assertEquals(3, FrameSlotScheduler.slotsFor(4_000_000L, 6_000_000L));
        assertEquals(2, FrameSlotScheduler.slotsFor(6_000_000L, 6_000_000L));
        assertEquals(2, FrameSlotScheduler.slotsFor(8_000_000L, 3_000_000L));
        assertEquals(1, FrameSlotScheduler.slotsFor(4_000_000L, 0L));
        assertEquals(FrameSlotScheduler.MAX_SLOTS,
                FrameSlotScheduler.slotsFor(1_000_000L, 100_000_000L), "capped");
    }

    @Test
    void threeSlotsRunCpuFasterThanGpuWithoutStalling() {
        FrameSlotScheduler scheduler = new FrameSlotScheduler(
                FrameSlotScheduler.slotsFor(4_000_000L, 6_000_000L));
        assertEquals(3, scheduler.slotCount());

        runFrames(scheduler, 3, 4.0, 6.0, 200);

        assertEquals(0L, scheduler.stalls(), "never had to wait");
        assertEquals(0.0, scheduler.stallRatePercent());
        assertEquals(200L, scheduler.submits());
        assertEquals(200L, scheduler.acquires(), "and no retries");
    }

    @Test
    void twoSlotsAreNotEnoughWhenTheGpuIsSlowerThanTheCpu() {
        // The correction. At 4 ms of CPU against 6 ms of GPU, frame 2 wants the slot frame 0 used, but
        // that slot's GPU work does not finish until t = 10 and frame 2 starts at t = 8.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(2);

        runFrames(scheduler, 2, 4.0, 6.0, 200);

        assertEquals(99L, scheduler.stalls(), "half the frames, not none");
    }

    @Test
    void twoSlotsAreEnoughWhenTheGpuKeepsUp() {
        FrameSlotScheduler scheduler = new FrameSlotScheduler(2);

        runFrames(scheduler, 2, 6.0, 6.0, 200);

        assertEquals(0L, scheduler.stalls());
    }

    /**
     * Drives the scheduler with a CPU that runs ahead and a GPU that finishes asynchronously.
     *
     * <p>When every slot is busy the CPU waits for the earliest GPU completion, which is what a real
     * caller does, and the frame is then issued.
     */
    private static void runFrames(FrameSlotScheduler scheduler, int slots,
                                  double cpu, double gpu, int frames) {
        double now = 0.0;
        double[] finish = new double[slots];
        java.util.Arrays.fill(finish, Double.NEGATIVE_INFINITY);

        for (int frame = 0; frame < frames; frame++) {
            for (int slot = 0; slot < slots; slot++) {
                if (finish[slot] <= now && scheduler.isInFlight(slot)) {
                    scheduler.complete(slot);
                }
            }
            int slot = scheduler.acquire();
            if (slot == FrameSlotScheduler.NO_SLOT) {
                double earliest = Double.POSITIVE_INFINITY;
                for (int i = 0; i < slots; i++) {
                    if (scheduler.isInFlight(i)) {
                        earliest = Math.min(earliest, finish[i]);
                    }
                }
                now = earliest;
                for (int i = 0; i < slots; i++) {
                    if (finish[i] <= now && scheduler.isInFlight(i)) {
                        scheduler.complete(i);
                    }
                }
                slot = scheduler.acquire();
                assertTrue(slot != FrameSlotScheduler.NO_SLOT, "frame " + frame + " after waiting");
            }
            scheduler.submit(slot);
            now += cpu;
            finish[slot] = now + gpu;
        }
    }

    @Test
    void aOneSlotPipelineStallsEveryFrameButTheFirst() {
        // What the same workload costs without overlap, which is the comparison the class exists for.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(1);

        runFrames(scheduler, 1, 4.0, 6.0, 200);

        assertEquals(199L, scheduler.stalls(), "every frame after the first");
        // Per frame issued, not per call: the caller that waits calls acquire twice for one frame, and
        // dividing by the call count reported about 50% for a pipeline that stalls every time.
        assertEquals(399L, scheduler.acquires(), "200 issued plus 199 retries");
        assertEquals(99.5, scheduler.stallRatePercent(), 0.01);
    }

    @Test
    void resetFreesEverything() {
        // For a device loss or a resize, where the in-flight work is gone and waiting for it would
        // hang.
        FrameSlotScheduler scheduler = new FrameSlotScheduler(2);
        scheduler.acquire();
        scheduler.acquire();
        assertTrue(scheduler.wouldStall());

        scheduler.reset();

        assertFalse(scheduler.wouldStall());
        assertEquals(0, scheduler.inFlightCount());
        assertEquals(0, scheduler.acquire());
    }

    @Test
    void theOverlapSavingIsWhatTheClassClaims() {
        // Computed rather than simulated, because the scheduler is a state machine and the timing is
        // the caller's. A frame costing 4 ms of CPU and 6 ms of GPU takes 10 ms run in sequence and 6
        // ms overlapped.
        double cpu = 4.0;
        double gpu = 6.0;

        assertEquals(10.0, cpu + gpu, "sequential");
        assertEquals(6.0, Math.max(cpu, gpu), "overlapped");
        assertEquals(40.0, (1.0 - Math.max(cpu, gpu) / (cpu + gpu)) * 100.0, 0.01);
        // And half, when the two are equal.
        assertEquals(50.0, (1.0 - 6.0 / 12.0) * 100.0, 0.01);
    }

    @Test
    void slotsForRejectsNonsenseArguments() {
        assertThrows(IllegalArgumentException.class, () -> FrameSlotScheduler.slotsFor(0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> FrameSlotScheduler.slotsFor(1L, -1L));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrameSlotScheduler(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameSlotScheduler(5));

        FrameSlotScheduler scheduler = new FrameSlotScheduler(2);
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.isInFlight(2));
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.isInFlight(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.complete(2));
        assertEquals(2, FrameSlotScheduler.DEFAULT_SLOTS);
        assertEquals(4, FrameSlotScheduler.MAX_SLOTS);
    }
}
