package net.amdfaster.mesh.job;

import net.amdfaster.dirty.SectionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshJobQueueTest {

    @Test
    void pushesSpreadAcrossWorkersRoundRobin() {
        // A single shared queue needs a lock on every take, and at thousands of sections per second the
        // lock is the whole cost. Round-robin spreads the work with no coordination at all.
        MeshJobQueue queue = new MeshJobQueue(4, 64);
        for (int i = 0; i < 10; i++) {
            assertTrue(queue.push(i, 4, 0, MeshJobQueue.KIND_GEOMETRY), "push " + i);
        }
        assertEquals(3, queue.pendingFor(0), "workers 0 and 1 get the extra two");
        assertEquals(3, queue.pendingFor(1));
        assertEquals(2, queue.pendingFor(2));
        assertEquals(2, queue.pendingFor(3));
        assertEquals(10, queue.pending(), "and nothing was lost");
        assertEquals(10, queue.pushed());
    }

    @Test
    void aWorkerDrainsItsOwnDequeInOrder() {
        MeshJobQueue queue = new MeshJobQueue(4, 64);
        for (int i = 0; i < 8; i++) {
            queue.push(i, 4, 0, MeshJobQueue.KIND_GEOMETRY);
        }
        // Round robin over four workers puts 0 and 4 on worker 0.
        assertEquals(SectionCoord.key(0, 4, 0), queue.poll(0));
        assertEquals(SectionCoord.key(4, 4, 0), queue.poll(0));
        assertEquals(-1L, queue.poll(0), "and then it is empty");
        assertEquals(0, queue.pendingFor(0));
    }

    @Test
    void anIdleWorkerStealsFromTheLongestOtherDeque() {
        MeshJobQueue queue = new MeshJobQueue(4, 64);
        for (int i = 0; i < 8; i++) {
            queue.push(i, 4, 0, MeshJobQueue.KIND_GEOMETRY);
        }
        queue.poll(0);
        queue.poll(0);
        assertEquals(-1L, queue.poll(0), "worker 0 has nothing left");

        // Longest deque because that is where the imbalance is; stealing from a short one moves the
        // problem around instead of solving it. Round robin over four workers put 1,5 on w1, 2,6 on w2
        // and 3,7 on w3, so the first three steals each take the newest job off a deque of two.
        assertEquals(SectionCoord.key(5, 4, 0), queue.steal(0), "from worker 1");
        assertEquals(SectionCoord.key(6, 4, 0), queue.steal(0), "then worker 2");
        assertEquals(SectionCoord.key(7, 4, 0), queue.steal(0), "then worker 3");
        // Those three deques now hold one job each, so three more steals are needed before the queue is
        // actually empty. Asserting -1 here instead would have passed on a pool that silently dropped
        // half the backlog.
        assertEquals(SectionCoord.key(1, 4, 0), queue.steal(0), "worker 1's remaining job");
        assertEquals(SectionCoord.key(2, 4, 0), queue.steal(0), "worker 2's");
        assertEquals(SectionCoord.key(3, 4, 0), queue.steal(0), "worker 3's");
        assertEquals(-1L, queue.steal(0), "and only now is there nothing anywhere");
        assertEquals(6, queue.stolen());
        assertEquals(0, queue.pending());
    }

    @Test
    void stealingTakesTheFarEndSoTheOwnerIsNotContended() {
        // The owner takes from the head and the thief from the tail, so the two only meet when the deque
        // is nearly empty. Taking from the head instead would put both at the same slot constantly.
        MeshJobQueue queue = new MeshJobQueue(2, 64);
        for (int i = 0; i < 6; i++) {
            queue.push(i, 4, 0, MeshJobQueue.KIND_GEOMETRY);
        }
        // Worker 1 holds 1, 3, 5 in that order.
        assertEquals(SectionCoord.key(5, 4, 0), queue.steal(0), "the newest, from the far end");
        assertEquals(SectionCoord.key(1, 4, 0), queue.poll(1), "the owner still takes the oldest");
    }

    @Test
    void aGenerationBumpCancelsTheWholeBacklogInOneWrite() {
        // A teleport, a dimension change, a render distance change: everything queued was chosen for a
        // camera position that no longer holds. Removing a thousand jobs would cost a walk over the
        // backlog; a generation bump costs nothing and the slots are reclaimed as they are polled past.
        MeshJobQueue queue = new MeshJobQueue(4, 512);
        for (int i = 0; i < 1000; i++) {
            queue.push(i & 15, 4, i >> 4, MeshJobQueue.KIND_GEOMETRY);
        }
        assertEquals(1000, queue.pending(), "all live");
        assertEquals(0, queue.generation());

        queue.invalidateAll();
        assertEquals(1, queue.generation());
        assertEquals(0, queue.pending(), "nothing live any more");
        assertEquals(1000, queue.resident(), "but the slots are still occupied until polled past");

        queue.push(0, 9, 0, MeshJobQueue.KIND_GEOMETRY);
        assertEquals(1, queue.pending(), "the one new job is live");
        assertEquals(1001, queue.resident(), "plus the thousand stale slots still waiting to be passed");
        assertEquals(SectionCoord.key(0, 9, 0), queue.poll(0), "a new job surfaces, not a stale one");
        assertEquals(0, queue.pending(), "and that was the only live one");
        assertEquals(1000, queue.droppedStale(), "every stale slot was stepped over to reach it");
    }

    @Test
    void aStaleJobIsSteppedOverNotReturned() {
        // The caller must never receive a job it should not run. Stepping over stale slots at poll time
        // is what lets cancellation be free; reporting them and letting the caller check would spread
        // the same test across every call site.
        MeshJobQueue queue = new MeshJobQueue(1, 16);
        queue.push(7, 4, 0, MeshJobQueue.KIND_GEOMETRY);
        queue.invalidateAll();
        queue.push(9, 4, 0, MeshJobQueue.KIND_GEOMETRY);

        assertEquals(SectionCoord.key(9, 4, 0), queue.poll(0));
        assertEquals(-1L, queue.poll(0), "the stale one is gone, not waiting behind it");
        assertEquals(1, queue.droppedStale(), "and it was counted");
    }

    @Test
    void cancellingOneSectionLeavesTheRestQueued() {
        // A section that changed again while its build was waiting. Building it would be pure waste,
        // and a burst of block edits marks the same section over and over.
        MeshJobQueue queue = new MeshJobQueue(4, 64);
        queue.push(1, 4, 1, MeshJobQueue.KIND_GEOMETRY);
        queue.push(2, 4, 2, MeshJobQueue.KIND_GEOMETRY);
        queue.push(1, 4, 1, MeshJobQueue.KIND_LIGHT);

        assertEquals(2, queue.cancelSection(1, 4, 1), "both queued jobs for that section");
        assertEquals(1, queue.pending(), "the other section is untouched");
        assertEquals(0, queue.cancelSection(1, 4, 1), "and they are not cancelled twice");

        assertEquals(SectionCoord.key(2, 4, 2), queue.poll(1), "the surviving job still runs");
        assertEquals(-1L, queue.poll(0), "the cancelled one on worker 0 does not");
        // One, not two: cancellation marks slots, and a marked slot is only counted when something
        // polls past it. The other cancelled job is still resident on worker 2 and has not been passed.
        assertEquals(1, queue.droppedCancelled());
        assertEquals(1, queue.resident(), "the second cancelled job is still occupying its slot");
    }

    @Test
    void cancellingSomethingNotQueuedDoesNothing() {
        MeshJobQueue queue = new MeshJobQueue(2, 16);
        queue.push(1, 4, 1, MeshJobQueue.KIND_GEOMETRY);
        assertEquals(0, queue.cancelSection(5, 5, 5));
        assertEquals(1, queue.pending());
        assertEquals(SectionCoord.key(1, 4, 1), queue.poll(0));
    }

    @Test
    void aFullDequeRefusesTheJobRatherThanGrowing() {
        // A full deque means the builders are falling behind. The right answer is to drop the job: it
        // will be requeued the next time the section is marked dirty, which happens continuously for
        // anything the player can see. Growing instead would turn a slow frame into an allocation.
        MeshJobQueue queue = new MeshJobQueue(1, 4);
        for (int i = 0; i < 4; i++) {
            assertTrue(queue.push(i, 4, 0, MeshJobQueue.KIND_GEOMETRY), "slot " + i);
        }
        assertFalse(queue.push(9, 4, 0, MeshJobQueue.KIND_GEOMETRY), "the fifth is refused");
        assertEquals(4, queue.pushed(), "and not counted as pushed");

        queue.poll(0);
        assertTrue(queue.push(9, 4, 0, MeshJobQueue.KIND_GEOMETRY), "a slot frees up and it fits again");
    }

    @Test
    void theRingWrapsWithoutLosingJobs() {
        // The deque is a ring, so push and poll have to keep the head and tail consistent across a wrap.
        // Getting it wrong does not throw; it quietly returns a job that was already built.
        MeshJobQueue queue = new MeshJobQueue(1, 4);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 4; i++) {
                assertTrue(queue.push(i, 4, round, MeshJobQueue.KIND_GEOMETRY), "round " + round + " slot " + i);
            }
            for (int i = 0; i < 4; i++) {
                assertEquals(SectionCoord.key(i, 4, round), queue.poll(0), "round " + round + " job " + i);
            }
            assertEquals(-1L, queue.poll(0));
        }
        assertEquals(200, queue.pushed());
        assertEquals(200, queue.polled());
    }

    @Test
    void theKindTravelsWithTheJob() {
        // Geometry needs a full rebuild; light only needs the baked values refreshed. Confusing them
        // either rebuilds geometry that did not change or leaves a mesh shaded with stale light.
        MeshJobQueue queue = new MeshJobQueue(2, 16);
        queue.push(1, 4, 1, MeshJobQueue.KIND_GEOMETRY);
        queue.push(2, 4, 2, MeshJobQueue.KIND_LIGHT);

        queue.poll(0);
        assertEquals(MeshJobQueue.KIND_GEOMETRY, queue.lastKind());
        queue.poll(1);
        assertEquals(MeshJobQueue.KIND_LIGHT, queue.lastKind());
    }

    @Test
    void stealingCarriesTheKindToo() {
        MeshJobQueue queue = new MeshJobQueue(2, 16);
        queue.push(1, 4, 1, MeshJobQueue.KIND_GEOMETRY);
        queue.push(2, 4, 2, MeshJobQueue.KIND_LIGHT);
        queue.push(3, 4, 3, MeshJobQueue.KIND_LIGHT);

        queue.poll(0);
        queue.poll(0);
        assertEquals(-1L, queue.poll(0));
        queue.steal(0);
        assertEquals(MeshJobQueue.KIND_LIGHT, queue.lastKind(), "a stolen job keeps its kind");
    }

    @Test
    void clearEmptiesEveryDequeButKeepsTheCounters() {
        MeshJobQueue queue = new MeshJobQueue(4, 64);
        for (int i = 0; i < 20; i++) {
            queue.push(i & 15, 4, 0, MeshJobQueue.KIND_GEOMETRY);
        }
        queue.poll(0);
        queue.clear();

        assertEquals(0, queue.pending());
        assertEquals(0, queue.resident(), "the slots are released, not just marked stale");
        for (int w = 0; w < 4; w++) {
            assertEquals(-1L, queue.poll(w), "worker " + w + " is empty");
        }
        assertEquals(20, queue.pushed(), "the run totals survive");
        assertEquals(1, queue.polled());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MeshJobQueue(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new MeshJobQueue(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new MeshJobQueue(-1, 16));
    }
}
