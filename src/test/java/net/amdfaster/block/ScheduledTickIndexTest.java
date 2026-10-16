package net.amdfaster.block;

import net.amdfaster.block.ScheduledTickIndex.Booking;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTickIndexTest {

    @Test
    void aBookingFiresOnItsTick() {
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 100L);

        assertTrue(index.due(99L).isEmpty(), "not yet");
        List<Booking> fired = index.due(100L);

        assertEquals(1, fired.size());
        assertEquals(1L, fired.get(0).id());
    }

    @Test
    void nothingDueIsEverMissed() {
        // The failure this must not have. Water stops flowing, a repeater stops propagating, a crop
        // stops growing -- a machine that no longer works, with nothing to connect it to a renderer mod.
        ScheduledTickIndex index = new ScheduledTickIndex();
        for (int i = 0; i < 5000; i++) {
            index.schedule(i, i % 16, 64, i / 16, 100L + i % 50);
        }

        List<Booking> fired = index.due(149L);

        assertEquals(5000, fired.size(), "everything booked at or before 149");
        assertEquals(0, index.pending());
    }

    @Test
    void aBookingInTheFutureIsLeftAlone() {
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 200L);

        assertTrue(index.due(100L).isEmpty());
        assertEquals(1, index.pending());
    }

    @Test
    void aClockThatWentBackwardsStillFiresWhatWasDue() {
        // A world rollback or a server time correction. Bookings that were already due are still due,
        // and dropping them would leave a machine permanently stuck.
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 50L);
        index.schedule(2, 0, 64, 0, 60L);

        List<Booking> fired = index.due(40L);

        assertTrue(fired.isEmpty(), "nothing is due at 40");
        assertEquals(2, index.pending());

        // The clock moves forward past both, then the caller asks at an earlier time again.
        assertEquals(2, index.due(100L).size());
        assertEquals(0, index.pending(), "and they are gone, not left to fire twice");
    }

    @Test
    void firingRemovesTheBooking() {
        // Otherwise it would run every tick forever, which for water means an endless flow update.
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 10L);

        assertEquals(1, index.due(10L).size());
        assertTrue(index.due(10L).isEmpty(), "not twice");
        assertEquals(0, index.pending());
    }

    @Test
    void severalBookingsOnOneTickAllFire() {
        ScheduledTickIndex index = new ScheduledTickIndex();
        for (int i = 0; i < 10; i++) {
            index.schedule(i, i, 64, 0, 25L);
        }

        assertEquals(10, index.due(25L).size());
    }

    @Test
    void cancellingStopsABookingFromFiring() {
        // A block removed before its tick came must not run the tick anyway.
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(7, 3, 64, 4, 50L);

        assertTrue(index.cancel(7, 50L));
        assertTrue(index.due(50L).isEmpty());
        assertEquals(0, index.pending());
    }

    @Test
    void cancellingSomethingThatIsNotThereReportsIt() {
        ScheduledTickIndex index = new ScheduledTickIndex();

        assertFalse(index.cancel(1, 50L));
        assertFalse(index.cancel(1, 999L), "wrong tick");
    }

    @Test
    void cancellingByPositionClearsEverythingAtThatBlock() {
        // A block that changed invalidates whatever it had booked.
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 3, 64, 4, 10L);
        index.schedule(2, 3, 64, 4, 20L);
        index.schedule(3, 5, 64, 4, 20L);

        assertEquals(2, index.cancelAt(3, 64, 4));
        assertEquals(1, index.pending());
        assertEquals(3L, index.due(20L).get(0).id(), "and the other block's booking survived");
    }

    @Test
    void nextDueTickReportsTheEarliest() {
        ScheduledTickIndex index = new ScheduledTickIndex();
        assertEquals(Long.MAX_VALUE, index.nextDueTick(), "nothing booked");

        index.schedule(1, 0, 64, 0, 50L);
        index.schedule(2, 0, 64, 0, 30L);
        index.schedule(3, 0, 64, 0, 90L);

        assertEquals(30L, index.nextDueTick());
    }

    @Test
    void anIdleTickCanBeSkippedEntirely() {
        // Where most of the saving actually lands: not in the lookup, but in not waking up at all.
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 100L);

        assertTrue(index.isIdleAt(99L));
        assertFalse(index.isIdleAt(100L));
    }

    @Test
    void theScanThisReplacesIsMuchMoreExpensive() {
        // 5,000 bookings examined every tick to find the 50 that are due: 99% of the work looks at
        // bookings that are not ready yet.
        ScheduledTickIndex index = new ScheduledTickIndex();
        for (int i = 0; i < 5000; i++) {
            index.schedule(i, i % 16, 64, i / 16, i);
        }

        for (long tick = 0; tick < 20; tick++) {
            index.due(tick);
        }

        long without = index.checksWithoutIndex(5000, 20);
        assertEquals(100_000L, without);
        assertTrue(index.checksSaved() < without,
                "bucketing has to help: " + index.checksSaved() + " vs " + without);
    }

    @Test
    void thePeakIsRecorded() {
        ScheduledTickIndex index = new ScheduledTickIndex();
        index.schedule(1, 0, 64, 0, 10L);
        index.schedule(2, 0, 64, 0, 20L);
        index.due(10L);
        index.schedule(3, 0, 64, 0, 30L);

        assertEquals(2, index.peakPending(), "two were waiting at the busiest moment");
    }

    @Test
    void aFreshIndexReportsZeroNotNaN() {
        ScheduledTickIndex index = new ScheduledTickIndex();

        assertEquals(0.0, index.savedPercent(100, 10));
        assertEquals(0, index.pending());
        assertEquals(0L, index.scheduled());
        assertTrue(index.isIdleAt(0), "nothing is ever due");
    }
}
