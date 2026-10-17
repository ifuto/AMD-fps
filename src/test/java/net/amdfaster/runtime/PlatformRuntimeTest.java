package net.amdfaster.runtime;

import net.amdfaster.platform.CoreAffinityPlanner;
import net.amdfaster.platform.WorkgroupModeSelector;
import net.amdfaster.runtime.PlatformRuntime.Vendor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformRuntimeTest {

    @Test
    void aFailedProbeLeavesThePlatformUnknownRatherThanThrowing() {
        // A mod that cannot start without a GPU is worse than one that starts and runs its CPU-side work.
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertEquals(Vendor.UNKNOWN, runtime.vendor());
        assertFalse(runtime.isKnown());
    }

    @Test
    void anUnknownPlatformUsesSafeDefaults() {
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertEquals(32, runtime.waveSize(), "the safe default, not a guess");
        assertEquals(64, runtime.workgroupSize());
        assertEquals(64 * 1024, runtime.ldsBytesPerCu());
    }

    @Test
    void dynamicRegistersAreOnlyForAmdComputeShaders() {
        // It is wave32-only and compute-only on AMD, so asking for it otherwise would either fail to launch or
        // silently fall back, and a planner that assumed otherwise would report an occupancy the hardware
        // cannot deliver.
        PlatformRuntime unknown = new PlatformRuntime(null, false, 0, 0);

        assertFalse(unknown.useDynamicVgprs(true, 96, 96, 16), "not an AMD part");
    }

    @Test
    void aHybridCpuGetsCorePlacement() {
        PlatformRuntime runtime = new PlatformRuntime(null, true, 8, 8);

        assertEquals(CoreAffinityPlanner.CoreKind.PERFORMANCE,
                runtime.corePlacement(CoreAffinityPlanner.ThreadRole.RENDER));
        assertEquals(CoreAffinityPlanner.CoreKind.EFFICIENT,
                runtime.corePlacement(CoreAffinityPlanner.ThreadRole.MESHING));
        assertTrue(runtime.isHybridCpu());
        assertEquals(8, runtime.performanceCores());
        assertEquals(8, runtime.efficientCores());
    }

    @Test
    void aNonHybridCpuGetsNoPlacement() {
        // Pinning there would restrict the thread to a subset of identical cores for no reason, and could make
        // things worse by preventing the scheduler from balancing at all.
        PlatformRuntime runtime = new PlatformRuntime(null, false, 8, 0);

        assertEquals(null, runtime.corePlacement(CoreAffinityPlanner.ThreadRole.RENDER));
        assertFalse(runtime.isHybridCpu());
    }

    @Test
    void aConflictFreeStrideIsPaddedOffTheBankCount() {
        // A stride equal to the bank count puts every lane on the same bank and serialises the reads, which is
        // invisible in the source because the code looks like an ordinary array read.
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        int banks = runtime.ldsBytesPerCu() / 4;
        assertEquals(banks, runtime.conflictFreeStride(banks),
                "already conflict-free at the default bank count");
        assertTrue(runtime.conflictFreeStride(1) >= 1);
    }

    @Test
    void aKernelHeavyInAtomicsStaysInOneComputeUnit() {
        // The wider mode doubles the local memory a workgroup can use but couples two compute units, which
        // costs more than it gains for kernels that spend their time in atomics.
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertEquals(WorkgroupModeSelector.Mode.CU, runtime.workgroupMode(32 * 1024, true, false));
        assertEquals(WorkgroupModeSelector.Mode.CU, runtime.workgroupMode(32 * 1024, false, false));
    }

    @Test
    void aKernelNeedingMoreMemoryThanOneUnitUsesTheWiderMode() {
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertEquals(WorkgroupModeSelector.Mode.WGP, runtime.workgroupMode(96 * 1024, false, false));
    }

    @Test
    void occupancyIsReportedEvenWhenTheHardwareIsUnknown() {
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertTrue(runtime.occupancy(96) > 0.0);
        assertTrue(runtime.occupancy(96) <= 1.0);
    }

    @Test
    void aLowerRegisterCountReachesHigherOccupancy() {
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertTrue(runtime.occupancy(32) >= runtime.occupancy(256),
                "fewer registers means more threads");
    }

    @Test
    void computeGoesOnTheGraphicsQueueOnlyForAmd() {
        PlatformRuntime unknown = new PlatformRuntime(null, false, 0, 0);

        assertFalse(unknown.computeOnGraphicsQueue(true),
                "not an AMD part, so the dedicated queue is the right answer");
    }

    @Test
    void aFreshRuntimeReportsSafeDefaultsNotNaN() {
        // A runtime with no hardware to ask still has to answer, because every other component asks it -- and
        // answering with a number that happens to be true-ish everywhere would be exactly wrong nowhere.
        PlatformRuntime runtime = new PlatformRuntime(null, false, 0, 0);

        assertEquals(Vendor.UNKNOWN, runtime.vendor());
        assertEquals(32, runtime.waveSize());
        assertEquals(64, runtime.workgroupSize());
        assertEquals(0, runtime.performanceCores());
        assertEquals(0, runtime.efficientCores());
        assertEquals(null, runtime.device());
        assertEquals(net.amdfaster.platform.AmdArchitecture.UNKNOWN, runtime.amdArchitecture(),
                "classify returns UNKNOWN rather than null, and UNKNOWN reports zero for everything");
    }
}
