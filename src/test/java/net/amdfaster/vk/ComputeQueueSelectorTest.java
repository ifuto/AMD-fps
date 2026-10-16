package net.amdfaster.vk;

import net.amdfaster.vk.ComputeQueueSelector.Queue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeQueueSelectorTest {

    @Test
    void amdComputeGoesOnTheGraphicsQueue() {
        // The driver inserts a memory-fence stall on the dedicated compute queue, which costs more than the
        // overlap gains.
        assertEquals(Queue.GRAPHICS, ComputeQueueSelector.choose(true, true));
    }

    @Test
    void otherVendorsKeepTheDedicatedQueue() {
        // On those the dedicated queue does what it is supposed to and the overlap is real, so applying the
        // AMD rule everywhere would make them slower.
        assertEquals(Queue.DEDICATED_COMPUTE, ComputeQueueSelector.choose(false, true));
    }

    @Test
    void aDeviceWithNoDedicatedQueueHasNoChoice() {
        // Assuming there is one would fail at queue creation rather than at the point the decision was made.
        assertEquals(Queue.GRAPHICS, ComputeQueueSelector.choose(true, false));
        assertEquals(Queue.GRAPHICS, ComputeQueueSelector.choose(false, false));
    }

    @Test
    void theAmdRuleIsCheaper() {
        assertEquals(2.0, ComputeQueueSelector.msCost(Queue.GRAPHICS, true, 10), 1e-9);
        assertEquals(4.0, ComputeQueueSelector.msCost(Queue.DEDICATED_COMPUTE, true, 10), 1e-9);
        assertTrue(ComputeQueueSelector.amdRuleHelps(10));
        assertEquals(50.0, ComputeQueueSelector.savedPercent(10), 1e-9);
    }

    @Test
    void theCostIsTheSameAtAnyDispatchCount() {
        // Both scale with the dispatches, so the ratio does not depend on how many there are.
        assertEquals(1.0, ComputeQueueSelector.amdRuleHelps(1) ? 1.0 : 0.0, 1e-12);
        assertEquals(50.0, ComputeQueueSelector.savedPercent(1), 1e-9);
        assertEquals(50.0, ComputeQueueSelector.savedPercent(1000), 1e-9);
    }

    @Test
    void aFrameWithNoComputeSavesNothing() {
        // It is not a matter of principle: with no dispatches there is nothing to save, and the two costs
        // are compared rather than assumed.
        assertFalse(ComputeQueueSelector.amdRuleHelps(0));
        assertEquals(0.0, ComputeQueueSelector.savedPercent(0), 1e-12);
        assertEquals(0.0, ComputeQueueSelector.msCost(Queue.GRAPHICS, true, 0), 1e-12);
    }

    @Test
    void aNonAmdDevicePaysNoStall() {
        assertEquals(10 * ComputeQueueSelector.MS_CROSS_QUEUE_SYNC,
                ComputeQueueSelector.msCost(Queue.DEDICATED_COMPUTE, false, 10), 1e-9);
        assertEquals(0.5, ComputeQueueSelector.msCost(Queue.DEDICATED_COMPUTE, false, 10), 1e-9);
    }

    @Test
    void sharingAQueueCostsTheLostOverlap() {
        // Work on one queue runs in the order it was submitted, so compute put on the graphics queue cannot
        // run at the same time as the frame -- it runs before or after it.
        assertEquals(10 * ComputeQueueSelector.MS_OVERLAP_LOST,
                ComputeQueueSelector.msCost(Queue.GRAPHICS, false, 10), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ComputeQueueSelector.msCost(null, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeQueueSelector.msCost(Queue.GRAPHICS, true, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ComputeQueueSelector.amdRuleHelps(-1));
    }

    @Test
    void theConstantsMatchTheMeasurements() {
        assertEquals(0.35, ComputeQueueSelector.MS_AMD_FENCE_STALL, 1e-12);
        assertEquals(0.20, ComputeQueueSelector.MS_OVERLAP_LOST, 1e-12);
        assertEquals(0.05, ComputeQueueSelector.MS_CROSS_QUEUE_SYNC, 1e-12);
        assertTrue(ComputeQueueSelector.MS_OVERLAP_LOST
                < ComputeQueueSelector.MS_AMD_FENCE_STALL + ComputeQueueSelector.MS_CROSS_QUEUE_SYNC,
                "which is what makes the AMD rule worth applying");
    }
}
