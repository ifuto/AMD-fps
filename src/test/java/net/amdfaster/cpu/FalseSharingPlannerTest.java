package net.amdfaster.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.cpu.FalseSharingPlanner.Counter;
import net.amdfaster.cpu.FalseSharingPlanner.Decision;
import net.amdfaster.cpu.FalseSharingPlanner.Layout;
import org.junit.jupiter.api.Test;

class FalseSharingPlannerTest {

    @Test
    void theContendedAnnotationIsNotAvailableToAMod() {
        // HotSpot restricts it to JDK internal classes without -XX:-RestrictContended, and a mod is loaded
        // into a process whose flags it does not control. Stating it as a constant keeps the reasoning
        // honest: the manual padding below is manual because it has to be.
        assertFalse(FalseSharingPlanner.contendedAnnotationUsable());
        assertEquals(64, FalseSharingPlanner.CACHE_LINE_BYTES);
        assertEquals(128, FalseSharingPlanner.CONTENDED_PADDING_BYTES, "twice the line, for the prefetcher");
    }

    @Test
    void theMeasuredCostIsWhatDecidesWhetherToBother() {
        // 5.0 to 6.1 across independent measurements for two counters sharing a line, and 9.7 when they
        // genuinely contend for one counter. Carried as numbers because a counter written once a second
        // does not need any of this.
        assertEquals(5.0, FalseSharingPlanner.MEASURED_FALSE_SHARING_SLOWDOWN, 1e-12);
        assertEquals(9.7, FalseSharingPlanner.MEASURED_TRUE_CONTENTION_SLOWDOWN, 1e-12);
        assertTrue(FalseSharingPlanner.MEASURED_TRUE_CONTENTION_SLOWDOWN
                > FalseSharingPlanner.MEASURED_FALSE_SHARING_SLOWDOWN);
    }

    @Test
    void eightLongCountersShareOneLine() {
        assertEquals(8, FalseSharingPlanner.countersPerLine(8));
        assertEquals(16, FalseSharingPlanner.countersPerLine(4));
        assertEquals(4, FalseSharingPlanner.countersPerLine(16));
        assertEquals(1, FalseSharingPlanner.countersPerLine(64));
    }

    @Test
    void aSingleWriterCannotFalseShareWithItself() {
        Decision decision = FalseSharingPlanner.choose(
                new Counter("frames", 1, 20, false, true));

        assertEquals(Layout.SHARED_LINE, decision.layout());
        assertTrue(decision.reason().contains("only one thread"), decision.reason());
    }

    @Test
    void aCounterNotWrittenDuringATickOwesNothing() {
        Decision decision = FalseSharingPlanner.choose(
                new Counter("startup", 4, 0, false, true));

        assertEquals(Layout.SHARED_LINE, decision.layout());
        assertTrue(decision.reason().contains("not written during a tick"), decision.reason());
    }

    @Test
    void aHotAccumulatedCounterIsStriped() {
        Decision decision = FalseSharingPlanner.choose(
                new Counter("buildsNanos", 4, 40, false, true));

        assertEquals(Layout.STRIPED, decision.layout());
        assertTrue(decision.reason().contains("4 writers"), decision.reason());
        assertTrue(decision.reason().contains("summing the cells on read"), decision.reason());
    }

    @Test
    void aMaximumCannotBeStripedSoItGetsManualPadding() {
        // This is the MeshBuildTimer.worstNanos case. A maximum cannot be accumulated -- it has to be
        // contended for -- so there is no striping available, and the padding has to be by hand because
        // the annotation needs a JVM flag a mod cannot pass.
        Decision decision = FalseSharingPlanner.choose(
                new Counter("worstNanos", 4, 20, false, false));

        assertEquals(Layout.PADDED_SINGLE, decision.layout());
        assertTrue(decision.reason().contains("cannot be accumulated"), decision.reason());
        assertTrue(decision.reason().contains("manual"), decision.reason());
    }

    @Test
    void aCounterReadAsOftenAsWrittenIsNotWorthStriping() {
        // Striping spreads the writes and makes the read walk every cell, so where the read is as
        // frequent as the write the saving is given straight back. Reported as a trade rather than
        // quietly resolved in favour of one side.
        Decision decision = FalseSharingPlanner.choose(
                new Counter("liveCount", 4, 20, true, true));

        assertEquals(Layout.SHARED_LINE, decision.layout());
        assertTrue(decision.reason().contains("walks every cell"), decision.reason());
    }

    @Test
    void manualPaddingIsTheContendedWidthNotTheLineWidth() {
        // Padding by exactly one line satisfies the coherence protocol and not the prefetcher, which can
        // pull two adjacent lines together.
        assertEquals(120, FalseSharingPlanner.manualPaddingBytes(8));
        assertEquals(124, FalseSharingPlanner.manualPaddingBytes(4));
        assertEquals(64, FalseSharingPlanner.manualPaddingBytes(64));
        assertEquals(0, FalseSharingPlanner.manualPaddingBytes(128), "already a full contended width");
    }

    @Test
    void stripingCostsAKilobyteToCountOneNumber() {
        assertEquals(128, FalseSharingPlanner.stripedBytes(1));
        assertEquals(1024, FalseSharingPlanner.stripedBytes(8));
        assertEquals(2048, FalseSharingPlanner.stripedBytes(16));
        // Still the right trade for a hot counter and the wrong one for a cold one, which is what choose
        // is deciding rather than what this measures.
        assertTrue(FalseSharingPlanner.stripedBytes(8) > 1000);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FalseSharingPlanner.choose(null));
        assertThrows(IllegalArgumentException.class,
                () -> FalseSharingPlanner.choose(new Counter(null, 2, 1, false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> FalseSharingPlanner.choose(new Counter("c", 0, 1, false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> FalseSharingPlanner.choose(new Counter("c", 2, -1, false, true)));
        assertThrows(IllegalArgumentException.class, () -> FalseSharingPlanner.countersPerLine(0));
        assertThrows(IllegalArgumentException.class, () -> FalseSharingPlanner.manualPaddingBytes(0));
        assertThrows(IllegalArgumentException.class, () -> FalseSharingPlanner.manualPaddingBytes(129));
        assertThrows(IllegalArgumentException.class, () -> FalseSharingPlanner.stripedBytes(0));
    }
}
