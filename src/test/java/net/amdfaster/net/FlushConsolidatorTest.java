package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushConsolidatorTest {

    @Test
    void aTicksPacketsGoOutInOneFlush() {
        // The syscalls are the expensive part when the packets are small, which is most of them. Holding the
        // writes until the end of the tick turns dozens of syscalls into one.
        FlushConsolidator consolidator = new FlushConsolidator();

        for (int i = 0; i < 40; i++) {
            assertFalse(consolidator.write(false), "held until the tick ends");
        }
        assertEquals(40, consolidator.pending());

        assertTrue(consolidator.endTick());

        assertEquals(1L, consolidator.flushes());
        assertEquals(0, consolidator.pending());
    }

    @Test
    void anUrgentPacketGoesImmediately() {
        // A disconnect has to reach the other end now, not at the end of the tick: a player who has been
        // kicked should see it immediately.
        FlushConsolidator consolidator = new FlushConsolidator();

        consolidator.write(false);
        assertTrue(consolidator.write(true));

        assertEquals(1L, consolidator.flushes());
        assertEquals(1L, consolidator.immediate());
        assertEquals(0, consolidator.pending(), "the held packet went with it");
    }

    @Test
    void aFullBatchFlushesEarly() {
        FlushConsolidator consolidator = new FlushConsolidator();

        for (int i = 0; i < FlushConsolidator.BATCH_LIMIT - 1; i++) {
            consolidator.write(false);
        }
        assertTrue(consolidator.write(false), "the batch is full");

        assertEquals(1L, consolidator.earlyFlushes());
        assertEquals(0, consolidator.pending());
    }

    @Test
    void anEmptyTickFlushesNothing() {
        FlushConsolidator consolidator = new FlushConsolidator();

        assertFalse(consolidator.endTick());
        assertEquals(0L, consolidator.flushes());
    }

    @Test
    void chunkLoadingIsAlmostAllOneFlushATick() {
        // A single-player game runs an integrated server in the same process, and the connection is still a
        // network connection with the same flushes.
        FlushConsolidator consolidator = new FlushConsolidator();

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 200; i++) {
                consolidator.write(false);
            }
            consolidator.endTick();
        }

        assertEquals(4000L, consolidator.written());
        assertEquals(40L, consolidator.flushes(),
                "two per tick: one when the 128 batch fills, one at the end of the tick");
        assertEquals(20L, consolidator.earlyFlushes());
        assertEquals(100.0, consolidator.packetsPerFlush(), 1e-9);
        assertEquals(4000 * FlushConsolidator.MS_PER_FLUSH,
                consolidator.msWithoutConsolidation(), 1e-9);
        assertEquals(99.0, consolidator.savedPercent(), 1e-9);
    }

    @Test
    void everyPacketUrgentGainsNothing() {
        // The honest case: consolidation only helps when the packets can wait for each other.
        FlushConsolidator consolidator = new FlushConsolidator();

        for (int i = 0; i < 100; i++) {
            consolidator.write(true);
        }

        assertEquals(100L, consolidator.flushes());
        assertEquals(0.0, consolidator.savedPercent(), 1e-9);
    }

    @Test
    void aFreshConsolidatorReportsZeroNotNaN() {
        // A consolidator that has sent nothing has technically avoided every flush, but reporting that as a
        // saving would describe an unused one as though it had done something.
        FlushConsolidator consolidator = new FlushConsolidator();

        assertEquals(0.0, consolidator.savedPercent());
        assertEquals(0.0, consolidator.packetsPerFlush());
        assertEquals(0.0, consolidator.msFlushing(), 1e-12);
        assertEquals(0L, consolidator.written());
        assertEquals(0L, consolidator.flushes());
        assertEquals(0, consolidator.pending());
        assertEquals(128, FlushConsolidator.BATCH_LIMIT);
    }
}
