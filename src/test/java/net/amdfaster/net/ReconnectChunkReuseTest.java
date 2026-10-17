package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectChunkReuseTest {

    private static final long SEED = 12345L;
    private static final int OVERWORLD = 0;
    private static final int NETHER = -1;

    /** Fills the cache as a previous connection would have, by storing chunks as they arrived. */
    private static ReconnectChunkReuse withCache(int chunks) {
        ReconnectChunkReuse reuse = new ReconnectChunkReuse();
        reuse.connect(SEED, OVERWORLD);
        for (int i = 0; i < chunks; i++) {
            reuse.store();
        }
        assertEquals(chunks, reuse.cached());
        return reuse;
    }

    @Test
    void theCacheIsAdoptedWhenTheWorldMatches() {
        // The world did not move, so the same terrain is the same terrain.
        ReconnectChunkReuse reuse = new ReconnectChunkReuse();

        reuse.connect(SEED, OVERWORLD);
        assertEquals(0, reuse.cached(), "nothing from a first connection");
    }

    @Test
    void aDifferentWorldDiscardsEverything() {
        // Two servers can share a hostname and be different worlds, and the same server can reset. Showing
        // part of the old one would be wrong everywhere rather than in the places that changed.
        ReconnectChunkReuse reuse = withCache(50);

        assertEquals(0, reuse.connect(SEED + 1, OVERWORLD));
        assertEquals(50L, reuse.discarded());
        assertEquals(0, reuse.cached());
    }

    @Test
    void aDifferentDimensionIsAlsoADifferentWorld() {
        ReconnectChunkReuse reuse = withCache(50);

        assertEquals(0, reuse.connect(SEED, NETHER));
        assertEquals(50L, reuse.discarded());
    }

    @Test
    void reconnectingToTheSameWorldKeepsTheCache() {
        ReconnectChunkReuse reuse = withCache(50);

        assertEquals(50, reuse.connect(SEED, OVERWORLD), "the same seed and dimension");
        assertEquals(0L, reuse.discarded());
    }

    @Test
    void aCachedChunkIsDrawnWhileTheRealOneDownloads() {
        ReconnectChunkReuse reuse = withCache(100);

        assertTrue(reuse.connect(SEED, OVERWORLD) >= 0);
        assertTrue(reuse.drawCached());
        assertTrue(reuse.drawn() > 0);
    }

    @Test
    void aRealChunkReplacesTheCachedOne() {
        // Keeping both would mean two answers for one place, and whichever is drawn last would decide what
        // the player sees -- a bug that appears and disappears depending on timing.
        ReconnectChunkReuse reuse = withCache(10);

        reuse.receive();

        assertEquals(1L, reuse.replaced());
        assertEquals(9, reuse.cached());
    }

    @Test
    void gameplayUseIsAlwaysRefused() {
        // Someone could have dug that tunnel out while the client was disconnected, and a player who walks
        // into a wall that is only a picture falls through the world.
        ReconnectChunkReuse reuse = withCache(10);

        assertFalse(reuse.allowGameplayUse());
        assertFalse(reuse.allowGameplayUse());
        assertEquals(2L, reuse.refusedForGameplay());
    }

    @Test
    void drawingNothingWhenTheCacheIsEmpty() {
        ReconnectChunkReuse reuse = new ReconnectChunkReuse();

        reuse.connect(SEED, OVERWORLD);

        assertFalse(reuse.drawCached());
        assertEquals(0L, reuse.drawn());
        assertEquals(0, reuse.cached());
    }

    @Test
    void theWaitIsMostlyRemoved() {
        // The download is not avoided for good -- the server still sends the chunk -- so this is the wait
        // removed, not bandwidth saved.
        ReconnectChunkReuse reuse = withCache(100);

        for (int i = 0; i < 100; i++) {
            reuse.drawCached();
        }

        assertEquals(400.0, reuse.msSaved(), 1e-9);
        assertEquals(5.0, reuse.msDrawing(), 1e-9);
        assertEquals(98.75, reuse.savedPercent(), 1e-9);
    }

    @Test
    void theWorldKeyDistinguishesSeedAndDimension() {
        assertFalse(ReconnectChunkReuse.worldKey(SEED, OVERWORLD)
                == ReconnectChunkReuse.worldKey(SEED, NETHER));
        assertFalse(ReconnectChunkReuse.worldKey(SEED, OVERWORLD)
                == ReconnectChunkReuse.worldKey(SEED + 1, OVERWORLD));
        assertEquals(ReconnectChunkReuse.worldKey(SEED, OVERWORLD),
                ReconnectChunkReuse.worldKey(SEED, OVERWORLD));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has drawn nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        ReconnectChunkReuse reuse = new ReconnectChunkReuse();

        assertEquals(0.0, reuse.savedPercent());
        assertEquals(0.0, reuse.msSaved(), 1e-12);
        assertEquals(0.0, reuse.msDrawing(), 1e-12);
        assertEquals(0L, reuse.drawn());
        assertEquals(0L, reuse.discarded());
        assertEquals(0, reuse.cached());
        assertEquals(4.0, ReconnectChunkReuse.MS_PER_DOWNLOAD, 1e-12);
    }
}
