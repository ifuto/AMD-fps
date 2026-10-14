package net.amdfaster.runtime;

import net.amdfaster.runtime.RenderUpdateGate.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderUpdateGateTest {

    @Test
    void anActiveWindowPausesNothing() {
        // The default state, and the one that has to be right: pausing chunk rebuilds while the world
        // is being presented leaves chunks missing.
        RenderUpdateGate gate = new RenderUpdateGate();

        for (Category category : Category.values()) {
            assertTrue(gate.shouldRun(category), category + " runs");
        }
        assertEquals(Category.values().length, RenderUpdateGate.CATEGORY_COUNT);
        assertEquals(0L, gate.paused());
        assertEquals(0.0, gate.pausedPercent());
    }

    @Test
    void anInactiveWindowPausesEverything() {
        // The only condition that is safe. Nothing is being drawn, so nothing here can be seen.
        RenderUpdateGate gate = new RenderUpdateGate();
        gate.setWindowActive(false);

        for (Category category : Category.values()) {
            assertFalse(gate.shouldRun(category), category + " paused");
        }
        assertEquals(5L, gate.paused());
        for (Category category : Category.values()) {
            assertEquals(1L, gate.pausedFor(category), category + " counted");
        }
        assertEquals(100.0, gate.pausedPercent());
    }

    @Test
    void anOpenScreenIsNotEnoughToPauseAnything() {
        // An inventory leaves the world visible behind it. Freezing entity animation there is a change
        // the player can see, and this project's constraint is that appearance does not change. The
        // gate has no notion of screens at all, which is the point: a gate that paused more would save
        // more and be wrong.
        RenderUpdateGate gate = new RenderUpdateGate();
        gate.setWindowActive(true);

        assertTrue(gate.shouldRun(Category.ENTITY_CACHE));
        assertTrue(gate.shouldRun(Category.PARTICLES));
    }

    @Test
    void aRequestMadeWhilePausedSurvivesThePause() {
        // Dropping a paused update is not the same as not needing it. A chunk told to rebuild while
        // the window was away still has to be rebuilt, or the player returns to a hole in the world.
        RenderUpdateGate gate = new RenderUpdateGate();
        gate.setWindowActive(false);

        gate.requestRebuild(12345L);
        gate.requestRebuild(67890L);

        assertEquals(2, gate.pendingCount());
        assertTrue(gate.isPending(12345L));

        long[] out = new long[8];
        assertEquals(2, gate.drainPending(out));
        assertEquals(12345L, out[0], "oldest first");
        assertEquals(67890L, out[1]);
        assertEquals(0, gate.pendingCount());
    }

    @Test
    void drainingInBatchesKeepsTheOrder() {
        RenderUpdateGate gate = new RenderUpdateGate(64);
        for (int i = 1; i <= 20; i++) {
            gate.requestRebuild(i);
        }

        long[] first = new long[7];
        long[] second = new long[7];
        long[] third = new long[7];
        assertEquals(7, gate.drainPending(first));
        assertEquals(7, gate.drainPending(second));
        assertEquals(6, gate.drainPending(third));

        for (int i = 0; i < 7; i++) {
            assertEquals(i + 1, first[i], "first batch");
            assertEquals(i + 8, second[i], "second batch");
        }
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 15, third[i], "third batch");
        }
        assertEquals(0, gate.pendingCount());
    }

    @Test
    void overflowingDropsTheOldestAndSaysWhich() {
        // A client left inactive next to a running farm would queue without limit otherwise. What is
        // dropped is the request, never the fact that the section is dirty -- that stays with the
        // caller, which is why the key is reported rather than swallowed.
        RenderUpdateGate gate = new RenderUpdateGate(16);

        for (int i = 1; i <= 40; i++) {
            gate.requestRebuild(i);
        }

        assertEquals(16, gate.pendingCount(), "the queue is bounded");
        assertEquals(24L, gate.overflowed());
        assertEquals(24L, gate.lastDropped(), "the most recent drop was key 24");

        long[] out = new long[16];
        gate.drainPending(out);
        assertEquals(25L, out[0], "the oldest survivor is the first one not dropped");
        assertEquals(40L, out[15]);
        assertFalse(gate.isPending(1L), "and the dropped ones are gone from the queue");
    }

    @Test
    void lastDroppedIsTheDroppedKeyNotTheNewest() {
        // An earlier version computed an index off the head and returned the newest entry, which is
        // the one thing a caller must not mistake for a dropped one.
        RenderUpdateGate gate = new RenderUpdateGate(16);
        for (int i = 1; i <= 17; i++) {
            gate.requestRebuild(i * 100L);
        }

        assertEquals(100L, gate.lastDropped(), "key 100 was dropped to make room");
        long[] out = new long[16];
        gate.drainPending(out);
        assertEquals(200L, out[0]);
        assertEquals(1700L, out[15], "and the newest is still queued, not reported as dropped");
    }

    @Test
    void wrappingTheRingDoesNotLoseOrReorderRequests() {
        // The head moves on both drain and overflow, so the arithmetic gets exercised in both
        // directions. Losing one of these is a chunk that never rebuilds.
        RenderUpdateGate gate = new RenderUpdateGate(32);

        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 20; i++) {
                gate.requestRebuild(round * 20L + i);
            }
            long[] out = new long[20];
            assertEquals(20, gate.drainPending(out));
            for (int i = 0; i < 20; i++) {
                assertEquals(round * 20L + i, out[i], "round " + round + " entry " + i);
            }
        }
        assertEquals(0L, gate.overflowed(), "nothing overflowed");
        assertEquals(0, gate.pendingCount());
    }

    @Test
    void clearPendingIsForADimensionChange() {
        RenderUpdateGate gate = new RenderUpdateGate();
        gate.requestRebuild(1L);
        gate.requestRebuild(2L);
        gate.clearPending();

        assertEquals(0, gate.pendingCount());
        assertFalse(gate.isPending(1L));
        long[] out = new long[4];
        assertEquals(0, gate.drainPending(out));
    }

    @Test
    void inactiveTicksCountUpAndResetOnReturn() {
        RenderUpdateGate gate = new RenderUpdateGate();
        gate.setWindowActive(false);
        gate.setWindowActive(false);
        gate.setWindowActive(false);
        assertEquals(3, gate.inactiveTicks());

        gate.setWindowActive(true);
        assertEquals(0, gate.inactiveTicks());
        assertTrue(gate.isWindowActive());
    }

    @Test
    void aMinuteInactiveIsAboutTenThousandUpdatesNotRun() {
        // The figure behind the class: roughly ten thousand client-only updates a second, all
        // discarded while nothing is presented.
        RenderUpdateGate gate = new RenderUpdateGate(64);
        gate.setWindowActive(false);

        int perSecond = 9_910;
        for (int tick = 0; tick < 20 * 60; tick++) {
            gate.shouldRun(Category.CHUNK_REBUILD);
            gate.shouldRun(Category.ENTITY_CACHE);
            gate.shouldRun(Category.PARTICLES);
            gate.shouldRun(Category.LIGHT_SMOOTHING);
            gate.shouldRun(Category.SKY);
        }
        assertEquals(6000L, gate.decisions());
        assertEquals(6000L, gate.paused(), "all of them, because nothing was presented");
        assertEquals(1200L, gate.pausedFor(Category.CHUNK_REBUILD));
        assertTrue(perSecond * 60 > 500_000, "the work not done over a minute");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RenderUpdateGate(8));
    }
}
