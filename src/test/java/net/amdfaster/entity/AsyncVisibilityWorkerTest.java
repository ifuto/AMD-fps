package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncVisibilityWorkerTest {

    @Test
    void anEntityIsDrawnUntilAnAnswerSaysOtherwise() {
        // Guessing "hidden" while the worker is still running would make entities pop in as the answers
        // arrive, which is exactly the artefact culling is supposed to avoid.
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        assertTrue(worker.isVisible(), "no answer yet, so drawn");
        worker.issue(0);
        assertTrue(worker.isVisible(), "still no answer, still drawn");
    }

    @Test
    void anAnswerThatFindsItHiddenStopsTheDrawing() {
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        worker.issue(0);
        assertTrue(worker.answer(0, true));

        assertFalse(worker.isVisible());
        assertEquals(0L, worker.answerTick());
    }

    @Test
    void aLaterAnswerCanBringItBack() {
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        worker.issue(0);
        worker.answer(0, true);
        worker.issue(4);
        worker.answer(4, false);

        assertTrue(worker.isVisible());
        assertEquals(4L, worker.answerTick());
    }

    @Test
    void anOlderAnswerArrivingLastIsDiscarded() {
        // Two tests can be in flight for the same entity. If the older one finished last and overwrote the
        // newer, the entity would be drawn according to where it was rather than where it is, and a
        // fast-moving one would flicker between visible and hidden.
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        worker.issue(0);
        worker.issue(4);
        worker.answer(4, true);

        assertFalse(worker.isVisible(), "the newer answer");
        assertFalse(worker.answer(0, false), "the older one arrives late");
        assertFalse(worker.isVisible(), "and does not overwrite it");
        assertEquals(4L, worker.answerTick());
        assertEquals(1L, worker.staleDiscarded());
    }

    @Test
    void aFullWorkerDropsNewTestsRatherThanQueueing() {
        // A queue that grows without bound means the workers are falling behind, and every answer they
        // produce is further out of date -- culling based on where entities were many frames ago, which is
        // worse than not culling. Dropping leaves the entity drawn, which is the safe direction.
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        for (int i = 0; i < AsyncVisibilityWorker.QUEUE_LIMIT; i++) {
            assertTrue(worker.issue(i));
        }
        assertFalse(worker.issue(999), "the queue is full");

        assertEquals(1L, worker.droppedFull());
        assertEquals(AsyncVisibilityWorker.QUEUE_LIMIT, worker.queued());
        assertTrue(worker.isVisible(), "and the entity is still drawn");
    }

    @Test
    void answeringFreesRoomInTheQueue() {
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        for (int i = 0; i < AsyncVisibilityWorker.QUEUE_LIMIT; i++) {
            worker.issue(i);
        }
        worker.answer(0, false);

        assertEquals(AsyncVisibilityWorker.QUEUE_LIMIT - 1, worker.queued());
        assertTrue(worker.issue(999), "room again");
    }

    @Test
    void theWorkIsMovedOffTheRenderThread() {
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        for (int i = 0; i < 100; i++) {
            worker.issue(i);
            worker.answer(i, i % 2 == 0);
        }

        assertEquals(100L, worker.issued());
        assertEquals(100L, worker.answered());
        assertEquals(100 * AsyncVisibilityWorker.MS_PER_TEST,
                worker.msMovedOffRenderThread(), 1e-9);
        assertEquals(0.0, worker.lostPercent(), 1e-9);
    }

    @Test
    void aFreshWorkerReportsZeroNotNaN() {
        // A worker that has been given nothing has technically lost nothing, but reporting that as a rate
        // would describe an unused one as though it had a result.
        AsyncVisibilityWorker worker = new AsyncVisibilityWorker();

        assertEquals(0.0, worker.lostPercent());
        assertEquals(0.0, worker.msMovedOffRenderThread());
        assertEquals(0L, worker.issued());
        assertEquals(0L, worker.answered());
        assertEquals(0, worker.queued());
        assertTrue(worker.isVisible());
        assertEquals(64, AsyncVisibilityWorker.QUEUE_LIMIT);
        assertEquals(4, AsyncVisibilityWorker.ISSUE_INTERVAL);
    }
}
