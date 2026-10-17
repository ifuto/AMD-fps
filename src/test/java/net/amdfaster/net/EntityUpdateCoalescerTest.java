package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityUpdateCoalescerTest {

    @Test
    void anOrdinaryUpdateIsHeldForTheEndOfTheTick() {
        // The renderer draws the entity once per frame, at wherever it ended up, so every arrival except the
        // last produced state that was never drawn.
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        assertFalse(coalescer.receive(false));
        assertEquals(1L, coalescer.received());
        assertEquals(0L, coalescer.applied());
    }

    @Test
    void aTeleportIsAppliedImmediately() {
        // An ordinary update is a delta, so dropping one and applying the next still lands in the right
        // place. A teleport is absolute and resets the interpolation, and dropping it would leave the entity
        // interpolating smoothly towards a place it is no longer at.
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        assertTrue(coalescer.receive(true));
        assertEquals(1L, coalescer.teleports());
        assertEquals(1L, coalescer.applied());
        assertEquals(0L, coalescer.coalesced());
    }

    @Test
    void threeUpdatesBecomeOneApplication() {
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        coalescer.receive(false);
        coalescer.receive(false);
        coalescer.receive(false);
        assertEquals(1, coalescer.endTick(1));

        assertEquals(3L, coalescer.received());
        assertEquals(1L, coalescer.applied());
        assertEquals(2L, coalescer.coalesced());
    }

    @Test
    void aBusyServerSavesTwoThirdsOfTheWork() {
        // Two hundred entities arriving three times a tick: four hundred of the six hundred applications
        // produce nothing the player can see.
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 600; i++) {
                coalescer.receive(false);
            }
            coalescer.endTick(200);
        }

        assertEquals(12_000L, coalescer.received());
        assertEquals(4_000L, coalescer.applied());
        assertEquals(8_000L, coalescer.coalesced());
        assertEquals(66.6667, coalescer.coalescedPercent(), 0.001);
        assertEquals(32.0, coalescer.msSaved(), 1e-9);
        assertEquals(48.0, coalescer.msWithoutCoalescing(), 1e-9);
        assertEquals(66.6667, coalescer.savedPercent(), 0.001);
    }

    @Test
    void aServerThatSendsOneUpdateEachSavesNothing() {
        // The applications still happen, so what is saved is the superseded ones and nothing else.
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 200; i++) {
                coalescer.receive(false);
            }
            coalescer.endTick(200);
        }

        assertEquals(4_000L, coalescer.received());
        assertEquals(0L, coalescer.coalesced());
        assertEquals(0.0, coalescer.savedPercent(), 1e-12);
    }

    @Test
    void aTeleportIsNeverSupersededByALaterUpdate() {
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        coalescer.receive(true);
        coalescer.receive(false);
        coalescer.receive(false);
        coalescer.endTick(1);

        assertEquals(2L, coalescer.applied(), "the teleport plus the one coalesced application");
        assertEquals(1L, coalescer.coalesced());
    }

    @Test
    void morePendingEntitiesThanUpdatesIsRejected() {
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        coalescer.receive(false);

        assertThrows(IllegalStateException.class, () -> coalescer.endTick(2));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        assertThrows(IllegalArgumentException.class, () -> coalescer.endTick(-1));
    }

    @Test
    void aFreshCoalescerReportsZeroNotNaN() {
        // A coalescer that has received nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        EntityUpdateCoalescer coalescer = new EntityUpdateCoalescer();

        assertEquals(0.0, coalescer.coalescedPercent());
        assertEquals(0.0, coalescer.savedPercent());
        assertEquals(0.0, coalescer.msSaved(), 1e-12);
        assertEquals(0L, coalescer.received());
        assertEquals(0L, coalescer.applied());
        assertEquals(0L, coalescer.ticks());
        assertEquals(0.004, EntityUpdateCoalescer.MS_PER_APPLY, 1e-12);
    }
}
