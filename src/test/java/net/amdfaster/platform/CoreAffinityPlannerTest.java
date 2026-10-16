package net.amdfaster.platform;

import net.amdfaster.platform.CoreAffinityPlanner.CoreKind;
import net.amdfaster.platform.CoreAffinityPlanner.ThreadRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAffinityPlannerTest {

    @Test
    void theRenderThreadStaysOnTheFastCores() {
        // If the render thread is moved to an efficient core partway through a frame, that frame takes
        // longer, and the player sees it as a hitch that happens every so often for no reason they can find.
        assertEquals(CoreKind.PERFORMANCE, CoreAffinityPlanner.placement(ThreadRole.RENDER, true, 8, 8));
        assertEquals(CoreKind.PERFORMANCE, CoreAffinityPlanner.placement(ThreadRole.SERVER, true, 8, 8));
    }

    @Test
    void theWorkThatDoesNotCareGoesOnTheEfficientCores() {
        // Meshing a chunk is not on the critical path of any single frame, so it belongs on the efficient
        // cores, and keeping it off the fast ones is most of the benefit.
        assertEquals(CoreKind.EFFICIENT, CoreAffinityPlanner.placement(ThreadRole.MESHING, true, 8, 8));
        assertEquals(CoreKind.EFFICIENT, CoreAffinityPlanner.placement(ThreadRole.UPLOAD, true, 8, 8));
        assertEquals(CoreKind.EFFICIENT, CoreAffinityPlanner.placement(ThreadRole.SOUND, true, 8, 8));
        assertEquals(CoreKind.EFFICIENT, CoreAffinityPlanner.placement(ThreadRole.OTHER, true, 8, 8));
    }

    @Test
    void nothingIsPinnedOnAProcessorWithOneKindOfCore() {
        // Setting an affinity there would restrict the thread to a subset of identical cores for no reason,
        // which on a small part could make things worse by preventing the scheduler from balancing at all.
        assertNull(CoreAffinityPlanner.placement(ThreadRole.RENDER, false, 8, 0));
        assertNull(CoreAffinityPlanner.placement(ThreadRole.MESHING, false, 4, 0));
        assertTrue(CoreAffinityPlanner.priorityAloneSuffices(false));
    }

    @Test
    void priorityIsNotPlacement() {
        // Priority makes the scheduler prefer a thread but does not decide which kind of core it runs on, so
        // a thread can be the highest priority thing running and still be on a slow core.
        assertFalse(CoreAffinityPlanner.priorityAloneSuffices(true));
    }

    @Test
    void aHybridWithNoEfficientCoresFallsBack() {
        assertEquals(CoreKind.PERFORMANCE, CoreAffinityPlanner.placement(ThreadRole.MESHING, true, 8, 0));
    }

    @Test
    void onlyTheFrameCriticalThreadsArePinned() {
        // Pinning everything to the fast cores would put more threads there than there are cores, and they
        // would contend with each other for the very resource the pinning was protecting.
        assertEquals(2, CoreAffinityPlanner.maxPinnedToPerformance(8));
        assertEquals(2, CoreAffinityPlanner.maxPinnedToPerformance(2));
        assertEquals(1, CoreAffinityPlanner.maxPinnedToPerformance(1), "capped by the cores");
    }

    @Test
    void theShareOfTheFastCoresIsReported() {
        // On a two-performance-core part the frame-critical threads take all of them.
        assertEquals(0.25, CoreAffinityPlanner.performanceCoreShare(8), 1e-12);
        assertEquals(1.0, CoreAffinityPlanner.performanceCoreShare(2), 1e-12);
        assertEquals(1.0, CoreAffinityPlanner.performanceCoreShare(1), 1e-12);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreAffinityPlanner.placement(null, true, 8, 8));
        assertThrows(IllegalArgumentException.class,
                () -> CoreAffinityPlanner.placement(ThreadRole.RENDER, true, -1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> CoreAffinityPlanner.placement(ThreadRole.RENDER, true, 8, -1));
        assertThrows(IllegalArgumentException.class,
                () -> CoreAffinityPlanner.maxPinnedToPerformance(0));
        assertThrows(IllegalArgumentException.class,
                () -> CoreAffinityPlanner.performanceCoreShare(0));
    }
}
