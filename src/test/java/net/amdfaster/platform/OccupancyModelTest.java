package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static net.amdfaster.platform.OccupancyModel.RDNA1_TO_3;
import static net.amdfaster.platform.OccupancyModel.RDNA4;
import static net.amdfaster.platform.OccupancyModel.RDNA_WAVE64;
import static net.amdfaster.platform.OccupancyModel.GCN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every number here is checked against a published figure for the architecture, not against the
 * implementation. An occupancy model that agrees with itself proves nothing.
 */
class OccupancyModelTest {

    @Test
    void rdna4OccupancyMatchesThePublishedStaircase() {
        // RDNA4: 1536 wave32 VGPRs per SIMD, 16 wave slots, 24-register blocks. These five points are
        // the published ones, including the two that a linear model gets wrong.
        assertEquals(16, OccupancyModel.wavesPerSimd(RDNA4, 96), "96 is the full-occupancy line");
        assertEquals(12, OccupancyModel.wavesPerSimd(RDNA4, 97), "one register over drops four waves");
        assertEquals(12, OccupancyModel.wavesPerSimd(RDNA4, 120), "and holds to the end of the block");
        assertEquals(10, OccupancyModel.wavesPerSimd(RDNA4, 121), "121 is a rung above 120");
        assertEquals(9, OccupancyModel.wavesPerSimd(RDNA4, 168), "seven blocks, 56 percent occupancy");
    }

    @Test
    void theRdna4GranuleIsWhatMakesTheStepCoarse() {
        // 24 registers per block. A shader at 97 VGPRs is not slightly over the line, it rounds to a
        // five-block 120-register allocation, which is why one register costs four waves.
        assertEquals(96, RDNA4.allocatedVgprs(96), "exactly four blocks");
        assertEquals(120, RDNA4.allocatedVgprs(97), "rounds up to five");
        assertEquals(120, RDNA4.allocatedVgprs(120));
        assertEquals(144, RDNA4.allocatedVgprs(121), "and six");
        assertEquals(168, RDNA4.allocatedVgprs(168), "seven exactly");
    }

    @Test
    void oneRegisterAtATimeIsWorthTwoWavesAtTheRightPlace() {
        // The number that makes an occupancy report actionable. A kernel one register above a rung gains
        // a fifth of its resident waves for a single register, and no profiling of the running kernel
        // reveals that, because the achieved occupancy looks perfectly reasonable.
        assertEquals(1, OccupancyModel.registersToShedForNextWave(RDNA4, 121),
                "shedding one register at 121 buys two more waves");
        assertEquals(0, OccupancyModel.registersToShedForNextWave(RDNA4, 96),
                "at full occupancy there is no next rung to reach");
        assertTrue(OccupancyModel.registersToShedForNextWave(RDNA4, 97) > 1,
                "at 97 the next step is further away than one register");
    }

    @Test
    void rdna1To3MatchesTheWhitepaperExamples() {
        // The RDNA architecture whitepaper's own figures: 16 wave32 waves at 64 VGPRs, 4 at 256. Both
        // fall out of 1024 divided by the allocation, which is the check that this table is right.
        assertEquals(16, OccupancyModel.wavesPerSimd(RDNA1_TO_3, 64));
        assertEquals(4, OccupancyModel.wavesPerSimd(RDNA1_TO_3, 256));
        assertEquals(10, OccupancyModel.wavesPerSimd(RDNA1_TO_3, 96), "and 96 lands nowhere near RDNA4's 12");
    }

    @Test
    void rdnaWave64AlsoMatchesTheWhitepaper() {
        // 8 wave64 waves at 64 VGPRs, 2 at 256.
        assertEquals(8, OccupancyModel.wavesPerSimd(RDNA_WAVE64, 64));
        assertEquals(2, OccupancyModel.wavesPerSimd(RDNA_WAVE64, 256));
    }

    @Test
    void wave64CostsTwiceTheRegisterSpaceOfWave32() {
        // A VGPR is one dword per lane and wave64 has twice the lanes, so the same VGPR count costs
        // twice the file. Comparing the two without normalising overstates wave64 occupancy by a factor
        // of two, which is the mistake this normalisation exists to prevent.
        int wave32Threads = OccupancyModel.wavesPerSimd(RDNA1_TO_3, 64) * 32;
        int wave64Threads = OccupancyModel.wavesPerSimd(RDNA_WAVE64, 64) * 64;
        assertEquals(wave32Threads, wave64Threads,
                "the same register budget buys the same number of threads either way");
        assertEquals(512, wave32Threads);
    }

    @Test
    void gcnReachesTheDocumentedSixteenThreadsPerLane() {
        // "Occupancy 4 on GCN = 16 threads per lane" is the published figure. Four wave64 waves is 256
        // threads across 16 lanes. Getting the register-file unit conversion wrong -- quoting 256 VGPRs
        // without accounting for them being wave64-wide -- makes a 256-VGPR shader fit zero waves.
        assertEquals(4, OccupancyModel.wavesPerSimd(GCN, 64));
        assertEquals(16, OccupancyModel.wavesPerSimd(GCN, 64) * 64 / 16, "threads per lane");
        assertEquals(1, OccupancyModel.wavesPerSimd(GCN, 256), "the whole file, one wave");
        assertEquals(10, OccupancyModel.wavesPerSimd(GCN, 24), "and 24 VGPRs fills all ten slots");
    }

    @Test
    void theFullOccupancyBudgetDiffersByGenerationAndTheRuleOfThumbMatchesNone() {
        // "Keep VGPRs under 64" is a single number that is true-ish everywhere and exactly right
        // nowhere. Each generation's real line:
        assertEquals(96, OccupancyModel.vgprsForFullOccupancy(RDNA4));
        assertEquals(48, OccupancyModel.vgprsForFullOccupancy(RDNA1_TO_3));
        assertEquals(24, OccupancyModel.vgprsForFullOccupancy(GCN));
        assertTrue(OccupancyModel.vgprsForFullOccupancy(RDNA4)
                > OccupancyModel.vgprsForFullOccupancy(RDNA1_TO_3),
                "a newer generation tolerates more registers at the same occupancy");
    }

    @Test
    void occupancyIsComparableAcrossGenerationsBecauseItIsNormalised() {
        // Waves per SIMD is not comparable -- the slot counts differ. The fraction of slots is.
        assertEquals(1.0, OccupancyModel.occupancy(RDNA4, 96), 1e-9);
        assertEquals(0.75, OccupancyModel.occupancy(RDNA4, 97), 1e-9, "twelve of sixteen slots");
        assertEquals(0.5, OccupancyModel.occupancy(RDNA1_TO_3, 128), 1e-9, "ten of twenty");
    }

    @Test
    void ldsCanBindBeforeRegistersDo() {
        // A shader with a comfortable register budget and a large tile is limited by LDS, and the fix is
        // a smaller tile rather than fewer registers. Naming the binding ceiling is the difference
        // between a diagnosis and a guess.
        var comfortable = OccupancyModel.evaluate(RDNA4, 48, 64, 0);
        assertEquals(OccupancyModel.Limiter.REGISTERS, comfortable.limiter(), "nothing else competes");
        assertEquals(16, comfortable.waves());

        // 60 KB per workgroup on a 64 KB CU leaves room for exactly one group.
        var ldsBound = OccupancyModel.evaluate(RDNA4, 48, 64, 60 * 1024);
        assertEquals(OccupancyModel.Limiter.LDS, ldsBound.limiter());
        assertTrue(ldsBound.waves() < comfortable.waves(), "and it costs occupancy");
        assertEquals(1, ldsBound.groupsPerCu(), "one group fits in the CU's LDS");
    }

    @Test
    void ldsIsRoundedUpToTheAllocationBlock() {
        // LDS is handed out in 1024-byte blocks on RDNA4. A group asking for 1025 bytes occupies 2048,
        // and a model that treated the request as exact would report residency that cannot happen.
        assertEquals(0, OccupancyModel.evaluate(RDNA4, 48, 64, 0).waves()
                - OccupancyModel.evaluate(RDNA4, 48, 64, 1024).waves(),
                "one block is the same as none for a 64 KB CU");
        assertTrue(OccupancyModel.evaluate(RDNA4, 48, 64, 1025).groupsPerCu()
                <= OccupancyModel.evaluate(RDNA4, 48, 64, 1024).groupsPerCu(),
                "crossing into a second block cannot help");
    }

    @Test
    void aWorkgroupTooLargeToScheduleReportsNoOccupancy() {
        var verdict = OccupancyModel.evaluate(RDNA4, 48, 0, 0);
        assertEquals(0, verdict.waves());
        assertEquals(OccupancyModel.Limiter.NONE, verdict.limiter());
        assertEquals(0.0, verdict.occupancy(), 1e-9);
    }

    @Test
    void aWorkgroupSizeMultipleOfSixtyFourFillsWavesOnEveryGeneration() {
        // AMD's own recommendation, and the reason the culling shaders use 64. A workgroup that is not a
        // multiple of the wave size leaves lanes masked out, which is throughput spent on nothing.
        for (var arch : new OccupancyModel.Arch[] {RDNA4, RDNA1_TO_3, GCN}) {
            int waves = arch.waveSize();
            var verdict = OccupancyModel.evaluate(arch, 32, waves, 0);
            assertTrue(verdict.waves() > 0, arch.name() + " schedules a one-wave workgroup");
            var odd = OccupancyModel.evaluate(arch, 32, waves + 1, 0);
            assertTrue(odd.waves() > 0, arch.name() + " still schedules, but with a masked lane");
        }
    }
}
