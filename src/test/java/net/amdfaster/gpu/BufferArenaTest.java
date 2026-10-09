package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferArenaTest {

    @Test
    void offsetsAreAlignedEvenWhenThatWastesSpace() {
        // The alignment is a Vulkan device limit, not a preference: a dynamic offset that is not a
        // multiple of minUniformBufferOffsetAlignment is rejected by the driver. An allocator that
        // just adds sizes produces those offsets and fails at bind time, far from here.
        BufferArena arena = new BufferArena(1024, 256);

        long first = arena.allocate(100);
        long second = arena.allocate(100);

        assertEquals(0, first);
        assertEquals(256, second, "the second allocation starts on the next boundary, not at 100");
        assertEquals(0, second % 256);
        assertEquals(200, arena.allocatedBytes());
        assertEquals(356, arena.highWaterBytes());
        assertTrue(arena.isConsistent());
    }

    @Test
    void freeingCoalescesWithTheNeighbouringHole() {
        // Without coalescing an arena that has seen enough churn reports plenty of free bytes and
        // still cannot satisfy a request larger than any single gap. That presents as running out of
        // memory in a buffer that is mostly empty, which sends the investigation in entirely the
        // wrong direction.
        BufferArena arena = new BufferArena(1024, 1);
        long a = arena.allocate(100);
        long b = arena.allocate(100);
        long c = arena.allocate(100);
        assertEquals(0, a);
        assertEquals(100, b);
        assertEquals(200, c);
        assertEquals(1, arena.freeBlockCount(), "one hole at the end");

        arena.free(b, 100);
        assertEquals(2, arena.freeBlockCount(), "a hole in the middle");

        arena.free(c, 100);
        assertEquals(1, arena.freeBlockCount(), "the two holes merged into one");
        assertEquals(924, arena.largestFreeBlock());
        assertTrue(arena.isConsistent());
    }

    @Test
    void totalFreeSpaceAndLargestUsableGapDisagreeWhenShredded() {
        // The distinction that explains an allocation failure. 424 bytes are free but the largest
        // aligned gap is 256, so a 600-byte request fails. Reporting only the total would say the
        // arena had room.
        BufferArena arena = new BufferArena(1024, 256);
        assertEquals(0, arena.allocate(600));
        assertEquals(-1, arena.allocate(600), "no aligned gap is large enough");

        assertEquals(424, arena.freeBytes(), "the arena is not remotely full");
        assertEquals(256, arena.largestFreeBlock(), "but the biggest usable gap is one slice");
        assertEquals(168, arena.alignmentWaste(), "and some of the free space is unreachable");
        assertEquals(1, arena.failedAllocations());
        assertTrue(arena.isConsistent());
    }

    @Test
    void aRandomSequenceOfAllocationsAndFreesNeverOverlapsOrLeaks() {
        // The property that matters more than any single offset. An allocator whose bookkeeping
        // drifts does not fail loudly: it eventually returns an offset that overlaps a live one, and
        // two sections draw each other's geometry.
        BufferArena arena = new BufferArena(4096, 256);
        java.util.Map<Long, Integer> live = new java.util.HashMap<>();
        java.util.Random random = new java.util.Random(20261009L);

        for (int step = 0; step < 5000; step++) {
            boolean allocate = live.size() < 4 || random.nextInt(10) < 6;
            if (allocate) {
                int bytes = 1 + random.nextInt(512);
                long offset = arena.allocate(bytes);
                if (offset < 0) {
                    continue;
                }
                assertEquals(0, offset % 256, "step " + step + " is aligned");
                for (java.util.Map.Entry<Long, Integer> other : live.entrySet()) {
                    boolean overlaps = offset < other.getKey() + other.getValue()
                            && other.getKey() < offset + bytes;
                    assertFalse(overlaps, "step " + step + " overlaps a live allocation");
                }
                live.put(offset, bytes);
            } else {
                long offset = new java.util.ArrayList<>(live.keySet())
                        .get(random.nextInt(live.size()));
                arena.free(offset, live.remove(offset));
            }
            assertTrue(arena.isConsistent(), "bookkeeping after step " + step);
            assertEquals(live.size(), arena.allocationCount() - arena.freeCount(),
                    "live count after step " + step);
        }
        assertTrue(arena.failedAllocations() > 0, "the arena filled up at some point, as intended");
    }

    @Test
    void resetRestoresOneContiguousBlockButKeepsTheHighWaterMark() {
        BufferArena arena = new BufferArena(1024, 256);
        arena.allocate(300);
        arena.allocate(300);
        long peak = arena.highWaterBytes();
        assertTrue(peak > 0);

        arena.reset();

        assertEquals(0, arena.allocatedBytes());
        assertEquals(1, arena.freeBlockCount());
        assertEquals(1024, arena.largestFreeBlock());
        assertEquals(0, arena.allocationCount());
        assertEquals(0, arena.failedAllocations());
        assertEquals(peak, arena.highWaterBytes(),
                "the peak is what sizes the next arena, so resetting it would hide the spike");
    }

    @Test
    void anEmptyArenaOffersItsWholeCapacity() {
        BufferArena arena = new BufferArena(8192, 256);
        assertEquals(8192, arena.capacity());
        assertEquals(256, arena.alignment());
        assertEquals(0, arena.allocatedBytes());
        assertEquals(8192, arena.freeBytes());
        assertEquals(8192, arena.largestFreeBlock());
        assertEquals(0, arena.alignmentWaste());
        assertEquals(0, arena.failedAllocations());
        assertTrue(arena.isConsistent());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BufferArena(0, 256));
        assertThrows(IllegalArgumentException.class, () -> new BufferArena(-1, 256));
        assertThrows(IllegalArgumentException.class, () -> new BufferArena(1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferArena(1024, 3),
                "a non-power-of-two alignment cannot be applied with a mask");

        BufferArena arena = new BufferArena(1024, 256);
        assertThrows(IllegalArgumentException.class, () -> arena.allocate(0));
        assertThrows(IllegalArgumentException.class, () -> arena.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> arena.free(0, 0));
        assertThrows(IllegalArgumentException.class, () -> arena.free(0, 2048),
                "freeing a range outside the arena would corrupt the hole list");
    }
}
