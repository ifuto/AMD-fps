package net.amdfaster.runtime;

import net.amdfaster.particle.ParticlePool;
import net.amdfaster.rng.FastRandom;
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

    @Test
    void scatterMovesTheParticleButNeverBeyondTheExtent() {
        ParticleRuntime runtime = new ParticleRuntime();
        FastRandom.reset();
        int slot = runtime.pool().acquire();

        runtime.scatter(slot, 10.0f, 20.0f, 30.0f, 0.25f);

        // The jitter is symmetric around the nominal point and strictly inside it on the far side, because
        // the draw is in [0, 1) scaled to [-extent, extent). Asserting the closed upper bound would pass on
        // a one-sided jitter, which is the bug this is guarding against.
        float x = runtime.pool().get(slot, ParticlePool.POSITION_X);
        float y = runtime.pool().get(slot, ParticlePool.POSITION_Y);
        float z = runtime.pool().get(slot, ParticlePool.POSITION_Z);
        assertTrue(x >= 9.75f && x < 10.25f, "x drifted out of the extent: " + x);
        assertTrue(y >= 19.75f && y < 20.25f, "y drifted out of the extent: " + y);
        assertTrue(z >= 29.75f && z < 30.25f, "z drifted out of the extent: " + z);
        assertEquals(3L, FastRandom.draws(), "one draw per axis, no more");
        assertEquals(0L, FastRandom.refusedDraws(), "particles are an acceleratable usage");
    }

    @Test
    void aZeroExtentStoresTheNominalPointExactly() {
        ParticleRuntime runtime = new ParticleRuntime();
        FastRandom.reset();
        int slot = runtime.pool().acquire();
        // Put something else in the slot first. It holds whatever the last occupant left there, so a zero
        // scatter that skipped the write would leave the previous particle's position behind.
        runtime.pool().set(slot, ParticlePool.POSITION_X, 999.0f);

        runtime.scatter(slot, 10.0f, 20.0f, 30.0f, 0.0f);

        assertEquals(10.0f, runtime.pool().get(slot, ParticlePool.POSITION_X));
        assertEquals(20.0f, runtime.pool().get(slot, ParticlePool.POSITION_Y));
        assertEquals(30.0f, runtime.pool().get(slot, ParticlePool.POSITION_Z));
        assertEquals(0L, FastRandom.draws(), "a point that does not move needs no random numbers");
    }

    @Test
    void scatterRefusesAnUnheldSlotAndANonsenseExtent() {
        ParticleRuntime runtime = new ParticleRuntime();
        int slot = runtime.pool().acquire();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.scatter(-1, 0.0f, 0.0f, 0.0f, 0.25f), "no slot is held at -1");
        assertThrows(IllegalArgumentException.class,
                () -> runtime.scatter(slot, 0.0f, 0.0f, 0.0f, -0.25f), "a distance cannot be negative");
        assertThrows(IllegalArgumentException.class,
                () -> runtime.scatter(slot, 0.0f, 0.0f, 0.0f, Float.NaN));
    }

    @Test
    void aBurstOfParticlesDoesNotStackOnOnePoint() {
        // The reason to scatter at all. Every particle of a burst spawned from one block would otherwise
        // occupy the same coordinates and read as a single brighter dot rather than a puff.
        ParticleRuntime runtime = new ParticleRuntime();
        FastRandom.reset();
        int[] slots = new int[8];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = runtime.pool().acquire();
            runtime.scatter(slots[i], 10.0f, 20.0f, 30.0f, 0.25f);
        }

        int distinct = 0;
        float first = runtime.pool().get(slots[0], ParticlePool.POSITION_X);
        for (int slot : slots) {
            if (runtime.pool().get(slot, ParticlePool.POSITION_X) != first) {
                distinct++;
            }
        }
        assertTrue(distinct >= 6, "only " + distinct + " of 8 particles landed off the first one's x");
        assertEquals(24L, FastRandom.draws(), "8 particles, 3 axes each");
    }

    @Test
    void spawnAtScattersWhatItDrawsAndSpendsNothingOnWhatItDrops() {
        ParticleRuntime runtime = new ParticleRuntime();
        FastRandom.reset();

        Spawn drawn = runtime.spawnAt(MOD, TEX, 0.2f, 10.0f, 20.0f, 30.0f, 0.25f,
                16.0f, 20.0f, 0.0, HALF_FOV, 900, 500, 940, 540, 0.5f);
        assertTrue(drawn.drawn());
        assertEquals(3L, FastRandom.draws(), "the drawn particle was scattered");
        float x = runtime.pool().get(drawn.slot(), ParticlePool.POSITION_X);
        assertTrue(x >= 9.75f && x < 10.25f, "position was not stored in the slot: " + x);

        // Behind the camera, so it is dropped before the scatter. Random numbers are not free either, and
        // spending three per culled particle would put them back on the path this removes them from.
        Spawn dropped = runtime.spawnAt(MOD, TEX, 0.2f, 10.0f, 20.0f, 30.0f, 0.25f,
                16.0f, 20.0f, 180.0, HALF_FOV, 900, 500, 940, 540, 0.5f);
        assertFalse(dropped.drawn());
        assertEquals(3L, FastRandom.draws(), "a dropped particle spent nothing");
    }

}
