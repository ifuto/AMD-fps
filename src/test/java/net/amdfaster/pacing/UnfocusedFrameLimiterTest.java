package net.amdfaster.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnfocusedFrameLimiterTest {

    private static final int SETTING = 200;

    @Test
    void aFocusedGameUsesThePlayersOwnSetting() {
        // The player's own choice. Scaling it down would make the background rate depend on a setting
        // chosen for the foreground.
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        assertEquals(SETTING, limiter.limit(SETTING));
        assertTrue(limiter.isFocused(), "focused by default");
        assertEquals(1L, limiter.framesFocused());
    }

    @Test
    void anUnfocusedGameDropsToTheFloor() {
        // A game running in the background renders at its full frame rate for nobody, competing for the GPU
        // with whatever the player switched to.
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        limiter.setFocused(false);

        assertEquals(UnfocusedFrameLimiter.UNFOCUSED_FPS, limiter.limit(SETTING));
        assertEquals(1L, limiter.framesUnfocused());
    }

    @Test
    void theFloorIsNotZero() {
        // Stopping rendering entirely would show a frozen image, and a player glancing at a second monitor
        // would see a game that looks crashed. A few frames a second keeps it visibly alive.
        assertTrue(UnfocusedFrameLimiter.UNFOCUSED_FPS > 0);
        assertEquals(5, UnfocusedFrameLimiter.UNFOCUSED_FPS);
    }

    @Test
    void focusReturningRestoresTheRateOnThatFrame() {
        // A player alt-tabbing back expects the game to respond when they look at it, and a frame rate that
        // stayed low for a moment would show a frozen frame that then unfreezes.
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        limiter.setFocused(false);
        assertEquals(UnfocusedFrameLimiter.UNFOCUSED_FPS, limiter.limit(SETTING));

        limiter.setFocused(true);
        assertEquals(SETTING, limiter.limit(SETTING), "immediately, not a tick later");
        assertEquals(2L, limiter.focusChanges());
    }

    @Test
    void settingTheSameFocusTwiceIsNotAChange() {
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        limiter.setFocused(true);

        assertEquals(0L, limiter.focusChanges());
    }

    @Test
    void aLowSettingIsNotScaledDownFurther() {
        // A player who has capped the game at thirty has already chosen how much it costs, and the
        // unfocused floor is its own number rather than a fraction of theirs.
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();
        limiter.setFocused(false);

        assertEquals(UnfocusedFrameLimiter.UNFOCUSED_FPS, limiter.limit(30));
        assertEquals(UnfocusedFrameLimiter.UNFOCUSED_FPS, limiter.limit(SETTING));
    }

    @Test
    void anIdleBackgroundSavesAlmostAllOfTheRendering() {
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        limiter.setFocused(false);
        for (int frame = 0; frame < 1000; frame++) {
            limiter.limit(SETTING);
        }

        assertEquals(195 * UnfocusedFrameLimiter.MS_PER_FRAME,
                limiter.msSavedPerSecond(SETTING), 1e-9);
        assertEquals(975.0, limiter.msSavedPerSecond(SETTING), 1e-9);
        assertEquals(97.5, limiter.savedPercent(SETTING), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        assertThrows(IllegalArgumentException.class, () -> limiter.limit(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.limit(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.msSavedPerSecond(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.savedPercent(0));
    }

    @Test
    void aFreshLimiterReportsZeroNotNaN() {
        // A limiter that has rendered nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        UnfocusedFrameLimiter limiter = new UnfocusedFrameLimiter();

        assertEquals(0.0, limiter.savedPercent(SETTING));
        assertEquals(0L, limiter.framesFocused());
        assertEquals(0L, limiter.framesUnfocused());
        assertEquals(0L, limiter.focusChanges());
        assertTrue(limiter.isFocused());
    }
}
