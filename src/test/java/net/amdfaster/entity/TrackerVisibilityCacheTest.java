package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerVisibilityCacheTest {

    private static final AtomicInteger TESTS = new AtomicInteger();

    /** Visible when inside the range. */
    private static final TrackerVisibilityCache.Tester DISTANCE = (px, pz, ex, ez, range) -> {
        TESTS.incrementAndGet();
        double dx = px - ex;
        double dz = pz - ez;
        return dx * dx + dz * dz <= range * range;
    };

    @Test
    void anUnchangedPairIsNotRetested() {
        // Five players and eight hundred entities is 4,000 tests a tick, 80,000 a second, and a cow
        // standing in a field does not leave a player's range.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        TESTS.set(0);

        for (int tick = 0; tick < 10; tick++) {
            assertTrue(cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE));
        }

        assertEquals(1, TESTS.get(), "tested once, served nine times");
        assertEquals(9L, cache.hits());
    }

    @Test
    void movingWithinTheSlackKeepsTheAnswer() {
        // Checking distance every tick would defeat the purpose, so the slack is the margin the cached
        // answer may be wrong by before it is recomputed.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        TESTS.set(0);
        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);

        assertTrue(cache.isVisible(1, 2, 0.0, 0.0, 11.0, 0.0, 64.0, DISTANCE),
                "a metre of movement is inside the slack");
        assertEquals(1, TESTS.get());
    }

    @Test
    void movingPastTheSlackForcesARecompute() {
        // Otherwise an entity that walked out of range would still be sent, which is information the
        // player should not have -- what an X-ray cheat provides.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        TESTS.set(0);
        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);

        double slack = TrackerVisibilityCache.INVALIDATION_SLACK;
        cache.isVisible(1, 2, 0.0, 0.0, 10.0 + slack + 1.0, 0.0, 64.0, DISTANCE);

        assertEquals(2, TESTS.get(), "recomputed past the slack");
        assertEquals(1L, cache.invalidations());
    }

    @Test
    void anEntityThatLeftTheRangeStopsBeingVisible() {
        // The other direction, and the worse one: an entity the player should be told about but is not
        // means a mob that does not appear, and since it exists server-side it can still attack them.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        assertTrue(cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 16.0, DISTANCE));

        double far = 10.0 + TrackerVisibilityCache.INVALIDATION_SLACK + 1.0 + 16.0;
        assertFalse(cache.isVisible(1, 2, 0.0, 0.0, far, 0.0, 16.0, DISTANCE),
                "now outside the range");
    }

    @Test
    void aTeleportInvalidatesImmediately() {
        // A jump can be further than the slack, and waiting for the distance check to notice would serve
        // a stale answer for as long as the entity stayed near its new position.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);
        assertTrue(cache.isCached(1, 2));

        cache.invalidateEntity(2);

        assertFalse(cache.isCached(1, 2));
    }

    @Test
    void invalidatingAnEntityClearsEveryPlayer() {
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);
        cache.isVisible(3, 2, 5.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);

        cache.invalidateEntity(2);

        assertFalse(cache.isCached(1, 2));
        assertFalse(cache.isCached(3, 2));
        assertEquals(2L, cache.invalidations());
    }

    @Test
    void invalidatingAPlayerClearsEveryEntity() {
        // For one who teleported or changed dimension.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);
        cache.isVisible(1, 3, 0.0, 0.0, 20.0, 0.0, 64.0, DISTANCE);

        cache.invalidatePlayer(1);

        assertFalse(cache.isCached(1, 2));
        assertFalse(cache.isCached(1, 3));
    }

    @Test
    void pairsAreNotConfused() {
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        TESTS.set(0);

        cache.isVisible(1, 2, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);
        cache.isVisible(2, 1, 0.0, 0.0, 10.0, 0.0, 64.0, DISTANCE);

        assertEquals(2, TESTS.get(), "player 1 seeing entity 2 is not player 2 seeing entity 1");
    }

    @Test
    void aDifferentRangeIsRetested() {
        // Different entity kinds are tracked at different distances, and an answer computed for a 32
        // block range is wrong for a 128 block one.
        TrackerVisibilityCache cache = new TrackerVisibilityCache();
        TESTS.set(0);

        assertTrue(cache.isVisible(1, 2, 0.0, 0.0, 40.0, 0.0, 64.0, DISTANCE));
        assertFalse(cache.isVisible(1, 2, 0.0, 0.0, 40.0, 0.0, 32.0, DISTANCE));

        assertEquals(2, TESTS.get(), "the shorter range cannot see it");
    }

    @Test
    void entriesSurviveGrowth() {
        TrackerVisibilityCache cache = new TrackerVisibilityCache(16);

        for (int i = 0; i < 1000; i++) {
            cache.isVisible(1, i, 0.0, 0.0, 1.0, 0.0, 64.0, DISTANCE);
        }
        assertEquals(1000, cache.size());

        TESTS.set(0);
        for (int i = 0; i < 1000; i++) {
            cache.isVisible(1, i, 0.0, 0.0, 1.0, 0.0, 64.0, DISTANCE);
        }
        assertEquals(0, TESTS.get(), "every one still cached after the table grew");
    }

    @Test
    void theUncachedCostIsWhatItSays() {
        TrackerVisibilityCache cache = new TrackerVisibilityCache();

        assertEquals(5L * 800 * 20, cache.testsWithoutCache(5, 800, 20));
        assertEquals(80_000L, cache.testsWithoutCache(5, 800, 20), "80,000 a second");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrackerVisibilityCache(8));
        assertThrows(IllegalArgumentException.class, () -> new TrackerVisibilityCache(1000));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        TrackerVisibilityCache cache = new TrackerVisibilityCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.invalidations());
    }
}
