package net.amdfaster.vk;

import net.amdfaster.vk.QueueSelector.QueueFamily;
import net.amdfaster.vk.QueueSelector.QueueSelection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.amdfaster.vk.QueueSelector.COMPUTE;
import static net.amdfaster.vk.QueueSelector.GRAPHICS;
import static net.amdfaster.vk.QueueSelector.TRANSFER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueSelectorTest {

    /** What RADV and AMDVLK both expose: one graphics family, a compute-only one, an SDMA one. */
    private static final List<QueueFamily> AMD_TYPICAL = List.of(
            new QueueFamily(0, GRAPHICS | COMPUTE | TRANSFER, 16, true),
            new QueueFamily(1, COMPUTE | TRANSFER, 8, false),
            new QueueFamily(2, TRANSFER, 2, false));

    @Test
    void graphicsGoesToThePresentCapableFamily() {
        assertEquals(0, QueueSelector.select(AMD_TYPICAL).graphics());
    }

    @Test
    void asyncComputeUsesTheDedicatedEngine() {
        QueueSelection s = QueueSelector.select(AMD_TYPICAL);
        assertEquals(1, s.compute());
        assertTrue(s.asyncCompute());
        assertTrue(s.note().contains("dedicated async compute"), s.note());
    }

    @Test
    void theTransferOnlyFamilyIsKeptForStaging() {
        assertEquals(2, QueueSelector.select(AMD_TYPICAL).hostTransfer());
    }

    @Test
    void theTransferOnlyFamilyIsNeverTheComputeQueue() {
        // An SDMA engine cannot run a compute shader, and even if it could, a device-local to
        // device-local copy is faster on the graphics or compute queue.
        List<QueueFamily> families = List.of(
                new QueueFamily(0, GRAPHICS | COMPUTE | TRANSFER, 16, true),
                new QueueFamily(1, TRANSFER, 2, false));
        QueueSelection s = QueueSelector.select(families);
        assertEquals(0, s.compute());
        assertEquals(1, s.hostTransfer());
        assertFalse(s.asyncCompute());
    }

    @Test
    void withoutADedicatedComputeFamilyComputeSharesGraphics() {
        List<QueueFamily> families = List.of(
                new QueueFamily(0, GRAPHICS | COMPUTE | TRANSFER, 16, true));
        QueueSelection s = QueueSelector.select(families);
        assertEquals(0, s.graphics());
        assertEquals(0, s.compute());
        assertEquals(0, s.hostTransfer());
        assertFalse(s.asyncCompute());
        assertTrue(s.note().contains("shares the graphics queue"), s.note());
    }

    @Test
    void aGraphicsFamilyWithoutPresentIsStillUsable() {
        List<QueueFamily> families = List.of(
                new QueueFamily(0, GRAPHICS | COMPUTE, 16, false),
                new QueueFamily(1, COMPUTE, 8, false));
        QueueSelection s = QueueSelector.select(families);
        assertEquals(0, s.graphics());
        assertEquals(1, s.compute());
    }

    @Test
    void thePresentCapableGraphicsFamilyWinsOverAnEarlierOne() {
        List<QueueFamily> families = List.of(
                new QueueFamily(0, GRAPHICS | COMPUTE, 16, false),
                new QueueFamily(1, GRAPHICS | COMPUTE, 16, true),
                new QueueFamily(2, COMPUTE, 8, false));
        QueueSelection s = QueueSelector.select(families);
        assertEquals(1, s.graphics(), "presenting from the graphics queue avoids a sync edge");
    }

    @Test
    void anEmptyFamilyIsSkipped() {
        List<QueueFamily> families = List.of(
                new QueueFamily(0, GRAPHICS | COMPUTE, 0, true),
                new QueueFamily(1, GRAPHICS | COMPUTE, 4, true));
        assertEquals(1, QueueSelector.select(families).graphics());
    }

    @Test
    void aDeviceWithoutGraphicsFailsLoudly() {
        List<QueueFamily> families = List.of(new QueueFamily(0, COMPUTE | TRANSFER, 8, false));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> QueueSelector.select(families));
        assertTrue(e.getMessage().contains("no graphics queue family"), e.getMessage());
    }

    @Test
    void aDeviceWithNoFamiliesFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> QueueSelector.select(List.of()));
    }

    @Test
    void transferOnlyDetection() {
        assertTrue(new QueueFamily(2, TRANSFER, 2, false).isTransferOnly());
        assertFalse(new QueueFamily(0, GRAPHICS | TRANSFER, 16, true).isTransferOnly());
        assertFalse(new QueueFamily(1, COMPUTE | TRANSFER, 8, false).isTransferOnly());
    }
}
