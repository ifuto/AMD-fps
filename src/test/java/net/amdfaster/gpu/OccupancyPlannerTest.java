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
 * model that stops reproducing the whitepaper or the occupancy table fails rather than quietly guessing.
 */
class OccupancyPlannerTest {

    @Test
    void theWhitepaperDataPointsOnA1024RegisterFile() {
        // RDNA Architecture whitepaper: 4x wave32 at 256 VGPRs, 16x at 64, 2x wave64 at 256, 8x at 64.
        assertEquals(4, OccupancyPlanner.wavesPerSimd(256, 32, 1024));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(64, 32, 1024));
        assertEquals(2, OccupancyPlanner.wavesPerSimd(256, 64, 1024));
        assertEquals(8, OccupancyPlanner.wavesPerSimd(64, 64, 1024));
    }

    @Test
    void theRdna34OccupancyTableOnA1536RegisterFile() {
        assertEquals(16, OccupancyPlanner.wavesPerSimd(96, 32, 1536));
        assertEquals(12, OccupancyPlanner.wavesPerSimd(128, 32, 1536));
        assertEquals(8, OccupancyPlanner.wavesPerSimd(192, 32, 1536));
        assertEquals(6, OccupancyPlanner.wavesPerSimd(256, 32, 1536));
    }

    @Test
    void occupancyIsAClampedStaircaseNotASlope() {
        // Sixteen slots is the ceiling, so a cheap shader does not get more waves by using fewer
        // registers -- it already filled them.
        assertEquals(16, OccupancyPlanner.wavesPerSimd(1, 32, 1536));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(48, 32, 1536));
        assertEquals(16, OccupancyPlanner.wavesPerSimd(96, 32, 1536));
        // One register past the full-occupancy point and four slots go.
        assertEquals(12, OccupancyPlanner.wavesPerSimd(97, 32, 1536));
    }

    @Test
    void wave64WinsWhileTheShaderIsCheapAndLosesOnceItIsNot() {
        // The two sizes compete on lanes in flight, not on waves: a wave64 has twice the lanes but takes
        // twice the register slots, so which one is ahead depends entirely on the register count.
        assertEquals(512, OccupancyPlanner.lanesInFlight(64, 32, 1536));
        assertEquals(768, OccupancyPlanner.lanesInFlight(64, 64, 1536), "wave64 ahead at 64 VGPRs");

        assertEquals(512, OccupancyPlanner.lanesInFlight(96, 32, 1536));
        assertEquals(512, OccupancyPlanner.lanesInFlight(96, 64, 1536), "level at 96");

        assertEquals(384, OccupancyPlanner.lanesInFlight(128, 32, 1536));
        assertEquals(384, OccupancyPlanner.lanesInFlight(128, 64, 1536), "still level, half the waves");
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
    void theRegisterFileIsPerGeneration() {
        assertEquals(1024, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA1));
        assertEquals(1024, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA2));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA3));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA35));
        assertEquals(1536, OccupancyPlanner.registersPerSimd(AmdArchitecture.RDNA4));
        assertEquals(256, OccupancyPlanner.registersPerSimd(AmdArchitecture.GCN5_VEGA));
        assertEquals(0, OccupancyPlanner.registersPerSimd(AmdArchitecture.UNKNOWN), "nothing to plan against");
    }

    @Test
    void aCheapShaderWithoutReductionGetsWave64() {
        Footprint footprint = new Footprint(64, 16384, 64, false, 1);

        assertEquals(64, OccupancyPlanner.recommendWaveSize(footprint, AmdArchitecture.RDNA3));

        Occupancy occupancy = OccupancyPlanner.occupancy(footprint, 64, AmdArchitecture.RDNA3);
        assertEquals(12, occupancy.wavesPerSimd());
        assertEquals(768, occupancy.lanesInFlight());
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
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.wavesPerSimd(0, 32, 1536));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.wavesPerSimd(32, 16, 1536));
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.wavesPerSimd(32, 32, 0));
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
                () -> OccupancyPlanner.recommendWaveSize(new Footprint(64, 0, 64, false, 1), null));
        assertThrows(IllegalArgumentException.class,
                () -> OccupancyPlanner.occupancy(new Footprint(64, 0, 64, false, 1), 32,
                        AmdArchitecture.UNKNOWN), "no register file to plan against");
        assertThrows(IllegalArgumentException.class, () -> OccupancyPlanner.registersPerSimd(null));
    }
}
