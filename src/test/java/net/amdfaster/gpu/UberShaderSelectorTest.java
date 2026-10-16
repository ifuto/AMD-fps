package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UberShaderSelectorTest {

    @Test
    void aSmallBatchOfManyMaterialsUsesOneShader() {
        // A branch all the lanes take costs one test and no divergence, so it costs less than the bind it
        // replaced.
        UberShaderSelector selector = new UberShaderSelector();

        assertTrue(selector.useUberShader(100, 10, false));
        assertEquals(1L, selector.branched());
        assertEquals(0L, selector.diverged());
    }

    @Test
    void aLargeBatchIsCheaperToBind() {
        // Ten variants cost nine binds at 0.006 ms; a thousand draws cost 0.4 ms of branches, which is more.
        UberShaderSelector selector = new UberShaderSelector();

        assertFalse(selector.useUberShader(1000, 10, false));
        assertEquals(9L, selector.bound());
    }

    @Test
    void aMixedBatchIsMuchMoreExpensiveToBranch() {
        // Both sides execute for every lane and the shader does the work of both variants, which is worse than
        // either separate pipeline.
        UberShaderSelector selector = new UberShaderSelector();

        assertFalse(selector.useUberShader(100, 10, true), "0.2 ms of divergence against 0.054 of binds");
        assertEquals(0L, selector.diverged(), "and it was not chosen, so nothing diverged");
        assertEquals(9L, selector.bound());
    }

    @Test
    void aFewMaterialsWithManyDrawsIsWorthBranching() {
        UberShaderSelector selector = new UberShaderSelector();

        assertTrue(selector.useUberShader(20, 3, false), "0.008 of branches against 0.012 of binds");
        assertEquals(1L, selector.branched());
    }

    @Test
    void aPropertyThatRunsOftenBelongsInThePipeline() {
        // Leaving it dynamic to save a pipeline would trade a one-time compilation saving for a per-fragment
        // branch, which is the wrong way round for anything that runs a million times a frame.
        UberShaderSelector selector = new UberShaderSelector();

        assertTrue(selector.makeStatic(2, 100_000));
        assertFalse(selector.makeStatic(2, 1), "runs once, so the branch costs nothing");
    }

    @Test
    void tooManyCombinationsIsNotWorthThePipelines() {
        // A material with eight independent boolean properties is two hundred and fifty six pipelines, most of
        // which are never used.
        UberShaderSelector selector = new UberShaderSelector();

        assertTrue(selector.makeStatic(1 << UberShaderSelector.MAX_STATIC_PROPERTIES, 100_000));
        assertFalse(selector.makeStatic((1 << UberShaderSelector.MAX_STATIC_PROPERTIES) + 1, 100_000));
        assertEquals(4, UberShaderSelector.MAX_STATIC_PROPERTIES);
    }

    @Test
    void aSceneIsMeasuredAcrossItsBatches() {
        UberShaderSelector selector = new UberShaderSelector();

        selector.useUberShader(100, 10, false);
        selector.useUberShader(1000, 10, false);

        assertEquals(1100L, selector.draws());
        assertEquals(1L, selector.branched());
        assertEquals(9L, selector.bound());
        assertEquals(84.51, selector.savedPercent(), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        UberShaderSelector selector = new UberShaderSelector();

        assertThrows(IllegalArgumentException.class, () -> selector.useUberShader(-1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> selector.useUberShader(1, -1, false));
        assertThrows(IllegalArgumentException.class, () -> selector.useUberShader(1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> selector.makeStatic(0, 10));
        assertThrows(IllegalArgumentException.class, () -> selector.makeStatic(2, -1));
    }

    @Test
    void aFreshSelectorReportsZeroNotNaN() {
        // A selector that has decided nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        UberShaderSelector selector = new UberShaderSelector();

        assertEquals(0.0, selector.savedPercent());
        assertEquals(0L, selector.draws());
        assertEquals(0L, selector.bound());
        assertEquals(0L, selector.branched());
        assertEquals(0.006, UberShaderSelector.MS_PER_BIND, 1e-12);
    }
}
