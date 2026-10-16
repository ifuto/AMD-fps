package net.amdfaster.platform;

import net.amdfaster.platform.WorkgroupModeSelector.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkgroupModeSelectorTest {

    @Test
    void theWiderModeDoublesTheLocalMemory() {
        // A workgroup processor shares its local memory between two compute units, so launching across both
        // halves lets one workgroup address twice as much. That is the whole reason to want the wider mode.
        assertEquals(64 * 1024, WorkgroupModeSelector.capacity(Mode.CU));
        assertEquals(128 * 1024, WorkgroupModeSelector.capacity(Mode.WGP));
    }

    @Test
    void reservingMoreMeansFewerFitAtOnce() {
        // The choice is between a few large workgroups and many small ones.
        assertEquals(4, WorkgroupModeSelector.groupsPerCu(Mode.CU, 16 * 1024));
        assertEquals(2, WorkgroupModeSelector.groupsPerCu(Mode.CU, 32 * 1024));
        assertEquals(1, WorkgroupModeSelector.groupsPerCu(Mode.CU, 64 * 1024));
        assertEquals(4, WorkgroupModeSelector.groupsPerCu(Mode.WGP, 32 * 1024));
    }

    @Test
    void aKernelThatDoesNotFitInOneUnitUsesTheWiderMode() {
        assertEquals(Mode.WGP, WorkgroupModeSelector.choose(96 * 1024, false, false));
    }

    @Test
    void aKernelHeavyInAtomicsStaysInOneUnit() {
        // Local memory atomics are fastest when the waves share one unit's memory, because that is a single
        // arbitration point. Spread across a processor the same traffic crosses between the halves and is
        // serialised against the other unit's waves.
        assertEquals(Mode.CU, WorkgroupModeSelector.choose(32 * 1024, true, false));
        assertEquals(Mode.CU, WorkgroupModeSelector.choose(32 * 1024, false, true));
    }

    @Test
    void aKernelThatFitsInOneUnitUsesIt() {
        assertEquals(Mode.CU, WorkgroupModeSelector.choose(32 * 1024, false, false));
    }

    @Test
    void occupancyIsTheStricterOfTheTwoLimits() {
        // Local memory and vector registers limit occupancy independently and whichever is tighter decides.
        assertEquals(0.5, WorkgroupModeSelector.occupancy(16, 8, 16), 1e-12,
                "local memory binds at eight waves");
        assertEquals(0.375, WorkgroupModeSelector.occupancy(6, 16, 16), 1e-12,
                "and registers bind at six");
        assertEquals(1.0, WorkgroupModeSelector.occupancy(32, 32, 16), 1e-12,
                "capped by the slots");
    }

    @Test
    void halvingTheLocalMemoryGainsNothingIfRegistersAlreadyBind() {
        // A common way to make a change that measures slower and looks like it should have been faster.
        assertFalse(WorkgroupModeSelector.ldsIsTheBindingLimit(6, 16),
                "registers allow six, local memory sixteen, so registers bind");
        assertTrue(WorkgroupModeSelector.ldsIsTheBindingLimit(16, 8));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkgroupModeSelector.capacity(null));
        assertThrows(IllegalArgumentException.class, () -> WorkgroupModeSelector.groupsPerCu(Mode.CU, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WorkgroupModeSelector.groupsPerCu(Mode.CU, 128 * 1024));
        assertThrows(IllegalArgumentException.class, () -> WorkgroupModeSelector.choose(0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> WorkgroupModeSelector.ldsIsTheBindingLimit(0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> WorkgroupModeSelector.occupancy(8, 8, 0));
    }

    @Test
    void theCapacityMatchesTheArchitecture() {
        assertEquals(64 * 1024, WorkgroupModeSelector.LDS_PER_CU);
        assertEquals(2, WorkgroupModeSelector.capacity(Mode.WGP) / WorkgroupModeSelector.LDS_PER_CU);
    }
}
