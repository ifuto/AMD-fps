package dev.amdfaster.core.occupancy;

import dev.amdfaster.core.arch.AmdArch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the occupancy maths against the numbers AMD's own compilers produce.
 *
 * <p>Every expectation here was derived by hand from the ISA rules (registers are handed out in
 * allocation blocks; a wave gets a whole block or nothing) and cross-checked against LLVM's
 * {@code getNumWavesPerEUWithNumVGPRs()} behaviour, so a regression in the model shows up as a
 * failing test rather than as a mysterious FPS change.
 */
class OccupancyModelTest {

    private static OccupancyModel.Kernel wave32(int vgprs, int workgroupSize) {
        return new OccupancyModel.Kernel(vgprs, 0, 0, 32, workgroupSize);
    }

    private static OccupancyModel.Kernel wave64(int vgprs, int workgroupSize) {
        return new OccupancyModel.Kernel(vgprs, 0, 0, 64, workgroupSize);
    }

    @Test
    @DisplayName("RDNA 2: 64 VGPRs in a 256 thread workgroup fills the CU (16 wave32 slots)")
    void rdna2FullOccupancy() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2, wave32(64, 256));
        assertEquals(16, r.wavesPerSimd());
        assertEquals(16, r.maxWavesPerSimd());
        assertEquals(1.0, r.occupancy(), 1.0e-9);
        assertEquals(OccupancyModel.Limiter.WAVE_SLOTS, r.limitingFactor());
        assertEquals(16, r.vgprGranule());
        assertEquals(32, r.wavesPerCu());
        assertEquals(8, r.wavesPerWorkgroup());
        // wavesPerCu / wavesPerWorkgroup = 4 workgroups fit on an RDNA CU.
        assertEquals(4, r.maxWorkgroupsPerCu());
        assertFalse(r.spillsExpected());
        assertEquals(-1, r.vgprsToReleaseForNextStep(),
                "a kernel already at the wave slot limit has no next step to report");
    }

    @Test
    @DisplayName("RDNA 2: 96 VGPRs costs exactly one wave, and 16 VGPRs are needed to win it back")
    void rdna2GranularityCliff() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2, wave32(96, 256));
        // 1024 VGPRs per SIMD / alignTo(96, 16) = 10 waves.
        assertEquals(10, r.wavesPerSimd());
        assertEquals(OccupancyModel.Limiter.VGPR, r.limitingFactor());
        assertEquals(96, r.maxVgprsForCurrentWaves());
        assertEquals(80, r.maxVgprsForNextWaveStep());
        assertEquals(16, r.vgprsToReleaseForNextStep());
        assertTrue(r.describe().contains("gains a wave"));
    }

    @Test
    @DisplayName("RDNA 2: 65 VGPRs costs the same block as 80, so rounding up is reported")
    void rdna2RoundsUp() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2, wave32(65, 32));
        assertEquals(12, r.wavesPerSimd(), "alignTo(65,16)=80 and 1024/80=12");
        assertEquals(80, r.maxVgprsForCurrentWaves());
        // Getting to 13 waves needs a budget of alignDown(1024/13, 16) = 64 VGPRs, i.e. one VGPR
        // below the 65 this kernel uses. Note that a budget of 64 VGPRs actually buys 16 waves, not
        // 13: the allocation blocks make the step function coarser than a linear inverse, which is
        // exactly why the block size is worth reporting to a shader author.
        assertEquals(64, r.maxVgprsForNextWaveStep());
        assertEquals(1, r.vgprsToReleaseForNextStep());
    }

    @Test
    @DisplayName("RDNA 2 wave64: the wave32 slot budget halves to 8 waves")
    void rdna2Wave64() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2, wave64(64, 256));
        assertEquals(8, r.maxWavesPerSimd());
        assertEquals(8, r.wavesPerSimd());
        assertEquals(8, r.vgprGranule());
        assertEquals(512, OccupancyModel.totalVgprs(AmdArch.RDNA2, 64));
        assertEquals(4, r.wavesPerWorkgroup());
    }

    @Test
    @DisplayName("RDNA 3: the 24 VGPR allocation granularity makes 96 the next stop below 100")
    void rdna3Granularity() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA3, wave32(100, 256));
        assertEquals(12, r.wavesPerSimd(), "alignTo(100,24)=120 and 1536/120=12");
        assertEquals(24, r.vgprGranule());
        assertEquals(120, r.maxVgprsForCurrentWaves());
        assertEquals(96, r.maxVgprsForNextWaveStep());
        assertEquals(4, r.vgprsToReleaseForNextStep());
    }

    @Test
    @DisplayName("GCN (Vega): 40 VGPRs at wave64 gives 6 of 10 waves")
    void vegaWave64() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.VEGA, wave64(40, 256));
        assertEquals(10, r.maxWavesPerSimd(), "GCN counts its 10 waves per SIMD in wave64");
        assertEquals(6, r.wavesPerSimd(), "256/40 = 6");
        assertEquals(0.6, r.occupancy(), 1.0e-9);
        assertEquals(OccupancyModel.Limiter.VGPR, r.limitingFactor());
        assertEquals(24, r.wavesPerCu(), "4 SIMD16 per GCN compute unit");
        assertEquals(6, r.maxWorkgroupsPerCu());
    }

    @Test
    @DisplayName("GCN: SGPRs limit occupancy (they stop doing so on GFX10 and newer)")
    void gcnSgprLimit() {
        // 800 SGPRs per SIMD, granularity 8, one reserved for the trap handler:
        // alignTo(125, 8) + 1 = 129 -> 800 / 129 = 6 waves.
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.VEGA,
                new OccupancyModel.Kernel(24, 125, 0, 64, 256));
        assertEquals(6, r.wavesPerSimd());
        assertEquals(OccupancyModel.Limiter.SGPR, r.limitingFactor());

        // The same register consumption on RDNA 2 must not be SGPR limited at all.
        OccupancyModel.Result rdna = OccupancyModel.evaluate(AmdArch.RDNA2,
                new OccupancyModel.Kernel(24, 125, 0, 32, 256));
        assertEquals(16, rdna.wavesPerSimd());
        assertEquals(OccupancyModel.Limiter.WAVE_SLOTS, rdna.limitingFactor());
        assertFalse(AmdArch.RDNA2.sgprsLimitOccupancy());
        assertTrue(AmdArch.VEGA.sgprsLimitOccupancy());
    }

    @Test
    @DisplayName("LDS can cap occupancy: a 100 KiB workgroup leaves room for one per CU")
    void ldsLimit() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2,
                new OccupancyModel.Kernel(32, 0, 100 * 1024, 32, 256));
        // 128 KiB LDS per CU / 100 KiB = 1 workgroup, 8 waves each.
        assertEquals(8, r.wavesPerSimd());
        assertEquals(OccupancyModel.Limiter.LDS, r.limitingFactor());
    }

    @Test
    @DisplayName("a kernel needing more VGPRs than the whole file is reported as spilling")
    void spillDetection() {
        OccupancyModel.Result r = OccupancyModel.evaluate(AmdArch.RDNA2, wave32(2048, 32));
        assertEquals(1, r.wavesPerSimd());
        assertTrue(r.spillsExpected());
        assertTrue(r.describe().contains("spills"));
    }

    @Test
    @DisplayName("budget table gives the register target for every occupancy step")
    void budgetTable() {
        int[][] table = OccupancyModel.budgetTable(AmdArch.RDNA2, 32);
        assertEquals(16, table.length);
        assertEquals(64, OccupancyModel.maxVgprsForWaves(AmdArch.RDNA2, 32, 16));
        assertEquals(128, OccupancyModel.maxVgprsForWaves(AmdArch.RDNA2, 32, 8));
        assertEquals(1024, OccupancyModel.maxVgprsForWaves(AmdArch.RDNA2, 32, 1));

        // The table is ordered by increasing wave target, so the register budget must be
        // monotonically non-increasing: asking for more waves can never allow more registers.
        assertEquals(1, table[0][0]);
        assertEquals(16, table[table.length - 1][0]);
        int previous = Integer.MAX_VALUE;
        for (int[] entry : table) {
            assertTrue(entry[1] <= previous,
                    "register budget must not grow when the wave target grows (wave target " + entry[0] + ")");
            previous = entry[1];
        }
    }

    @Test
    @DisplayName("wave size and granule selection follows the architecture")
    void waveSizeSelection() {
        assertTrue(AmdArch.RDNA2.supportsWave32());
        assertTrue(AmdArch.RDNA3.supportsWave32());
        assertFalse(AmdArch.VEGA.supportsWave32());
        assertEquals(16, OccupancyModel.maxWavesPerSimd(AmdArch.RDNA3, 32));
        assertEquals(8, OccupancyModel.maxWavesPerSimd(AmdArch.RDNA3, 64));
        assertEquals(10, OccupancyModel.maxWavesPerSimd(AmdArch.VEGA_APU, 64));
        assertEquals(24, OccupancyModel.vgprGranule(AmdArch.RDNA4, 32));
        assertEquals(12, OccupancyModel.vgprGranule(AmdArch.RDNA4, 64));
        assertEquals(1536, OccupancyModel.totalVgprs(AmdArch.RDNA4, 32));
    }
}
