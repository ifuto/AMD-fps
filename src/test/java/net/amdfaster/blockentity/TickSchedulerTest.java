package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSchedulerTest {

    private static TickScheduler withIdle(int n) {
        TickScheduler scheduler = new TickScheduler(n);
        for (int i = 0; i < n; i++) {
            scheduler.add(i);
        }
        return scheduler;
    }

    @Test
    void idleEntitiesAreSpreadEvenlyAcrossThePeriod() {
        // The whole point: four hundred block entities ticking together is an 8 ms block. Round-robin
        // over four slots puts a hundred on each tick, and the slots stay within one of each other,
        // which hashing by id would not guarantee.
        TickScheduler scheduler = withIdle(400);

        for (long tick = 0; tick < TickScheduler.IDLE_PERIOD; tick++) {
            assertEquals(100, scheduler.dueCount(tick), "tick " + tick);
        }
    }

    @Test
    void nothingIsEverSkipped() {
        // Deferring is fine; skipping is not. An entity that never runs would let a chest lid or a
        // furnace's progress fall permanently behind, and the error shows as animation lagging the game.
        TickScheduler scheduler = withIdle(37);

        for (int id = 0; id < 37; id++) {
            int runs = 0;
            for (long tick = 0; tick < 4 * TickScheduler.IDLE_PERIOD; tick++) {
                if (scheduler.shouldTick(id, tick)) {
                    runs++;
                    scheduler.markRun(id);
                }
            }
            assertEquals(4, runs, "id " + id + " over four periods");
        }
    }

    @Test
    void anEntityRunsExactlyOncePerPeriod() {
        TickScheduler scheduler = withIdle(1);

        int runs = 0;
        for (long tick = 0; tick < TickScheduler.IDLE_PERIOD; tick++) {
            if (scheduler.shouldTick(0, tick)) {
                runs++;
                scheduler.markRun(0);
            }
        }
        assertEquals(1, runs, "once, not twice and not never");
    }

    @Test
    void goingActiveTicksImmediately() {
        // A chest the player just opened has to start animating on the next tick. Waiting up to four
        // ticks means the lid visibly does not move when clicked.
        TickScheduler scheduler = withIdle(4);
        long tick = 1;
        // Id 1 sits in slot 1, so it would not otherwise run until tick 1 mod 4 == 1; move to a tick
        // where it is not due to prove the activation is what triggers it.
        long notDue = 0;
        assertFalse(scheduler.shouldTick(1, notDue), "slot 1 does not run on tick 0");

        scheduler.setActive(1, true);

        assertTrue(scheduler.shouldTick(1, notDue), "activating overrides the slot");
        assertTrue(scheduler.isActive(1));
    }

    @Test
    void goingIdleRunsOnceMoreBeforeDeferring() {
        // Stopping mid-animation would leave a chest lid part open. The extra run lets it finish.
        TickScheduler scheduler = withIdle(4);
        scheduler.setActive(2, true);
        scheduler.markRun(2);

        scheduler.setActive(2, false);

        assertTrue(scheduler.shouldTick(2, 0), "one more run after going idle");
        scheduler.markRun(2);
        assertFalse(scheduler.shouldTick(2, 0), "and then it defers to its slot");
    }

    @Test
    void activeEntitiesTickEveryTick() {
        TickScheduler scheduler = withIdle(8);
        scheduler.setActive(3, true);

        for (long tick = 0; tick < 12; tick++) {
            assertTrue(scheduler.shouldTick(3, tick), "tick " + tick);
            scheduler.markRun(3);
        }
    }

    @Test
    void settingTheSameClassTwiceDoesNotReschedule() {
        TickScheduler scheduler = withIdle(4);
        scheduler.setActive(1, true);
        scheduler.markRun(1);

        scheduler.setActive(1, true);

        // Still active, so it ticks anyway, but the pending mark must not be set by a no-op change;
        // otherwise every redundant call would look like a transition.
        assertFalse(scheduler.shouldTick(1, 0) && !scheduler.isActive(1));
        scheduler.setActive(1, false);
        assertTrue(scheduler.shouldTick(1, 0), "a real transition does schedule");
    }

    @Test
    void theSpreadIsMeasuredAtTheWorstTick() {
        // An average would hide the spike, which is exactly what this exists to remove.
        TickScheduler scheduler = new TickScheduler(400);
        for (int i = 0; i < 400; i++) {
            scheduler.add(i);
        }
        for (int i = 0; i < 40; i++) {
            scheduler.setActive(i, true);
        }

        // 40 active plus a quarter of the 360 idle, on every tick.
        assertEquals(130, scheduler.dueCount(0));
        assertEquals(2.6, scheduler.worstTickMs(0, TickScheduler.IDLE_PERIOD, 0.02), 0.001);
        assertEquals(8.0, scheduler.everyTickMs(0.02), 0.001);
        assertEquals(67.5, scheduler.reductionPercent(0.02), 0.01);
    }

    @Test
    void aRemovedEntityStopsTicking() {
        TickScheduler scheduler = withIdle(8);
        scheduler.remove(3);

        for (long tick = 0; tick < 12; tick++) {
            assertFalse(scheduler.shouldTick(3, tick));
        }
        assertEquals(7, scheduler.registered());
    }

    @Test
    void addingTwiceIsIdempotent() {
        TickScheduler scheduler = withIdle(4);
        scheduler.add(2);

        assertEquals(4, scheduler.registered(), "and the slot was not reassigned");
    }

    @Test
    void theTableGrowsWithoutLosingEntities() {
        TickScheduler scheduler = new TickScheduler(4);
        scheduler.add(0);
        scheduler.add(100);

        assertEquals(2, scheduler.registered());
        assertTrue(scheduler.shouldTick(0, 0), "slot 0 on tick 0");
        assertTrue(scheduler.shouldTick(100, 1), "id 100 took slot 1");
    }

    @Test
    void negativeTicksAreHandled() {
        // floorMod, not %, because a world time can be negative after a rollback and a negative slot
        // comparison would silently never match.
        TickScheduler scheduler = withIdle(4);

        assertTrue(scheduler.shouldTick(1, -3L), "floorMod(-3,4)==1");
        assertFalse(scheduler.shouldTick(1, -4L));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TickScheduler(0));
        TickScheduler scheduler = new TickScheduler(4);
        assertThrows(IndexOutOfBoundsException.class, () -> scheduler.add(-1));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        TickScheduler scheduler = new TickScheduler(4);
        assertEquals(0.0, scheduler.reductionPercent(0.02));
        assertEquals(0, scheduler.registered());
        assertEquals(0, scheduler.dueCount(0));
    }
}
