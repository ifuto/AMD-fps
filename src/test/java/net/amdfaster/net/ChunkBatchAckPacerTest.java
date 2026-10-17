package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBatchAckPacerTest {

    @Test
    void itStartsConservativeBeforeMeasuringAnything() {
        // A teleport invalidates every chunk the client has, so the next batch is the start of a few hundred
        // rather than a trickle.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(ChunkBatchAckPacer.CONSERVATIVE_MS_PER_CHUNK, pacer.currentMsPerChunk(), 1e-12);
        assertEquals(20.0, pacer.currentMsPerChunk(), 1e-12);
    }

    @Test
    void itReportsWhatItMeasured() {
        // The server cannot know how long this client takes, so the protocol asks for a measurement rather
        // than a guess.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(2.0, pacer.acknowledge(10, 20.0), 1e-12);
        assertEquals(1L, pacer.batches());
        assertEquals(10L, pacer.chunks());
        assertEquals(0L, pacer.clampedLow());
    }

    @Test
    void aVeryFastMeasurementIsRaisedToTheFloor() {
        // A zero or a fraction would tell the server this client can absorb chunks instantly, and it would
        // send everything at once -- the flood this exists to prevent, caused by the thing meant to prevent
        // it.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(ChunkBatchAckPacer.MIN_MS_PER_CHUNK, pacer.acknowledge(10, 0.5), 1e-12);
        assertEquals(1L, pacer.clampedLow());
    }

    @Test
    void aVerySlowMeasurementIsLoweredToTheCeiling() {
        // A huge number would tell the server the client is hopeless and the world would never load.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(ChunkBatchAckPacer.MAX_MS_PER_CHUNK, pacer.acknowledge(10, 5000.0), 1e-12);
        assertEquals(1L, pacer.clampedHigh());
    }

    @Test
    void anOrdinarySlowMeasurementIsNotClamped() {
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(50.0, pacer.acknowledge(10, 500.0), 1e-12);
        assertEquals(0L, pacer.clampedHigh());
        assertEquals(0L, pacer.clampedLow());
    }

    @Test
    void theBatchSizeFitsTheFrameBudget() {
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        pacer.acknowledge(10, 20.0);
        assertEquals(4, pacer.chunksPerBatch(), "8 ms of budget at 2 ms a chunk");

        pacer.acknowledge(10, 5.0);
        assertEquals(8, pacer.chunksPerBatch(), "clamped to the floor, so a bigger batch");
    }

    @Test
    void aVerySlowClientStillGetsOneChunk() {
        // Bounded below at one, so a slow machine makes progress rather than stalling at zero.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        pacer.acknowledge(10, 5000.0);
        assertEquals(1, pacer.chunksPerBatch());
    }

    @Test
    void theBatchSizeIsCappedByTheProtocol() {
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        pacer.acknowledge(100, 100.0);
        assertTrue(pacer.chunksPerBatch() <= ChunkBatchAckPacer.MAX_UNACKED_BATCHES);
        assertEquals(10, ChunkBatchAckPacer.MAX_UNACKED_BATCHES);
    }

    @Test
    void aTeleportResetsThePace() {
        // The measurement taken just before is of a quiet moment that is about to stop being quiet, so
        // starting from the slow end means the first frames after a teleport are smooth.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        pacer.acknowledge(10, 20.0);
        assertEquals(2.0, pacer.currentMsPerChunk(), 1e-12);

        pacer.resetForTeleport();

        assertEquals(20.0, pacer.currentMsPerChunk(), 1e-12);
        assertEquals(1L, pacer.resets());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertThrows(IllegalArgumentException.class, () -> pacer.acknowledge(0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> pacer.acknowledge(-1, 10.0));
        assertThrows(IllegalArgumentException.class, () -> pacer.acknowledge(10, -1.0));
    }

    @Test
    void aFreshPacerReportsZeroNotNaN() {
        // A pacer that has measured nothing has technically needed no clamping, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        ChunkBatchAckPacer pacer = new ChunkBatchAckPacer();

        assertEquals(0.0, pacer.clampedPercent());
        assertEquals(0.0, pacer.averageMsPerChunk(), 1e-12);
        assertEquals(0L, pacer.batches());
        assertEquals(0L, pacer.chunks());
        assertEquals(0L, pacer.resets());
    }
}
