package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTickSkipperTest {

    @Test
    void aChunkWithNothingGrowableIsSkipped() {
        // The saving. Plain stone and dirt cannot produce a random-tick event no matter which positions
        // are chosen, so the whole chunk can be left out.
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 0);

        assertTrue(skipper.shouldSkip(0, 0));
        assertEquals(RandomTickSkipper.ATTEMPTS_PER_CHUNK, skipper.attemptsSaved());
    }

    @Test
    void aChunkWithOneGrowableBlockIsNeverSkipped() {
        // The failure this must avoid. Skipping it stops the growth permanently and silently, and a
        // wheat field that never ripens is a broken farm, not a performance problem.
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 1);

        assertFalse(skipper.shouldSkip(0, 0));
        assertEquals(RandomTickSkipper.ATTEMPTS_PER_CHUNK, skipper.attemptsMade());
    }

    @Test
    void placingAGrowableBlockClearsTheSkipImmediately() {
        // There is no periodic rescan to fall back on, because the window before it runs is exactly
        // when the farm stops working. The count is authoritative.
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 0);
        assertTrue(skipper.shouldSkip(0, 0));

        skipper.addGrowable(0, 0, 1);

        assertEquals(1, skipper.growableBlocks(0, 0));
        assertFalse(skipper.shouldSkip(0, 0), "the farm works again on the next tick");
    }

    @Test
    void removingTheLastGrowableBlockRestoresTheSkip() {
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 2);

        skipper.addGrowable(0, 0, -1);
        assertFalse(skipper.shouldSkip(0, 0), "one left");

        skipper.addGrowable(0, 0, -1);
        assertTrue(skipper.shouldSkip(0, 0), "and none left");
    }

    @Test
    void theCountIsClampedAtZero() {
        // A double removal would otherwise make it negative, and a negative count reads as "not zero"
        // so the chunk would tick forever -- the safe direction, but it would never be re-skipped.
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 1);

        skipper.addGrowable(0, 0, -5);

        assertEquals(0, skipper.growableBlocks(0, 0));
        assertTrue(skipper.shouldSkip(0, 0));
    }

    @Test
    void chunkCoordinatesAreNotConfused() {
        // Chunk coordinates are dense and near zero. A hash ignoring the high bits would collide
        // constantly, and accepting a collision gives one chunk another's count -- which is how a farm
        // ends up in a chunk that gets skipped.
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 0);
        skipper.load(1, 0, 5);
        skipper.load(0, 1, 5);
        skipper.load(1, 1, 5);

        assertTrue(skipper.shouldSkip(0, 0));
        assertFalse(skipper.shouldSkip(1, 0));
        assertFalse(skipper.shouldSkip(0, 1));
        assertFalse(skipper.shouldSkip(1, 1));
        assertEquals(4, skipper.chunkCount());
    }

    @Test
    void negativeCoordinatesWork() {
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(-5, -7, 3);
        skipper.load(-5, -8, 0);

        assertFalse(skipper.shouldSkip(-5, -7));
        assertTrue(skipper.shouldSkip(-5, -8));
    }

    @Test
    void anUnloadedChunkIsForgottenAndItsSlotCanBeReused() {
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(3, 4, 9);
        skipper.unload(3, 4);

        assertEquals(0, skipper.growableBlocks(3, 4), "an untracked chunk reads as nothing growable");
        assertEquals(0, skipper.chunkCount());

        // A different chunk hashing to the same slot must not inherit the old count.
        skipper.load(3, 4, 2);
        assertEquals(2, skipper.growableBlocks(3, 4));
    }

    @Test
    void aBlockChangeInAnUnloadedChunkIsIgnored() {
        // Recording it would invent a count from nothing. The load scan is what establishes the truth.
        RandomTickSkipper skipper = new RandomTickSkipper();

        skipper.addGrowable(9, 9, 4);

        assertEquals(0, skipper.chunkCount());
        assertEquals(0, skipper.growableBlocks(9, 9));
    }

    @Test
    void countsSurviveGrowth() {
        RandomTickSkipper skipper = new RandomTickSkipper(16);

        for (int i = 0; i < 500; i++) {
            skipper.load(i, 0, i % 3);
        }

        assertEquals(500, skipper.chunkCount());
        for (int i = 0; i < 500; i++) {
            assertEquals(i % 3, skipper.growableBlocks(i, 0), "chunk " + i);
        }
        // One third have nothing growable.
        assertEquals(167, skipper.skippableCount());
    }

    @Test
    void theSavingIsTheShareOfAttemptsNeverMade() {
        RandomTickSkipper skipper = new RandomTickSkipper();

        // 70% of 100 chunks are plain stone and dirt.
        for (int i = 0; i < 100; i++) {
            skipper.load(i, 0, i < 30 ? 4 : 0);
        }
        for (int i = 0; i < 100; i++) {
            skipper.shouldSkip(i, 0);
        }

        assertEquals(70 * RandomTickSkipper.ATTEMPTS_PER_CHUNK, skipper.attemptsSaved());
        assertEquals(30 * RandomTickSkipper.ATTEMPTS_PER_CHUNK, skipper.attemptsMade());
        assertEquals(70.0, skipper.savedPercent(), 0.01);
    }

    @Test
    void loadingTwiceReplacesTheCount() {
        RandomTickSkipper skipper = new RandomTickSkipper();
        skipper.load(0, 0, 0);
        skipper.load(0, 0, 6);

        assertEquals(6, skipper.growableBlocks(0, 0));
        assertEquals(1, skipper.chunkCount(), "not two entries for one chunk");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RandomTickSkipper(0));
        RandomTickSkipper skipper = new RandomTickSkipper();
        assertThrows(IllegalArgumentException.class, () -> skipper.load(0, 0, -1));
    }

    @Test
    void aFreshSkipperReportsZeroNotNaN() {
        RandomTickSkipper skipper = new RandomTickSkipper();
        assertEquals(0.0, skipper.savedPercent());
        assertEquals(0, skipper.chunkCount());
        assertEquals(0, skipper.skippableCount());
    }
}
