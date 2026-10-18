package net.amdfaster.explosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.explosion.BlastPhasePlanner.Blast;
import net.amdfaster.explosion.BlastPhasePlanner.Schedule;
import org.junit.jupiter.api.Test;

/**
 * The reach figures are checked against the published blast radii, so a change to the attenuation model
 * that stops landing on them fails rather than quietly measuring something else.
 */
class BlastPhasePlannerTest {

    @Test
    void theCheckpointCountFloorsRatherThanRounds() {
        // TNT at power 4 with the strongest intensity roll: 5.2 over 0.225 is 23.11, so 23 checkpoints.
        // Rounding up would let the ray reach a block the game never tests.
        assertEquals(23, BlastPhasePlanner.checkpoints(4.0, 1.3));
        assertEquals(17, BlastPhasePlanner.checkpoints(4.0, 1.0));
        assertEquals(12, BlastPhasePlanner.checkpoints(4.0, 0.7));
    }

    @Test
    void theReachReproducesThePublishedBlastRadii() {
        assertEquals(6.9, BlastPhasePlanner.reach(4.0, 1.3), 1e-9, "primed TNT");
        assertEquals(5.1, BlastPhasePlanner.reach(3.0, 1.3), 1e-9, "creeper");
        assertEquals(8.4, BlastPhasePlanner.reach(5.0, 1.3), 1e-9, "bed in the Nether");
        assertEquals(10.2, BlastPhasePlanner.reach(6.0, 1.3), 1e-9, "end crystal / charged creeper");
        assertEquals(2.4, BlastPhasePlanner.reach(1.5, 1.3), 1e-9, "ghast fireball");
    }

    @Test
    void theReachBandIsWideBecauseTheRollIsPerRay() {
        // The intensity factor is drawn per ray, not per explosion, so a single blast's rays reach
        // anywhere in this band. Planning against the mean would understate the region that can be
        // destroyed.
        assertEquals(3.6, BlastPhasePlanner.minReach(4.0), 1e-9);
        assertEquals(5.1, BlastPhasePlanner.reach(4.0, 1.0), 1e-9);
        assertEquals(6.9, BlastPhasePlanner.maxReach(4.0), 1e-9);
        assertTrue(BlastPhasePlanner.maxReach(4.0) > BlastPhasePlanner.minReach(4.0));
    }

    @Test
    void overlappingBlastsCannotShareASnapshot() {
        // A destroys 823 blocks, then B casts its rays and has to see A's holes. Two TNT twelve blocks
        // apart have overlapping reach -- 6.9 plus 6.9 is 13.8 -- so B reads blocks A may have removed.
        Blast a = new Blast(0, 0, 0, 4.0);
        Blast near = new Blast(0, 0, 12, 4.0);
        Blast far = new Blast(0, 0, 20, 4.0);

        assertFalse(BlastPhasePlanner.canShareSnapshot(a, near));
        assertTrue(BlastPhasePlanner.canShareSnapshot(a, far));
        // Symmetric, since neither ordering is safe when they overlap.
        assertFalse(BlastPhasePlanner.canShareSnapshot(near, a));
        assertTrue(BlastPhasePlanner.canShareSnapshot(far, a));
    }

    @Test
    void weakerExplosionsShareAtAShorterSeparation() {
        // Two ghast fireballs reach 2.4 each, so anything past 4.8 blocks apart is safe to batch.
        Blast a = new Blast(0, 0, 0, 1.5);

        assertFalse(BlastPhasePlanner.canShareSnapshot(a, new Blast(4, 0, 0, 1.5)));
        assertTrue(BlastPhasePlanner.canShareSnapshot(a, new Blast(6, 0, 0, 1.5)));
    }

    @Test
    void verticalSeparationCountsToo() {
        // The test is on distance, not on horizontal distance, so a blast directly above at the same
        // horizontal position is just as disjoint as one far away sideways.
        Blast a = new Blast(0, 64, 0, 4.0);

        assertFalse(BlastPhasePlanner.canShareSnapshot(a, new Blast(0, 76, 0, 4.0)));
        assertTrue(BlastPhasePlanner.canShareSnapshot(a, new Blast(0, 84, 0, 4.0)));
    }

    @Test
    void aChainNeedsOnePhasePerBlast() {
        // Three TNT two blocks apart in a line. Each overlaps its neighbours, so no two can share and the
        // tick needs three ray phases with two invalidations between them.
        Schedule schedule = BlastPhasePlanner.schedule(
                new Blast(0, 0, 0, 4.0),
                new Blast(0, 0, 2, 4.0),
                new Blast(0, 0, 4, 4.0));

        assertEquals(3, schedule.groups());
        assertEquals(0, schedule.shared());
        assertEquals(2 * 823, schedule.invalidations(), "823 destroyed blocks per phase boundary");
        assertFalse(schedule.singlePhase());
    }

    @Test
    void scatteredDetonationsShareOnePhase() {
        // Three TNT well outside each other's reach. One snapshot serves all three and nothing has to be
        // invalidated, which is the case the planner exists to recognise.
        Schedule schedule = BlastPhasePlanner.schedule(
                new Blast(0, 0, 0, 4.0),
                new Blast(0, 0, 20, 4.0),
                new Blast(0, 0, 40, 4.0));

        assertEquals(1, schedule.groups());
        assertEquals(2, schedule.shared());
        assertEquals(0, schedule.invalidations());
        assertTrue(schedule.singlePhase());
        assertEquals(2L * ChainReactionDeduplicator.LOOKUPS_PER_BLAST, schedule.lookupsShared());
    }

    @Test
    void groupingIsGreedyNotOptimalAndThatIsFine() {
        // The middle blast overlaps both outer ones but they do not overlap each other, so a minimum
        // grouping would still need two phases. Greedy reaches the same answer here, and where it does
        // not the cost is one extra invalidation rather than a wrong result.
        Schedule schedule = BlastPhasePlanner.schedule(
                new Blast(0, 0, 0, 4.0),
                new Blast(0, 0, 10, 4.0),
                new Blast(0, 0, 30, 4.0));

        assertEquals(2, schedule.groups());
        assertEquals(1, schedule.shared());
        assertEquals(823, schedule.invalidations());
    }

    @Test
    void anEmptyTickNeedsNoPhases() {
        Schedule schedule = BlastPhasePlanner.schedule();

        assertEquals(0, schedule.groups());
        assertEquals(0, schedule.shared());
        assertEquals(0, schedule.invalidations());
        assertTrue(schedule.singlePhase(), "nothing to invalidate");
        assertEquals(0L, schedule.lookupsShared());
    }

    @Test
    void aSingleBlastNeverInvalidates() {
        // Within one explosion nothing is written during the ray phase, so the cache cannot go stale
        // under itself. That is what makes the sharing safe at all.
        Schedule schedule = BlastPhasePlanner.schedule(new Blast(0, 0, 0, 4.0));

        assertEquals(1, schedule.groups());
        assertEquals(0, schedule.shared());
        assertEquals(0, schedule.invalidations());
        assertTrue(schedule.singlePhase());
    }

    @Test
    void aChainSpreadsOverTwentyOneTicks() {
        // TNT primed by an explosion fuses 10 to 30 ticks rather than the 80 of a direct priming, so a
        // chain does not produce same-tick blasts on its own. Two coinciding means independent fuses that
        // happened to match, which is one chance in twenty-one.
        assertEquals(21, BlastPhasePlanner.chainFuseSpan());
        assertEquals(1.0 / 21.0, BlastPhasePlanner.chainFuseCollisionChance(), 1e-12);
        assertEquals(0.047619047619047616, BlastPhasePlanner.chainFuseCollisionChance(), 1e-15);
        assertEquals(80, BlastPhasePlanner.DIRECT_FUSE_TICKS);
        assertTrue(BlastPhasePlanner.CHAIN_FUSE_MAX_TICKS < BlastPhasePlanner.DIRECT_FUSE_TICKS);
    }

    @Test
    void theConstantsAreTheDocumentedOnes() {
        assertEquals(0.3, BlastPhasePlanner.RAY_STEP, 1e-12);
        assertEquals(0.22500001, BlastPhasePlanner.ATTENUATION_PER_STEP, 1e-12);
        assertEquals(0.7, BlastPhasePlanner.MIN_INTENSITY_FACTOR, 1e-12);
        assertEquals(1.3, BlastPhasePlanner.MAX_INTENSITY_FACTOR, 1e-12);
        assertEquals(1352, BlastPhasePlanner.RAYS_PER_BLAST);
        assertEquals(823, BlastPhasePlanner.BLOCKS_PER_BLAST);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.checkpoints(0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.checkpoints(-1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.checkpoints(4.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.checkpoints(4.0, 1.5));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.reach(4.0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.maxReach(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> BlastPhasePlanner.canShareSnapshot(null, new Blast(0, 0, 0, 4.0)));
        assertThrows(IllegalArgumentException.class,
                () -> BlastPhasePlanner.canShareSnapshot(new Blast(0, 0, 0, 4.0), null));
        assertThrows(IllegalArgumentException.class, () -> BlastPhasePlanner.schedule((Blast[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> BlastPhasePlanner.schedule(new Blast(0, 0, 0, 4.0), null));
    }
}
