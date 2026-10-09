package net.amdfaster.light;

import net.amdfaster.dirty.SectionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightQueueTest {

    @Test
    void theBrightestQueuedCellComesOutFirst() {
        // A flood fill has to process bright cells before dim ones, because a cell's final level is
        // the brightest thing reaching it. Processing a dim cell first means recomputing it when a
        // brighter neighbour arrives, which is what the bucketing removes.
        LightQueue queue = new LightQueue();
        queue.push(3, SectionCoord.key(0, 0, 0));
        queue.push(9, SectionCoord.key(1, 0, 0));
        queue.push(6, SectionCoord.key(2, 0, 0));

        assertEquals(9, queue.peekLevel());
        assertEquals(SectionCoord.key(1, 0, 0), queue.poll());
        assertEquals(6, queue.peekLevel());
        assertEquals(SectionCoord.key(2, 0, 0), queue.poll());
        assertEquals(3, queue.peekLevel());
        assertEquals(SectionCoord.key(0, 0, 0), queue.poll());
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.peekLevel());
    }

    @Test
    void duplicatesAreRejected() {
        // Without this the queue grows far faster than the fill progresses: a cell is reached from up
        // to six neighbours and enqueued by each. In an open area the same cell can be enqueued dozens
        // of times before it is processed once.
        LightQueue queue = new LightQueue();
        long here = SectionCoord.key(4, 4, 4);

        assertTrue(queue.push(7, here));
        assertFalse(queue.push(7, here), "already queued");
        assertFalse(queue.push(3, here), "even at a different level");
        assertEquals(1, queue.size());
        assertEquals(2, queue.rejectedDuplicates());

        assertEquals(here, queue.poll());
        assertTrue(queue.push(7, here), "and it can be queued again once it has been processed");
        assertEquals(0, queue.rejectedDuplicates() - 2);
    }

    @Test
    void theZeroCoordinateIsARealCoordinateAndNotAnEmptySlot() {
        // SectionCoord.key(MIN, MIN, MIN) is legitimately zero. A hash table using zero as its
        // empty sentinel would treat that coordinate as absent forever -- the same class of bug as a
        // light field whose origin collides with a sentinel. The occupancy array is what prevents it.
        long minimum = SectionCoord.key(SectionCoord.MIN, SectionCoord.MIN, SectionCoord.MIN);
        assertEquals(0L, minimum, "this is exactly the coordinate that would break a sentinel scheme");

        LightQueue queue = new LightQueue();
        assertTrue(queue.push(5, minimum));
        assertFalse(queue.push(5, minimum), "and it is still recognised as present");
        assertEquals(minimum, queue.poll());
        assertTrue(queue.isEmpty());
        assertTrue(queue.push(5, minimum), "and it can be re-queued after being processed");
    }

    @Test
    void removalKeepsTheProbeChainIntact() {
        // Deleting from an open-addressed table by clearing a slot breaks lookups for anything that
        // probed past it. The implementation backshifts instead. Pushing and polling a large set
        // interleaved is what would expose a chain that got broken.
        LightQueue queue = new LightQueue(16);
        for (int i = 0; i < 2000; i++) {
            queue.push(i % 16, SectionCoord.key(i, i * 3, -i));
        }
        assertEquals(2000, queue.size());

        int polled = 0;
        while (!queue.isEmpty()) {
            queue.poll();
            polled++;
        }
        assertEquals(2000, polled, "every entry came back out exactly once");

        // And the table still works afterwards.
        for (int i = 0; i < 500; i++) {
            assertTrue(queue.push(8, SectionCoord.key(i, 0, 0)), "entry " + i);
        }
        assertEquals(500, queue.size());
    }

    @Test
    void growthPreservesEveryMember() {
        LightQueue queue = new LightQueue(2);
        for (int i = 0; i < 5000; i++) {
            queue.push(15, SectionCoord.key(i, 0, 0));
        }
        assertEquals(5000, queue.size(), "nothing was lost across the rehashes");

        for (int i = 0; i < 5000; i++) {
            assertFalse(queue.push(15, SectionCoord.key(i, 0, 0)),
                    "coordinate " + i + " survived the rehash and is still recognised");
        }
    }

    @Test
    void clearEmptiesTheQueuesButKeepsTheCapacity() {
        LightQueue queue = new LightQueue(64);
        for (int i = 0; i < 100; i++) {
            queue.push(10, SectionCoord.key(i, 0, 0));
        }
        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.rejectedDuplicates());
        assertEquals(-1, queue.peekLevel());
        assertTrue(queue.push(10, SectionCoord.key(0, 0, 0)), "and it works again");
    }

    @Test
    void pollingAnEmptyQueueIsAnErrorNotASilentZero() {
        LightQueue queue = new LightQueue();
        assertThrows(IllegalStateException.class, queue::poll);
        assertThrows(IllegalArgumentException.class, () -> queue.push(-1, 0L));
        assertThrows(IllegalArgumentException.class, () -> queue.push(16, 0L));
    }
}
