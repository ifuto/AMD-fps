package net.amdfaster.pacing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDistanceControllerTest {

    private static final long FPS_60 = 16_666_667L;

    @Test
    void aFrameTwiceTheBudgetStepsTheDistanceDownTwoAtATime() {
        // From 16 the controller reaches the floor in six adjustments, one every 30 frames. The step
        // limit is what makes this a horizon that closes rather than a hole that opens.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);

        for (int frame = 0; frame < 600; frame++) {
            controller.onFrame(2 * FPS_60);
        }

        assertEquals(RenderDistanceController.MIN_DISTANCE, controller.distance());
        assertEquals(6, controller.adjustments(), "16, 14, 12, 10, 8, 6, 4");
    }

    @Test
    void aFrameHalfTheBudgetStepsTheDistanceUp() {
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 8);

        for (int frame = 0; frame < 600; frame++) {
            controller.onFrame(FPS_60 / 2);
        }

        assertEquals(RenderDistanceController.MAX_DISTANCE, controller.distance());
        assertEquals(12, controller.adjustments(), "8 to 32 in steps of 2");
    }

    @Test
    void theCorrectionIsASquareRootNotAProportion() {
        // Chunks grow as the square of the distance, so a frame at four times the budget needs the
        // distance halved, not quartered. The step limit means a single frame never applies that much,
        // but the direction and the magnitude of the proposal have to follow the square root or the
        // controller over-corrects and pumps.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);

        controller.onFrame(4 * FPS_60);
        assertEquals(16, controller.distance(), "still inside the settling window");

        for (int i = 0; i < RenderDistanceController.SETTLE_FRAMES; i++) {
            controller.onFrame(4 * FPS_60);
        }
        assertEquals(14, controller.distance(),
                "one step of two, not the eight the proportional rule would have wanted");
    }

    @Test
    void nothingChangesInsideTheHysteresisBand() {
        // A controller that corrects noise instead of trend oscillates, and the oscillation is visible as
        // chunks appearing and disappearing at the horizon.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);

        for (int frame = 0; frame < 399; frame++) {
            controller.onFrame((long) (FPS_60 * 1.05));
        }

        assertEquals(16, controller.distance());
        assertEquals(0, controller.adjustments());
        assertTrue(controller.suppressedByHysteresis() > 0, "and the near misses are counted");
    }

    @Test
    void outsideTheBandItDoesAct() {
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);

        for (int frame = 0; frame < 399; frame++) {
            controller.onFrame((long) (FPS_60 * 1.20));
        }

        assertTrue(controller.distance() < 16);
        assertEquals(0, controller.suppressedByHysteresis());
        assertTrue(controller.adjustments() > 0);
    }

    @Test
    void adjustmentsAreSpacedOut() {
        // The effect of a distance change is not measurable until the chunks at the new distance have
        // loaded, which takes longer than one frame. Reacting before then corrects for a measurement that
        // does not yet reflect the change.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);

        // The counter is incremented before the test, so the 30th call sees framesSinceChange == 30 and
        // is no longer inside the window. Twenty-nine calls are suppressed and the thirtieth acts.
        for (int frame = 0; frame < RenderDistanceController.SETTLE_FRAMES - 1; frame++) {
            controller.onFrame(10 * FPS_60);
            assertEquals(16, controller.distance(), "frame " + frame);
        }
        assertEquals(RenderDistanceController.SETTLE_FRAMES - 1, controller.suppressedBySettling());

        controller.onFrame(10 * FPS_60);
        assertEquals(14, controller.distance(), "and it acts on the first frame after settling");

        controller.onFrame(10 * FPS_60);
        assertEquals(14, controller.distance(), "then the window starts again");
    }

    @Test
    void absurdFrameTimesAreIgnored() {
        // A negative time means the clock stepped backwards; a minute-long frame means the window was
        // dragged or the game was paused. Neither says anything about what the scene costs, and reacting
        // to either would drop the distance for no reason.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);
        for (int i = 0; i < 200; i++) {
            controller.onFrame(10 * FPS_60);
        }
        assertTrue(controller.distance() < 16, "the controller is working");

        int before = controller.distance();
        controller.onFrame(0L);
        controller.onFrame(-1L);
        controller.onFrame(61_000_000_000L);

        assertEquals(before, controller.distance());
    }

    @Test
    void theDistanceIsBoundedEvenUnderSustainedPressure() {
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);
        for (int frame = 0; frame < 5000; frame++) {
            int distance = controller.onFrame(100 * FPS_60);
            assertTrue(distance >= RenderDistanceController.MIN_DISTANCE, "never below the floor");
            assertTrue(distance <= RenderDistanceController.MAX_DISTANCE, "never above the ceiling");
        }
        assertTrue(controller.clamped() > 0, "and hitting the bound is reported");
    }

    @Test
    void theInitialDistanceIsClampedIntoRange() {
        assertEquals(RenderDistanceController.MIN_DISTANCE,
                new RenderDistanceController(FPS_60, 0).distance());
        assertEquals(RenderDistanceController.MIN_DISTANCE,
                new RenderDistanceController(FPS_60, 2).distance());
        assertEquals(16, new RenderDistanceController(FPS_60, 16).distance());
        assertEquals(RenderDistanceController.MAX_DISTANCE,
                new RenderDistanceController(FPS_60, 40).distance());
    }

    @Test
    void settingTheDistanceManuallyRestartsTheSettlingWindow() {
        // A player changing the distance by hand is not a measurement, and the controller must not undo
        // it on the next frame.
        RenderDistanceController controller = new RenderDistanceController(FPS_60, 16);
        for (int i = 0; i < 200; i++) {
            controller.onFrame(FPS_60);
        }

        controller.setDistance(20);

        assertEquals(20, controller.distance());
        controller.onFrame(FPS_60);
        assertEquals(20, controller.distance(), "a frame on budget does not move it");
    }

    @Test
    void nonsenseConstructionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RenderDistanceController(0L, 16));
        assertThrows(IllegalArgumentException.class, () -> new RenderDistanceController(-1L, 16));
        assertThrows(IllegalArgumentException.class, () -> new RenderDistanceController(FPS_60, 16, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> new RenderDistanceController(FPS_60, 16, 32, 4));
    }

    @Test
    void theConstantsAreTheOnesTheDesignDependsOn() {
        assertEquals(4, RenderDistanceController.MIN_DISTANCE);
        assertEquals(32, RenderDistanceController.MAX_DISTANCE);
        assertEquals(2, RenderDistanceController.MAX_STEP);
        assertEquals(30, RenderDistanceController.SETTLE_FRAMES);
        assertEquals(0.10, RenderDistanceController.HYSTERESIS, 1e-9);
    }
}
