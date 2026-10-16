package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedChunkCacheTest {

    @Test
    void aRetainedChunkIsDrawableAfterTheServerStopsSendingIt() {
        // Terrain already received is, in the ordinary case, unchanged, so drawing it anyway extends the
        // visible distance without asking the server for anything.
        RetainedChunkCache cache = new RetainedChunkCache();

        cache.receive(10, 20);
        cache.retain(10, 20);

        assertTrue(cache.restore(10, 20));
        assertEquals(1L, cache.retained(), "marking a received chunk as retained counts");
        assertEquals(1L, cache.hits());
    }

    @Test
    void theServersAnswerAlwaysWins() {
        // Retained terrain can be stale: someone may have dug it out, built on it or set it on fire while
        // nobody was watching. A chunk arriving from the server replaces the retained copy with no
        // comparison and no preference for the older one.
        RetainedChunkCache cache = new RetainedChunkCache();

        cache.receive(10, 20);
        cache.retain(10, 20);

        assertTrue(cache.receive(10, 20), "the real chunk replaced the retained copy");
        assertEquals(1L, cache.replaced());
        assertFalse(cache.restore(10, 20), "and the live chunk is not served from the retained cache");
    }

    @Test
    void aChunkNeverSeenIsNotDrawable() {
        RetainedChunkCache cache = new RetainedChunkCache();

        assertFalse(cache.restore(99, 99));
        assertEquals(1L, cache.misses());
    }

    @Test
    void anOverflowEvictsTheOldestRetainedChunk() {
        // Retained chunks are memory, and a long walk in one direction would accumulate every chunk ever
        // seen until the client runs out.
        RetainedChunkCache cache = new RetainedChunkCache();

        for (int i = 0; i < RetainedChunkCache.CAPACITY; i++) {
            cache.receive(i, 0);
            cache.retain(i, 0);
        }
        cache.receive(9_999, 0);

        assertEquals(1L, cache.evicted());
        assertFalse(cache.restore(0, 0), "the oldest was dropped");
        assertFalse(cache.restore(9_999, 0),
                "the new one is live, so it is drawn from the real chunk and not served from here");
    }

    @Test
    void aLiveChunkIsNeverEvicted() {
        // The server would have to send it again to draw it at all, so dropping it costs a download.
        RetainedChunkCache cache = new RetainedChunkCache();

        for (int i = 0; i < RetainedChunkCache.CAPACITY; i++) {
            cache.receive(i, 0);
        }

        assertThrows(IllegalStateException.class, () -> cache.receive(9_999, 0));
    }

    @Test
    void theKeyDoesNotCollide() {
        // Packed rather than combined by addition, which would collide.
        assertEquals(RetainedChunkCache.key(0, 1), RetainedChunkCache.key(0, 1));
        assertFalse(RetainedChunkCache.key(1, 0) == RetainedChunkCache.key(0, 1),
                "x and z are not interchangeable");
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache asked nothing has technically always succeeded, but reporting a hit rate for an unused
        // one reads as a result when it is the absence of one.
        RetainedChunkCache cache = new RetainedChunkCache();

        assertEquals(0.0, cache.hitPercent());
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0L, cache.retained());
        assertEquals(0L, cache.replaced());
        assertEquals(0L, cache.hits());
        assertEquals(0L, cache.misses());
        assertEquals(4096, RetainedChunkCache.CAPACITY);
    }
}
