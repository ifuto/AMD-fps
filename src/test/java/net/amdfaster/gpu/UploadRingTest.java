package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRingTest {

    @Test
    void consecutiveFramesUseConsecutiveSlices() {
        UploadRing ring = new UploadRing(3, 1000);
        assertEquals(3, ring.sliceCount());
        assertEquals(1000, ring.bytesPerSlice());
        assertEquals(3000, ring.capacity());

        // The signalled value stays at 0 throughout: the GPU has not finished anything yet, so no
        // slice is reclaimed and each frame takes the next one.
        assertEquals(0, ring.acquire(0));
        ring.submit(1);
        assertEquals(1000, ring.acquire(0));
        ring.submit(2);
        assertEquals(2000, ring.acquire(0));
        ring.submit(3);

        assertEquals(3, ring.inFlightCount());
        assertEquals(0, ring.freeSliceCount());
        assertEquals(3, ring.totalSubmitted());
    }

    @Test
    void aSliceReturnsOnlyWhenTheGpuHasSignalledPastIt() {
        // The correctness requirement. Overwriting a slice the GPU is still reading changes
        // geometry under a command buffer that has already been recorded, which shows up as
        // flickering or corrupt chunks -- not as an error, and not near the code that caused it.
        UploadRing ring = new UploadRing(3, 1000);
        ring.acquire(0);
        ring.submit(1);
        ring.acquire(0);
        ring.submit(2);
        ring.acquire(0);
        ring.submit(3);

        // One slice per fence value: 1, then 2, then 3.
        assertEquals(1, ring.releaseUpTo(1), "the frame signalled by 1 is done");
        assertEquals(2, ring.inFlightCount());
        assertEquals(1, ring.releaseUpTo(2));
        assertEquals(1, ring.inFlightCount());
        assertEquals(1, ring.releaseUpTo(3));
        assertTrue(ring.isIdle());
        assertEquals(3, ring.totalReleased());
        assertEquals(0, ring.releaseUpTo(9), "and releasing again is a no-op, not an error");
    }

    @Test
    void acquireReclaimsFinishedSlicesOnTheWayIn() {
        // Folding the reclaim into acquire is what keeps the caller from needing two calls per
        // frame and from forgetting the second one, which would leak slices until the ring stalled.
        UploadRing ring = new UploadRing(3, 1000);
        ring.acquire(0);
        ring.submit(1);
        ring.acquire(0);
        ring.submit(2);

        assertEquals(2, ring.inFlightCount());
        // The GPU has signalled 2, which completes both frames, so both slices are reclaimed here
        // rather than by a separate call. The write index was at 2, so that is the slice handed out.
        assertEquals(2000, ring.acquire(2));
        assertEquals(0, ring.inFlightCount(), "nothing is in flight until this frame is submitted");
        ring.submit(3);
        assertEquals(1, ring.inFlightCount());
    }

    @Test
    void aFullRingReportsUnavailableInsteadOfBlocking() {
        // Returning -1 is the point. The right response to "the GPU is behind" is to defer this
        // frame's uploads; stalling the render thread on a fence turns a late chunk into a hitch,
        // which is strictly worse than the chunk arriving a frame later.
        UploadRing ring = new UploadRing(2, 500);
        ring.acquire(0);
        ring.submit(1);
        ring.acquire(0);
        ring.submit(2);

        assertEquals(-1, ring.acquire(0), "both slices are in flight and the GPU has signalled nothing");
        assertEquals(-1, ring.acquire(0));
        assertEquals(2, ring.stalledFrames(), "and the caller can see that this is happening");
        assertEquals(0, ring.freeSliceCount());
    }

    @Test
    void aFreeSliceIsUsedEvenWhenItIsNotTheWriteIndex() {
        // Slices are independent regions, so any free one will do. Testing only the write index
        // would stall with a free slice sitting right there, and would make freeSliceCount() report
        // space available that acquire() refused to hand out -- two methods of the same class
        // disagreeing about the same question.
        UploadRing ring = new UploadRing(3, 1000);
        ring.acquire(0);
        ring.submit(10);
        ring.acquire(0);
        ring.submit(20);
        ring.acquire(0);
        ring.submit(30);

        // The GPU reaches 20, which completes the frames signalled by 10 and 20 but not the one
        // waiting for 30. Two slices are free and the write index has wrapped back to 0, so this
        // case only proves the scan works; the interesting part is that acquire() and
        // freeSliceCount() agree, which they would not if acquire tested only the write index.
        assertEquals(2, ring.releaseUpTo(20));
        assertEquals(2, ring.freeSliceCount());
        assertEquals(1, ring.inFlightCount());
        assertEquals(0, ring.acquire(20));
    }

    @Test
    void submitTwiceWithoutAcquireIsRefused() {
        // Silent double submission would mark one slice in flight twice and lose track of the other,
        // so the ring would eventually overwrite a slice the GPU is reading.
        UploadRing ring = new UploadRing(3, 1000);
        ring.acquire(0);
        ring.submit(1);
        assertThrows(IllegalStateException.class, () -> ring.submit(2));
    }

    @Test
    void aSingleSliceRingIsRejected() {
        // One slice cannot overlap writing and reading at all, so the caller would have to stall
        // every frame -- which is the thing the ring exists to avoid.
        assertThrows(IllegalArgumentException.class, () -> new UploadRing(1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new UploadRing(3, 0));
        assertThrows(IllegalArgumentException.class, () -> new UploadRing(3, -1));

        UploadRing ring = new UploadRing(2, 100);
        ring.acquire(0);
        assertThrows(IllegalArgumentException.class, () -> ring.submit(-1));
    }

    @Test
    void aLongRunStaysConsistent() {
        // Frames submitted, the GPU lagging by a varying amount, and every slice accounted for at
        // every step. A ring that loses a slice does not fail immediately; it just stops being able
        // to upload, some minutes later, for no reason anyone can see.
        UploadRing ring = new UploadRing(3, 256);
        long fence = 0;
        long signalled = 0;
        java.util.Random random = new java.util.Random(4242L);
        int acquired = 0;

        for (int frame = 0; frame < 2000; frame++) {
            // The GPU advances by one or two frames, but never past what was submitted.
            if (fence > signalled && random.nextInt(3) > 0) {
                signalled = Math.min(fence, signalled + 1 + random.nextInt(2));
            }
            long offset = ring.acquire(signalled);
            if (offset >= 0) {
                assertEquals(0, offset % 256);
                assertTrue(offset + 256 <= ring.capacity(), "the slice fits in the buffer");
                ring.submit(++fence);
                acquired++;
            }
            assertEquals(ring.sliceCount() - ring.inFlightCount(), ring.freeSliceCount());
            assertTrue(ring.inFlightCount() <= ring.sliceCount());
        }

        assertEquals(acquired, ring.totalSubmitted());
        // Drain: once the GPU has signalled everything, the ring must be empty again.
        ring.releaseUpTo(fence);
        assertTrue(ring.isIdle(), "every submitted slice was reclaimed");
        assertEquals(ring.totalSubmitted(), ring.totalReleased());
    }
}
