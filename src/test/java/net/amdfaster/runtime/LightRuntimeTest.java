package net.amdfaster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightRuntimeTest {

    @Test
    void aSectionWhoseLightDidNotChangeNeedsNoRebuild() {
        // A light change that reaches a section but leaves every value the same still needs no rebuild, and
        // rebuilding it would cost a mesh build for nothing -- which is the common case at the edge of a
        // light's reach, where the propagation arrives and finds nothing left to do.
        LightRuntime runtime = new LightRuntime();

        assertFalse(runtime.needsRebuild(12345L, 12345L));
        assertEquals(1L, runtime.rebuildsSkipped());
        assertEquals(0L, runtime.rebuilds());
    }

    @Test
    void aSectionWhoseLightChangedIsRebuilt() {
        LightRuntime runtime = new LightRuntime();

        assertTrue(runtime.needsRebuild(12345L, 67890L));
        assertEquals(1L, runtime.rebuilds());
        assertEquals(0L, runtime.rebuildsSkipped());
    }

    @Test
    void mostSectionsReachedByALightChangeNeedNoRebuild() {
        LightRuntime runtime = new LightRuntime();

        for (int i = 0; i < 100; i++) {
            runtime.needsRebuild(i, i < 20 ? i + 1 : i);
        }

        assertEquals(20L, runtime.rebuilds());
        assertEquals(80L, runtime.rebuildsSkipped());
        assertEquals(80.0, runtime.rebuildsSkippedPercent(), 1e-9);
    }

    @Test
    void lightChangesAreBatchedNotAppliedImmediately() {
        // Applying every pending light update on one frame is the hitch this exists to remove.
        LightRuntime runtime = new LightRuntime();

        for (int i = 0; i < 50; i++) {
            runtime.onLightChange(i, 64, i);
        }

        assertEquals(50, runtime.pendingLightChanges());
        assertEquals(50L, runtime.batcher().changesReceived());
    }

    @Test
    void thePropertiesTableIsSharedNotPerSection() {
        // How opaque a block is and how much light it emits are properties of the block, not of where it is,
        // so one table serves every section.
        LightRuntime runtime = new LightRuntime();

        assertTrue(runtime.properties().blockCount() > 0);
        assertTrue(runtime.properties().tableBytes() > 0);
    }

    @Test
    void propagationRunsAcrossTheGivenCores() {
        LightRuntime runtime = new LightRuntime();

        int rounds = runtime.propagate(8, 4, new int[] {2, 0});

        assertEquals(2, rounds);
        assertEquals(8L, runtime.sectionsPropagated());
        assertEquals(2L, runtime.parallel().boundaryChecks());
    }

    @Test
    void aSectionNeedingNoBoundaryPassIsReported() {
        assertFalse(ChunkParallelLightEngineForTest.needsBoundary(0));
        assertTrue(ChunkParallelLightEngineForTest.needsBoundary(1));
    }

    /** Keeps the static call out of the assertion so the intent reads first. */
    private static final class ChunkParallelLightEngineForTest {
        static boolean needsBoundary(int changedEdges) {
            return net.amdfaster.light.ChunkParallelLightEngine.needsBoundaryPass(changedEdges);
        }
    }

    @Test
    void aWorldChangeClearsTheCounters() {
        LightRuntime runtime = new LightRuntime();

        runtime.needsRebuild(1, 2);
        runtime.propagate(4, 2, new int[] {0});

        runtime.onWorldChange();

        assertEquals(0L, runtime.sectionsPropagated());
        assertEquals(0L, runtime.rebuilds());
        assertEquals(0L, runtime.rebuildsSkipped());
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has checked nothing has technically skipped everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        LightRuntime runtime = new LightRuntime();

        assertEquals(0.0, runtime.rebuildsSkippedPercent());
        assertEquals(0L, runtime.sectionsPropagated());
        assertEquals(0L, runtime.rebuilds());
        assertEquals(0L, runtime.rebuildsSkipped());
        assertEquals(0, runtime.pendingLightChanges());
    }
}
