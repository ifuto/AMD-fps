package net.amdfaster.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.BufferPool.Slot;
import org.junit.jupiter.api.Test;

class BufferPoolTest {

    @Test
    void requestsRoundUpToAPowerOfTwoClass() {
        assertEquals(4096, BufferPool.classFor(1));
        assertEquals(4096, BufferPool.classFor(4096));
        assertEquals(8192, BufferPool.classFor(4097), "one byte over and it is the next class");
        assertEquals(65536, BufferPool.classFor(60000));
        assertEquals(262144, BufferPool.classFor(262144), "exactly the largest class");
        assertEquals(7, BufferPool.CLASS_COUNT);
    }

    @Test
    void aboveTheLargestClassIsNotPooled() {
        // Caching a buffer that large would pin its whole size for the life of the pool, and for
        // something that big a dedicated allocation destroyed on retire costs less than the memory held
        // idle waiting for a request that may not come.
        assertEquals(-1, BufferPool.classFor(262145));
        assertEquals(-1, BufferPool.classFor(1 << 20));

        BufferPool pool = new BufferPool();
        assertNull(pool.acquire(262145, 0L), "the caller gives this a dedicated allocation instead");
        assertEquals(1, pool.beyondMaxClass());
        assertEquals(0, pool.acquisitions(), "it was never a pooled acquisition");

        pool.retire(262145, 1L);
        assertEquals(2, pool.beyondMaxClass());
        assertEquals(0, pool.retires());
    }

    @Test
    void classIndexesAddressTheSevenPools() {
        assertEquals(0, BufferPool.classIndex(4096));
        assertEquals(1, BufferPool.classIndex(8192));
        assertEquals(6, BufferPool.classIndex(262144));

        assertThrows(IllegalArgumentException.class, () -> BufferPool.classIndex(5000), "not a class size");
        assertThrows(IllegalArgumentException.class, () -> BufferPool.classIndex(2048), "below the smallest");
        assertThrows(IllegalArgumentException.class, () -> BufferPool.classIndex(524288), "above the largest");
    }

    @Test
    void anEmptyPoolAllocatesFresh() {
        BufferPool pool = new BufferPool();

        Slot slot = pool.acquire(4096, 0L);

        assertEquals(4096, slot.bytes());
        assertEquals(-1L, slot.retiredFrame(), "in use, so it has no retirement frame");
        assertEquals(1, pool.acquisitions());
        assertEquals(0, pool.reused());
        assertEquals(1, pool.allocatedFresh());
        assertEquals(0.0, pool.reusePercent(), 1e-12);
    }

    @Test
    void aRetiredBufferIsNotReusableUntilItsFenceSignalled() {
        // This is the part that cannot be skipped. A buffer whose contents are still queued cannot be
        // written to again: the new upload would overwrite data a queued transfer has not read, and the
        // geometry that comes out belongs to neither section. That is corruption, not a slowdown.
        BufferPool pool = new BufferPool();
        pool.acquire(4096, 0L);
        pool.retire(4096, 5L);

        assertEquals(1, pool.pendingCount());
        assertEquals(0, pool.pooledCount(), "retired but not yet released");

        Slot again = pool.acquire(4096, 4L);
        assertEquals(1, pool.allocatedFresh(), "nothing was available, so a fresh one was made");
        assertEquals(0, pool.reused());

        assertEquals(1, pool.releaseThrough(5L), "frame 5's fence has now signalled");
        assertEquals(0, pool.pendingCount());
        assertEquals(1, pool.pooledCount());

        Slot recycled = pool.acquire(4096, 5L);
        assertEquals(4096, recycled.bytes());
        assertEquals(1, pool.reused());
    }

    @Test
    void releasingStopsAtTheFirstSlotStillInFlight() {
        // The pending queues are in retirement order and frames retire in order, so the walk does not have
        // to scan the whole queue.
        BufferPool pool = new BufferPool();
        pool.retire(4096, 1L);
        pool.retire(4096, 2L);
        pool.retire(4096, 9L);

        assertEquals(2, pool.releaseThrough(2L), "frames 1 and 2 only");
        assertEquals(1, pool.pendingCount(), "frame 9 is still queued");
        assertEquals(2, pool.pooledCount());

        assertEquals(0, pool.releaseThrough(2L), "nothing new to release");
        assertEquals(1, pool.releaseThrough(9L));
        assertEquals(0, pool.pendingCount());
    }

    @Test
    void aBufferUpToAQuarterLargerIsAccepted() {
        // Varying sizes reuse each other rather than each accumulating its own pool, but a 4 KiB request
        // does not hold a 256 KiB buffer idle.
        BufferPool pool = new BufferPool();
        pool.retire(8192, 1L);
        pool.releaseThrough(1L);

        assertEquals(1, pool.pooledCount());

        // 6144 rounds to the 8192 class, whose slack ceiling is 10240, so the pooled 8192 fits.
        Slot fits = pool.acquire(6144, 1L);
        assertEquals(8192, fits.bytes());
        assertEquals(1, pool.reused());
    }

    @Test
    void aLargerClassIsNotTakenForASmallRequest() {
        BufferPool pool = new BufferPool();
        pool.retire(8192, 1L);
        pool.releaseThrough(1L);

        // 4096 rounds to the 4096 class, whose slack ceiling is 5120. The pooled 8192 is above it, so it
        // stays pooled and a fresh 4096 is allocated instead.
        Slot fresh = pool.acquire(4096, 1L);
        assertEquals(4096, fresh.bytes());
        assertEquals(0, pool.reused());
        assertEquals(1, pool.allocatedFresh());
        assertEquals(1, pool.pooledCount(), "the 8192 buffer is still there for a request that wants it");
    }

    @Test
    void classesDoNotServeEachOtherBelowTheirOwnIndex() {
        // The walk goes upward from the request's class, never downward, so a pooled 4 KiB buffer is not
        // handed to a 64 KiB request that would overflow it.
        BufferPool pool = new BufferPool();
        pool.retire(4096, 1L);
        pool.releaseThrough(1L);

        Slot big = pool.acquire(65536, 1L);
        assertEquals(65536, big.bytes());
        assertEquals(0, pool.reused());
        assertEquals(1, pool.pooledCount());
    }

    @Test
    void theReuseRatioIsReported() {
        BufferPool pool = new BufferPool();
        pool.acquire(4096, 0L);
        pool.acquire(4096, 0L);
        pool.retire(4096, 1L);
        pool.retire(4096, 1L);
        pool.releaseThrough(1L);

        pool.acquire(4096, 1L);
        pool.acquire(4096, 1L);
        pool.acquire(4096, 1L);

        assertEquals(5, pool.acquisitions());
        assertEquals(2, pool.reused());
        assertEquals(3, pool.allocatedFresh());
        assertEquals(40.0, pool.reusePercent(), 1e-9);
        assertEquals(2, pool.retires());
        assertEquals(2, pool.releases());
    }

    @Test
    void pooledBytesCountsWhatThePoolIsHoldingWarm() {
        BufferPool pool = new BufferPool();
        pool.retire(4096, 1L);
        pool.retire(65536, 1L);
        assertEquals(0, pool.pooledBytes(), "still pending");

        pool.releaseThrough(1L);
        assertEquals(4096 + 65536, pool.pooledBytes());
        assertEquals(2, pool.pooledCount());
    }

    @Test
    void clearingDropsTheBuffersRatherThanRecyclingThem() {
        BufferPool pool = new BufferPool();
        pool.retire(4096, 1L);
        pool.releaseThrough(1L);
        pool.retire(8192, 2L);

        pool.clear();

        assertEquals(0, pool.pooledCount());
        assertEquals(0, pool.pendingCount());
        assertEquals(0, pool.pooledBytes());
    }

    @Test
    void anEmptyPoolReportsZeroNotNaN() {
        BufferPool pool = new BufferPool();

        assertEquals(0.0, pool.reusePercent(), 1e-12);
        assertEquals(0, pool.acquisitions());
        assertEquals(0, pool.reused());
        assertEquals(0, pool.retires());
        assertEquals(0, pool.beyondMaxClass());
        assertTrue(pool.pooledBytes() == 0L);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BufferPool.classFor(0));
        assertThrows(IllegalArgumentException.class, () -> BufferPool.classFor(-1));
        BufferPool pool = new BufferPool();
        assertThrows(IllegalArgumentException.class, () -> pool.acquire(0, 0L));
        assertThrows(IllegalArgumentException.class, () -> pool.retire(-4096, 0L));
    }
}
