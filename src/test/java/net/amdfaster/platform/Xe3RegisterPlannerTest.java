package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Xe3RegisterPlannerTest {

    @Test
    void theRequestRoundsUpToABlock() {
        // Asking for 100 costs the same as asking for 128. A plan that reported the raw request would
        // understate what is reserved and compute an occupancy the hardware cannot deliver.
        assertEquals(128, Xe3RegisterPlanner.allocatedEntries(100));
        assertEquals(128, Xe3RegisterPlanner.allocatedEntries(128));
        assertEquals(160, Xe3RegisterPlanner.allocatedEntries(129));
        assertEquals(32, Xe3RegisterPlanner.BLOCK_ENTRIES);
    }

    @Test
    void occupancyFollowsTheAllocationNotTheRequest() {
        // The difference is the rounding, and on a kernel near a block boundary it is the whole difference
        // between two occupancy levels.
        assertEquals(1.0, Xe3RegisterPlanner.occupancy(32), 1e-12);
        assertEquals(0.25, Xe3RegisterPlanner.occupancy(128), 1e-12);
        assertEquals(0.1875, Xe3RegisterPlanner.occupancy(129), 1e-12,
                "one register more costs a whole block");
        assertEquals(0.1875, Xe3RegisterPlanner.occupancy(140), 1e-12);
        assertEquals(0.1875, Xe3RegisterPlanner.occupancy(160), 1e-12);
    }

    @Test
    void flexibleAllocationBeatsTheLegacyTwoModes() {
        // A kernel needing more than the normal budget had to take the large one and lose half its
        // occupancy for registers it did not need.
        assertEquals(0.125, Xe3RegisterPlanner.legacyOccupancy(140), 1e-12,
                "the old large mode");
        assertEquals(0.1875, Xe3RegisterPlanner.occupancy(140), 1e-12,
                "and five blocks instead of eight");
        assertTrue(Xe3RegisterPlanner.occupancy(140) > Xe3RegisterPlanner.legacyOccupancy(140));
    }

    @Test
    void aKernelUnderTheNormalBudgetIsUnchanged() {
        assertEquals(Xe3RegisterPlanner.legacyOccupancy(100),
                Xe3RegisterPlanner.occupancy(100), 1e-12);
        assertEquals(0.25, Xe3RegisterPlanner.occupancy(100), 1e-12);
    }

    @Test
    void theFullOccupancyBudgetIsReported() {
        assertEquals(32, Xe3RegisterPlanner.registersForFullOccupancy());
        assertEquals(64, Xe3RegisterPlanner.MAX_THREADS);
        assertEquals(2048, Xe3RegisterPlanner.FILE_ENTRIES);
    }

    @Test
    void sheddingToTheNextLevelIsQuantified() {
        // A kernel that sheds a few registers to get under a boundary gains a whole occupancy level, while
        // one that sheds a few without crossing it gains nothing.
        assertEquals(12, Xe3RegisterPlanner.registersToShedForNextLevel(140),
                "140 -> 128 crosses from twelve threads to sixteen");
        assertEquals(0, Xe3RegisterPlanner.registersToShedForNextLevel(32),
                "already at full occupancy");
    }

    @Test
    void sheddingWithinABlockGainsNothing() {
        // 128 is already a block boundary, so shedding a register or thirty-one of them changes nothing.
        assertEquals(32, Xe3RegisterPlanner.registersToShedForNextLevel(128),
                "the whole next block has to go");
        assertEquals(Xe3RegisterPlanner.occupancy(128),
                Xe3RegisterPlanner.occupancy(97), 1e-12);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Xe3RegisterPlanner.allocatedEntries(0));
        assertThrows(IllegalArgumentException.class, () -> Xe3RegisterPlanner.allocatedEntries(-1));
    }

    @Test
    void theConstantsMatchTheArchitecture() {
        assertEquals(64 * 1024, Xe3RegisterPlanner.FILE_BYTES);
        assertEquals(32, Xe3RegisterPlanner.BYTES_PER_ENTRY);
        assertEquals(128, Xe3RegisterPlanner.LEGACY_NORMAL);
        assertEquals(256, Xe3RegisterPlanner.LEGACY_LARGE);
        assertEquals(Xe3RegisterPlanner.FILE_BYTES / Xe3RegisterPlanner.BYTES_PER_ENTRY,
                Xe3RegisterPlanner.FILE_ENTRIES);
    }
}
