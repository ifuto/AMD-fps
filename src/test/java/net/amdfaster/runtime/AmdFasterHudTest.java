package net.amdfaster.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.amdfaster.perf.FrameTimeRecorder;
import org.junit.jupiter.api.Test;

/**
 * The heap line, and the position it sits in.
 *
 * <p>Every expected string here was printed by a model of the same formatting before the test was written.
 */
class AmdFasterHudTest {

    private static final long MB = 1L << 20;

    private static final int DEFAULT_COLOUR = 0xFFE0E0E0;
    private static final int WARN_COLOUR = 0xFFFF6040;

    private static AmdFasterHud.Line heapLine(long used, long max) {
        List<AmdFasterHud.Line> lines =
                AmdFasterHud.build(null, null, null, null, used, max);
        return lines.stream()
                .filter(line -> line.text().startsWith("heap"))
                .findFirst()
                .orElse(null);
    }

    @Test
    void theHeapLineReportsMegabytesAndAFraction() {
        AmdFasterHud.Line line = heapLine(512 * MB, 2048 * MB);

        assertEquals("heap  512 / 2048 MB  (25%)", line.text());
        assertEquals(DEFAULT_COLOUR, line.colour(), "a quarter of the heap is not a warning");
    }

    @Test
    void aHeapPastTheWarningFractionChangesColour() {
        // 1894 / 2048 is 92 percent, past the monitor's 0.9 threshold.
        AmdFasterHud.Line line = heapLine(1894 * MB, 2048 * MB);

        assertEquals("heap  1894 / 2048 MB  (92%)", line.text());
        assertEquals(WARN_COLOUR, line.colour(),
                "the overlay and the log have to agree about when this is a problem");
    }

    @Test
    void theWarningColourTurnsOnAtTheSameFractionTheMonitorUses() {
        // 1843 / 2048 is 0.899902, just under; 1844 / 2048 is 0.900391, just over.
        assertEquals(DEFAULT_COLOUR, heapLine(1843 * MB, 2048 * MB).colour());
        assertEquals(WARN_COLOUR, heapLine(1844 * MB, 2048 * MB).colour());
    }

    @Test
    void aFullHeapStillFormats() {
        AmdFasterHud.Line line = heapLine(2047 * MB, 2048 * MB);

        assertEquals("heap  2047 / 2048 MB  (100%)", line.text());
        assertEquals(WARN_COLOUR, line.colour());
    }

    @Test
    void noHeapReadingMeansNoHeapLine() {
        // A maximum of zero is "not sampled yet", not "an empty heap". Printing 0 / 0 MB would be a
        // division by zero, and printing 0 / 0 as though it were measured would be a lie.
        assertNull(heapLine(0L, 0L), "nothing to show before the first sample");

        // A reading of zero used against a real maximum is a legitimate measurement, though.
        assertEquals("heap  0 / 2048 MB  (0%)", heapLine(0L, 2048 * MB).text());
    }

    @Test
    void theHeapLineSitsBetweenTheCacheAndTheGovernor() {
        // Position matters because the overlay is read top to bottom: frame timings, then the caches,
        // then the heap, then what the mod decided to do about it.
        FrameTimeRecorder recorder = new FrameTimeRecorder();
        recorder.record(16_000_000L);
        recorder.record(17_000_000L);

        List<AmdFasterHud.Line> lines =
                AmdFasterHud.build(recorder, null, null, null, 512 * MB, 2048 * MB);

        int heap = -1;
        int variance = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).text().startsWith("heap")) {
                heap = i;
            }
            if (lines.get(i).text().startsWith("variance")) {
                variance = i;
            }
        }
        assertTrue(heap > variance, "the heap line follows the frame-time lines");
        assertEquals(lines.size() - 1, heap, "and is last when no governor or stages are present");
    }

    @Test
    void theFourArgumentOverloadShowsNoHeapLine() {
        List<AmdFasterHud.Line> lines = AmdFasterHud.build(null, null, null, null);

        assertTrue(lines.stream().noneMatch(line -> line.text().startsWith("heap")),
                "the overload that takes no heap reading must not invent one");
    }
}
