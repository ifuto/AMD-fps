package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivationRangeSchedulerTest {

    private static final double FAR = 200.0;
    private static final double NEAR = 10.0;

    @Test
    void anEntityInRangeTicksEveryTick() {
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        for (int tick = 0; tick < 4; tick++) {
            assertTrue(scheduler.shouldTick(NEAR, false, tick, 0));
        }
        assertEquals(4L, scheduler.active());
    }

    @Test
    void aDistantEntityTicksOnItsOwnPhase() {
        // A mob that thinks four times a second instead of twenty is not observably different at that
        // distance, and the tick budget goes back to entities the player will see.
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        assertTrue(scheduler.shouldTick(FAR, false, 0, 0), "phase zero is due on tick zero");
        assertFalse(scheduler.shouldTick(FAR, false, 0, 1), "phase one is not");
        assertTrue(scheduler.shouldTick(FAR, false, 1, 1), "and is due a tick later");
    }

    @Test
    void anItemEntityAlwaysTicks() {
        // An item is merging or despawning, an arrow is falling, a TNT has a fuse counting down. Slowing
        // those would be visible and would be wrong -- an arrow that falls in slow motion, a fuse that takes
        // four times as long.
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        for (int tick = 0; tick < 4; tick++) {
            assertTrue(scheduler.shouldTick(FAR, true, tick, 0));
        }
        assertEquals(4L, scheduler.exempt());
    }

    @Test
    void theRangeIsADistanceNotAChunkCount() {
        // A chunk is sixteen blocks wide, so a range measured in chunks changes in sixteen-block jumps: an
        // entity at 15.9 blocks and one at 16.1 would be treated differently for no reason a player could
        // perceive.
        assertTrue(ActivationRangeScheduler.isActive(31.9));
        assertTrue(ActivationRangeScheduler.isActive(32.0));
        assertFalse(ActivationRangeScheduler.isActive(32.1));
    }

    @Test
    void comingIntoRangeRestoresTheNormalRate() {
        // A player walking up to a mob expects it to react. If the rate only changed at the next scheduled
        // tick, the mob would sit still for up to a quarter of a second after the player arrived.
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        // Entity zero is due on tick zero, four, eight -- so on tick one it is off phase and far away.
        assertFalse(scheduler.shouldTick(FAR, false, 1, 0), "off phase, distant");
        assertTrue(scheduler.shouldTick(NEAR, false, 1, 0), "and immediately when it is close");
    }

    @Test
    void theWorkIsSpreadAcrossTheInterval() {
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();
        int[] perTick = new int[4];

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 100; id++) {
                if (scheduler.shouldTick(FAR, false, tick, id)) {
                    perTick[tick]++;
                }
            }
        }

        for (int tick = 0; tick < 4; tick++) {
            assertEquals(25, perTick[tick], "a quarter on every tick");
        }
    }

    @Test
    void aFarmAFarAwayCostsAQuarter() {
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        for (int tick = 0; tick < 4; tick++) {
            for (int id = 0; id < 100; id++) {
                scheduler.shouldTick(FAR, false, tick, id);
            }
        }

        assertEquals(400L, scheduler.ticks());
        assertEquals(100L, scheduler.idle());
        assertEquals(2.0, scheduler.msTicking(), 1e-9);
        assertEquals(8.0, scheduler.msWithoutScheduling(), 1e-9);
        assertEquals(75.0, scheduler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.shouldTick(-1.0, false, 0, 0));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has been offered nothing has technically avoided everything, but reporting that
        // as a saving would describe an unused one as though it had done something.
        ActivationRangeScheduler scheduler = new ActivationRangeScheduler();

        assertEquals(0.0, scheduler.savedPercent());
        assertEquals(0.0, scheduler.msTicking(), 1e-12);
        assertEquals(0L, scheduler.ticks());
        assertEquals(0L, scheduler.active());
        assertEquals(0L, scheduler.exempt());
        assertEquals(32.0, ActivationRangeScheduler.ACTIVE_RANGE, 1e-12);
        assertEquals(4, ActivationRangeScheduler.IDLE_INTERVAL);
    }
}
