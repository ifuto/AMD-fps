package net.amdfaster.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCacheTest {

    private static final class World implements PathCache.WalkabilitySource {
        final java.util.Set<Integer> blocked = new java.util.HashSet<>();
        int calls;

        void block(int x, int y, int z) {
            this.blocked.add(PathCache.packPosition(x, y, z));
        }

        @Override
        public boolean walkableAt(int x, int y, int z) {
            this.calls++;
            return !this.blocked.contains(PathCache.packPosition(x, y, z));
        }
    }

    @Test
    void oneSearchAsksEachPositionOnce() {
        // Neighbouring nodes get reached from several parents, so without a cache the same block is
        // read from each of them.
        World world = new World();
        world.block(3, 64, 3);
        PathCache cache = new PathCache();
        cache.beginSearch();

        for (int i = 0; i < 50; i++) {
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) {
                    cache.walkableAt(world, x, 64, z);
                }
            }
        }
        assertEquals(50 * 64, cache.walkReads());
        assertEquals(64, world.calls, "64 distinct positions, one read each");
        assertFalse(cache.walkableAt(world, 3, 64, 3), "and the blocked one stayed blocked");
        assertTrue(cache.walkableAt(world, 0, 64, 0));
    }

    @Test
    void bothAnswersAreCachedNotJustTheWalkableOnes() {
        // A blocked position is the more common answer in a failed search, and treating it as
        // "nothing cached" would re-read exactly the positions the search keeps bumping into.
        World world = new World();
        world.block(1, 64, 1);
        PathCache cache = new PathCache();
        cache.beginSearch();

        assertFalse(cache.walkableAt(world, 1, 64, 1));
        assertEquals(1, world.calls);
        assertFalse(cache.walkableAt(world, 1, 64, 1));
        assertEquals(1, world.calls, "the blocked answer was cached");
    }

    @Test
    void aNewSearchDropsWhatTheLastOneLearned() {
        // Walkability is only trusted for the length of one search. Blocks can change between them,
        // and a cache that spanned searches would route a mob through a block that is there now.
        World world = new World();
        PathCache cache = new PathCache();

        cache.beginSearch();
        assertTrue(cache.walkableAt(world, 5, 64, 5));
        world.block(5, 64, 5);
        assertTrue(cache.walkableAt(world, 5, 64, 5), "still cached within the search");

        cache.beginSearch();
        assertFalse(cache.walkableAt(world, 5, 64, 5), "and re-read afterwards");
        assertEquals(2, world.calls);
    }

    @Test
    void aRecordedFailureIsBelievedForAWhile() {
        PathCache cache = new PathCache();

        assertFalse(cache.isKnownUnreachable(7, 100, 64, 100, 500), "nothing recorded yet");
        cache.recordFailure(7, 100, 64, 100, 500);

        assertTrue(cache.isKnownUnreachable(7, 100, 64, 100, 500));
        assertTrue(cache.isKnownUnreachable(7, 100, 64, 100, 540), "exactly at the window edge");
        assertEquals(2L, cache.memoHits());
    }

    @Test
    void aFailureExpiresRatherThanBindingTheMobForever() {
        // A mob that could never retry would stand still for the rest of the session, which is worse
        // than the wasted search this exists to avoid.
        PathCache cache = new PathCache();
        cache.recordFailure(3, 10, 64, 10, 1000);

        assertTrue(cache.isKnownUnreachable(3, 10, 64, 10, 1040));
        assertFalse(cache.isKnownUnreachable(3, 10, 64, 10, 1041), "one tick past the window");
        assertFalse(cache.isKnownUnreachable(3, 10, 64, 10, 1042), "and the entry is gone, not stale");
    }

    @Test
    void aBlockChangeVoidsEveryRecordedFailure() {
        // The correctness case. A door someone just opened has to be pathable again, and a memo that
        // survived the change would leave a mob unable to reach it with no error anywhere.
        PathCache cache = new PathCache();
        for (int e = 0; e < 20; e++) {
            cache.recordFailure(e, 50, 64, 50, 200);
        }
        for (int e = 0; e < 20; e++) {
            assertTrue(cache.isKnownUnreachable(e, 50, 64, 50, 210), "entity " + e);
        }

        cache.bumpWorldVersion();

        for (int e = 0; e < 20; e++) {
            assertFalse(cache.isKnownUnreachable(e, 50, 64, 50, 210), "entity " + e + " retries");
        }
        assertEquals(1L, cache.worldVersion());
        assertEquals(1L, cache.memoVoids());
    }

    @Test
    void differentTargetsAreDifferentMemos() {
        // A mob failing to reach one player says nothing about whether it can reach another.
        PathCache cache = new PathCache();
        cache.recordFailure(1, 100, 64, 100, 300);

        assertTrue(cache.isKnownUnreachable(1, 100, 64, 100, 300));
        assertFalse(cache.isKnownUnreachable(1, 101, 64, 100, 300), "a block away is a new target");
        assertFalse(cache.isKnownUnreachable(2, 100, 64, 100, 300), "and so is another mob");
        assertFalse(cache.isKnownUnreachable(1, 100, 65, 100, 300));
    }

    @Test
    void memoKeysDoNotCollide() {
        PathCache cache = new PathCache();
        java.util.Set<Long> keys = new java.util.HashSet<>();
        long expected = 0;
        for (int e = 0; e < 200; e++) {
            for (int dx = -40; dx < 40; dx++) {
                for (int dz = -40; dz < 40; dz++) {
                    keys.add(cache.memoKey(e, 500 + dx, 64, 500 + dz));
                    expected++;
                }
            }
        }
        assertEquals(expected, keys.size(), "every entity and target pair keys distinctly");
    }

    @Test
    void theWorldVersionIsPartOfTheKey() {
        // So a memo cannot outlive the world it describes even if a caller forgets to check.
        PathCache cache = new PathCache();
        long before = cache.memoKey(1, 10, 64, 10);
        cache.bumpWorldVersion();
        long after = cache.memoKey(1, 10, 64, 10);

        assertFalse(before == after, "the same coordinates key differently across versions");
    }

    @Test
    void recordingAtTickZeroStillRecords() {
        // A tick of zero is the table's empty marker, so storing it verbatim would look like no
        // failure at all. The implementation shifts it, which costs a tick of validity.
        PathCache cache = new PathCache();
        cache.recordFailure(9, 5, 64, 5, 0);

        assertTrue(cache.isKnownUnreachable(9, 5, 64, 5, 0));
        assertTrue(cache.isKnownUnreachable(9, 5, 64, 5, 20));
    }

    @Test
    void aFarmOfFailingMobsStopsRecomputing() {
        // The shape of the saving: mobs that cannot reach their target, retrying every second.
        int mobs = 50;
        int limit = 32;
        int seconds = 60;
        PathCache cache = new PathCache(64, 1024);

        long readsWithout = 0;
        long readsWith = 0;
        for (int s = 0; s < seconds; s++) {
            int tick = s * 20;
            for (int e = 0; e < mobs; e++) {
                readsWithout += limit * 8L;
                if (!cache.isKnownUnreachable(e, 300, 64, 300, tick)) {
                    readsWith += limit * 8L;
                    cache.recordFailure(e, 300, 64, 300, tick);
                }
            }
        }
        assertEquals(50 * 60 * 32 * 8L, readsWithout, "what vanilla would read");
        // A memo recorded at tick t is believed while tick - t is 40 or less, so the next search is
        // at t + 60, not t + 40: the window is inclusive at the far end and the checks land every 20
        // ticks. Twenty searches a mob over a minute rather than thirty.
        assertEquals(50 * 20 * 32 * 8L, readsWith, "a third of it, from the memo alone");
        assertEquals(50 * 40L, cache.memoHits(), "the other forty seconds of checks were answered");
    }

    @Test
    void fillingBothTablesGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and returns to its start.
        // Another class here did that and hung the thread; CI reported it as a timeout.
        World world = new World();
        PathCache cache = new PathCache(16, 16);

        cache.beginSearch();
        for (int i = 0; i < 3000; i++) {
            cache.walkableAt(world, i % 60 - 30, 64, i / 60 - 25);
        }
        for (int i = 0; i < 3000; i++) {
            cache.recordFailure(i % 200, i % 500, 64, i / 6, 100 + i);
            cache.isKnownUnreachable(i % 200, i % 500, 64, i / 6, 100 + i);
        }

        assertEquals(3000, world.calls, "every position was distinct, so every one was read");
        assertTrue(cache.memoRecords() > 0);
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        PathCache cache = new PathCache();
        assertEquals(0.0, cache.walkHitRatePercent());
        assertEquals(0L, cache.walkReads());
        assertEquals(0L, cache.worldVersion());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PathCache(8, 64));
        assertThrows(IllegalArgumentException.class, () -> new PathCache(64, 8));
    }
}
