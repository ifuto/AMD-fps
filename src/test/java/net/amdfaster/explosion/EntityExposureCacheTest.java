package net.amdfaster.explosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.explosion.EntityExposureCache.BlockSource;
import org.junit.jupiter.api.Test;

/**
 * A counting block source, so a test can tell the difference between an answer served from the table and
 * one that went to the world.
 */
class EntityExposureCacheTest {

    /** Reports every position as obstructing, and counts how often it was asked. */
    private static final class Counting implements BlockSource {
        int calls;
        boolean value = true;

        @Override
        public boolean obstructs(int x, int y, int z) {
            this.calls++;
            return this.value;
        }
    }

    @Test
    void theSampleCountDependsOnTheBoundingBox() {
        // Reproduced from vanilla's grid rather than approximated: the step on an axis is
        // 1 / (size * 2 + 1) and the walk accumulates in floating point from 0 while the value is at
        // most 1, so the count is the number of iterations and not floor(1 / step) + 1. Exposure is a
        // fraction whose denominator this is, so a coarser count would change the damage.
        assertEquals(45, EntityExposureCache.sampleCount(0.6, 1.8, 0.6), "player, zombie: 3 x 5 x 3");
        assertEquals(45, EntityExposureCache.sampleCount(0.6, 1.7, 0.6), "creeper: 3 x 5 x 3");
        assertEquals(36, EntityExposureCache.sampleCount(0.9, 1.4, 0.9), "cow: 3 x 4 x 3");
        assertEquals(27, EntityExposureCache.sampleCount(0.98, 0.98, 0.98), "primed TNT: 3 x 3 x 3");
        assertEquals(8, EntityExposureCache.sampleCount(0.25, 0.25, 0.25), "dropped item: 2 x 2 x 2");
    }

    @Test
    void aNonsenseBoxIsRejectedRatherThanCountedAsZero() {
        // A zero or negative axis would divide by something at most one and produce a count that looks
        // plausible, and then every damage figure downstream would be scaled by it.
        assertThrows(IllegalArgumentException.class, () -> EntityExposureCache.sampleCount(0.0, 1.8, 0.6));
        assertThrows(IllegalArgumentException.class, () -> EntityExposureCache.sampleCount(0.6, -1.0, 0.6));
        assertThrows(IllegalArgumentException.class,
                () -> EntityExposureCache.sampleCount(Double.NaN, 1.8, 0.6));
        assertThrows(IllegalArgumentException.class,
                () -> EntityExposureCache.sampleCount(Double.POSITIVE_INFINITY, 1.8, 0.6));
    }

    @Test
    void aPositionSurvivesPackingIncludingTheAwkwardOnes() {
        // The first version of this used twenty-six bits for each axis, which is seventy-eight and does not
        // fit in a long. It compiled and ran, and silently kept twelve bits of z. These are the cases that
        // would have collided: same x and y, different z.
        int[][] cases = {
            {-1, -1, -1},
            {0, 0, 0},
            {29_999_984, -64, 29_999_984},
            {-29_999_984, 319, -29_999_984},
            {1, -64, 1},
            {-33_554_432, -2_048, -33_554_432},
            {33_554_431, 2_047, 33_554_431},
            {5, 5, 6},
            {5, 5, -6},
        };
        for (int[] c : cases) {
            long packed = EntityExposureCache.pack(c[0], c[1], c[2]);

            // Recovered through the cache, which is the only place the unpacking is used. The source
            // records what it was actually asked about, so a wrong sign extension or a truncated field
            // shows up as a coordinate mismatch rather than passing quietly.
            int[] asked = new int[3];
            EntityExposureCache cache = new EntityExposureCache();
            cache.obstructs(packed, (x, y, z) -> {
                asked[0] = x;
                asked[1] = y;
                asked[2] = z;
                return true;
            });

            assertEquals(c[0], asked[0], "x round-tripped for " + java.util.Arrays.toString(c));
            assertEquals(c[1], asked[1], "y round-tripped for " + java.util.Arrays.toString(c));
            assertEquals(c[2], asked[2], "z round-tripped for " + java.util.Arrays.toString(c));
        }
    }

    @Test
    void twoPositionsSharingXAndYAreNotTheSameEntry() {
        // This is the collision the seventy-eight bit packing would have produced.
        EntityExposureCache cache = new EntityExposureCache();
        long a = EntityExposureCache.pack(5, 64, 7);
        long b = EntityExposureCache.pack(5, 64, 7 + (1 << 12));

        assertFalse(a == b, "the two keys must differ");

        int[] asked = new int[3];
        cache.obstructs(a, (x, y, z) -> {
            asked[0] = x;
            asked[1] = y;
            asked[2] = z;
            return true;
        });
        assertEquals(7, asked[2]);

        cache.obstructs(b, (x, y, z) -> {
            asked[0] = x;
            asked[1] = y;
            asked[2] = z;
            return true;
        });
        assertEquals(7 + (1 << 12), asked[2], "the second position must be asked for separately");
        assertEquals(2, cache.size());
    }

    @Test
    void oneBlockIsAskedOnceHoweverManyRaysCrossIt() {
        EntityExposureCache cache = new EntityExposureCache();
        Counting source = new Counting();
        long packed = EntityExposureCache.pack(10, 70, 10);

        // A player-sized box is 45 sample rays, and a block near the entity is crossed by most of them.
        for (int i = 0; i < 45; i++) {
            cache.obstructs(packed, source);
        }

        assertEquals(1, source.calls, "resolved once, served 44 times");
        assertEquals(45L, cache.lookups());
        assertEquals(1L, cache.resolved());
        assertEquals(1, cache.size());
        assertEquals(97.78, cache.hitPercent(), 0.01);
        assertEquals(44 * EntityExposureCache.MS_PER_TRAVERSAL, cache.msSaved(), 1e-12);
    }

    @Test
    void aDestroyedBlockIsAskedAgain() {
        // The correctness hazard. A block blast A removed no longer stops blast B's rays, so an entity that
        // was standing behind it is no longer shielded. Serving the stale answer would deny that entity
        // damage and knockback it should have taken.
        EntityExposureCache cache = new EntityExposureCache();
        Counting source = new Counting();
        long packed = EntityExposureCache.pack(10, 70, 10);

        assertTrue(cache.obstructs(packed, source));
        assertEquals(1, source.calls);

        cache.invalidate(packed);
        assertEquals(1L, cache.invalidations());
        assertEquals(0, cache.size());

        source.value = false;
        assertFalse(cache.obstructs(packed, source), "the position is air now and must say so");
        assertEquals(2, source.calls, "asked again rather than served from the table");
    }

    @Test
    void invalidatingSomethingThatWasNeverCachedDoesNothing() {
        EntityExposureCache cache = new EntityExposureCache();

        cache.invalidate(EntityExposureCache.pack(1, 2, 3));

        assertEquals(0L, cache.invalidations(), "there was no entry to drop");
        assertEquals(0, cache.size());
    }

    @Test
    void destructionArrivesAsAList() {
        EntityExposureCache cache = new EntityExposureCache();
        Counting source = new Counting();
        long[] destroyed = new long[3];
        for (int i = 0; i < 3; i++) {
            destroyed[i] = EntityExposureCache.pack(i, 70, i);
            cache.obstructs(destroyed[i], source);
        }
        assertEquals(3, cache.size());

        cache.invalidateRange(destroyed, 2);

        assertEquals(2L, cache.invalidations());
        assertEquals(1, cache.size(), "the third entry was outside the live range");
    }

    @Test
    void aRangeOutsideTheArrayIsRejected() {
        EntityExposureCache cache = new EntityExposureCache();

        assertThrows(IllegalArgumentException.class, () -> cache.invalidateRange(null, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateRange(new long[2], 3));
        assertThrows(IllegalArgumentException.class, () -> cache.invalidateRange(new long[2], -1));
    }

    @Test
    void theWholeTableGoesAtTheEndOfTheTick() {
        // Blocks placed, water flowed and pistons moved between ticks, so an obstruction answer carried
        // across that boundary describes a world that is no longer there.
        EntityExposureCache cache = new EntityExposureCache();
        Counting source = new Counting();
        cache.obstructs(EntityExposureCache.pack(1, 70, 1), source);

        cache.endTick();

        assertEquals(0, cache.size());
        source.value = false;
        assertFalse(cache.obstructs(EntityExposureCache.pack(1, 70, 1), source));
        assertEquals(2, source.calls, "resolved fresh in the new tick");
    }

    @Test
    void exposureIsTheShareOfSampleRaysThatReached() {
        EntityExposureCache cache = new EntityExposureCache();

        cache.beginEntity();
        for (int i = 0; i < 8; i++) {
            cache.countRay(true);
        }
        for (int i = 0; i < 37; i++) {
            cache.countRay(false);
        }

        assertEquals(8.0 / 45.0, cache.exposure(), 1e-12);
        assertEquals(45L, cache.rays());
        assertEquals(8L, cache.unobstructed());
        assertEquals(1L, cache.entities());
    }

    @Test
    void eachEntityGetsItsOwnTally() {
        // Without the reset, an entity mostly behind cover would inherit the exposure of the one standing
        // in the open and take damage it should not have.
        EntityExposureCache cache = new EntityExposureCache();

        cache.beginEntity();
        for (int i = 0; i < 45; i++) {
            cache.countRay(true);
        }
        assertEquals(1.0, cache.exposure(), 1e-12, "the one in the open");

        cache.beginEntity();
        cache.countRay(true);
        for (int i = 0; i < 44; i++) {
            cache.countRay(false);
        }
        assertEquals(1.0 / 45.0, cache.exposure(), 1e-12, "the one behind the wall");
        assertEquals(2L, cache.entities());
    }

    @Test
    void exposureWithNoRaysIsZeroNotNaN() {
        // NaN would flow into a damage calculation, and anything times NaN is NaN, so the entity would take
        // no damage at all. That is a worse answer than zero.
        EntityExposureCache cache = new EntityExposureCache();

        cache.beginEntity();

        assertEquals(0.0, cache.exposure(), 1e-12);
        assertFalse(Double.isNaN(cache.exposure()));
    }

    @Test
    void theTableSurvivesGrowingPastItsFirstAllocation() {
        // Growth rehashes into a new array. A rehash that lost entries would not throw -- it would just
        // re-resolve positions, which is a silent performance regression rather than a wrong answer, and
        // the only way to see it is to count.
        EntityExposureCache cache = new EntityExposureCache();
        Counting source = new Counting();
        int positions = 3_000;

        for (int i = 0; i < positions; i++) {
            cache.obstructs(EntityExposureCache.pack(i, 70, i * 2), source);
        }
        assertEquals(positions, source.calls, "each position resolved once");
        assertEquals(positions, cache.size());

        int before = source.calls;
        for (int i = 0; i < positions; i++) {
            cache.obstructs(EntityExposureCache.pack(i, 70, i * 2), source);
        }
        assertEquals(before, source.calls, "nothing re-resolved after the second pass");
        assertEquals(0.0, cache.hitPercent() - 100.0 * (2 * positions - positions) / (2 * positions), 1e-9);
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        EntityExposureCache cache = new EntityExposureCache();

        assertEquals(0L, cache.lookups());
        assertEquals(0L, cache.resolved());
        assertEquals(0L, cache.rays());
        assertEquals(0L, cache.entities());
        assertEquals(0L, cache.invalidations());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.hitPercent(), 1e-12);
        assertEquals(0.0, cache.msSaved(), 1e-12);
    }

    @Test
    void aMissingSourceIsRejected() {
        EntityExposureCache cache = new EntityExposureCache();

        assertThrows(IllegalArgumentException.class,
                () -> cache.obstructs(EntityExposureCache.pack(0, 0, 0), null));
    }
}
