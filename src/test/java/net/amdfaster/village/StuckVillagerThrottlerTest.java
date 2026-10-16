package net.amdfaster.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckVillagerThrottlerTest {

    @Test
    void aVillagerWithRoomToWalkTicksNormally() {
        // A villager wandering a village should tick normally, and one in a pen with room to walk should too.
        // Throttling all of them would mean villagers in the open visibly hesitating.
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();

        for (int tick = 0; tick < 20; tick++) {
            assertTrue(throttler.shouldTick(true, false, tick, 0));
        }
        assertEquals(20L, throttler.normal());
        assertEquals(0L, throttler.throttled());
    }

    @Test
    void aStuckVillagerTicksOnItsOwnPhase() {
        // A villager in a trading hall sits in a one-block space and reaches the same conclusion every tick --
        // that there is nowhere to go.
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();

        assertTrue(throttler.shouldTick(false, false, 0, 0), "phase zero is due on tick zero");
        assertFalse(throttler.shouldTick(false, false, 0, 1), "phase one is not");
        assertTrue(throttler.shouldTick(false, false, 1, 1), "and is due a tick later");
    }

    @Test
    void aPlayerInteractingRestoresItImmediately() {
        // Opening a trading screen, feeding a villager or hitting one all need it to respond on the tick it
        // happens. If it only noticed at its next scheduled tick the interaction would take up to a second to
        // register, which the player would experience as the game ignoring them.
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();

        assertFalse(throttler.shouldTick(false, false, 1, 0), "off phase, stuck");
        assertTrue(throttler.shouldTick(false, true, 1, 0), "and immediately on interaction");
        assertEquals(1L, throttler.restored());
    }

    @Test
    void aTradingHallTicksAlmostNothing() {
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();

        for (int tick = 0; tick < 20; tick++) {
            for (int id = 0; id < 200; id++) {
                throttler.shouldTick(false, false, tick, id);
            }
        }

        assertEquals(4000L, throttler.ticks());
        assertEquals(200L, throttler.throttled());
        assertEquals(10.0, throttler.msTicking(), 1e-9);
        assertEquals(200.0, throttler.msWithoutThrottling(), 1e-9);
        assertEquals(95.0, throttler.savedPercent(), 1e-9);
    }

    @Test
    void theWorkIsSpreadAcrossTheInterval() {
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();
        int[] perTick = new int[20];

        for (int tick = 0; tick < 20; tick++) {
            for (int id = 0; id < 200; id++) {
                if (throttler.shouldTick(false, false, tick, id)) {
                    perTick[tick]++;
                }
            }
        }

        for (int tick = 0; tick < 20; tick++) {
            assertEquals(10, perTick[tick], "a twentieth on every tick");
        }
    }

    @Test
    void aFreshThrottlerReportsZeroNotNaN() {
        // A throttler that has been offered nothing has technically avoided everything, but reporting that as
        // a saving would describe an unused one as though it had done something.
        StuckVillagerThrottler throttler = new StuckVillagerThrottler();

        assertEquals(0.0, throttler.savedPercent());
        assertEquals(0.0, throttler.msTicking(), 1e-12);
        assertEquals(0L, throttler.ticks());
        assertEquals(0L, throttler.normal());
        assertEquals(0L, throttler.restored());
        assertEquals(20, StuckVillagerThrottler.THROTTLE_INTERVAL);
    }
}
