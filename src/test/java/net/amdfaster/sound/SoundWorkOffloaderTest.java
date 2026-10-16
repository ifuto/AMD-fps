package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundWorkOffloaderTest {

    @Test
    void queueingCostsAlmostNothingOnTheRenderThread() {
        // A scene that triggers a hundred sounds in one tick spends the frame doing audio work. Handing the
        // work to a dedicated thread means the frame cost is the queueing rather than the work.
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < 100; i++) {
            assertTrue(offloader.offer());
        }

        assertEquals(100L, offloader.offered());
        assertEquals(100, offloader.queued());
        assertEquals(100 * SoundWorkOffloader.MS_PER_QUEUE, offloader.msOnRenderThread(), 1e-9);
        assertEquals(0.05, offloader.msOnRenderThread(), 1e-9);
    }

    @Test
    void theWorkHappensOnTheOtherThread() {
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < 100; i++) {
            offloader.offer();
        }
        assertEquals(0L, offloader.started(), "not started yet");

        for (int i = 0; i < 100; i++) {
            assertTrue(offloader.drain());
        }

        assertEquals(100L, offloader.started());
        assertEquals(0, offloader.queued());
        assertFalse(offloader.drain(), "nothing left");
    }

    @Test
    void aFullQueueDropsTheOldestRatherThanBlocking() {
        // The whole point is that the render thread must not wait, and blocking on a full queue would put
        // the stall straight back.
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < SoundWorkOffloader.QUEUE_LIMIT; i++) {
            assertTrue(offloader.offer());
        }
        assertFalse(offloader.offer(), "the queue is full, so the oldest gives way");

        assertEquals(1L, offloader.dropped());
        assertEquals(SoundWorkOffloader.QUEUE_LIMIT, offloader.queued(),
                "the new one took the freed slot");
    }

    @Test
    void drainingMakesRoomAgain() {
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < SoundWorkOffloader.QUEUE_LIMIT; i++) {
            offloader.offer();
        }
        offloader.drain();

        assertEquals(SoundWorkOffloader.QUEUE_LIMIT - 1, offloader.queued());
        assertTrue(offloader.offer());
    }

    @Test
    void shutdownPlaysWhateverIsLeft() {
        // A sound queued and then thrown away would cut off mid-play, and on the way out of a world that is
        // audible as a stutter at the exact moment the player expects a clean transition.
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < 40; i++) {
            offloader.offer();
        }
        for (int i = 0; i < 10; i++) {
            offloader.drain();
        }

        assertEquals(30, offloader.drainAll());
        assertEquals(0, offloader.queued());
        assertEquals(40L, offloader.started());
        assertEquals(30L, offloader.drainedOnShutdown());
    }

    @Test
    void drainingAnEmptyQueueDoesNothing() {
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        assertFalse(offloader.drain());
        assertEquals(0, offloader.drainAll());
    }

    @Test
    void anExplosionIsAlmostFreeOnTheRenderThread() {
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        for (int i = 0; i < 100; i++) {
            offloader.offer();
        }

        assertEquals(5.0, offloader.msWithoutOffloading(), 1e-9);
        assertEquals(99.0, offloader.savedPercent(), 1e-9,
                "0.05 of queueing against 5.0 of starting");
    }

    @Test
    void aFreshOffloaderReportsZeroNotNaN() {
        // An offloader that has been given nothing has technically removed everything, but reporting that as
        // a saving would describe an unused one as though it had done something.
        SoundWorkOffloader offloader = new SoundWorkOffloader();

        assertEquals(0.0, offloader.droppedPercent());
        assertEquals(0.0, offloader.savedPercent());
        assertEquals(0.0, offloader.msOnRenderThread(), 1e-12);
        assertEquals(0L, offloader.offered());
        assertEquals(0L, offloader.started());
        assertEquals(0, offloader.queued());
        assertEquals(256, SoundWorkOffloader.QUEUE_LIMIT);
    }
}
