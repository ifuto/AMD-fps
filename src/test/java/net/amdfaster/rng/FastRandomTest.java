package net.amdfaster.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.rng.FastRandom.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The counters are static, so every test starts from a reset; and whether an LXM generator exists depends on
 * the JVM running the test, so what is asserted is that the class is consistent about it rather than that it
 * found one.
 */
class FastRandomTest {

    @BeforeEach
    void reset() {
        FastRandom.reset();
    }

    @Test
    void onlyTheUnobservableUsagesAreAcceleratable() {
        assertTrue(FastRandom.mayAccelerate(Usage.PARTICLES), "jitter nobody records");
        assertTrue(FastRandom.mayAccelerate(Usage.AMBIENT));
        assertTrue(FastRandom.mayAccelerate(Usage.RENDER_JITTER));

        // Everything that is persisted or that the player could notice differing between two runs.
        assertEquals(false, FastRandom.mayAccelerate(Usage.WORLDGEN), "changing this changes the world");
        assertEquals(false, FastRandom.mayAccelerate(Usage.LOOT), "reproducible from a seed");
        assertEquals(false, FastRandom.mayAccelerate(Usage.MOB_AI));
        assertEquals(false, FastRandom.mayAccelerate(Usage.ITEM_SCATTER), "stored on the entity");
        assertEquals(false, FastRandom.mayAccelerate(Usage.RANDOM_TICK));
    }

    @Test
    void aWorldgenDrawIsRefusedRatherThanServedFromAnotherSequence() {
        // This is the whole point of the class. A different generator from the same seed produces different
        // terrain, so the draw has to fail loudly instead of quietly changing the world.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextInt(Usage.WORLDGEN));

        assertTrue(thrown.getMessage().contains("WORLDGEN"), "names the usage that was refused");
        assertEquals(1, FastRandom.refusedDraws(), "and the refusal is counted");
        assertEquals(0, FastRandom.draws(), "a refused draw is not a draw");
    }

    @Test
    void everyNonAcceleratableUsageIsRefused() {
        int refused = 0;
        for (Usage usage : Usage.values()) {
            if (!usage.acceleratable()) {
                assertThrows(IllegalArgumentException.class, () -> FastRandom.nextInt(usage), usage.name());
                refused++;
            }
        }
        assertEquals(5, refused, "worldgen, loot, mob AI, item scatter and random tick");
        assertEquals(5, FastRandom.refusedDraws());
    }

    @Test
    void anAcceleratableDrawIsCounted() {
        FastRandom.nextInt(Usage.PARTICLES);

        assertEquals(1, FastRandom.draws());
        assertEquals(0, FastRandom.refusedDraws());
        // Whatever the value is, the counters have to agree about whether LXM was in use.
        if (FastRandom.isAccelerated()) {
            assertEquals(1, FastRandom.acceleratedDraws());
        } else {
            assertEquals(0, FastRandom.acceleratedDraws(), "the fallback is not counted as accelerated");
        }
    }

    @Test
    void aBoundedDrawStaysInBounds() {
        for (int i = 0; i < 1000; i++) {
            int value = FastRandom.nextInt(Usage.PARTICLES, 6);
            assertTrue(value >= 0 && value < 6, "a die roll came up " + value);
        }
        assertEquals(1000, FastRandom.draws());
    }

    @Test
    void theBoundIsCheckedBeforeTheUsage() {
        // Order matters: a nonsense bound is a programming error at the call site, whereas a refused usage
        // is a policy decision. Reporting the bound first says which one it is.
        assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextInt(Usage.PARTICLES, 0), "a bound of zero has no values in it");
        assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextInt(Usage.PARTICLES, -1));
        assertEquals(0, FastRandom.refusedDraws(), "a bad bound is not a refusal");
    }

    @Test
    void aBoundedWorldgenDrawIsStillRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextInt(Usage.WORLDGEN, 10));
        assertEquals(1, FastRandom.refusedDraws());
    }

    @Test
    void symmetricJitterSpansBothDirections() {
        // The shape cosmetic jitter actually wants. Without it every call site writes
        // (nextFloat() * 2 - 1) * extent, and the one that forgets the doubling only ever pushes one way.
        boolean sawNegative = false;
        boolean sawPositive = false;
        for (int i = 0; i < 2000; i++) {
            float value = FastRandom.nextSymmetricFloat(Usage.RENDER_JITTER, 0.5f);
            assertTrue(value >= -0.5f && value < 0.5f, "strayed outside the extent: " + value);
            sawNegative |= value < 0.0f;
            sawPositive |= value > 0.0f;
        }
        assertTrue(sawNegative, "never jittered negative, so it is not symmetric");
        assertTrue(sawPositive, "never jittered positive, so it is not symmetric");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FastRandom.mayAccelerate(null));
        assertThrows(IllegalArgumentException.class, () -> FastRandom.nextInt(null));
        assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextSymmetricFloat(Usage.PARTICLES, 0.0f), "no extent means no jitter");
        assertThrows(IllegalArgumentException.class,
                () -> FastRandom.nextSymmetricFloat(Usage.PARTICLES, Float.NaN));
    }

    @Test
    void theAccelerationClaimAgreesWithTheGeneratorItNames() {
        // Whether LXM is present depends on the JVM, and headless builds often ship without it. What must
        // hold either way is that the class does not claim to be accelerated while naming no generator.
        assertEquals(FastRandom.isAccelerated(), FastRandom.generatorName() != null,
                "an acceleration claim with no generator behind it would be a false report");
    }

    @Test
    void drawsComeFromTheGeneratorRatherThanRepeating() {
        // A ThreadLocal initialiser that threw would poison the thread, and a generator that never advanced
        // would hand back the same value forever. Neither shows up in the counters.
        int distinct = 0;
        int previous = FastRandom.nextInt(Usage.PARTICLES);
        for (int i = 0; i < 64; i++) {
            int next = FastRandom.nextInt(Usage.PARTICLES);
            if (next != previous) {
                distinct++;
            }
            previous = next;
        }
        assertTrue(distinct > 32, "only " + distinct + " of 64 draws differed from the one before");
    }

    @Test
    void resetClearsTheCountersButNotTheGenerators() {
        FastRandom.nextInt(Usage.PARTICLES);

        FastRandom.reset();

        assertEquals(0, FastRandom.draws());
        assertEquals(0, FastRandom.acceleratedDraws());
        assertEquals(0, FastRandom.refusedDraws());
        // Still usable: the generators hold no world state, so a world change has no reason to rebuild them.
        FastRandom.nextInt(Usage.PARTICLES);
        assertEquals(1, FastRandom.draws());
    }
}
