package net.amdfaster.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveQualityTest {

    private static void fill(AdaptiveQuality controller, double frameMs) {
        for (int i = 0; i < AdaptiveQuality.WINDOW; i++) {
            controller.record(frameMs);
        }
    }

    @Test
    void aSustainedSlowFrameTimeDropsTheDistance() {
        // A village or a TNT chain pushes frame times past the target, and the distance that was fine
        // over open plains is suddenly too much.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);
        fill(controller, 25.0);

        assertEquals(11, controller.distance(), "one chunk, not a plunge");
        assertEquals(1L, controller.drops());
    }

    @Test
    void aSustainedFastFrameTimeRaisesItBack() {
        AdaptiveQuality controller = new AdaptiveQuality(8, 16);
        fill(controller, 10.0);

        assertEquals(9, controller.distance());
        assertEquals(1L, controller.raises());
    }

    @Test
    void theDeadBandStopsItHunting() {
        // A single threshold hunts: dropping the distance makes frames faster, which looks like
        // headroom, which raises it again. The player watches the world breathing in and out.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);

        assertTrue(controller.isStable(17.0, 200), "17 ms sits in the dead band");
        assertEquals(0L, controller.drops());
        assertEquals(0L, controller.raises());
    }

    @Test
    void theDeadBandBoundariesAreInclusive() {
        assertTrue(AdaptiveQuality.inDeadBand(AdaptiveQuality.RAISE_BELOW_MS));
        assertTrue(AdaptiveQuality.inDeadBand(AdaptiveQuality.DROP_ABOVE_MS));
        assertFalse(AdaptiveQuality.inDeadBand(13.9));
        assertFalse(AdaptiveQuality.inDeadBand(20.1));
        assertTrue(AdaptiveQuality.RAISE_BELOW_MS < AdaptiveQuality.DROP_ABOVE_MS,
                "the raise threshold must be below the drop one or there is no band");
    }

    @Test
    void oneBadFrameDoesNotMoveAnything() {
        // A single long frame is a garbage collection, a chunk loading, or the player opening a menu.
        // Reacting to it shrinks the world for something that was never a sustained load.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);
        fill(controller, 10.0);

        int before = controller.distance();
        controller.record(500.0);

        assertEquals(before, controller.distance(), "the average barely moved");
    }

    @Test
    void theWindowHasToFillBeforeDeciding() {
        // The first frame of a session is usually a chunk loading.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);

        for (int i = 0; i < AdaptiveQuality.WINDOW - 1; i++) {
            controller.record(50.0);
        }

        assertEquals(12, controller.distance(), "not enough frames to mean anything");
    }

    @Test
    void theCooldownLetsAChangeShowUpInTheAverage() {
        // Without it, a change would trigger another change before the first had any effect on the
        // measurement, and the distance would slide all the way down.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);
        fill(controller, 25.0);

        assertEquals(11, controller.distance());
        for (int i = 0; i < AdaptiveQuality.COOLDOWN; i++) {
            controller.record(25.0);
        }

        assertEquals(11, controller.distance(), "still cooling down");
        assertEquals(1L, controller.drops());
    }

    @Test
    void itKeepsDroppingWhileTheLoadPersists() {
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);

        for (int i = 0; i < 10 * (AdaptiveQuality.WINDOW + AdaptiveQuality.COOLDOWN); i++) {
            controller.record(30.0);
        }

        assertTrue(controller.distance() < 12, "it gave ground");
        assertTrue(controller.distance() >= AdaptiveQuality.MIN_DISTANCE);
    }

    @Test
    void itNeverGoesBelowTheFloor() {
        // At distance two nothing is visible, which is worse than the stutter it was meant to fix.
        AdaptiveQuality controller = new AdaptiveQuality(AdaptiveQuality.MIN_DISTANCE, 16);

        for (int i = 0; i < 5000; i++) {
            controller.record(100.0);
        }

        assertEquals(AdaptiveQuality.MIN_DISTANCE, controller.distance());
        assertEquals(4, AdaptiveQuality.MIN_DISTANCE);
    }

    @Test
    void itNeverExceedsThePlayerSetting() {
        // The maximum is the player's own choice, not a suggestion.
        AdaptiveQuality controller = new AdaptiveQuality(16, 16);

        for (int i = 0; i < 5000; i++) {
            controller.record(1.0);
        }

        assertEquals(16, controller.distance());
    }

    @Test
    void theSavingScalesWithTheSquareOfTheDistance() {
        // Sections scale with distance squared, so dropping from 12 to 10 is not a sixth less work but
        // nearly a third.
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);

        assertEquals(144.0 / 256.0, controller.loadFraction(), 1e-9);
        assertEquals(43.75, controller.savedPercent(), 0.001);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveQuality(8, 3),
                "a maximum below the floor");
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveQuality(2, 16),
                "a start below the floor");
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveQuality(20, 16),
                "a start above the maximum");

        AdaptiveQuality controller = new AdaptiveQuality(8, 16);
        assertThrows(IllegalArgumentException.class, () -> controller.record(0.0));
        assertThrows(IllegalArgumentException.class, () -> controller.record(-1.0));
        assertThrows(IllegalArgumentException.class, () -> controller.record(Double.NaN));
    }

    @Test
    void aFreshControllerReportsZeroNotNaN() {
        AdaptiveQuality controller = new AdaptiveQuality(12, 16);

        assertEquals(0.0, controller.average());
        assertEquals(12, controller.distance());
        assertEquals(0L, controller.drops());
        assertEquals(0L, controller.raises());
    }
}
