package net.amdfaster.boot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupPriorityControllerTest {

    @Test
    void aThreadIsRaisedDuringStartup() {
        // Startup deliberately runs more work at once than the machine can handle, so the scheduler
        // divides time evenly and every thread makes progress slowly.
        StartupPriorityController controller = new StartupPriorityController();
        Thread thread = Thread.currentThread();
        int original = thread.getPriority();

        try {
            assertEquals(original, controller.raise(thread));
            assertEquals(StartupPriorityController.STARTUP_PRIORITY, thread.getPriority());
            assertTrue(controller.isActive());
            assertEquals(1, controller.raisedCount());
        } finally {
            thread.setPriority(original);
        }
    }

    @Test
    void thePriorityGoesBackAfterwards() {
        // A process that leaves its threads boosted after startup keeps taking more than its share of the
        // machine for as long as it runs, which the player experiences as everything else becoming
        // sluggish.
        StartupPriorityController controller = new StartupPriorityController();
        Thread thread = Thread.currentThread();
        int original = thread.getPriority();

        controller.raise(thread);
        thread.setPriority(original);
        assertEquals(1, controller.restore());

        assertFalse(controller.isActive());
        assertEquals(0, controller.raisedCount());
    }

    @Test
    void eachThreadsOriginalPriorityIsRememberedNotAFixedValue() {
        // Threads do not all start at the same priority. Restoring to a fixed value would quietly change
        // the ones that were different, so a deliberately low-priority background thread would come back
        // competing for time it was never meant to take.
        StartupPriorityController controller = new StartupPriorityController();
        Thread low = new Thread("background");
        low.setPriority(Thread.MIN_PRIORITY);
        Thread normal = new Thread("worker");

        assertEquals(Thread.MIN_PRIORITY, controller.raise(low));
        assertEquals(Thread.NORM_PRIORITY, controller.raise(normal));

        assertEquals(List.of("background", "worker"), controller.raisedNames());
    }

    @Test
    void restoringTwiceIsHarmless() {
        // Shutdown paths are not always orderly, and a restore that throws on the second call would turn a
        // clean exit into an error at the worst moment.
        StartupPriorityController controller = new StartupPriorityController();
        Thread thread = Thread.currentThread();
        int original = thread.getPriority();

        controller.raise(thread);
        thread.setPriority(original);

        assertEquals(1, controller.restore());
        assertEquals(0, controller.restore(), "nothing left to restore, and no complaint");
        assertEquals(2L, controller.restores());
    }

    @Test
    void restoreAppliesTheRecordedPriorities() {
        StartupPriorityController controller = new StartupPriorityController();
        Thread thread = new Thread("restore-me");
        int original = thread.getPriority();
        controller.raise(thread);
        assertEquals(StartupPriorityController.STARTUP_PRIORITY, thread.getPriority());

        int restored = controller.restore(List.of(thread), Map.of("restore-me", original));

        assertEquals(1, restored);
        assertEquals(original, thread.getPriority());
    }

    @Test
    void theBoostOnlyHelpsWhenThreadsCompete() {
        // On a machine with more cores than threads, nothing is waiting and the boost changes nothing.
        StartupPriorityController controller = new StartupPriorityController();

        assertEquals(8 * StartupPriorityController.MS_PER_THREAD, controller.msSaved(24, 16), 1e-9);
        assertEquals(0.0, controller.msSaved(8, 16), 1e-9, "more cores than threads");
    }

    @Test
    void raisingNothingIsRejected() {
        StartupPriorityController controller = new StartupPriorityController();

        assertThrows(IllegalArgumentException.class, () -> controller.raise(null));
    }

    @Test
    void aFreshControllerReportsZeroNotNaN() {
        StartupPriorityController controller = new StartupPriorityController();

        assertEquals(0, controller.raisedCount());
        assertEquals(0L, controller.restores());
        assertFalse(controller.isActive());
        assertEquals(0, controller.restore(), "nothing was raised");
    }
}
