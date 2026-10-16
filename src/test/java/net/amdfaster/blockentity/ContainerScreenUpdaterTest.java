package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerScreenUpdaterTest {

    @Test
    void anIdleChestRefreshesEveryThirdTick() {
        // A comparison and a packet each tick for a display that changes when an item moves -- which, in a
        // chest nobody is touching, is never.
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        assertFalse(updater.shouldRefresh(true, false));
        assertFalse(updater.shouldRefresh(true, false));
        assertTrue(updater.shouldRefresh(true, false), "the third tick");

        assertEquals(1L, updater.refreshed());
        assertEquals(3, ContainerScreenUpdater.UPDATE_INTERVAL);
    }

    @Test
    void aChangeRefreshesImmediately() {
        // A player who takes an item expects the slot to empty when they click, not fifty milliseconds later.
        // A display that lags the inventory feels like the click did not register, and the player clicks
        // again -- which in a chest with a full stack takes two items.
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        assertTrue(updater.shouldRefresh(true, true), "on the first tick, not the third");
        assertEquals(1L, updater.forced());
        assertEquals(1L, updater.refreshed());
    }

    @Test
    void aClosedScreenDoesNoWork() {
        // An open screen is the only reason the refresh happens. Continuing to compare the contents of a
        // chest nobody is looking at would cost exactly what this exists to remove.
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        for (int tick = 0; tick < 10; tick++) {
            assertFalse(updater.shouldRefresh(false, false));
        }

        assertEquals(10L, updater.skippedClosed());
        assertEquals(0L, updater.refreshed());
        assertEquals(0.0, updater.savedPercent(), 1e-9, "and not counted as a saving either");
    }

    @Test
    void anIdleSessionSavesTwoThirds() {
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        for (int tick = 0; tick < 90; tick++) {
            updater.shouldRefresh(true, false);
        }

        assertEquals(90L, updater.ticks());
        assertEquals(30L, updater.refreshed());
        assertEquals(2.4, updater.msRefreshed(), 1e-9);
        assertEquals(7.2, updater.msWithoutScheduling(), 1e-9);
        assertEquals(66.67, updater.savedPercent(), 0.01);
    }

    @Test
    void aSessionWithChangesStillRefreshesPromptly() {
        // The schedule is for the ticks where nothing moved.
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        for (int tick = 1; tick <= 90; tick++) {
            boolean changed = tick == 1 || tick == 2 || tick == 4 || tick == 5 || tick == 7;
            updater.shouldRefresh(true, changed);
        }

        assertEquals(35L, updater.refreshed(), "30 scheduled plus 5 brought forward");
        assertEquals(5L, updater.forced());
        assertEquals(61.11, updater.savedPercent(), 0.01);
    }

    @Test
    void aFreshUpdaterReportsZeroNotNaN() {
        // A scheduler that has run no ticks has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        ContainerScreenUpdater updater = new ContainerScreenUpdater();

        assertEquals(0.0, updater.savedPercent());
        assertEquals(0.0, updater.msRefreshed(), 1e-12);
        assertEquals(0L, updater.ticks());
        assertEquals(0L, updater.refreshed());
        assertEquals(0L, updater.forced());
        assertEquals(0.08, ContainerScreenUpdater.MS_PER_REFRESH, 1e-12);
    }
}
