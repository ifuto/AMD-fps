package net.amdfaster.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.OccupancyPlanner.Footprint;
import net.amdfaster.gpu.OccupancyPlanner.Occupancy;
import net.amdfaster.platform.AmdArchitecture;
import org.junit.jupiter.api.Test;

/**
 * Every published data point the model is checked against is asserted here, so a change to the register
 * model that stops reproducing the whitepaper or the worked examples fails rather than quietly guessing.
 */
class OccupancyPlannerTest {

    /** Registers per lane in an RDNA 1/2 SIMD, allocated in blocks of eight. */
    private static final int RDNA1 = OccupancyPlanner.VGPRS_RDNA1;
    private static final int G1 = OccupancyPlanner.VGPR_GRANULE_RDNA1;

    /** Registers per lane in an RDNA 3/4 SIMD, allocated in blocks of twenty-four. */
    private static final int RDNA3 = OccupancyPlanner.VGPRS_RDNA3;
    private static final int G3 = OccupancyPlanner.VGPR_GRANULE_RDNA3;

    @Test
    void theWhitepaperDataPointsOnA1024RegisterFile() {
        // RDNA Architecture whitepaper: 4x wave32 at 256 VGPRs, 16x at 64, 2x wave64 at 256, 8x at 64.
        assertEquals(4, OccupancyPlanner.wavesPerSimd(256, 32, RDNA1, G1));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(64, 32, RDNA1, G1));
        assertEquals(2, OccupancyPlanner.wavesPerSimd(256, 64, RDNA1, G1));
        assertEquals(8, OccupancyPlanner.wavesPerSimd(64, 64, RDNA1, G1));
    }

    @Test
    void theWorkedExamplesThatPinTheGranule() {
        // The published arithmetic, not the summary table: 97 VGPRs allocates five 24-register blocks, so
        // 120, and 1536 over 120 is twelve waves. 168 VGPRs is seven blocks exactly, so 168, and nine.
        // A model that skipped the rounding would answer fifteen for the 97 register shader -- 25 per
        // cent more waves than the hardware schedules, which is the whole error this guards against.
        assertEquals(16, OccupancyPlanner.wavesPerSimd(96, 32, RDNA3, G3));
        assertEquals(12, OccupancyPlanner.wavesPerSimd(97, 32, RDNA3, G3));
        assertEquals(12, OccupancyPlanner.wavesPerSimd(120, 32, RDNA3, G3), "still five blocks");
        assertEquals(10, OccupancyPlanner.wavesPerSimd(128, 32, RDNA3, G3), "six blocks: 144");
        assertEquals(9, OccupancyPlanner.wavesPerSimd(168, 32, RDNA3, G3));
        assertEquals(8, OccupancyPlanner.wavesPerSimd(192, 32, RDNA3, G3));
        assertEquals(6, OccupancyPlanner.wavesPerSimd(240, 32, RDNA3, G3));
        assertEquals(5, OccupancyPlanner.wavesPerSimd(256, 32, RDNA3, G3), "eleven blocks: 264");
    }

    @Test
    void occupancyIsAClampedStaircaseNotASlope() {
        // Sixteen slots is the ceiling, so a cheap shader gets no more waves for using fewer registers --
        // it already filled them. The step down happens at a block boundary, not at every register.
        assertEquals(16, OccupancyPlanner.wavesPerSimd(1, 32, RDNA3, G3));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(24, 32, RDNA3, G3));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(48, 32, RDNA3, G3));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(64, 32, RDNA3, G3), "three blocks: 72");
        assertEquals(16, OccupancyPlanner.wavesPerSimd(96, 32, RDNA3, G3));
        assertEquals(12, OccupancyPlanner.wavesPerSimd(97, 32, RDNA3, G3), "four slots gone at 97");
    }

    @Test
    void wave64WinsWhileTheShaderIsCheapAndStopsAtTheBlockBoundary() {
        // The two sizes compete on lanes in flight, not on waves: a wave64 has twice the lanes but is
        // charged twice the registers, so which one is ahead depends entirely on the allocation.
        assertEquals(512, OccupancyPlanner.lanesInFlight(64, 32, RDNA3, G3));
        assertEquals(640, OccupancyPlanner.lanesInFlight(64, 64, RDNA3, G3), "wave64 ahead at 64 VGPRs");

        assertEquals(512, OccupancyPlanner.lanesInFlight(96, 32, RDNA3, G3));
        assertEquals(512, OccupancyPlanner.lanesInFlight(96, 64, RDNA3, G3), "level at 96");

        assertEquals(320, OccupancyPlanner.lanesInFlight(128, 32, RDNA3, G3));
        assertEquals(320, OccupancyPlanner.lanesInFlight(128, 64, RDNA3, G3), "still level, half the waves");
    }

    @Test
    void theCrossoverIsAtSeventySixRegisters() {
        // Seventy-six rounds up to four blocks of 24, which is 96, and from there the two sizes are level.
        // Below it wave64 is ahead; at it and above, wave32 is the better of two equals.
        assertEquals(1024, OccupancyPlanner.lanesInFlight(48, 64, RDNA3, G3));
        assertEquals(512, OccupancyPlanner.lanesInFlight(48, 32, RDNA3, G3));
        assertEquals(OccupancyPlanner.lanesInFlight(76, 32, RDNA3, G3),
                OccupancyPlanner.lanesInFlight(76, 64, RDNA3, G3));
    }

    @Test
    void ldsCanBindBeforeRegistersDo() {
        // RDNA 3 has 64 KB per CU, so a 16 KB tile allows four workgroups however few registers the
        // shader uses. Checked separately because either limit can be the one that bites.
        assertEquals(4, OccupancyPlanner.workgroupsPerCu(16384, 65536));
        assertEquals(2, OccupancyPlanner.workgroupsPerCu(32768, 65536));
        assertEquals(1, OccupancyPlanner.workgroupsPerCu(65536, 65536));
        // A 128 KB WGP, as on RDNA 1 and 2.
        assertEquals(8, OccupancyPlanner.workgroupsPerCu(16384, 131072));
        assertEquals(32, OccupancyPlanner.workgroupsPerCu(4096, 131072));
        // No shared memory means LDS is not the constraint at all.
        assertEquals(16, OccupancyPlanner.workgroupsPerCu(0, 65536));
    }

    @Test
    void wave64CollidesOnTwiceAsManyLdsBanks() {
        // gfx12 has 64 banks of 4 bytes. Lane i touches bank (i * stride) mod 64.
        assertEquals(0, OccupancyPlanner.ldsBankConflicts(32, 1));
        assertEquals(0, OccupancyPlanner.ldsBankConflicts(32, 2));
        assertEquals(16, OccupancyPlanner.ldsBankConflicts(32, 4), "32 lanes folded onto 16 banks");
        assertEquals(0, OccupancyPlanner.ldsBankConflicts(64, 1));
        assertEquals(32, OccupancyPlanner.ldsBankConflicts(64, 2), "the pattern that is free at wave32");
        assertEquals(48, OccupancyPlanner.ldsBankConflicts(64, 4));
    }

    @Test
    void theRegisterFileAndGranuleArePerGeneration() {
        assertEquals(1024, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA1));
        assertEquals(1024, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA2));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA3));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA35));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA4));
        assertEquals(256, OccupancyPlanner.registersPerSimd(AmdArchitecture.GCN5_VEGA));
        assertEquals(0, OccupancyPlanner.registersPerSimd(AmdArchitecture.UNKNOWN), "nothing to plan against");

        assertEquals(8, OccupancyPlanner.granuleFor(AmdArchitecture.RDNA1));
        assertEquals(8, OccupancyPlanner.granuleFor(AmdArchitecture.RDNA2));
        assertEquals(24, OccupancyPlanner.granuleFor(AmdArchitecture.RDNA3));
        assertEquals(24, OccupancyPlanner.granuleFor(AmdArchitecture.RDNA4));
        assertEquals(4, OccupancyPlanner.granuleFor(AmdArchitecture.GCN5_VEGA));
        assertEquals(1, OccupancyPlanner.granuleFor(AmdArchitecture.UNKNOWN));
    }

    @Test
    void aCheapShaderWithoutReductionGetsWave64() {
        Footprint footprint = new Footprint(64, 16384, 64, false, 1);

        assertEquals(64, OccupancyPlanner.recommendWaveSize(footprint, AmdArchitecture.RDNA3));

        Occupancy occupancy = OccupancyPlanner.occupancy(footprint, 64, AmdArchitecture.RDNA3);
        assertEquals(10, occupancy.wavesPerSimd(), "72 allocated, charged twice for 64 lanes");
        assertEquals(640, occupancy.lanesInFlight());
        assertEquals(4, occupancy.workgroupsPerCu(), "64 KB per CU over a 16 KB tile");
        assertFalse(occupancy.fullOccupancy());
    }

    @Test
    void acrossLaneReductionPrefersWave32WhateverTheLaneCountSays() {
        // A 32 lane reduction is one tree level shorter and one barrier weaker than a 64 lane one, and
        // the cost of that extra level is exactly what the wider wave was meant to amortize. So this
        // overrides the lane arithmetic that would otherwise pick wave64 here.
        Footprint reducing = new Footprint(64, 16384, 64, true, 1);
        Footprint plain = new Footprint(64, 16384, 64, false, 1);

        assertEquals(64, OccupancyPlanner.recommendWaveSize(plain, AmdArchitecture.RDNA3));
        assertEquals(32, OccupancyPlanner.recommendWaveSize(reducing, AmdArchitecture.RDNA3));

        Occupancy occupancy = OccupancyPlanner.occupancy(reducing, 32, AmdArchitecture.RDNA3);
        assertEquals(16, occupancy.wavesPerSimd());
        assertTrue(occupancy.fullOccupancy());
        assertEquals(0, occupancy.ldsBankConflicts());
    }

    @Test
    void aTieGoesToWave32() {
        // Same lanes either way at 96 registers, and wave32 has the shorter wave lifetime, ramps up
        // quicker after a barrier, wastes less on a partial tail, and touches half as many LDS banks.
        Footprint footprint = new Footprint(96, 16384, 64, false, 1);

        assertEquals(32, OccupancyPlanner.recommendWaveSize(footprint, AmdArchitecture.RDNA3));
    }

    @Test
    void gcnHasNoChoice() {
        // Vega is wave64 only, so there is nothing to weigh and the register file is never consulted.
        Footprint footprint = new Footprint(256, 16384, 64, false, 1);

        assertEquals(64, OccupancyPlanner.recommendWaveSize(footprint, AmdArchitecture.GCN5_VEGA));
        assertEquals(64, OccupancyPlanner.recommendWorkgroupSize(footprint, AmdArchitecture.GCN5_VEGA));
    }

    @Test
    void theWorkgroupSizeIsTheSmallestMultipleOfSixtyFour() {
        // AMD's guide: a multiple of 64 performs best across all generations. 64 is exactly one wave64 on
        // GCN and two wave32s on RDNA, so it is the one value right on every part targeted here.
        Footprint footprint = new Footprint(64, 16384, 64, false, 1);

        assertEquals(64, AmdArchitecture.WORKGROUP_SIZE);
        assertEquals(64, OccupancyPlanner.recommendWorkgroupSize(footprint, AmdArchitecture.RDNA3));
        assertEquals(64, OccupancyPlanner.recommendWorkgroupSize(footprint, AmdArchitecture.RDNA4));
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.wavesPerSimd(0, 32, RDNA3, G3));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.wavesPerSimd(32, 16, RDNA3, G3));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.wavesPerSimd(32, 32, 0, G3));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.wavesPerSimd(32, 32, RDNA3, 0));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.workgroupsPerCu(-1, 65536));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.workgroupsPerCu(1024, 0));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.ldsBankConflicts(32, 0));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.ldsBankConflicts(48, 1));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.occupancy(null, 32, AmdArchitecture.RDNA3));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.occupancy(new Footprint(64, 16384, 32, false, 1), 64,
                        AmdArchitecture.RDNA3), "a workgroup smaller than the wave");
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.occupancy(new Footprint(64, 65537, 64, false, 1), 32,
                        AmdArchitecture.RDNA3), "more than 64 KB of LDS");
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.occupancy(new Footprint(64, 0, 64, false, 1), 32,
                        AmdArchitecture.UNKNOWN), "no register file to plan against");
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.recommendWaveSize(new Footprint(64, 0, 64, false, 1), null));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.registersPerSimd(null));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.granuleFor(null));
    }
}
