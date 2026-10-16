package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuTerrainGeneratorTest {

    @Test
    void aMachineWithNoGpuGeneratesOnTheCpu() {
        // No compute support, a driver that will not initialise, a virtual machine. The CPU path is the
        // reference, so the game still runs and the terrain is identical.
        GpuTerrainGenerator generator = new GpuTerrainGenerator(false);

        assertFalse(generator.shouldDispatch(100));
        assertFalse(generator.usesGpu());
        assertEquals(100L, generator.onCpu());
        assertEquals(0L, generator.dispatched());
    }

    @Test
    void aMachineWithAGpuDispatches() {
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        assertTrue(generator.shouldDispatch(100));
        assertEquals(100L, generator.dispatched());
        assertEquals(100L, generator.inFlight());
    }

    @Test
    void anEmptyBatchIsNotWorthDispatching() {
        // The dispatch has a fixed cost, and for a small batch that cost exceeds the computation.
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        assertFalse(generator.shouldDispatch(0));
        assertEquals(0L, generator.dispatched());
    }

    @Test
    void aChunkIsGeneratedOnceEvenWhenDispatched() {
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        generator.shouldDispatch(10);
        generator.collect(10);

        assertEquals(10L, generator.completed());
        assertEquals(0L, generator.inFlight());
    }

    @Test
    void collectingMoreThanWasDispatchedIsRejected() {
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        generator.shouldDispatch(5);

        assertThrows(IllegalStateException.class, () -> generator.collect(6));
    }

    @Test
    void theCpuTimeAvoidedIsThePointOfTheExercise() {
        // Moving it off the CPU leaves that core free for the meshing, lighting and entity work that cannot
        // be parallelised the same way.
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        generator.shouldDispatch(100);

        assertEquals(100 * GpuTerrainGenerator.MS_CPU, generator.msCpuAvoided(), 1e-9);
        assertEquals(1970.0, generator.msCpuAvoided(), 1e-9);
        assertEquals(100 * GpuTerrainGenerator.MS_GPU + GpuTerrainGenerator.MS_ROUND_TRIP,
                generator.msGpuSpent(), 1e-9);
        assertEquals(100.0, generator.savedPercent(), 1e-9);
    }

    @Test
    void aMixedWorkloadReportsTheShareMoved() {
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        generator.shouldDispatch(75);
        generator.shouldDispatch(0);
        generator.collect(75);

        assertEquals(75L, generator.dispatched());
        assertEquals(0L, generator.onCpu());
        assertEquals(100.0, generator.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        assertThrows(IllegalArgumentException.class, () -> generator.shouldDispatch(-1));
        assertThrows(IllegalArgumentException.class, () -> generator.collect(-1));
    }

    @Test
    void aFreshGeneratorReportsZeroNotNaN() {
        // A generator that has done nothing has technically moved everything off the CPU, but reporting that
        // as a saving would describe an unused one as though it had done something.
        GpuTerrainGenerator generator = new GpuTerrainGenerator(true);

        assertEquals(0.0, generator.savedPercent());
        assertEquals(0.0, generator.msCpuAvoided(), 1e-12);
        assertEquals(0.0, generator.msGpuSpent(), 1e-12);
        assertEquals(0L, generator.dispatched());
        assertEquals(0L, generator.completed());
        assertEquals(0L, generator.inFlight());
        assertEquals(16 * 16 * 384, GpuTerrainGenerator.BLOCKS_PER_CHUNK);
    }
}
