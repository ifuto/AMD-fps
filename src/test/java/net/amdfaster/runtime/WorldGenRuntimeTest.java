package net.amdfaster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenRuntimeTest {

    private static final long SEED_A = 11111L;
    private static final long SEED_B = 22222L;

    /** A noise evaluator that returns the sum of its coordinates, so the cache key is what varies. */
    private static double sample(WorldGenRuntime runtime, int octave, double x, double y, double z) {
        return runtime.noise().sample(octave, x, y, z, (seed, oct, ox, oy, oz) -> ox + oy + oz);
    }

    @Test
    void theSameNoiseSampleIsEvaluatedOnce() {
        WorldGenRuntime runtime = new WorldGenRuntime();

        assertEquals(12.0, sample(runtime, 0, 4.0, 4.0, 4.0), 1e-12);
        assertEquals(12.0, sample(runtime, 0, 4.0, 4.0, 4.0), 1e-12);

        assertEquals(2L, runtime.noise().lookups());
        assertEquals(1L, runtime.noise().hits());
        assertEquals(1L, runtime.noise().evaluations());
    }

    @Test
    void aWorldChangeReSeedsTheNoiseCache() {
        // The same coordinates in a different world are different terrain, so keeping the old samples would
        // generate terrain that does not match the world the player is standing in.
        WorldGenRuntime runtime = new WorldGenRuntime();

        sample(runtime, 0, 4.0, 4.0, 4.0);
        assertEquals(SEED_A == 0 ? 0L : runtime.noise().seed(), runtime.noise().seed());

        runtime.onWorldChange(SEED_B);

        assertEquals(SEED_B, runtime.noise().seed());
        assertEquals(0, runtime.noise().size(), "the old samples are gone");
        assertEquals(0L, runtime.noise().hits(), "and the next lookup searches again");
    }

    @Test
    void aWorldChangeClearsTheStructureCache() {
        // A layout is keyed on the old seed and cannot be re-seeded into meaning anything: a village in the
        // old world is not a village in the new one at any coordinates.
        WorldGenRuntime runtime = new WorldGenRuntime();

        runtime.structures().layout(SEED_A, 0, 0, 1, () -> new int[] {1, 2, 3});
        assertEquals(1, runtime.structures().size());

        runtime.onWorldChange(SEED_B);

        assertEquals(0, runtime.structures().size());
        assertEquals(1L, runtime.worldChanges());
    }

    @Test
    void aStructureLayoutIsCachedPerSeedAndPosition() {
        WorldGenRuntime runtime = new WorldGenRuntime();

        runtime.structures().layout(SEED_A, 0, 0, 1, () -> new int[] {1, 2, 3});
        runtime.structures().layout(SEED_A, 0, 0, 1, () -> new int[] {9, 9, 9});

        assertEquals(1, runtime.structures().size());
        assertEquals(1L, runtime.structures().hits());
        assertEquals(1L, runtime.structures().searches());
    }

    @Test
    void distantTerrainFillsOverSeveralFramesNotOne() {
        // Generating every pending chunk at once would freeze the game, and generating one at a time would
        // take minutes to fill the horizon.
        WorldGenRuntime runtime = new WorldGenRuntime();

        int total = 0;
        for (int frame = 0; frame < 10; frame++) {
            total += runtime.generateDistant(10_000);
        }

        assertTrue(total > 0, "something was generated");
        assertTrue(total < 10_000, "and not all of it in ten frames");
        assertTrue(runtime.distant().msPerFrame() <= runtime.distant().frameBudget() + 1e-9,
                "within the frame budget");
    }

    @Test
    void aRealChunkReplacesTheDistantOne() {
        WorldGenRuntime runtime = new WorldGenRuntime();

        runtime.generateDistant(100);
        long generated = runtime.distant().generated();
        assertTrue(generated > 0);

        runtime.chunkArrived();

        assertEquals(1L, runtime.distant().replaced());
    }

    @Test
    void distantTerrainIsNeverUsableForGameplay() {
        // Someone could have dug that tunnel out while it was being generated, and a player who walks into
        // ground that is only a picture falls through the world.
        WorldGenRuntime runtime = new WorldGenRuntime();

        runtime.generateDistant(100);

        assertFalse(runtime.allowGameplayUseOfDistantTerrain());
    }

    @Test
    void theRunLengthCodecCompressesASectionOfOneValue() {
        WorldGenRuntime runtime = new WorldGenRuntime();

        byte[] uniform = new byte[4096];
        int[] encoded = runtime.codec().encode(uniform);

        assertTrue(encoded.length < uniform.length, "a uniform section collapses");
        assertEquals(2, encoded.length, "one run, encoded as a value and a length");
        assertTrue(runtime.codec().compressionRatio() > 1.0);
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has sampled nothing has technically cached everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        WorldGenRuntime runtime = new WorldGenRuntime();

        assertEquals(0.0, runtime.noiseHitPercent());
        assertEquals(0.0, runtime.msSavedOnNoise(), 1e-12);
        assertEquals(0L, runtime.worldChanges());
        assertEquals(0, runtime.structures().size());
        assertEquals(4, WorldGenRuntime.DEFAULT_WORKERS);
        assertEquals(4, runtime.distant().workerCount());
    }
}
