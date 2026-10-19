package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossModParticleBudgetTest {

    private static final String VANILLA = "minecraft";
    private static final String MOD_A = "magicmod";
    private static final String MOD_B = "ambientmod";

    @Test
    void aSingleSourceMayUseTheWholeBudget() {
        // The share grows as sources go quiet, so one active source may use all of it.
        CrossModParticleBudget budget = new CrossModParticleBudget();

        for (int i = 0; i < CrossModParticleBudget.TOTAL_BUDGET; i++) {
            assertTrue(budget.allow(VANILLA), "particle " + i);
        }

        assertEquals(CrossModParticleBudget.TOTAL_BUDGET, budget.total());
        assertEquals(1, budget.sources());
        assertEquals(500, CrossModParticleBudget.shareFor(1));
    }

    @Test
    void tenSourcesEachGetATenth() {
        // A mod that adds ambient effects everywhere would otherwise starve one whose particles are the point
        // of what it does.
        CrossModParticleBudget budget = new CrossModParticleBudget();

        for (int i = 0; i < 10; i++) {
            assertTrue(budget.allow("mod" + i));
        }

        assertEquals(50, CrossModParticleBudget.shareFor(10));
        assertEquals(10, budget.sources());
        assertEquals(10, budget.total());
    }

    @Test
    void aSourceIsRefusedPastItsShare() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        budget.allow(VANILLA);
        budget.allow(MOD_A);

        int share = budget.currentShare();
        assertEquals(250, share, "two active sources");

        for (int i = 1; i < share; i++) {
            assertTrue(budget.allow(VANILLA));
        }
        assertFalse(budget.allow(VANILLA), "at its share now");
        assertEquals(1L, budget.refusedShare());
        assertTrue(budget.allow(MOD_A), "and the other source is unaffected");
    }

    @Test
    void theVanillaParticlesAreInTheSameBudget() {
        // Treating them separately would mean the total is the game's limit plus every mod's, which is the
        // problem again with one more term.
        CrossModParticleBudget budget = new CrossModParticleBudget();

        budget.allow(VANILLA);
        budget.allow(MOD_A);
        budget.allow(MOD_B);

        assertEquals(3, budget.total());
        assertEquals(3, budget.sources());
    }

    @Test
    void theWholeBudgetIsNeverExceeded() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        for (int i = 0; i < 2000; i++) {
            budget.allow(VANILLA);
        }

        assertEquals(CrossModParticleBudget.TOTAL_BUDGET, budget.total());
        assertTrue(budget.refusedTotal() > 0);
        assertEquals(500L, budget.spawned());
    }

    @Test
    void releasingMakesRoomAgain() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        budget.allow(VANILLA);
        budget.allow(VANILLA);
        assertEquals(2, budget.total());

        budget.release(VANILLA);

        assertEquals(1, budget.total());
        assertEquals(1, budget.heldBy(VANILLA));
        assertTrue(budget.allow(VANILLA));
    }

    @Test
    void theLastParticleFromASourceRemovesIt() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        budget.allow(MOD_A);
        budget.release(MOD_A);

        assertEquals(0, budget.sources(), "an idle source stops taking a share");
        assertEquals(500, CrossModParticleBudget.shareFor(1));
    }

    @Test
    void aFloodIsCappedAndTheSavingIsReported() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        for (int i = 0; i < 1000; i++) {
            budget.allow(VANILLA);
        }

        assertEquals(1000L, budget.requested());
        assertEquals(500L, budget.spawned());
        assertEquals(1.0, budget.msCapped(), 1e-9);
        assertEquals(2.0, budget.msWithoutBudget(), 1e-9);
        assertEquals(50.0, budget.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        CrossModParticleBudget budget = new CrossModParticleBudget();

        assertThrows(IllegalArgumentException.class, () -> budget.allow(null));
        assertThrows(IllegalArgumentException.class, () -> budget.allow(""));
        assertThrows(IllegalArgumentException.class, () -> CrossModParticleBudget.shareFor(0));
        assertThrows(IllegalStateException.class, () -> budget.release(MOD_A));
    }

    @Test
    void aFreshBudgetReportsZeroNotNaN() {
        // A budget that has been asked for nothing has technically removed everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        CrossModParticleBudget budget = new CrossModParticleBudget();

        assertEquals(0.0, budget.savedPercent());
        assertEquals(0.0, budget.msCapped(), 1e-12);
        assertEquals(0.0, budget.msWithoutBudget(), 1e-12);
        assertEquals(0L, budget.requested());
        assertEquals(0, budget.total());
        assertEquals(0, budget.sources());
        assertEquals(500, CrossModParticleBudget.TOTAL_BUDGET);
    }

    @Test
    void clearingEmptiesTheBudgetWithoutRewritingWhatHappened() {
        // Leaving a world drops every particle without removing them one at a time, so the grants they
        // hold would otherwise still be counted in the next one -- and a budget that starts full refuses
        // every particle the new world tries to draw.
        CrossModParticleBudget budget = new CrossModParticleBudget();
        for (int i = 0; i < 50; i++) {
            assertTrue(budget.allow(MOD_A));
        }
        assertEquals(50, budget.total());
        assertEquals(1, budget.sources());

        budget.clear();

        assertEquals(0, budget.total());
        assertEquals(0, budget.sources(), "no source is holding anything any more");
        assertEquals(50L, budget.spawned(), "what was granted still happened; only the live count resets");
        assertEquals(50L, budget.requested());
        assertTrue(budget.allow(MOD_A), "and the next world can draw again");
        assertEquals(1, budget.total());
    }
}
