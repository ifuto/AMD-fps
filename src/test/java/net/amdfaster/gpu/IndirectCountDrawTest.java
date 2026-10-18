package net.amdfaster.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.IndirectCountDraw.Call;
import org.junit.jupiter.api.Test;

class IndirectCountDrawTest {

    @Test
    void aCommandIsFiveWords() {
        assertEquals(20, IndirectCountDraw.COMMAND_BYTES);
        assertEquals(5, IndirectCountDraw.COMMAND_WORDS);
        assertEquals(20, IndirectCountDraw.STRIDE_BYTES, "20 is already a multiple of 4, so no padding");
        assertEquals(0, IndirectCountDraw.COUNT_OFFSET_BYTES);
    }

    @Test
    void theLastCommandEndsAtItsOwnEndNotAStridePastIt() {
        // n commands occupy stride x (n - 1) + 20, not stride x n. Getting that backwards overstates the
        // count by one, which is a validation error at the draw rather than a slightly smaller batch.
        assertEquals(0, IndirectCountDraw.bytesFor(0, 20));
        assertEquals(20, IndirectCountDraw.bytesFor(1, 20));
        assertEquals(40, IndirectCountDraw.bytesFor(2, 20));
        assertEquals(20000, IndirectCountDraw.bytesFor(1000, 20));
        assertEquals(23996, IndirectCountDraw.bytesFor(1000, 24), "padding to 24 costs a fifth");
        assertEquals(276480, IndirectCountDraw.bytesFor(13824, 20), "a 12 chunk distance's sections");
    }

    @Test
    void maxDrawCountIsTheInverseAndLandsOnTheSameNumbers() {
        assertEquals(1000, IndirectCountDraw.maxDrawCount(20000, 20, 0));
        assertEquals(1638, IndirectCountDraw.maxDrawCount(32768, 20, 0));
        assertEquals(833, IndirectCountDraw.maxDrawCount(20000, 24, 0));
        assertEquals(1000, IndirectCountDraw.maxDrawCount(20010, 20, 8), "the offset comes off first");

        // Round trip: the count derived from a buffer fits in it, and one more does not.
        int count = IndirectCountDraw.maxDrawCount(20000, 20, 0);
        assertTrue(IndirectCountDraw.bytesFor(count, 20) <= 20000);
        assertFalse(IndirectCountDraw.bytesFor(count + 1, 20) <= 20000);
    }

    @Test
    void aBufferTooSmallForOneCommandHoldsNone() {
        assertEquals(1, IndirectCountDraw.maxDrawCount(20, 20, 0));
        assertEquals(0, IndirectCountDraw.maxDrawCount(19, 20, 0));
        assertEquals(0, IndirectCountDraw.maxDrawCount(0, 20, 0));
        assertEquals(0, IndirectCountDraw.maxDrawCount(24, 20, 8), "eight bytes left is not a command");
    }

    @Test
    void prepareRefusesACountTheBufferCannotHold() {
        Call fits = IndirectCountDraw.prepare(1000, 20000, 0);
        assertEquals(1000, fits.maxDrawCount());
        assertEquals(20, fits.stride());
        assertEquals(0, fits.offset());
        assertEquals(20000, fits.bytesNeeded());
        assertTrue(fits.fitsIn(20000));
        assertFalse(fits.fitsIn(19999));

        assertNull(IndirectCountDraw.prepare(1001, 20000, 0), "one command over and it does not fit");
    }

    @Test
    void theCountIsClampedNotTrusted() {
        // The device executes the smaller of what the cull shader wrote and the cap, so a cull shader that
        // went wrong cannot make the host draw past the buffer it allocated.
        assertEquals(500, IndirectCountDraw.executed(500, 1000));
        assertEquals(1000, IndirectCountDraw.executed(1500, 1000), "clamped to the buffer's capacity");
        assertEquals(0, IndirectCountDraw.executed(0, 1000), "everything culled is nothing drawn");
        assertEquals(1000, IndirectCountDraw.executed(1000, 1000));
    }

    @Test
    void multiDrawAboveOneNeedsTheFeature() {
        // Without multiDrawIndirect a draw count above one is invalid, not merely unsupported, and the
        // fallback is one draw per meshlet -- the submission cost this exists to remove.
        assertTrue(IndirectCountDraw.multiDrawAllowed(true, 5));
        assertTrue(IndirectCountDraw.multiDrawAllowed(true, 13824));
        assertFalse(IndirectCountDraw.multiDrawAllowed(false, 5));
        assertTrue(IndirectCountDraw.multiDrawAllowed(false, 1), "one draw is always legal");
        assertTrue(IndirectCountDraw.multiDrawAllowed(false, 0), "and so is none");
    }

    @Test
    void theDeviceLimitCanForceSeparateDrawsAnyway() {
        // maxDrawIndirectCount can be as low as one, in which case every draw is issued separately no
        // matter what else the device supports.
        assertTrue(IndirectCountDraw.withinDeviceLimit(1, 1));
        assertFalse(IndirectCountDraw.withinDeviceLimit(2, 1));
        assertTrue(IndirectCountDraw.withinDeviceLimit(13824, 13824));
        assertFalse(IndirectCountDraw.withinDeviceLimit(13825, 13824));
    }

    @Test
    void commandOffsetsStepByTheStride() {
        assertEquals(0, IndirectCountDraw.commandOffset(0, 20));
        assertEquals(20, IndirectCountDraw.commandOffset(1, 20));
        assertEquals(60, IndirectCountDraw.commandOffset(3, 20));
        assertEquals(72, IndirectCountDraw.commandOffset(3, 24));
        // Far enough along that a 32 bit product would still be fine, but the method returns a long.
        assertEquals(276460L, IndirectCountDraw.commandOffset(13823, 20));
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.bytesFor(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.bytesFor(1, 19), "stride under one command");
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.maxDrawCount(1000, 22, 0), "stride not a multiple of 4");
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.maxDrawCount(1000, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.maxDrawCount(1000, 20, 2), "offset not a multiple of 4");
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.prepare(-1, 20000, 0));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.executed(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.executed(1, -10));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.multiDrawAllowed(true, -1));
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.withinDeviceLimit(1, 0), "a device allows at least one");
        assertThrows(IllegalArgumentException.class, () -> IndirectCountDraw.commandOffset(-1, 20));
    }
}
