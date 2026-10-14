package net.amdfaster.hopper;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperTransferCacheTest {

    private static final class World implements HopperTransferCache.ContainerLookup {
        final Map<Integer, Long> containers = new HashMap<>();
        int calls;

        void set(int x, int y, int z, long token) {
            this.containers.put(HopperTransferCache.pack(x, y, z), token);
        }

        @Override
        public long containerAt(int x, int y, int z) {
            this.calls++;
            return this.containers.getOrDefault(HopperTransferCache.pack(x, y, z),
                    HopperTransferCache.NO_CONTAINER);
        }
    }

    @Test
    void aPositionIsAskedOncePerInvalidation() {
        World world = new World();
        world.set(0, 64, 0, 4242L);
        HopperTransferCache cache = new HopperTransferCache();

        for (int i = 0; i < 200; i++) {
            assertEquals(4242L, cache.containerAt(world, 0, 64, 0));
        }
        assertEquals(1, world.calls, "one search for two hundred questions");
        assertEquals(1L, cache.size());
    }

    @Test
    void aPositionWithNoContainerIsCachedToo() {
        // The majority case in a farm, and the one that a zero-as-empty-marker would break. Asking
        // again every time would leave the cache reporting hits while searching every tick.
        World world = new World();
        HopperTransferCache cache = new HopperTransferCache();

        assertEquals(HopperTransferCache.NO_CONTAINER, cache.containerAt(world, 1, 64, 1));
        assertEquals(1, world.calls);
        assertEquals(HopperTransferCache.NO_CONTAINER, cache.containerAt(world, 1, 64, 1));
        assertEquals(1, world.calls, "the absence was cached, not just the presence");
        assertEquals(1, cache.size());
        assertEquals(1L, cache.hits());
    }

    @Test
    void changingTheBlockForcesANewSearch() {
        // A chest replaced by a barrel still has to be found, or the hopper pulls from a container
        // that is no longer there.
        World world = new World();
        world.set(2, 64, 2, 7L);
        HopperTransferCache cache = new HopperTransferCache();

        cache.containerAt(world, 2, 64, 2);
        world.set(2, 64, 2, 99L);
        assertEquals(7L, cache.containerAt(world, 2, 64, 2));

        cache.invalidateAt(2, 64, 2);
        assertEquals(99L, cache.containerAt(world, 2, 64, 2));
        assertEquals(2, world.calls);
    }

    @Test
    void invalidationKeepsTheRestOfTheFarmFindable() {
        World world = new World();
        HopperTransferCache cache = new HopperTransferCache();
        int[][] positions = new int[400][3];
        for (int i = 0; i < positions.length; i++) {
            positions[i][0] = i % 20;
            positions[i][1] = 64;
            positions[i][2] = i / 20;
            world.set(positions[i][0], 64, positions[i][2], i + 1);
            cache.containerAt(world, positions[i][0], 64, positions[i][2]);
        }
        assertEquals(positions.length, world.calls);

        for (int i = 0; i < positions.length; i += 2) {
            cache.invalidateAt(positions[i][0], 64, positions[i][2]);
        }
        for (int i = 0; i < positions.length; i++) {
            int before = world.calls;
            cache.containerAt(world, positions[i][0], 64, positions[i][2]);
            if (i % 2 == 0) {
                assertEquals(before + 1, world.calls, "position " + i + " was invalidated");
            } else {
                assertEquals(before, world.calls, "position " + i + " was not, so backshift lost it");
            }
        }
    }

    @Test
    void anIdleHopperStopsSearchingUntilSomethingArrives() {
        // The saving that matters. A container lookup made cheaper is still a lookup; not making it
        // is the difference between a farm ticking and a farm costing nothing.
        HopperTransferCache cache = new HopperTransferCache();

        assertTrue(cache.shouldAttempt(0), "the first attempt always runs");
        cache.afterAttempt(0, false);

        for (int i = 0; i < 100; i++) {
            assertFalse(cache.shouldAttempt(0), "idle, so attempt " + i + " is skipped");
        }
        assertEquals(1L, cache.searches());
        assertEquals(100L, cache.skipped());

        cache.wake(0);
        assertTrue(cache.shouldAttempt(0), "and it runs again after an insert");
        assertEquals(1L, cache.wakes());
    }

    @Test
    void aHopperThatMovedKeepsTrying() {
        // Moving one item means there may be more. Sleeping here would drain a chest one item per
        // eight ticks until something woke it.
        HopperTransferCache cache = new HopperTransferCache();

        for (int i = 0; i < 50; i++) {
            assertTrue(cache.shouldAttempt(1));
            cache.afterAttempt(1, true);
        }
        assertEquals(50L, cache.searches());
        assertEquals(0L, cache.skipped());
    }

    @Test
    void aFarmOfMostlyIdleHoppersSkipsMostOfItsWork() {
        // The measured shape of an item farm: hundreds of hoppers, almost all of them empty.
        int hoppers = 200;
        int ticks = 200;
        int transferInterval = 8;
        HopperTransferCache cache = new HopperTransferCache(512, hoppers);

        // Every fourth hopper has something to move.
        for (int h = 0; h < hoppers; h++) {
            cache.afterAttempt(h, h % 4 == 0);
        }

        long wouldHaveRun = 0;
        for (int t = 0; t < ticks; t += transferInterval) {
            for (int h = 0; h < hoppers; h++) {
                wouldHaveRun++;
                cache.shouldAttempt(h);
            }
        }
        assertEquals((long) (ticks / transferInterval) * hoppers, wouldHaveRun, "5000 attempts");
        assertEquals(wouldHaveRun, cache.searches() + cache.skipped());
        // Three quarters idle, so three quarters skipped.
        assertEquals(wouldHaveRun * 3 / 4, cache.skipped());
        assertEquals(wouldHaveRun / 4, cache.searches());
    }

    @Test
    void wakeAllIsCoarseButNeverMisses() {
        // Where not every insert path can be hooked, waking everyone is slow and correct. A missed
        // wake is fast and leaves a farm silently stopped.
        HopperTransferCache cache = new HopperTransferCache(64, 8);
        for (int h = 0; h < 8; h++) {
            cache.afterAttempt(h, false);
        }
        for (int h = 0; h < 8; h++) {
            assertFalse(cache.shouldAttempt(h));
        }

        cache.wakeAll();

        for (int h = 0; h < 8; h++) {
            assertTrue(cache.shouldAttempt(h), "hopper " + h + " woke");
        }
    }

    @Test
    void wakingAnAlreadyAwakeHopperIsNotCounted() {
        HopperTransferCache cache = new HopperTransferCache(64, 4);
        cache.wake(2);
        assertEquals(0L, cache.wakes(), "it was never asleep");
    }

    @Test
    void growingTheTrackedSetKeepsExistingIdleState() {
        HopperTransferCache cache = new HopperTransferCache(64, 4);
        cache.afterAttempt(0, false);
        cache.growTo(16);

        assertEquals(16, cache.hopperCount());
        assertFalse(cache.shouldAttempt(0), "still asleep");
        assertTrue(cache.shouldAttempt(9), "new hoppers start awake");
        cache.growTo(2);
        assertEquals(16, cache.hopperCount(), "shrinking is not supported and does nothing");
    }

    @Test
    void positionsInAFarmDoNotPackAlike() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z++) {
                for (int y = 60; y < 68; y++) {
                    keys.add(HopperTransferCache.pack(x, y, z));
                }
            }
        }
        assertEquals(128 * 128 * 8, keys.size(), "every position in a generous farm volume is distinct");
    }

    @Test
    void fillingPastTheCapacityGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and comes back to its start.
        // Another class here did exactly that and hung the thread; CI reported it as a timeout
        // rather than a failure.
        World world = new World();
        HopperTransferCache cache = new HopperTransferCache(16, 4);

        for (int i = 0; i < 10000; i++) {
            world.set(i % 100 - 50, 64, i / 100 - 50, i);
            cache.containerAt(world, i % 100 - 50, 64, i / 100 - 50);
        }

        assertEquals(10000L, cache.lookups());
        assertTrue(cache.size() > 0);
    }

    @Test
    void aTokenEqualToTheAbsentMarkerIsRejected() {
        // The other half of the separation. NO_CONTAINER is a cached value and ABSENT_SLOT is an
        // empty slot, so a lookup returning the latter would be stored and then read back as absent.
        HopperTransferCache cache = new HopperTransferCache();
        assertThrows(IllegalArgumentException.class,
                () -> cache.containerAt((x, y, z) -> Long.MIN_VALUE, 0, 64, 0));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HopperTransferCache(8, 4));
        assertThrows(IllegalArgumentException.class, () -> new HopperTransferCache(64, 0));

        HopperTransferCache cache = new HopperTransferCache(64, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> cache.shouldAttempt(4));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.shouldAttempt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.afterAttempt(9, true));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.wake(9));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        HopperTransferCache cache = new HopperTransferCache();
        assertEquals(0.0, cache.hitRatePercent());
        assertEquals(0L, cache.lookups());
        assertEquals(256, cache.hopperCount());
    }
}
