package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundPressureControllerTest {

    private static final String MACHINE = "minecraft:block.piston";
    private static final String STEPS = "minecraft:entity.player.step";

    @Test
    void oneSourceCannotTakeTheWholeBudget() {
        // Refusing whichever arrives last means a single machine firing ten times a second takes the whole
        // budget, and the footsteps and the door behind it all go silent.
        SoundPressureController controller = new SoundPressureController();

        for (int i = 0; i < 10; i++) {
            controller.canPlay(MACHINE, false);
        }

        assertEquals(SoundPressureController.PER_SOURCE_LIMIT, controller.heldBy(MACHINE));
        assertEquals(6L, controller.refusedSource());
        assertTrue(controller.canPlay(STEPS, false), "and another source still gets through");
    }

    @Test
    void playerFeedbackBypassesTheBudget() {
        // Taking damage, a creeper hissing, an anvil landing. Those are information the player changes what
        // they are doing because of, and losing one to a budget is worse than losing ten machine noises.
        SoundPressureController controller = new SoundPressureController();

        for (int i = 0; i < SoundPressureController.CHANNEL_BUDGET; i++) {
            controller.canPlay("machine." + i, false);
        }
        assertEquals(SoundPressureController.CHANNEL_BUDGET, controller.inUse());

        assertTrue(controller.canPlay("minecraft:entity.player.hurt", true));
        assertEquals(1L, controller.feedback());
    }

    @Test
    void pressureTightensUnderLoad() {
        // Sound costs mixing time on the same thread that is already struggling, so the game sheds atmosphere
        // rather than ticks.
        SoundPressureController controller = new SoundPressureController();

        controller.adjust(48.0);

        assertEquals(SoundPressureController.CHANNEL_BUDGET - SoundPressureController.PRESSURE_STEP,
                controller.budget());
        assertEquals(1L, controller.pressureChanges());
    }

    @Test
    void pressureHasAFloor() {
        // A budget that shrank to nothing under load would silence the game exactly when the player is
        // fighting something difficult, which is when they most need to hear it.
        SoundPressureController controller = new SoundPressureController();

        for (int tick = 0; tick < 20; tick++) {
            controller.adjust(48.0);
        }

        assertEquals(SoundPressureController.MIN_BUDGET, controller.budget());
    }

    @Test
    void theGapBetweenThresholdsStopsFluttering() {
        SoundPressureController controller = new SoundPressureController();

        controller.adjust(48.0);
        assertEquals(56, controller.budget());

        controller.adjust(40.0);
        assertEquals(56, controller.budget(), "between the thresholds, so held");

        controller.adjust(30.0);
        assertEquals(64, controller.budget(), "comfortable, so eased back");
        assertEquals(2L, controller.pressureChanges());
    }

    @Test
    void releasingAFreesTheChannel() {
        SoundPressureController controller = new SoundPressureController();

        controller.canPlay(MACHINE, false);
        assertEquals(1, controller.inUse());

        controller.release(MACHINE);

        assertEquals(0, controller.inUse());
        assertEquals(0, controller.heldBy(MACHINE));
    }

    @Test
    void releasingMoreThanWasHeldDoesNotGoNegative() {
        SoundPressureController controller = new SoundPressureController();

        controller.release(MACHINE);
        controller.release(MACHINE);

        assertEquals(0, controller.inUse());
        assertEquals(0, controller.heldBy(MACHINE));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SoundPressureController controller = new SoundPressureController();

        assertThrows(IllegalArgumentException.class, () -> controller.adjust(-1.0));
        assertThrows(IllegalArgumentException.class, () -> controller.canPlay(null, false));
        assertThrows(IllegalArgumentException.class, () -> controller.canPlay("", false));
        assertThrows(IllegalArgumentException.class, () -> controller.release(null));
    }

    @Test
    void aFreshControllerReportsZeroNotNaN() {
        // A controller that has heard nothing has technically refused everything, but reporting that as a
        // rate would describe an unused one as though it had decided something.
        SoundPressureController controller = new SoundPressureController();

        assertEquals(0.0, controller.refusedPercent());
        assertEquals(0.0, controller.msMixing(), 1e-12);
        assertEquals(0L, controller.offered());
        assertEquals(0L, controller.played());
        assertEquals(0, controller.inUse());
        assertEquals(64, controller.budget());
        assertEquals(4, SoundPressureController.PER_SOURCE_LIMIT);
        assertEquals(24, SoundPressureController.MIN_BUDGET);
    }
}
