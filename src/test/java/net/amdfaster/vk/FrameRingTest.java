package net.amdfaster.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameRingTest {

    @Test
    void theBackingBufferIsOneWindowPerFrameInFlight() {
        FrameRing ring = new FrameRing(1024, 3);
        assertEquals(1024, ring.windowBytes());
        assertEquals(3, ring.framesInFlight());
        assertEquals(3072, ring.totalBytes());
    }

    @Test
    void eachFrameGetsItsOwnWindow() {
        FrameRing ring = new FrameRing(1024, 3);
        ring.beginFrame(0);
        assertEquals(0, ring.reserve(100, 256));
        ring.beginFrame(1);
        assertEquals(1024, ring.reserve(100, 256));
        ring.beginFrame(2);
        assertEquals(2048, ring.reserve(100, 256));
    }

    @Test
    void consecutiveReservationsNeverOverlap() {
        FrameRing ring = new FrameRing(4096, 2);
        ring.beginFrame(0);
        long a = ring.reserve(100, 16);
        long b = ring.reserve(200, 16);
        long c = ring.reserve(50, 16);
        assertTrue(b >= a + 100, "b overlaps a");
        assertTrue(c >= b + 200, "c overlaps b");
        // cursors land at 100, 312 and 370; padding is 20
        assertEquals(370, ring.frameBytesUsed());
        assertEquals(20, ring.totalWasted());
    }

    @Test
    void offsetsHonourTheRequestedAlignment() {
        FrameRing ring = new FrameRing(4096, 2);
        ring.beginFrame(0);
        ring.reserve(100, 256);
        assertEquals(0, ring.totalWasted(), "a fresh window is already aligned");
        assertEquals(256, ring.reserve(100, 256), "the second one is padded up to 256");
        assertEquals(156, ring.totalWasted());
        assertEquals(356, ring.frameBytesUsed());
    }

    @Test
    void unalignedReservationsArePackedTightly() {
        FrameRing ring = new FrameRing(4096, 2);
        ring.beginFrame(0);
        assertEquals(0, ring.reserve(7, 1));
        assertEquals(7, ring.reserve(9, 1));
        assertEquals(0, ring.totalWasted());
    }

    @Test
    void theWindowIsResetAtTheStartOfEachFrame() {
        FrameRing ring = new FrameRing(1024, 2);
        ring.beginFrame(0);
        ring.reserve(900, 1);
        ring.beginFrame(1);
        assertEquals(0, ring.frameBytesUsed());
        assertEquals(1024, ring.reserve(900, 1), "frame 1 starts at the top of its own window");
    }

    @Test
    void peakWindowUsageTracksTheWorstFrame() {
        FrameRing ring = new FrameRing(4096, 3);
        ring.beginFrame(0);
        ring.reserve(100, 1);
        ring.beginFrame(1);
        ring.reserve(700, 1);
        ring.beginFrame(2);
        ring.reserve(300, 1);
        assertEquals(700, ring.peakWindowUsage());
    }

    @Test
    void overflowingTheWindowFailsWithTheNumbersNeededToResizeIt() {
        FrameRing ring = new FrameRing(1024, 2);
        ring.beginFrame(0);
        ring.reserve(900, 1);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ring.reserve(200, 1));
        assertTrue(e.getMessage().contains("window overflow"), e.getMessage());
        assertTrue(e.getMessage().contains("1024"), e.getMessage());
    }

    @Test
    void reservingBeforeStartingAFrameFails() {
        FrameRing ring = new FrameRing(1024, 2);
        assertThrows(IllegalStateException.class, () -> ring.reserve(16, 16));
        assertThrows(IllegalStateException.class, ring::windowOffset);
    }

    @Test
    void aFrameIndexOutsideTheRingFails() {
        FrameRing ring = new FrameRing(1024, 3);
        assertThrows(IllegalArgumentException.class, () -> ring.beginFrame(3));
        assertThrows(IllegalArgumentException.class, () -> ring.beginFrame(-1));
    }

    @Test
    void alignmentMustBeAPowerOfTwo() {
        FrameRing ring = new FrameRing(1024, 2);
        ring.beginFrame(0);
        assertThrows(IllegalArgumentException.class, () -> ring.reserve(16, 3));
        // 0 and 1 both mean "unaligned" rather than being errors.
        assertEquals(0, ring.reserve(16, 0));
    }

    @Test
    void aNonPositiveReservationFails() {
        FrameRing ring = new FrameRing(1024, 2);
        ring.beginFrame(0);
        assertThrows(IllegalArgumentException.class, () -> ring.reserve(0, 16));
        assertThrows(IllegalArgumentException.class, () -> ring.reserve(-1, 16));
    }

    @Test
    void aNonPositiveConstructionFails() {
        assertThrows(IllegalArgumentException.class, () -> new FrameRing(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new FrameRing(1024, 0));
    }

    @Test
    void totalReservedCountsBytesHandedOutNotPadding() {
        FrameRing ring = new FrameRing(4096, 2);
        ring.beginFrame(0);
        ring.reserve(100, 256);
        ring.reserve(100, 256);
        assertEquals(200, ring.totalReserved());
        assertEquals(156, ring.totalWasted());
    }
}
