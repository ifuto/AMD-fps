package net.amdfaster.net;

import net.amdfaster.net.PacketPriorityScheduler.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketPrioritySchedulerTest {

    private static final byte[] PAYLOAD = new byte[] {1, 2, 3};

    @Test
    void movementIsHandledBeforeBulkData() {
        // Under an elytra, two hundred chunk packets arrive ahead of the movement packets. Each chunk
        // costs about half a millisecond, so the bulk work is 100 ms and for all of it the player's own
        // movement sits in the queue -- which is felt as input lag.
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        for (int i = 0; i < 200; i++) {
            scheduler.submit(PacketPriorityScheduler.PRIORITY_BULK, i, 0, PAYLOAD);
        }
        scheduler.submit(PacketPriorityScheduler.PRIORITY_MOVEMENT, 900, 0, PAYLOAD);

        Packet first = scheduler.next(0);

        assertEquals(PacketPriorityScheduler.PRIORITY_MOVEMENT, first.priority());
        assertEquals(900L, first.subject());
    }

    @Test
    void packetsAboutTheSameSubjectKeepTheirArrivalOrder() {
        // The constraint that makes this more than a priority queue. A block break followed by a block
        // place at one position has to be applied in that order; reordering does not slow anything down,
        // it corrupts the world.
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        scheduler.submit(PacketPriorityScheduler.PRIORITY_BULK, 42, 0, PAYLOAD);
        scheduler.submit(PacketPriorityScheduler.PRIORITY_MOVEMENT, 42, 1, PAYLOAD);

        Packet first = scheduler.next(1);

        assertEquals(0, first.submittedTick(),
                "the older packet about subject 42 goes first even though it is bulk");
    }

    @Test
    void differentSubjectsCanBeReordered() {
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        scheduler.submit(PacketPriorityScheduler.PRIORITY_BULK, 1, 0, PAYLOAD);
        scheduler.submit(PacketPriorityScheduler.PRIORITY_MOVEMENT, 2, 1, PAYLOAD);

        assertEquals(2L, scheduler.next(1).subject(), "unrelated, so priority decides");
    }

    @Test
    void aWaitingBulkPacketIsEventuallyPromoted() {
        // Without aging, bulk traffic behind a stream of movement would never run and the player would
        // walk into a world that never loads.
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        scheduler.submit(PacketPriorityScheduler.PRIORITY_BULK, 1, 0, PAYLOAD);
        scheduler.submit(PacketPriorityScheduler.PRIORITY_MOVEMENT, 2, 0, PAYLOAD);

        assertEquals(2L, scheduler.next(0).subject(), "movement first at tick 0");

        scheduler.submit(PacketPriorityScheduler.PRIORITY_MOVEMENT, 3, 100, PAYLOAD);
        int aged = PacketPriorityScheduler.AGING_TICKS * (PacketPriorityScheduler.PRIORITY_LEVELS);
        Packet served = scheduler.next(aged);

        assertEquals(1L, served.subject(), "the bulk packet has waited long enough to outrank movement");
    }

    @Test
    void effectivePriorityRisesWithWaiting() {
        Packet fresh = new Packet(PacketPriorityScheduler.PRIORITY_BULK, 1, 0, PAYLOAD);

        assertEquals(0, PacketPriorityScheduler.effective(fresh, 0),
                "bulk is the lowest level, worth 0");
        assertEquals(1, PacketPriorityScheduler.effective(fresh, PacketPriorityScheduler.AGING_TICKS));
        assertEquals(2, PacketPriorityScheduler.effective(fresh, 2 * PacketPriorityScheduler.AGING_TICKS));
    }

    @Test
    void movementHasTheHighestBasePriority() {
        Packet movement = new Packet(PacketPriorityScheduler.PRIORITY_MOVEMENT, 1, 0, PAYLOAD);
        Packet bulk = new Packet(PacketPriorityScheduler.PRIORITY_BULK, 1, 0, PAYLOAD);

        assertTrue(PacketPriorityScheduler.effective(movement, 0)
                > PacketPriorityScheduler.effective(bulk, 0));
        assertEquals(PacketPriorityScheduler.PRIORITY_LEVELS - 1,
                PacketPriorityScheduler.effective(movement, 0));
    }

    @Test
    void anEmptyQueueReturnsNull() {
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        assertNull(scheduler.next(0));
        assertEquals(0, scheduler.pending());
    }

    @Test
    void everySubmittedPacketIsEventuallyHandled() {
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        for (int i = 0; i < 500; i++) {
            scheduler.submit(i % PacketPriorityScheduler.PRIORITY_LEVELS, i, i / 10, PAYLOAD);
        }
        assertEquals(500, scheduler.pending());

        int handled = 0;
        while (scheduler.next(handled / 10) != null) {
            handled++;
        }

        assertEquals(500, handled, "nothing starved, nothing lost");
        assertEquals(0, scheduler.pending());
    }

    @Test
    void theLagIsWhatTheFormulasSay() {
        // 200 bulk packets at half a millisecond each is what the player waits through.
        assertEquals(100.0, PacketPriorityScheduler.msLagWithoutPriority(200), 1e-9);
        assertEquals(2.0, PacketPriorityScheduler.msLagWithPriority(20), 1e-9);
        assertEquals(0.5, PacketPriorityScheduler.BULK_MS, 1e-9);
        assertEquals(0.1, PacketPriorityScheduler.MOVEMENT_MS, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(-1, 1, 0, PAYLOAD));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(PacketPriorityScheduler.PRIORITY_LEVELS, 1, 0, PAYLOAD));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.submit(0, 1, 0, null));
    }

    @Test
    void aFreshSchedulerReportsZero() {
        PacketPriorityScheduler scheduler = new PacketPriorityScheduler();

        assertEquals(0, scheduler.pending());
        assertEquals(0L, scheduler.submitted());
        assertEquals(0L, scheduler.handled());
        assertEquals(0L, scheduler.promoted());
        assertEquals(4, PacketPriorityScheduler.PRIORITY_LEVELS);
        assertEquals(20, PacketPriorityScheduler.AGING_TICKS);
    }
}
