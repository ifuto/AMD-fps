package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPressureMonitorTest {

    private static final long MAX = 4L * 1024 * 1024 * 1024;

    @Test
    void aHealthyHeapDoesNotWarn() {
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        assertFalse(monitor.sample(MAX / 2, MAX));
        assertFalse(monitor.isWarning());
        assertEquals(0L, monitor.warnings());
    }

    @Test
    void crossingTheThresholdWarns() {
        // A game that runs out of memory does not fail politely: it stutters, then freezes, then crashes
        // with an error that says nothing about what filled the heap.
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        assertTrue(monitor.sample((long) (MAX * 0.95), MAX));
        assertTrue(monitor.isWarning());
        assertEquals(1L, monitor.warnings());
    }

    @Test
    void itWarnsOncePerCrossingNotEveryTick() {
        // A heap sitting near the threshold would warn every tick, and a log filling with the same line
        // every fiftieth of a second is not readable -- the player would stop looking at it, including at
        // the line that matters.
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();
        long high = (long) (MAX * 0.95);

        assertTrue(monitor.sample(high, MAX));
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(monitor.sample(high, MAX), "already warned");
        }
        assertEquals(1L, monitor.warnings());
        assertEquals(101L, monitor.samples());
    }

    @Test
    void itCanWarnAgainAfterRecovering() {
        // A heap that drops and then climbs again is a second episode, not a continuation of the first, and
        // suppressing it would hide exactly the pattern -- a slow leak that refills -- that this exists to
        // surface.
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        assertTrue(monitor.sample((long) (MAX * 0.95), MAX));
        assertFalse(monitor.sample(MAX / 2, MAX), "recovered, and no warning for coming down");
        assertFalse(monitor.isWarning());

        assertTrue(monitor.sample((long) (MAX * 0.95), MAX), "a second episode");
        assertEquals(2L, monitor.warnings());
    }

    @Test
    void theGapBetweenTheThresholdsPreventsFlutter() {
        // Between 0.8 and 0.9 the flag holds, so a heap oscillating just under the warning does not produce
        // a warning every time it crosses back.
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        monitor.sample((long) (MAX * 0.95), MAX);
        monitor.sample((long) (MAX * 0.85), MAX);

        assertTrue(monitor.isWarning(), "still in the gap, so still warning");
        assertTrue(monitor.sample((long) (MAX * 0.95), MAX) == false, "and no second warning");
        assertEquals(1L, monitor.warnings());
    }

    @Test
    void theThresholdIsAFractionOfWhateverWasAllocated() {
        // Players allocate anywhere from two gigabytes to sixteen, and a fixed byte count would be almost
        // nothing on one and almost everything on the other.
        assertEquals((long) (2L * 1024 * 1024 * 1024 * 0.9),
                MemoryPressureMonitor.warnBytes(2L * 1024 * 1024 * 1024));
        assertEquals((long) (16L * 1024 * 1024 * 1024 * 0.9),
                MemoryPressureMonitor.warnBytes(16L * 1024 * 1024 * 1024));
    }

    @Test
    void thePeakIsTrackedForDiagnostics() {
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        monitor.sample(MAX / 4, MAX);
        monitor.sample((long) (MAX * 0.7), MAX);
        monitor.sample(MAX / 2, MAX);

        assertEquals(0.7, monitor.peak(), 1e-9, "the highest seen, not the latest");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        assertThrows(IllegalArgumentException.class, () -> monitor.sample(100, 0));
        assertThrows(IllegalArgumentException.class, () -> monitor.sample(-1, MAX));
        assertThrows(IllegalArgumentException.class, () -> MemoryPressureMonitor.warnBytes(0));
    }

    @Test
    void aFreshMonitorReportsZeroNotNaN() {
        // A monitor that has sampled nothing has technically never been under pressure, but reporting that
        // as a result would describe an unused one as though it had measured something.
        MemoryPressureMonitor monitor = new MemoryPressureMonitor();

        assertEquals(0L, monitor.samples());
        assertEquals(0L, monitor.warnings());
        assertEquals(0.0, monitor.peak(), 1e-12);
        assertFalse(monitor.isWarning());
        assertEquals(0.9, MemoryPressureMonitor.WARN_FRACTION, 1e-12);
        assertEquals(0.8, MemoryPressureMonitor.RECOVER_FRACTION, 1e-12);
    }
}
