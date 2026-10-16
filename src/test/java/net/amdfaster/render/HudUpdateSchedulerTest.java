package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudUpdateSchedulerTest {

    @Test
    void mostFramesReuseTheExistingHud() {
        // At two hundred frames a second the HUD is rebuilt two hundred times a second for geometry that
        // changes a few times a second at most.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        assertFalse(scheduler.shouldUpdate(false, false), "frame one, nothing happened");
        assertEquals(1L, scheduler.reused());
        assertEquals(0L, scheduler.scheduled());
    }

    @Test
    void theScheduleUpdatesRegardless() {
        // Updating only when something changed would need every possible change to be reported, and one
        // that was missed would leave the HUD showing a stale value indefinitely.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        for (int frame = 1; frame < HudUpdateScheduler.UPDATE_INTERVAL; frame++) {
            assertFalse(scheduler.shouldUpdate(false, false));
        }
        assertTrue(scheduler.shouldUpdate(false, false), "the tenth frame updates");
        assertEquals(1L, scheduler.scheduled());
        assertEquals(9L, scheduler.reused());
    }

    @Test
    void takingDamageForcesAnImmediateUpdate() {
        // A health bar that lags the damage taken is worse than no health bar: the player acts on what it
        // shows, and one that still reads full while the health is gone leads to a death that looks like
        // the game lied.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        assertTrue(scheduler.shouldUpdate(true, false), "on the first frame, not the tenth");
        assertEquals(1L, scheduler.forced());
        assertEquals(0L, scheduler.reused());
    }

    @Test
    void theDebugOverlayUpdatesEveryFrame() {
        // It shows the frame time, and a HUD updating every hundred milliseconds would smooth over exactly
        // the spikes it is there to reveal.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        for (int frame = 0; frame < 5; frame++) {
            assertTrue(scheduler.shouldUpdate(false, true));
        }
        assertEquals(5L, scheduler.forced());
        assertEquals(0L, scheduler.scheduled());
    }

    @Test
    void anIdleSessionReusesNineTenthsOfItsFrames() {
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        for (int frame = 0; frame < 1000; frame++) {
            scheduler.shouldUpdate(false, false);
        }

        assertEquals(1000L, scheduler.frames());
        assertEquals(100L, scheduler.scheduled());
        assertEquals(900L, scheduler.reused());
        assertEquals(90.0, scheduler.reusedPercent(), 1e-9);
        assertEquals(135.0, scheduler.msSaved(), 1e-9);
        assertEquals(150.0, scheduler.msWithoutScheduling(), 1e-9);
        assertEquals(90.0, scheduler.savedPercent(), 1e-9);
    }

    @Test
    void aSessionWithFrequentDamageStillUpdatesPromptly() {
        // The schedule is for the frames where nothing happened, not for the ones where something did.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        for (int frame = 0; frame < 100; frame++) {
            scheduler.shouldUpdate(frame % 20 == 0, false);
        }

        assertEquals(5L, scheduler.forced());
        assertEquals(95L, scheduler.scheduled() + scheduler.reused());
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has drawn nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        HudUpdateScheduler scheduler = new HudUpdateScheduler();

        assertEquals(0.0, scheduler.reusedPercent());
        assertEquals(0.0, scheduler.savedPercent());
        assertEquals(0.0, scheduler.msSaved());
        assertEquals(0L, scheduler.frames());
        assertEquals(10, HudUpdateScheduler.UPDATE_INTERVAL);
    }
}
