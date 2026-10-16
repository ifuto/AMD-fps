package net.amdfaster.mob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnCandidateCacheTest {

    @Test
    void aMarkedSectionIsSkipped() {
        // Seventy attempts a tick, 1,400 a second, and about 98% fail for the same reason every tick: a
        // lit surface does not become dark and open sky does not grow a floor.
        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(100, 64, 100);

        assertFalse(cache.shouldAttempt(100, 64, 100));
        assertEquals(1L, cache.attemptsSkipped());
        assertEquals(0L, cache.attemptsMade());
    }

    @Test
    void anUnmarkedSectionIsStillAttempted() {
        // The failure this must not have. Skipping a section that can host a spawn means the dark cave
        // the player built stops producing anything, and the symptom is "mobs stopped spawning" with
        // nothing to connect it to a renderer mod.
        SpawnCandidateCache cache = new SpawnCandidateCache();

        assertTrue(cache.shouldAttempt(0, 64, 0));
        assertEquals(1L, cache.attemptsMade());
        assertEquals(0L, cache.attemptsSkipped());
    }

    @Test
    void aBlockChangeClearsTheMark() {
        // Placing a torch, digging a hole or water flowing in can all turn an unspawnable section into a
        // spawnable one. There is no periodic re-check to fall back on, because the window before it
        // would run is exactly when the cave stops working.
        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(100, 64, 100);
        assertTrue(cache.isMarked(100, 64, 100));

        cache.invalidate(105, 66, 98);

        assertFalse(cache.isMarked(100, 64, 100), "the same section, cleared");
        assertTrue(cache.shouldAttempt(100, 64, 100));
        assertEquals(1L, cache.invalidations());
    }

    @Test
    void anyBlockInTheSectionClearsIt() {
        // A section is 16x16x16, so a change anywhere in that volume can matter.
        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(0, 64, 0);

        cache.invalidate(15, 79, 15);

        assertFalse(cache.isMarked(0, 64, 0), "the far corner of the same section");
    }

    @Test
    void theNeighbouringSectionIsNotCleared() {
        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(0, 64, 0);
        cache.markUnspawnable(16, 64, 0);

        cache.invalidate(0, 64, 0);

        assertFalse(cache.isMarked(0, 64, 0));
        assertTrue(cache.isMarked(16, 64, 0), "the next section is untouched");
    }

    @Test
    void markingTwiceIsIdempotent() {
        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(0, 64, 0);
        cache.markUnspawnable(5, 70, 5);

        assertEquals(1, cache.size(), "one section, not two entries");
    }

    @Test
    void negativeCoordinatesGroupCorrectly() {
        // floorDiv, not truncation, so negative coordinates group with their neighbours rather than
        // being pulled toward the origin.
        assertEquals(-1, SpawnCandidateCache.sectionOf(-1));
        assertEquals(-1, SpawnCandidateCache.sectionOf(-16));
        assertEquals(-2, SpawnCandidateCache.sectionOf(-17));
        assertEquals(0, SpawnCandidateCache.sectionOf(0));

        SpawnCandidateCache cache = new SpawnCandidateCache();
        cache.markUnspawnable(-1, -1, -1);

        assertFalse(cache.shouldAttempt(-16, -16, -16), "the same section");
        assertTrue(cache.shouldAttempt(-17, -1, -1), "the next one over");
    }

    @Test
    void sectionsSurviveGrowth() {
        SpawnCandidateCache cache = new SpawnCandidateCache(16);

        for (int i = 0; i < 1000; i++) {
            cache.markUnspawnable(i * 16, 64, 0);
        }
        assertEquals(1000, cache.size());

        for (int i = 0; i < 1000; i++) {
            assertFalse(cache.shouldAttempt(i * 16, 64, 0), "section " + i);
        }

        cache.invalidate(500 * 16, 64, 0);
        assertFalse(cache.isMarked(500 * 16, 64, 0), "still findable after the table grew");
    }

    @Test
    void theSavingIsTheShareOfAttemptsNeverMade() {
        SpawnCandidateCache cache = new SpawnCandidateCache();

        // 80% of sections are lit surface or empty air.
        for (int i = 0; i < 100; i++) {
            if (i < 80) {
                cache.markUnspawnable(i * 16, 64, 0);
            }
        }
        for (int i = 0; i < 100; i++) {
            cache.shouldAttempt(i * 16, 64, 0);
        }

        assertEquals(80L, cache.attemptsSkipped());
        assertEquals(20L, cache.attemptsMade());
        assertEquals(80.0, cache.skippedPercent(), 0.01);
        assertEquals(70, SpawnCandidateCache.ATTEMPTS_PER_TICK);
    }

    @Test
    void invalidatingAnUnmarkedSectionDoesNothing() {
        SpawnCandidateCache cache = new SpawnCandidateCache();

        cache.invalidate(0, 64, 0);

        assertEquals(0L, cache.invalidations());
        assertEquals(0, cache.size());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpawnCandidateCache(8));
        assertThrows(IllegalArgumentException.class, () -> new SpawnCandidateCache(1000));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        SpawnCandidateCache cache = new SpawnCandidateCache();

        assertEquals(0.0, cache.skippedPercent());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.attemptsMade());
    }
}
