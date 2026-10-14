package net.amdfaster.runtime;

import net.amdfaster.runtime.GuiUpdateThrottle.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiUpdateThrottleTest {

    private static final int HOTBAR = 0;
    private static final int FPS = 1;
    private static final int CLOCK = 2;
    private static final int CROSSHAIR = 3;

    private static GuiUpdateThrottle hud() {
        GuiUpdateThrottle throttle = new GuiUpdateThrottle(8);
        throttle.configure(HOTBAR, Kind.CONTENT_ONLY, 0);
        throttle.configure(FPS, Kind.NUMERIC_READOUT, GuiUpdateThrottle.COUNTER_INTERVAL_MS);
        throttle.configure(CLOCK, Kind.NUMERIC_READOUT, GuiUpdateThrottle.CLOCK_INTERVAL_MS);
        throttle.configure(CROSSHAIR, Kind.TRACKS_POSITION, 0);
        return throttle;
    }

    @Test
    void everyLayerDrawsOnTheFirstFrame() {
        // The never-drawn case has to be checked before the interval subtraction, because
        // now - Long.MIN_VALUE overflows to a large negative number and reads as "not enough time has
        // passed", which would leave the layer undrawn forever.
        GuiUpdateThrottle throttle = hud();

        for (int layer = 0; layer < 4; layer++) {
            assertTrue(throttle.shouldRedraw(layer, 42, 0L), "layer " + layer + " draws at t = 0");
        }
        assertEquals(4L, throttle.redraws());
        assertEquals(0L, throttle.skippedTooSoon());
    }

    @Test
    void unchangedContentIsNotRedrawn() {
        GuiUpdateThrottle throttle = hud();
        throttle.shouldRedraw(HOTBAR, 1234, 0L);

        for (long t = 16; t < 10_000; t += 16) {
            assertFalse(throttle.shouldRedraw(HOTBAR, 1234, t), "same content at t = " + t);
        }
        assertEquals(624L, throttle.skippedUnchanged());
        assertEquals(1L, throttle.redraws());
    }

    @Test
    void changedContentIsRedrawnImmediately() {
        // The hotbar follows the player's inventory, and a delay there is visible.
        GuiUpdateThrottle throttle = hud();
        assertTrue(throttle.shouldRedraw(HOTBAR, 1, 0L));

        assertTrue(throttle.shouldRedraw(HOTBAR, 2, 16L), "one frame later");
        assertTrue(throttle.shouldRedraw(HOTBAR, 3, 32L), "and the next");
        assertEquals(3L, throttle.redraws());
    }

    @Test
    void aCounterUpdatesAtFourHertzAndNotSixty() {
        // 250 ms. A number updating at 4 Hz is still legible as a number; below that it stutters.
        GuiUpdateThrottle throttle = hud();
        throttle.shouldRedraw(FPS, 1, 0L);

        int redraws = 0;
        for (long t = 16; t <= 1000; t += 16) {
            // The FPS value changes almost every frame, so content detection will not catch it.
            if (throttle.shouldRedraw(FPS, (int) t, t)) {
                redraws++;
            }
        }
        // Three, because the first draw was at t = 0 and the window is 1,000 ms: 256, 512 and 768.
        assertEquals(3, redraws, "three updates in a second rather than sixty");
        assertEquals(62L - 3, throttle.skippedTooSoon(), "every other check was too soon");
    }

    @Test
    void aClockUpdatesOnceASecond() {
        GuiUpdateThrottle throttle = hud();
        throttle.shouldRedraw(CLOCK, 1, 0L);

        int redraws = 0;
        for (long t = 16; t <= 10_000; t += 16) {
            if (throttle.shouldRedraw(CLOCK, (int) t, t)) {
                redraws++;
            }
        }
        // Nine, for the same reason: the first draw was at t = 0.
        assertEquals(9, redraws, "ten seconds, nine updates after the first");
    }

    @Test
    void aPositionTrackingLayerIsNeverThrottled() {
        // Delaying the crosshair or the hotbar's selection outline reads as input lag rather than as a
        // slow number, so this is refused even when the caller asks for it.
        GuiUpdateThrottle throttle = hud();

        assertFalse(throttle.tryThrottle(CROSSHAIR, 100));
        assertEquals(0L, throttle.intervalOf(CROSSHAIR));
        assertEquals(1L, throttle.throttleRefused());

        for (long t = 0; t < 1000; t += 16) {
            assertTrue(throttle.shouldRedraw(CROSSHAIR, (int) t, t), "drawn every frame at t = " + t);
        }
        assertEquals(0L, throttle.skippedTooSoon());
    }

    @Test
    void unchangedContentStillSkipsEvenForATrackedLayer() {
        // Not throttling a layer does not mean drawing it when nothing changed. The crosshair is static
        // most of the time and the content check catches that.
        GuiUpdateThrottle throttle = hud();
        assertTrue(throttle.shouldRedraw(CROSSHAIR, 7, 0L));

        assertFalse(throttle.shouldRedraw(CROSSHAIR, 7, 16L));
        assertFalse(throttle.shouldRedraw(CROSSHAIR, 7, 32L));
        assertEquals(2L, throttle.skippedUnchanged());
    }

    @Test
    void invalidatingForcesARedrawEvenWithIdenticalContent() {
        // For a resize or a scale change: the content is still right but the pixels it was drawn into
        // are not.
        GuiUpdateThrottle throttle = hud();
        throttle.shouldRedraw(HOTBAR, 99, 0L);
        assertFalse(throttle.shouldRedraw(HOTBAR, 99, 16L));

        throttle.invalidate(HOTBAR);

        assertTrue(throttle.shouldRedraw(HOTBAR, 99, 32L), "same content, redrawn anyway");
    }

    @Test
    void invalidateAllCoversEveryLayer() {
        GuiUpdateThrottle throttle = hud();
        for (int i = 0; i < 4; i++) {
            throttle.shouldRedraw(i, 5, 0L);
        }
        throttle.invalidateAll();

        for (int i = 0; i < 4; i++) {
            assertTrue(throttle.shouldRedraw(i, 5, 16L), "layer " + i);
        }
    }

    @Test
    void aTypicalHudSkipsAlmostEverything() {
        // The figure the class is for: eight layers at 60 fps, one of which changes.
        GuiUpdateThrottle throttle = new GuiUpdateThrottle(8);
        throttle.configure(0, Kind.CONTENT_ONLY, 0);
        throttle.configure(1, Kind.NUMERIC_READOUT, GuiUpdateThrottle.COUNTER_INTERVAL_MS);
        throttle.configure(2, Kind.NUMERIC_READOUT, GuiUpdateThrottle.CLOCK_INTERVAL_MS);
        throttle.configure(3, Kind.NUMERIC_READOUT, GuiUpdateThrottle.READOUT_INTERVAL_MS);
        for (int i = 4; i < 8; i++) {
            throttle.configure(i, Kind.CONTENT_ONLY, 0);
        }

        for (long t = 0; t <= 1000; t += 16) {
            for (int layer = 0; layer < 8; layer++) {
                // Only layer 0 changes, and only at t = 500.
                int hash = layer == 0 && t >= 496 ? 2 : 1;
                throttle.shouldRedraw(layer, hash, t);
            }
        }
        assertEquals(8L * 63, throttle.checks());
        assertTrue(throttle.skippedPercent() > 95.0,
                "almost nothing is redrawn, got " + throttle.skippedPercent());
        assertTrue(throttle.redraws() < 100, "and the redraws that happen are few: "
                + throttle.redraws());
    }

    @Test
    void intervalsAreTheOnesDocumented() {
        // Each is a claim about what can be read, and each would be visibly wrong if it were more
        // aggressive, so they are pinned here.
        assertEquals(250L, GuiUpdateThrottle.COUNTER_INTERVAL_MS);
        assertEquals(1000L, GuiUpdateThrottle.CLOCK_INTERVAL_MS);
        assertEquals(100L, GuiUpdateThrottle.READOUT_INTERVAL_MS);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GuiUpdateThrottle(0));

        GuiUpdateThrottle throttle = hud();
        assertThrows(IllegalArgumentException.class,
                () -> throttle.configure(0, Kind.NUMERIC_READOUT, -1));
        assertThrows(IllegalArgumentException.class, () -> throttle.tryThrottle(0, -5));
        assertThrows(IndexOutOfBoundsException.class, () -> throttle.invalidate(8));
        assertThrows(IndexOutOfBoundsException.class, () -> throttle.kindOf(-1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> throttle.shouldRedraw(8, 0, 0L));
    }

    @Test
    void aFreshThrottleReportsZeroNotNaN() {
        GuiUpdateThrottle throttle = new GuiUpdateThrottle();
        assertEquals(0.0, throttle.skippedPercent());
        assertEquals(0L, throttle.checks());
        assertEquals(Kind.CONTENT_ONLY, throttle.kindOf(0), "the default is the safe one");
    }
}
