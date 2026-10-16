package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxisPassSchedulerTest {

    @Test
    void aSectionIsVisitedThreeTimes() {
        // Three passes, one per axis, each of them coherent, cost less than one incoherent pass that touches
        // everything.
        AxisPassScheduler scheduler = new AxisPassScheduler();

        assertEquals(3 * AxisPassScheduler.SECTION_CELLS, scheduler.runSection(1000));
        assertEquals(3L * AxisPassScheduler.SECTION_CELLS, scheduler.cellsVisited());
        assertEquals(1L, scheduler.sections(), "one section, visited by three passes");
        assertEquals(3L * AxisPassScheduler.SECTION_CELLS, scheduler.hits() + scheduler.misses());
    }

    @Test
    void theCoherentPassesHitTheCache() {
        // A pass that only ever reads the neighbour in X moves through contiguous memory, so the cache line it
        // just loaded is the one it needs next.
        AxisPassScheduler scheduler = new AxisPassScheduler();
        scheduler.runSection(1000);

        assertEquals(93.75, scheduler.hitPercent(), 1e-9, "one miss per sixteen-cell line");
        assertEquals(11_520L, scheduler.hits());
        assertEquals(768L, scheduler.misses());
    }

    @Test
    void theStridesAreTheAxisSeparation() {
        assertEquals(1, AxisPassScheduler.STRIDE_X);
        assertEquals(16, AxisPassScheduler.STRIDE_Y);
        assertEquals(256, AxisPassScheduler.STRIDE_Z);
        assertEquals(3, AxisPassScheduler.AXES);
    }

    @Test
    void threeCoherentPassesCostLessThanOneIncoherentOne() {
        AxisPassScheduler scheduler = new AxisPassScheduler();
        scheduler.runSection(1000);

        assertEquals(1.236992, scheduler.msIncoherentPass(), 1e-9, "six reads, five of them misses");
        assertEquals(0.06912, scheduler.msCoherentPasses(), 1e-9);
        assertTrue(scheduler.msCoherentPasses() < scheduler.msIncoherentPass());
        assertEquals(94.41, scheduler.savedPercent(), 0.01);
    }

    @Test
    void anEmptySectionStillRunsItsPasses() {
        // The passes are over the section, not over the blocks in it -- an empty section is bounded by the
        // sweep, not by this.
        AxisPassScheduler scheduler = new AxisPassScheduler();

        assertEquals(3 * AxisPassScheduler.SECTION_CELLS, scheduler.runSection(0));
        assertEquals(1L, scheduler.sections());
    }

    @Test
    void aFullSectionIsAllowed() {
        AxisPassScheduler scheduler = new AxisPassScheduler();

        assertEquals(3 * AxisPassScheduler.SECTION_CELLS,
                scheduler.runSection(AxisPassScheduler.SECTION_CELLS));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        AxisPassScheduler scheduler = new AxisPassScheduler();

        assertThrows(IllegalArgumentException.class, () -> scheduler.runSection(-1));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.runSection(AxisPassScheduler.SECTION_CELLS + 1));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        // A scheduler that has run no passes has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        AxisPassScheduler scheduler = new AxisPassScheduler();

        assertEquals(0.0, scheduler.savedPercent());
        assertEquals(0.0, scheduler.hitPercent());
        assertEquals(0L, scheduler.sections());
        assertEquals(0L, scheduler.cellsVisited());
        assertEquals(0L, scheduler.hits());
        assertEquals(0L, scheduler.misses());
        assertEquals(4096, AxisPassScheduler.SECTION_CELLS);
    }
}
