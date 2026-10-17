package net.amdfaster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRuntimeTest {

    private static final long SEED = 98765L;
    private static final int OVERWORLD = 0;
    private static final int NETHER = -1;

    @Test
    void connectingStartsThePacerConservative() {
        // The first measurement is of a quiet moment that is about to stop being quiet.
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);

        assertTrue(runtime.isConnected());
        assertEquals(1L, runtime.connections());
        assertEquals(20.0, runtime.pacer().currentMsPerChunk(), 1e-12);
    }

    @Test
    void disconnectingClearsTheConnection() {
        // Clearing on the way out rather than on the way in means the next connection starts clean whatever
        // it is.
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        runtime.entityUpdate(1, false);
        runtime.disconnect();

        assertFalse(runtime.isConnected());
        assertEquals(0, runtime.pendingEntities());
    }

    @Test
    void reconnectingToTheSameWorldKeepsTheCachedChunks() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        for (int i = 0; i < 40; i++) {
            runtime.chunkStored();
        }
        runtime.disconnect();

        assertEquals(40, runtime.connect(SEED, OVERWORLD), "the same seed and dimension");
    }

    @Test
    void reconnectingToADifferentWorldDiscardsThem() {
        // A chunk cache that cannot tell two worlds apart shows the wrong terrain, which is not a stale
        // picture but a wrong one.
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        for (int i = 0; i < 40; i++) {
            runtime.chunkStored();
        }
        runtime.disconnect();

        assertEquals(0, runtime.connect(SEED, NETHER));
        assertEquals(40L, runtime.chunks().discarded());
    }

    @Test
    void threeUpdatesForOneEntityBecomeOneApplication() {
        // A set of ids rather than a count, because counting updates would pass a number equal to the updates
        // and the coalescer would apply all of them and record nothing coalesced.
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        assertFalse(runtime.entityUpdate(7, false));
        assertFalse(runtime.entityUpdate(7, false));
        assertFalse(runtime.entityUpdate(7, false));
        assertEquals(1, runtime.pendingEntities());

        runtime.endTick();

        assertEquals(0, runtime.pendingEntities());
        assertEquals(3L, runtime.coalescer().received());
        assertEquals(1L, runtime.coalescer().applied());
        assertEquals(2L, runtime.coalescer().coalesced());
    }

    @Test
    void aTeleportIsAppliedImmediatelyAndNotCoalesced() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        assertTrue(runtime.entityUpdate(7, true));
        assertEquals(0, runtime.pendingEntities(), "a teleport is not held for the tick end");
        assertEquals(1L, runtime.coalescer().teleports());
    }

    @Test
    void aTeleportResetsThePacer() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        runtime.acknowledgeChunks(10, 20.0);
        assertEquals(2.0, runtime.pacer().currentMsPerChunk(), 1e-12);

        runtime.teleported();

        assertEquals(20.0, runtime.pacer().currentMsPerChunk(), 1e-12);
    }

    @Test
    void acknowledgingChunksFeedsThePacer() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);

        assertEquals(2.0, runtime.acknowledgeChunks(10, 20.0), 1e-12);
        assertEquals(1.0, runtime.acknowledgeChunks(10, 0.5), 1e-12, "clamped to the floor");
        assertEquals(1L, runtime.pacer().clampedLow());
    }

    @Test
    void aCachedChunkIsDrawableButNotPlayable() {
        // Someone could have dug that tunnel out while the client was disconnected, and a player who walks
        // into a wall that is only a picture falls through the world.
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        runtime.chunkStored();

        assertTrue(runtime.drawCachedChunk());
        assertFalse(runtime.allowGameplayUseOfCachedChunk());
        assertEquals(1L, runtime.chunks().refusedForGameplay());
    }

    @Test
    void endTickWithNothingPendingDoesNothing() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        runtime.endTick();

        assertEquals(0L, runtime.coalescer().ticks());
        assertEquals(0L, runtime.coalescer().received());
    }

    @Test
    void aBusyServerSavesMostOfTheUpdateWork() {
        NetworkRuntime runtime = new NetworkRuntime();

        runtime.connect(SEED, OVERWORLD);
        for (int tick = 0; tick < 20; tick++) {
            for (int entity = 0; entity < 100; entity++) {
                for (int update = 0; update < 3; update++) {
                    runtime.entityUpdate(entity, false);
                }
            }
            runtime.endTick();
        }

        assertEquals(6000L, runtime.coalescer().received());
        assertEquals(2000L, runtime.coalescer().applied());
        assertEquals(4000L, runtime.coalescer().coalesced());
        assertEquals(66.6667, runtime.entityUpdateSavedPercent(), 0.001);
        assertEquals(16.0, runtime.msSavedOnEntityUpdates(), 1e-9);
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has received nothing has technically removed everything, but reporting that as a
        // saving would describe an unused one as though it had measured something.
        NetworkRuntime runtime = new NetworkRuntime();

        assertEquals(0.0, runtime.entityUpdateSavedPercent());
        assertEquals(0.0, runtime.msSavedOnEntityUpdates(), 1e-12);
        assertEquals(0.0, runtime.msSavedOnChunkDownload(), 1e-12);
        assertEquals(0L, runtime.connections());
        assertEquals(0, runtime.pendingEntities());
        assertFalse(runtime.isConnected());
    }
}
