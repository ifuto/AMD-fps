package net.amdfaster.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicLoadControllerTest {

    @Test
    void aComfortableTickChangesNothing() {
        DynamicLoadController controller = new DynamicLoadController();

        controller.adjust(20.0);

        assertEquals(DynamicLoadController.MAX_MOB_CAP, controller.mobCap());
        assertEquals(0L, controller.reductions());
        assertEquals(0L, controller.restorations());
    }

    @Test
    void aLateTickReducesTheLoadByOneStep() {
        // Snapping the cap from seventy to ten would make most of the world's mobs vanish at once, which is
        // far more noticeable than the stutter it was meant to fix.
        DynamicLoadController controller = new DynamicLoadController();

        controller.adjust(48.0);

        assertEquals(65, controller.mobCap());
        assertEquals(1L, controller.reductions());
    }

    @Test
    void theReductionHasAFloor() {
        // Reducing the cap to zero would stop mobs spawning entirely, and a farm the player built would
        // simply stop working -- which is not a performance adjustment, it is the game breaking under load.
        DynamicLoadController controller = new DynamicLoadController();

        for (int tick = 0; tick < 20; tick++) {
            controller.adjust(48.0);
        }

        assertEquals(DynamicLoadController.MIN_MOB_CAP, controller.mobCap());
        assertEquals(10L, controller.reductions(), "ten steps of five from seventy to twenty");
        assertEquals(10L, controller.atFloor(), "and ten ticks with nothing left to reduce");
    }

    @Test
    void theGapBetweenReduceAndRestoreStopsOscillation() {
        // Raising the load as soon as the tick improves would put back the work that made it slow, and the
        // controller would reduce, recover, raise, slow, reduce.
        DynamicLoadController controller = new DynamicLoadController();

        controller.adjust(48.0);
        assertEquals(65, controller.mobCap());

        controller.adjust(40.0);
        assertEquals(65, controller.mobCap(), "between the thresholds, so held");
        assertEquals(0L, controller.restorations());

        controller.adjust(30.0);
        assertEquals(70, controller.mobCap(), "comfortable, so restored");
        assertEquals(1L, controller.restorations());
    }

    @Test
    void theLoadComesBackGradually() {
        DynamicLoadController controller = new DynamicLoadController();

        for (int tick = 0; tick < 10; tick++) {
            controller.adjust(48.0);
        }
        assertEquals(DynamicLoadController.MIN_MOB_CAP, controller.mobCap());

        for (int tick = 0; tick < 5; tick++) {
            controller.adjust(30.0);
        }

        assertEquals(45, controller.mobCap(), "five steps of five back up");
    }

    @Test
    void theSimulationDistanceIsAdjustedSeparately() {
        // The two are different kinds of work and a machine may need one rather than the other.
        DynamicLoadController controller = new DynamicLoadController();

        controller.reduceSimulationDistance();
        assertEquals(7, controller.simulationDistance());

        for (int i = 0; i < 10; i++) {
            controller.reduceSimulationDistance();
        }
        assertEquals(4, controller.simulationDistance(), "and it has a floor too");

        controller.restoreSimulationDistance();
        assertEquals(5, controller.simulationDistance());
    }

    @Test
    void theSavingIsWhatTheCapReductionBuys() {
        DynamicLoadController controller = new DynamicLoadController();

        for (int tick = 0; tick < 10; tick++) {
            controller.adjust(48.0);
        }

        assertEquals(12.5, controller.msSaved(), 1e-9, "fifty fewer mobs at a quarter of a millisecond");
        assertEquals(71.43, controller.savedPercent(), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        DynamicLoadController controller = new DynamicLoadController();

        assertThrows(IllegalArgumentException.class, () -> controller.adjust(-1.0));
    }

    @Test
    void aFreshControllerReportsZeroNotNaN() {
        // A controller that has adjusted nothing has saved nothing and says so, rather than reporting the
        // saving it would make if it acted.
        DynamicLoadController controller = new DynamicLoadController();

        assertEquals(0.0, controller.savedPercent());
        assertEquals(0.0, controller.msSaved(), 1e-12);
        assertEquals(0L, controller.ticks());
        assertEquals(0L, controller.reductions());
        assertEquals(0L, controller.atFloor());
        assertEquals(70, DynamicLoadController.MAX_MOB_CAP);
        assertEquals(20, DynamicLoadController.MIN_MOB_CAP);
        assertTrue(DynamicLoadController.RESTORE_BELOW_MS < DynamicLoadController.REDUCE_ABOVE_MS);
    }
}
