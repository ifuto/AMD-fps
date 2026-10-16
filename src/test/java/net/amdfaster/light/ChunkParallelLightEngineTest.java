package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkParallelLightEngineTest {

    @Test
    void sectionsAreFilledAcrossTheCores() {
        // Placing one block can dirty a dozen sections, and the fill visits every block the light reaches.
        // On one thread that is a hitch at the moment the player placed the block.
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        engine.propagate(8, 8, new int[] {0});

        assertEquals(8L, engine.sectionsFilled());
        assertEquals(1L, engine.converged());
    }

    @Test
    void aBoundaryChangeCostsAnotherRound() {
        // Light crossing from one section into the next is a value the receiving section did not have while
        // it was being filled, so the difference has to be propagated in.
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        int rounds = engine.propagate(8, 8, new int[] {3, 1, 0});

        assertEquals(3, rounds);
        assertEquals(4L, engine.boundaryChecks());
        assertEquals(1L, engine.converged());
    }

    @Test
    void aFillThatRunsOutOfRoundsIsReported() {
        // Lighting is not an approximation: a block one level too dark looks wrong next to its neighbours,
        // and a seam along a boundary is a straight line of mislit blocks across the world.
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        engine.propagate(8, 8, new int[] {5, 5, 5, 5});

        assertEquals(1L, engine.notConverged());
        assertEquals(0L, engine.converged());
    }

    @Test
    void tooManyRoundsIsRejected() {
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        assertThrows(IllegalArgumentException.class,
                () -> engine.propagate(8, 8, new int[] {1, 1, 1, 1, 1}));
        assertEquals(4, ChunkParallelLightEngine.MAX_ROUNDS);
    }

    @Test
    void aSectionThatDidNotChangeSkipsTheBoundaryPass() {
        // Most of the time a fill stays inside its section -- a torch in the middle of a cave does not reach
        // the walls. Re-running the pass for sections that did not change would cost as much as the
        // parallelism saved.
        assertTrue(ChunkParallelLightEngine.needsBoundaryPass(1));
        assertFalse(ChunkParallelLightEngine.needsBoundaryPass(0));
    }

    @Test
    void parallelFillingSavesMostOfTheTime() {
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        engine.propagate(16, 8, new int[] {4});

        // Sixteen sections over eight cores is two per core, not eight.
        assertEquals(2 * ChunkParallelLightEngine.MS_PER_SECTION
                + 4 * ChunkParallelLightEngine.MS_PER_BOUNDARY,
                engine.msParallel(16, 8), 1e-9);
        assertEquals(2.6, engine.msParallel(16, 8), 1e-9,
                "2.4 of filling plus 0.2 of boundary checks");
        assertEquals(16 * ChunkParallelLightEngine.MS_PER_SECTION,
                engine.msSerial(16), 1e-9);
        assertEquals(19.2, engine.msSerial(16), 1e-9);
        assertEquals(86.46, engine.savedPercent(16, 8), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        assertThrows(IllegalArgumentException.class,
                () -> engine.propagate(-1, 8, new int[] {0}));
        assertThrows(IllegalArgumentException.class,
                () -> engine.propagate(8, 0, new int[] {0}));
        assertThrows(IllegalArgumentException.class,
                () -> engine.propagate(8, 8, null));
    }

    @Test
    void aFreshEngineReportsZeroNotNaN() {
        // An engine that has filled nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        ChunkParallelLightEngine engine = new ChunkParallelLightEngine();

        assertEquals(0.0, engine.savedPercent(0, 8));
        assertEquals(0.0, engine.msSerial(0), 1e-12);
        assertEquals(0L, engine.sectionsFilled());
        assertEquals(0L, engine.rounds());
        assertEquals(0L, engine.converged());
        assertEquals(0L, engine.notConverged());
        assertEquals(16, ChunkParallelLightEngine.SECTION_BLOCKS);
    }
}
