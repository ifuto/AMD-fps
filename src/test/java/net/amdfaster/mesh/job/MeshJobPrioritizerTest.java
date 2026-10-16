package net.amdfaster.mesh.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshJobPrioritizerTest {

    @Test
    void aVisibleSectionOutranksAnInvisibleOne() {
        // If jobs are built in the order they were queued, the ones directly in front of the player are
        // behind the ones at the edge of the render distance, and the holes close from the outside in.
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertTrue(prioritizer.priority(true, 0) > prioritizer.priority(false, 0));
        assertEquals(1L, prioritizer.chosenVisible());
        assertEquals(1L, prioritizer.chosenInvisible());
    }

    @Test
    void anInvisibleSectionIsStillBuilt() {
        // A section outside the current view is one turn of the head away from being in it, and a queue
        // that dropped them would trade a slow fill for a hole that never fills.
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertTrue(prioritizer.priority(false, 0) > 0, "queued, not dropped");
    }

    @Test
    void anInvisibleJobRisesTheLongerItWaits() {
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        int fresh = prioritizer.priority(false, 1);
        int waited = prioritizer.priority(false, 20);

        assertTrue(waited > fresh, "approaching a visible one rather than sitting at the bottom");
        assertEquals(10, fresh);
        assertEquals(200, waited);
    }

    @Test
    void anInvisibleJobIsPromotedAfterWaitingTooLong() {
        // A player standing still and looking at loaded terrain keeps queueing visible work, and if the
        // invisible jobs always lost they would wait indefinitely -- a queue that looks correct in a test
        // and starves in a real session.
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertEquals(1000, prioritizer.priority(false, MeshJobPrioritizer.STARVATION_LIMIT),
                "promoted to match a visible one");
        assertTrue(prioritizer.isStarved(MeshJobPrioritizer.STARVATION_LIMIT));
        assertFalse(prioritizer.isStarved(MeshJobPrioritizer.STARVATION_LIMIT - 1));
        assertEquals(1L, prioritizer.starved());
    }

    @Test
    void anInvisiblePriorityNeverExceedsAVisibleOneBeforeTheLimit() {
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        int highest = prioritizer.priority(false, MeshJobPrioritizer.STARVATION_LIMIT - 1);

        assertTrue(highest < 1000, "capped below a visible section");
        assertEquals(390, highest);
    }

    @Test
    void prioritisationCutsTheWaitForTheSectionsInFront() {
        // Walking into new terrain queues a hundred sections at a time, and the player sees holes where the
        // ground should be.
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertEquals(20 * MeshJobPrioritizer.MS_PER_SECTION,
                prioritizer.msBeforeVisibleBuilt(20, 100, true), 1e-9);
        assertEquals(60 * MeshJobPrioritizer.MS_PER_SECTION,
                prioritizer.msBeforeVisibleBuilt(20, 100, false), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertThrows(IllegalArgumentException.class, () -> prioritizer.priority(false, -1));
        assertThrows(IllegalArgumentException.class,
                () -> prioritizer.msBeforeVisibleBuilt(50, 20, true));
        assertThrows(IllegalArgumentException.class,
                () -> prioritizer.msBeforeVisibleBuilt(-1, 20, true));
    }

    @Test
    void aFreshPrioritizerReportsZeroNotNaN() {
        // A prioritizer that has been offered nothing has technically ordered everything, but reporting
        // that as a rate would describe an unused one as though it had a result.
        MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

        assertEquals(0.0, prioritizer.starvedPercent());
        assertEquals(0L, prioritizer.offered());
        assertEquals(0L, prioritizer.starved());
        assertEquals(0L, prioritizer.chosenVisible());
        assertEquals(40, MeshJobPrioritizer.STARVATION_LIMIT);
    }
}
