package net.amdfaster.runtime;

import net.amdfaster.runtime.ParticleRuntime.Spawn;
import net.amdfaster.runtime.ParticleRuntime.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleRuntimeTest {

    private static final String MOD = "testmod";
    private static final String TEX = "particle/flame";
    private static final double HALF_FOV = 35.0;

    /**
     * A particle close by, in front, and with nothing in the way.
     *
     * <p>The screen bounds are a small billboard well inside the frame, and the depth is in front of
     * everything, so the occlusion test keeps it.
     */
    private static Spawn spawnNear(ParticleRuntime runtime) {
        return runtime.spawn(MOD, TEX, 0.2f, 16.0f, 20.0f, 0.0, HALF_FOV,
                900, 500, 940, 540, 0.5f);
    }

    @Test
    void aVisibleParticleIsDrawn() {
        ParticleRuntime runtime = new ParticleRuntime();

        Spawn spawn = spawnNear(runtime);

        assertEquals(Verdict.DRAWN, spawn.verdict());
        assertTrue(spawn.drawn());
        assertTrue(spawn.slot() >= 0);
        assertEquals(1L, runtime.drawn());
    }

    @Test
    void aParticleBehindTheCameraIsNotDrawn() {
        ParticleRuntime runtime = new ParticleRuntime();

        Spawn spawn = runtime.spawn(MOD, TEX, 0.2f, 16.0f, 20.0f, 180.0, HALF_FOV,
                900, 500, 940, 540, 0.5f);

        assertEquals(Verdict.OFF_SCREEN, spawn.verdict());
        assertEquals(1L, runtime.offScreen());
        assertEquals(0, runtime.pool().inUse(), "and it gave its slot back");
        assertEquals(0, runtime.budget().total(), "and its share of the budget");
    }

    @Test
    void aParticleTooSmallToSeeIsNotDrawn() {
        // A comparison rather than a test, so it comes before both culling stages.
        ParticleRuntime runtime = new ParticleRuntime();

        Spawn spawn = runtime.spawn(MOD, TEX, 0.001f, 100_000.0f, 0.001f, 0.0, HALF_FOV,
                900, 500, 940, 540, 0.5f);

        assertEquals(Verdict.TOO_SMALL, spawn.verdict());
        assertEquals(1L, runtime.tooSmall());
        assertEquals(0, runtime.pool().inUse());
    }

    @Test
    void thePoolRunningOutIsReported() {
        ParticleRuntime runtime = new ParticleRuntime(4);

        for (int i = 0; i < 4; i++) {
            assertEquals(Verdict.DRAWN, spawnNear(runtime).verdict());
        }
        Spawn fifth = spawnNear(runtime);

        assertEquals(Verdict.NO_SLOT, fifth.verdict());
        assertEquals(1L, runtime.noSlot());
        assertEquals(4, runtime.pool().inUse());
    }

    @Test
    void theBudgetIsSharedAcrossSources() {
        // A mod that adds ambient effects everywhere would otherwise starve one whose particles are the point
        // of what it does.
        ParticleRuntime runtime = new ParticleRuntime();

        for (int i = 0; i < 3; i++) {
            assertEquals(Verdict.DRAWN, runtime.spawn("mod" + i, TEX,
                    0.2f, 16.0f, 20.0f, 0.0, HALF_FOV,
                    900 + i * 40, 500, 940 + i * 40, 540, 0.5f).verdict());
        }

        assertEquals(3, runtime.budget().sources());
        assertEquals(3, runtime.budget().total());
    }

    @Test
    void expiringAParticleFreesItsSlotAndItsShare() {
        ParticleRuntime runtime = new ParticleRuntime(2);

        Spawn first = spawnNear(runtime);
        Spawn second = spawnNear(runtime);
        assertEquals(Verdict.NO_SLOT, spawnNear(runtime).verdict());

        runtime.expire(first.slot(), MOD);

        assertEquals(1, runtime.pool().inUse());
        assertEquals(Verdict.DRAWN, spawnNear(runtime).verdict(), "the slot came back");
        assertTrue(second.slot() >= 0);
    }

    @Test
    void particlesWithTheSameTextureBatchTogether() {
        ParticleRuntime runtime = new ParticleRuntime();

        for (int i = 0; i < 50; i++) {
            spawnNear(runtime);
        }
        runtime.endFrame();

        assertEquals(50L, runtime.batcher().added());
        assertEquals(1L, runtime.batcher().calls(), "one call for fifty particles");
        assertEquals(1L, runtime.frames());
    }

    @Test
    void aDifferentTextureCostsAnotherCall() {
        ParticleRuntime runtime = new ParticleRuntime();

        for (int i = 0; i < 10; i++) {
            spawnNear(runtime);
        }
        runtime.spawn(MOD, "particle/smoke", 0.2f, 16.0f, 20.0f, 0.0, HALF_FOV,
                900, 500, 940, 540, 0.5f);
        runtime.endFrame();

        assertEquals(2L, runtime.batcher().calls());
        assertEquals(2L, runtime.batcher().textureSwitches(),
                "the first texture is a switch from nothing, and the smoke is a switch from the flame");
    }

    @Test
    void theCountersAddUpToTheSpawns() {
        ParticleRuntime runtime = new ParticleRuntime();

        for (int i = 0; i < 100; i++) {
            runtime.spawn(MOD, TEX, 0.2f, 16.0f, 20.0f,
                    i % 2 == 0 ? 0.0 : 180.0, HALF_FOV,
                    900, 500, 940, 540, 0.5f);
        }

        assertEquals(100L, runtime.spawned());
        assertEquals(runtime.spawned(), runtime.drawn() + runtime.refused(),
                "every spawn is either drawn or refused, with no double counting");
        assertEquals(50L, runtime.drawn());
        assertEquals(50L, runtime.offScreen());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ParticleRuntime runtime = new ParticleRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.spawn(null, TEX, 0.2f, 16.0f, 20.0f, 0, HALF_FOV, 0, 0, 4, 4, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.spawn(MOD, null, 0.2f, 16.0f, 20.0f, 0, HALF_FOV, 0, 0, 4, 4, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new ParticleRuntime(0));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has spawned nothing has technically refused nothing, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        ParticleRuntime runtime = new ParticleRuntime();

        assertEquals(0.0, runtime.refusedPercent());
        assertEquals(0L, runtime.spawned());
        assertEquals(0L, runtime.refused());
        assertEquals(0L, runtime.frames());
        assertEquals(4096, ParticleRuntime.DEFAULT_POOL_CAPACITY);
        assertFalse(runtime.pool().capacity() < 1);
    }
}
