package net.amdfaster.net;

import net.amdfaster.net.MovementPacketSuppressor.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketSuppressorTest {

    @Test
    void anEntityThatHasNotMovedSendsNothing() {
        // Most of those packets say "this entity is exactly where it was", and the receiving end does the
        // work of reading a packet that tells it nothing.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(Packet.NONE, suppressor.decide(0.0, 0.0));
        assertEquals(1L, suppressor.suppressed());
    }

    @Test
    void anEntityThatTurnedSendsOnlyItsRotation() {
        // A villager looking the wrong way is visible, so it still has to be sent -- but at a third of the
        // size.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(Packet.ROTATION, suppressor.decide(0.0, Math.PI));
        assertEquals(1L, suppressor.rotationOnly());
    }

    @Test
    void anEntityThatMovedSendsTheFullPacket() {
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(Packet.FULL, suppressor.decide(1.0, 0.0));
        assertEquals(1L, suppressor.full());
    }

    @Test
    void aMovementTooSmallToEncodeIsNotAChange() {
        // The protocol encodes position as a delta, and a delta smaller than the encoding's resolution
        // cannot be represented anyway -- sending it would transmit a difference that is lost on the way.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(Packet.NONE, suppressor.decide(MovementPacketSuppressor.MIN_DELTA / 2, 0.0));
        assertEquals(Packet.FULL, suppressor.decide(MovementPacketSuppressor.MIN_DELTA, 0.0));
    }

    @Test
    void aTeleportIsAlwaysSent() {
        // Suppressing one would leave the entity visibly in the wrong place on the other end until it moved
        // again, which is not a saving -- it is the entity being somewhere it is not.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(Packet.TELEPORT, suppressor.decide(MovementPacketSuppressor.TELEPORT_DISTANCE, 0.0));
        assertEquals(Packet.TELEPORT, suppressor.decide(100.0, 0.0));
        assertEquals(2L, suppressor.teleports());
    }

    @Test
    void aWorldOfStandingEntitiesSendsAlmostNothing() {
        // The cows in a pen, the villagers at their workstations, the armour stands in a storeroom.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        for (int tick = 0; tick < 100; tick++) {
            suppressor.decide(0.0, 0.0);
        }
        for (int tick = 0; tick < 10; tick++) {
            suppressor.decide(0.5, 0.0);
        }

        assertEquals(110L, suppressor.ticks());
        assertEquals(100L, suppressor.suppressed());
        assertEquals(90.91, suppressor.suppressedPercent(), 0.01);
        assertEquals(10L * MovementPacketSuppressor.BYTES_FULL, suppressor.bytesSent());
        assertEquals(110L * MovementPacketSuppressor.BYTES_FULL, suppressor.bytesWithoutSuppression());
        assertEquals(90.91, suppressor.savedPercent(), 0.01);
    }

    @Test
    void rotationOnlySavesPartOfThePacket() {
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        for (int tick = 0; tick < 100; tick++) {
            suppressor.decide(0.0, 1.0);
        }

        assertEquals(100L, suppressor.rotationOnly());
        assertEquals(100L * MovementPacketSuppressor.BYTES_ROTATION, suppressor.bytesSent());
        assertEquals(68.75, suppressor.savedPercent(), 0.01,
                "5 of 16 bytes, so 11 of 16 saved");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertThrows(IllegalArgumentException.class, () -> suppressor.decide(-1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> suppressor.decide(0.0, -1.0));
    }

    @Test
    void aFreshSuppressorReportsZeroNotNaN() {
        // A suppressor that has seen nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        MovementPacketSuppressor suppressor = new MovementPacketSuppressor();

        assertEquals(0.0, suppressor.suppressedPercent());
        assertEquals(0.0, suppressor.savedPercent());
        assertEquals(0.0, suppressor.msSaved(), 1e-12);
        assertEquals(0L, suppressor.ticks());
        assertEquals(0L, suppressor.bytesSent());
        assertEquals(16, MovementPacketSuppressor.BYTES_FULL);
        assertEquals(5, MovementPacketSuppressor.BYTES_ROTATION);
        assertTrue(MovementPacketSuppressor.TELEPORT_DISTANCE > 0.0);
    }
}
