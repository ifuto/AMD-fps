package net.amdfaster.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.OccupancyPlanner;
import net.amdfaster.mesh.Meshlet;
import net.amdfaster.platform.PathSelector.HizWorkaround;
import net.amdfaster.platform.PathSelector.Paths;
import org.junit.jupiter.api.Test;

class PathSelectorTest {

    @Test
    void onlyGfx12DefaultsToTheSuppressingWorkaround() {
        // Assuming FULL everywhere would push every AMD device into depth pre-passes it does not need;
        // assuming DISABLED everywhere would leave RDNA4 shading fragments early-Z could have discarded.
        assertEquals(HizWorkaround.FULL, PathSelector.defaultHizWorkaround(AmdArchitecture.RDNA4));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.RDNA3));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.RDNA35));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.RDNA2));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.RDNA1));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.GCN5_VEGA));
        assertEquals(HizWorkaround.DISABLED, PathSelector.defaultHizWorkaround(AmdArchitecture.UNKNOWN));
    }

    @Test
    void onlyTheFullSettingSuppressesEarlyZ() {
        assertTrue(HizWorkaround.FULL.suppressesEarlyZ());
        // full_rez applies the same workaround but re-tests depth late, so the rejection is recovered.
        assertFalse(HizWorkaround.FULL_REZ.suppressesEarlyZ());
        assertFalse(HizWorkaround.PARTIAL.suppressesEarlyZ());
        assertFalse(HizWorkaround.DISABLED.suppressesEarlyZ());
    }

    @Test
    void theGenerationSuppliesTheAnswerWhenNothingWasObserved() {
        assertTrue(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA4, null));
        assertFalse(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA3, null));
        assertFalse(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA1, null));
    }

    @Test
    void anObservedSettingOutranksTheGeneration() {
        // The workaround is a RADV tunable, so a user who changed it is not in the default case whatever
        // the chip is -- and a non-RADV driver never had it to begin with.
        assertFalse(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA4, HizWorkaround.FULL_REZ));
        assertFalse(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA4, HizWorkaround.DISABLED));
        assertTrue(PathSelector.earlyZSuppressed(AmdArchitecture.RDNA3, HizWorkaround.FULL),
                "a driver that reports FULL is suppressing early-Z even on a chip that would not by default");
    }

    @Test
    void gcnHasNoWave32ToDefaultTo() {
        assertEquals(32, PathSelector.defaultWaveSize(AmdArchitecture.RDNA4));
        assertEquals(32, PathSelector.defaultWaveSize(AmdArchitecture.RDNA3));
        assertEquals(32, PathSelector.defaultWaveSize(AmdArchitecture.RDNA1));
        assertEquals(64, PathSelector.defaultWaveSize(AmdArchitecture.GCN5_VEGA));
        // Unknown devices report no wave32 support, so the conservative wave64 is the safe default.
        assertEquals(64, PathSelector.defaultWaveSize(AmdArchitecture.UNKNOWN));
    }

    @Test
    void dynamicRegistersAreRdna4Wave32ComputeOnly() {
        assertTrue(PathSelector.dynamicVgprsAvailable(AmdArchitecture.RDNA4, true));
        assertFalse(PathSelector.dynamicVgprsAvailable(AmdArchitecture.RDNA4, false), "compute only");
        assertFalse(PathSelector.dynamicVgprsAvailable(AmdArchitecture.RDNA3, true), "RDNA4 only");
        assertFalse(PathSelector.dynamicVgprsAvailable(AmdArchitecture.RDNA35, true));
        assertFalse(PathSelector.dynamicVgprsAvailable(AmdArchitecture.GCN5_VEGA, true));
    }

    @Test
    void rdna4GetsTheSuppressedPathAndEverythingElseDoesNot() {
        Paths rdna4 = PathSelector.select(AmdArchitecture.RDNA4, null);

        assertTrue(rdna4.earlyZSuppressed());
        assertEquals(32, rdna4.waveSize());
        assertTrue(rdna4.dynamicVgprs());
        assertEquals(64, rdna4.workgroupSize());
        assertEquals(62, rdna4.meshletQuads(), "the count the draw derivation and strides assume");
        assertEquals(65536, rdna4.ldsBytes());

        Paths rdna3 = PathSelector.select(AmdArchitecture.RDNA3, null);
        assertFalse(rdna3.earlyZSuppressed());
        assertFalse(rdna3.dynamicVgprs());
        assertEquals(65536, rdna3.ldsBytes());
    }

    @Test
    void rdna1And2HaveTwiceTheSharedMemory() {
        Paths rdna1 = PathSelector.select(AmdArchitecture.RDNA1, null);

        assertEquals(131072, rdna1.ldsBytes(), "a WGP shares 128 KB across its two CUs");
        assertFalse(rdna1.earlyZSuppressed());
        assertEquals(32, rdna1.waveSize());
    }

    @Test
    void anUnrecognisedDeviceFallsBackToTheConservativeBudget() {
        Paths unknown = PathSelector.select(AmdArchitecture.UNKNOWN, null);

        // UNKNOWN reports no LDS at all, so the plan budgets against the largest a workgroup may take
        // rather than against zero, which would make every tile size come out as one texel.
        assertEquals(OccupancyPlanner.LDS_MAX_PER_WORKGROUP, unknown.ldsBytes());
        assertEquals(64, unknown.waveSize());
        assertFalse(unknown.earlyZSuppressed());
        assertFalse(unknown.dynamicVgprs());
        assertEquals(Meshlet.MAX_QUADS, unknown.meshletQuads());
        assertFalse(PathSelector.tuned(AmdArchitecture.UNKNOWN));
    }

    @Test
    void everyRecognisedGenerationIsTuned() {
        assertTrue(PathSelector.tuned(AmdArchitecture.RDNA4));
        assertTrue(PathSelector.tuned(AmdArchitecture.RDNA3));
        assertTrue(PathSelector.tuned(AmdArchitecture.RDNA35));
        assertTrue(PathSelector.tuned(AmdArchitecture.RDNA2));
        assertTrue(PathSelector.tuned(AmdArchitecture.RDNA1));
        assertTrue(PathSelector.tuned(AmdArchitecture.GCN5_VEGA));
    }

    @Test
    void theWorkgroupIsNeverSmallerThanTheWave() {
        // A workgroup smaller than one wave would leave lanes with no work and the reduction inside it
        // would have nothing to reduce over.
        for (AmdArchitecture architecture : AmdArchitecture.values()) {
            Paths paths = PathSelector.select(architecture, null);
            assertTrue(paths.workgroupSize() >= paths.waveSize(), architecture.displayName());
            assertEquals(0, paths.workgroupSize() % 64, "a multiple of 64 on every generation");
        }
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSelector.defaultHizWorkaround(null));
        assertThrows(IllegalArgumentException.class, () -> PathSelector.earlyZSuppressed(null, null));
        assertThrows(IllegalArgumentException.class, () -> PathSelector.defaultWaveSize(null));
        assertThrows(IllegalArgumentException.class,
                () -> PathSelector.dynamicVgprsAvailable(null, true));
        assertThrows(IllegalArgumentException.class, () -> PathSelector.select(null, null));
        assertThrows(IllegalArgumentException.class, () -> PathSelector.tuned(null));
    }
}
