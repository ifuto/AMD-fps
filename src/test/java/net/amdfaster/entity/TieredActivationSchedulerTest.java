package net.amdfaster.entity;

import net.amdfaster.entity.TieredActivationScheduler.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TieredActivationSchedulerTest {

    @Test
    void distanceDecidesTheTier() {
        // A single cut-off gets both cases wrong: it spends full attention on mobs that only needed some, and
        // takes every tick away from mobs that are far but not infinitely far.
        assertEquals(Tier.FULL, TieredActivationScheduler.tierFor(10.0, false));
        assertEquals(Tier.REDUCED, TieredActivationScheduler.tierFor(50.0, false));
        assertEquals(Tier.INACTIVE, TieredActivationScheduler.tierFor(100.0, false));
        assertEquals(Tier.MINIMAL, TieredActivationScheduler.tierFor(500.0, false));
    }

    @Test
    void theDivisorsGetLongerWithDistance() {
        assertEquals(1, Tier.FULL.divisor());
        assertEquals(2, Tier.REDUCED.divisor());
        assertEquals(8, Tier.INACTIVE.divisor());
        assertEquals(20, Tier.MINIMAL.divisor());
    }

    @Test
    void aPlayerIsNeverThrottled() {
        // A player entity that ticked at a reduced rate would move in steps, and on a server the other
        // players would see them stutter.
        assertEquals(Tier.FULL, TieredActivationScheduler.tierFor(500.0, true));

        TieredActivationScheduler scheduler = new TieredActivationScheduler();
        assertTrue(scheduler.shouldTick(500.0, true, false, 0, 0));
        assertEquals(1L, scheduler.exempt());
    }

    @Test
    void physicsIsNeverSkipped() {
        // A falling entity that stops being ticked stops falling and hangs in the air, and an entity on a
        // despawn timer that stops counting never despawns, so the world slowly fills with things that
        // should have gone.
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        assertTrue(scheduler.shouldTick(500.0, false, true, 0, 0));
        assertEquals(1L, scheduler.exempt());
        assertEquals(1L, scheduler.ticked());
    }

    @Test
    void aReducedMobTicksOnItsOwnPhase() {
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        assertTrue(scheduler.shouldTick(50.0, false, false, 0, 0), "phase zero, tick zero");
        assertFalse(scheduler.shouldTick(50.0, false, false, 0, 1));
        assertTrue(scheduler.shouldTick(50.0, false, false, 0, 2));
    }

    @Test
    void aMinimalEntityIsWokenButNotEveryTick() {
        // An entity that never ticks accumulates no time, so a mob five hundred blocks away would not age,
        // would not fall and would not despawn -- and walking up to it would show something that has been
        // suspended since the player left.
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        for (int i = 0; i < TieredActivationScheduler.MAX_WAKEUPS_PER_TICK; i++) {
            assertTrue(scheduler.shouldTick(500.0, false, false, i, 0));
        }
        assertFalse(scheduler.shouldTick(500.0, false, false, 99, 0),
                "the tick's wake-up budget is spent");

        assertEquals(4L, scheduler.wakeups());
        assertEquals(1L, scheduler.wakeupsRefused());
        assertEquals(0, scheduler.wakeupsLeft());
    }

    @Test
    void theWakeUpBudgetResetsEachTick() {
        // A hundred distant mobs all waking on the same tick is a hundred ticks of work at once, which is
        // the spike the tiers exist to avoid.
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        for (int i = 0; i < TieredActivationScheduler.MAX_WAKEUPS_PER_TICK; i++) {
            scheduler.shouldTick(500.0, false, false, i, 0);
        }
        scheduler.endTick();

        assertEquals(TieredActivationScheduler.MAX_WAKEUPS_PER_TICK, scheduler.wakeupsLeft());
        assertTrue(scheduler.shouldTick(500.0, false, false, 50, 1));
    }

    @Test
    void aWorldOfDistantMobsCostsAlmostNothing() {
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        for (int tick = 0; tick < 20; tick++) {
            for (int id = 0; id < 100; id++) {
                scheduler.shouldTick(100.0, false, false, id, tick);
            }
            scheduler.endTick();
        }

        assertEquals(2000L, scheduler.offered());
        assertEquals(252L, scheduler.ticked(),
                "every eighth tick; a hundred ids over eight phases is not exactly even");
        assertEquals(87.4, scheduler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TieredActivationScheduler.tierFor(-1.0, false));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has been offered nothing has technically avoided everything, but reporting that
        // as a saving would describe an unused one as though it had done something.
        TieredActivationScheduler scheduler = new TieredActivationScheduler();

        assertEquals(0.0, scheduler.tickedPercent());
        assertEquals(0.0, scheduler.savedPercent());
        assertEquals(0.0, scheduler.msTicking(), 1e-12);
        assertEquals(0L, scheduler.offered());
        assertEquals(0L, scheduler.wakeups());
        assertEquals(4, TieredActivationScheduler.MAX_WAKEUPS_PER_TICK);
    }
}
