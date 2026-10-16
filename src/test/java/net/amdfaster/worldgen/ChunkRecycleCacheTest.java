package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRecycleCacheTest {

    @Test
    void aStoredChunkComesBackInsteadOfBeingRegenerated() {
        // Under an elytra the chunks being dropped are the ones just behind the player, which is exactly
        // where they are about to fly back to. Regenerating one costs 8 ms of terrain, decoration and
        // lighting for terrain that was already correct a moment ago.
        ChunkRecycleCache cache = new ChunkRecycleCache();
        Object chunk = new Object();
        cache.store(3, 4, 100L, chunk);

        assertSame(chunk, cache.acquire(3, 4, 100L));
        assertEquals(1L, cache.hits());
    }

    @Test
    void aChunkThatChangedWhileCachedIsRefused() {
        // The failure this exists to avoid. Returning the stale copy would lose the player's edit -- a
        // mined hole refilled, a wall gone -- and it is intermittent in exactly the way that makes it
        // hard to report, because it only happens when they edited a chunk and then left it.
        ChunkRecycleCache cache = new ChunkRecycleCache();
        cache.store(3, 4, 100L, new Object());

        assertNull(cache.acquire(3, 4, 101L), "a newer version means the cache is wrong");
        assertEquals(1L, cache.stale());
        assertEquals(0L, cache.hits());
        assertFalse(cache.contains(3, 4), "and the stale entry is dropped, not kept for another try");
    }

    @Test
    void anOlderVersionIsAlsoRefused() {
        // A rollback is rarer than an edit, but handing back a chunk from the future is the same class of
        // bug in the other direction.
        ChunkRecycleCache cache = new ChunkRecycleCache();
        cache.store(3, 4, 100L, new Object());

        assertNull(cache.acquire(3, 4, 99L));
        assertEquals(1L, cache.stale());
    }

    @Test
    void acquiringRemovesTheEntry() {
        // A chunk handed back to the world is no longer unloaded, so keeping it would let the same
        // object be handed out twice.
        ChunkRecycleCache cache = new ChunkRecycleCache();
        Object chunk = new Object();
        cache.store(0, 0, 1L, chunk);

        assertSame(chunk, cache.acquire(0, 0, 1L));
        assertNull(cache.acquire(0, 0, 1L), "not twice");
        assertEquals(0, cache.size());
    }

    @Test
    void theLeastRecentlyUsedChunkIsEvictedFirst() {
        // The chunks worth keeping are the ones just behind the player, which are also the most recently
        // touched. Insertion order would evict those first and keep the ones from the other end of the
        // flight, which is the opposite of what is wanted.
        ChunkRecycleCache cache = new ChunkRecycleCache(2);
        cache.store(0, 0, 1L, new Object());
        cache.store(1, 0, 1L, new Object());

        cache.acquire(0, 0, 1L);           // 0 becomes the most recent
        cache.store(0, 0, 1L, new Object());
        cache.store(2, 0, 1L, new Object()); // evicts 1, the least recent

        assertFalse(cache.contains(1, 0), "the least recently used one went");
        assertTrue(cache.contains(0, 0));
        assertTrue(cache.contains(2, 0));
        assertEquals(1L, cache.evictions());
    }

    @Test
    void touchKeepsAChunkFromAgeingOut() {
        // A chunk looked at every tick but never removed would still age out on insertion order without
        // this.
        ChunkRecycleCache cache = new ChunkRecycleCache(2);
        cache.store(0, 0, 1L, new Object());
        cache.store(1, 0, 1L, new Object());

        cache.touch(0, 0);
        cache.store(2, 0, 1L, new Object());

        assertTrue(cache.contains(0, 0), "touched, so it stayed");
        assertFalse(cache.contains(1, 0));
    }

    @Test
    void invalidatingDropsTheCachedCopy() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        cache.store(5, 6, 7L, new Object());

        cache.invalidate(5, 6);

        assertFalse(cache.contains(5, 6));
        assertNull(cache.acquire(5, 6, 7L));
    }

    @Test
    void storingTheSameChunkTwiceReplacesIt() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        Object first = new Object();
        Object second = new Object();
        cache.store(0, 0, 1L, first);
        cache.store(0, 0, 2L, second);

        assertEquals(1, cache.size(), "not two entries for one chunk");
        assertSame(second, cache.acquire(0, 0, 2L));
    }

    @Test
    void chunkCoordinatesAreNotConfused() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        Object a = new Object();
        Object b = new Object();
        cache.store(0, 1, 1L, a);
        cache.store(1, 0, 1L, b);

        assertSame(a, cache.acquire(0, 1, 1L));
        assertSame(b, cache.acquire(1, 0, 1L));
    }

    @Test
    void negativeCoordinatesWork() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        Object chunk = new Object();
        cache.store(-3, -4, 9L, chunk);

        assertSame(chunk, cache.acquire(-3, -4, 9L));
    }

    @Test
    void theMemoryCostIsBounded() {
        // 64 chunks at one byte per block is about 6 MB, which is what makes the cache worth having
        // rather than a second copy of the world.
        ChunkRecycleCache cache = new ChunkRecycleCache();

        assertEquals(16 * 16 * 384, ChunkRecycleCache.CHUNK_BYTES);
        assertEquals(64, ChunkRecycleCache.DEFAULT_CAPACITY);
        assertEquals(64L * 16 * 16 * 384, cache.bytesAtCapacity());
        assertEquals(6.0, cache.bytesAtCapacity() / 1024.0 / 1024.0, 0.01);
    }

    @Test
    void theSavingIsReportedAgainstTheGenerationCost() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        for (int i = 0; i < 10; i++) {
            cache.store(i, 0, 1L, new Object());
        }
        for (int i = 0; i < 10; i++) {
            cache.acquire(i, 0, 1L);
        }

        assertEquals(100.0, cache.hitPercent(), 0.01);
        assertEquals(20 * ChunkRecycleCache.GENERATE_MS, cache.msSavedPerSecond(20.0), 0.01);
        assertEquals(160.0, cache.msSavedPerSecond(20.0), 0.01, "20 chunks a second at 8 ms each");
    }

    @Test
    void aCacheOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkRecycleCache(0));
    }

    @Test
    void storingNothingIsRejected() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        assertThrows(IllegalArgumentException.class, () -> cache.store(0, 0, 1L, null));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        ChunkRecycleCache cache = new ChunkRecycleCache();
        assertEquals(0.0, cache.hitPercent());
        assertEquals(0, cache.size());
        assertEquals(0.0, cache.msSavedPerSecond(20.0));
    }
}
