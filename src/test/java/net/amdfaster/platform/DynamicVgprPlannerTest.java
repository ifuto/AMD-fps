package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicVgprPlannerTest {

    @Test
    void dynamicAllocationNeedsWave32AndCompute() {
        // The ISA documents the feature as available in wave32 mode and not wave64, and as compute-only.
        // Asking for it otherwise would either fail to launch or silently fall back.
        assertTrue(DynamicVgprPlanner.isEligible(true, true));
        assertFalse(DynamicVgprPlanner.isEligible(false, true), "wave64 cannot use it");
        assertFalse(DynamicVgprPlanner.isEligible(true, false), "a graphics stage cannot use it");
        assertFalse(DynamicVgprPlanner.isEligible(false, false));
    }

    @Test
    void occupancyIsSetByHowFarAWaveMayGrow() {
        // A wave that can grow has to be able to reach its maximum with every other resident wave also at
        // theirs.
        assertEquals(16, DynamicVgprPlanner.residentWaves(96), "96 registers fits all sixteen slots");
        assertEquals(1.0, DynamicVgprPlanner.occupancy(16), 1e-12);

        assertEquals(12, DynamicVgprPlanner.residentWaves(128));
        assertEquals(0.75, DynamicVgprPlanner.occupancy(12), 1e-12);
    }

    @Test
    void occupancyIsCappedByTheWaveSlots() {
        assertEquals(16, DynamicVgprPlanner.residentWaves(DynamicVgprPlanner.INITIAL_VGPRS),
                "the register file could hold far more, but there are only sixteen slots");
        assertEquals(16, DynamicVgprPlanner.WAVE_SLOTS);
    }

    @Test
    void staticAllocationIsSetByTheDeclaredWorstCase() {
        // Static allocation reserves the declared count for every wave whether or not it is in use, so the
        // declared worst case sets the occupancy for the whole run.
        assertEquals(0.375, DynamicVgprPlanner.staticOccupancy(256), 1e-12);
        assertEquals(1.0, DynamicVgprPlanner.staticOccupancy(96), 1e-12);
    }

    @Test
    void aPlanThatFillsTheFileExactlyCanDeadlock() {
        // If every register on the core is handed out and every resident wave needs another one to make
        // progress, none of them can and the core hangs. A plan that fills the file exactly is not a tight
        // plan, it is a hang.
        assertFalse(DynamicVgprPlanner.canDeadlock(16, 96), "16 x 96 is exactly the file, so it fits");
        assertTrue(DynamicVgprPlanner.canDeadlock(16, 100), "16 x 100 overflows it");
    }

    @Test
    void aDeadlockingPlanIsNeverWorthIt() {
        assertFalse(DynamicVgprPlanner.isWorthIt(true, true, 256, 100, 16),
                "the occupancy would be better, but the core would hang");
    }

    @Test
    void aVaryingKernelGainsFromDynamicAllocation() {
        // A kernel whose pressure varies pays for its worst case the whole time under static allocation,
        // and occupancy is set by that worst case rather than by the average.
        assertTrue(DynamicVgprPlanner.isWorthIt(true, true, 256, 96, 16),
                "static gives 6 waves, dynamic gives sixteen");
    }

    @Test
    void aFlatKernelGainsNothing() {
        // If the pressure does not vary, the dynamic maximum is the static declaration and there is nothing
        // to recover.
        assertFalse(DynamicVgprPlanner.isWorthIt(true, true, 96, 96, 16)
                && DynamicVgprPlanner.occupancy(16) > DynamicVgprPlanner.staticOccupancy(96));
        assertEquals(DynamicVgprPlanner.occupancy(16),
                DynamicVgprPlanner.staticOccupancy(96), 1e-12);
    }

    @Test
    void anIneligibleKernelIsNeverWorthIt() {
        assertFalse(DynamicVgprPlanner.isWorthIt(false, true, 256, 96, 16));
        assertFalse(DynamicVgprPlanner.isWorthIt(true, false, 256, 96, 16));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DynamicVgprPlanner.residentWaves(1));
        assertThrows(IllegalArgumentException.class, () -> DynamicVgprPlanner.canDeadlock(0, 96));
        assertThrows(IllegalArgumentException.class, () -> DynamicVgprPlanner.occupancy(-1));
        assertThrows(IllegalArgumentException.class, () -> DynamicVgprPlanner.staticOccupancy(0));
    }

    @Test
    void theConstantsMatchTheArchitecture() {
        assertEquals(1536, DynamicVgprPlanner.FILE_VGPRS);
        assertEquals(16, DynamicVgprPlanner.WAVE_SLOTS);
        assertEquals(24, DynamicVgprPlanner.INITIAL_VGPRS);
        assertTrue(DynamicVgprPlanner.INITIAL_VGPRS < DynamicVgprPlanner.FILE_VGPRS);
    }
}
