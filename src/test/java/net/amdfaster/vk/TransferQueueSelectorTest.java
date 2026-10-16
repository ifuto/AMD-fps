package net.amdfaster.vk;

import net.amdfaster.vk.TransferQueueSelector.Route;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferQueueSelectorTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void anUploadOnADiscreteCardUsesTheTransferEngine() {
        // On a discrete card the source is system memory and the destination is across the PCI Express link,
        // so the copy is a DMA transfer a dedicated engine does without involving the shader cores.
        assertEquals(Route.TRANSFER, TransferQueueSelector.choose(false, true, false));
    }

    @Test
    void anOnDeviceCopyNeverUsesTheTransferEngine() {
        // A copy between two buffers that are both in video memory has nothing to transfer, and routing it
        // through the transfer engine means submitting to a second queue, waiting for it and synchronising
        // back -- all for a memcpy the graphics queue could have done inline.
        assertEquals(Route.GRAPHICS, TransferQueueSelector.choose(true, true, false));
        assertEquals(Route.GRAPHICS, TransferQueueSelector.choose(true, true, true));
    }

    @Test
    void anApuHasNoLinkToCross() {
        // A Vega 8, a 680M and a 780M share system memory with the shader cores, so an upload is not a
        // transfer between two memories and the dedicated engine has nothing to do that the graphics queue
        // does not.
        assertEquals(Route.GRAPHICS, TransferQueueSelector.choose(false, true, true));
        assertFalse(TransferQueueSelector.transferEngineWorthIt(16 * MB, true));
    }

    @Test
    void theEngineFreesTheGraphicsQueueRatherThanSpeedingTheCopy() {
        // The link's rate is the link's rate, so what the choice is about is how much of the graphics queue
        // the copy takes.
        assertEquals(0.5, TransferQueueSelector.msGraphicsQueueCost(Route.GRAPHICS, 16 * MB, false), 1e-9);
        assertEquals(TransferQueueSelector.MS_CROSSING,
                TransferQueueSelector.msGraphicsQueueCost(Route.TRANSFER, 16 * MB, false), 1e-9);
    }

    @Test
    void theTransferRouteCostsTheCrossingOnTopOfTheCopy() {
        assertEquals(0.5, TransferQueueSelector.msCost(Route.GRAPHICS, 16 * MB, false), 1e-9);
        assertEquals(0.5 + TransferQueueSelector.MS_CROSSING,
                TransferQueueSelector.msCost(Route.TRANSFER, 16 * MB, false), 1e-9);
    }

    @Test
    void aSmallCopyIsNotWorthTheCrossing() {
        // The engine's benefit is the graphics-queue time it frees, and the crossing costs a fixed amount,
        // so a small copy pays the crossing to free very little and comes out behind.
        assertFalse(TransferQueueSelector.transferEngineWorthIt(4096, false));
        assertTrue(TransferQueueSelector.transferEngineWorthIt(MB, false));
        assertTrue(TransferQueueSelector.transferEngineWorthIt(16 * MB, false));
    }

    @Test
    void theThresholdIsReported() {
        assertEquals(1_006_632L, TransferQueueSelector.worthItAboveBytes(),
                "just under a megabyte at PCI Express 4.0 x16");
        assertFalse(TransferQueueSelector.transferEngineWorthIt(
                TransferQueueSelector.worthItAboveBytes() - 1, false));
        assertTrue(TransferQueueSelector.transferEngineWorthIt(
                TransferQueueSelector.worthItAboveBytes() + 1, false));
    }

    @Test
    void unifiedMemoryIsFasterButStillLocal() {
        assertEquals(90_000.0, TransferQueueSelector.APU_MBPS, 1e-9);
        assertTrue(TransferQueueSelector.msCost(Route.GRAPHICS, 16 * MB, true)
                < TransferQueueSelector.msCost(Route.GRAPHICS, 16 * MB, false));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferQueueSelector.msCost(null, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> TransferQueueSelector.msCost(Route.GRAPHICS, -1, false));
        assertThrows(IllegalArgumentException.class,
                () -> TransferQueueSelector.msGraphicsQueueCost(null, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> TransferQueueSelector.msGraphicsQueueCost(Route.GRAPHICS, -1, false));
        assertThrows(IllegalArgumentException.class,
                () -> TransferQueueSelector.transferEngineWorthIt(-1, false));
    }

    @Test
    void theConstantsMatchTheHardware() {
        assertEquals(32_000.0, TransferQueueSelector.PCIE4_X16_MBPS, 1e-9);
        assertEquals(0.03, TransferQueueSelector.MS_CROSSING, 1e-12);
        assertTrue(TransferQueueSelector.APU_MBPS > TransferQueueSelector.PCIE4_X16_MBPS,
                "shared memory has no link to bottleneck on");
    }
}
