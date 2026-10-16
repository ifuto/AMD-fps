package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleDrawBatcherTest {

    private static final String RAIN = "textures/particle/rain.png";
    private static final String FLAME = "textures/particle/flame.png";

    @Test
    void particlesOfOneTextureAreOneCall() {
        // A thousand raindrops are a thousand calls, and the calls themselves are what make heavy rain slow,
        // because the CPU spends more time setting up draws than the GPU spends filling them.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        for (int i = 0; i < 1000; i++) {
            batcher.add(RAIN);
        }
        batcher.endFrame();

        assertEquals(1000L, batcher.added());
        assertEquals(1L, batcher.calls(), "one draw call for the whole storm");
    }

    @Test
    void aDifferentTextureFlushesTheBatchFirst() {
        // The texture is bound once per draw call, so mixing them would draw every particle with whichever
        // texture was bound last -- rain drawn as flame.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        for (int i = 0; i < 10; i++) {
            batcher.add(RAIN);
        }
        batcher.add(FLAME);

        assertEquals(2L, batcher.textureSwitches());
        assertEquals(10, batcher.buffered(), "the rain was flushed, the flame is buffered");
        assertEquals(1L, batcher.calls());
    }

    @Test
    void aFullBufferFlushesRatherThanOverflowing() {
        // Dropping the excess would make particles disappear, and growing without bound would defeat the
        // point of having one.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        for (int i = 0; i < ParticleDrawBatcher.BATCH_SIZE + 1; i++) {
            batcher.add(RAIN);
        }

        assertEquals(1L, batcher.flushesFull());
        assertEquals(1, batcher.buffered(), "the overflow started a new buffer");
    }

    @Test
    void aPartiallyFilledBatchIsDrawnAtTheEndOfTheFrame() {
        // A batch that is never full would otherwise wait forever for more particles to arrive, so this
        // bounds the delay to one frame and means a scene with few particles still draws them.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        batcher.add(RAIN);
        assertTrue(batcher.endFrame());

        assertEquals(1L, batcher.calls());
        assertEquals(0, batcher.buffered());
        assertFalse(batcher.endFrame(), "nothing left, so nothing flushed");
    }

    @Test
    void anEmptyBatcherIssuesNoCalls() {
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        assertFalse(batcher.endFrame());
        assertEquals(0L, batcher.calls());
    }

    @Test
    void heavyRainIsAlmostAllOneCall() {
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        for (int frame = 0; frame < 10; frame++) {
            for (int i = 0; i < 2000; i++) {
                batcher.add(RAIN);
            }
            batcher.endFrame();
        }

        assertEquals(20_000L, batcher.added());
        assertEquals(20L, batcher.calls(), "two per frame, since a buffer holds 1024");
        assertEquals(0.001, batcher.callsPerParticle(), 1e-9);
        assertEquals(20_000 * ParticleDrawBatcher.MS_PER_CALL, batcher.msWithoutBatching(), 1e-9);
        assertEquals(99.9, batcher.savedPercent(), 1e-9);
    }

    @Test
    void alternatingTexturesGainsNothing() {
        // The honest case: batching only helps when the particles actually share a texture.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        for (int i = 0; i < 100; i++) {
            batcher.add(i % 2 == 0 ? RAIN : FLAME);
        }
        batcher.endFrame();

        assertEquals(100L, batcher.added());
        assertEquals(100L, batcher.calls(), "a flush per particle");
        assertEquals(0.0, batcher.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        assertThrows(IllegalArgumentException.class, () -> batcher.add(null));
        assertThrows(IllegalArgumentException.class, () -> batcher.add(""));
    }

    @Test
    void aFreshBatcherReportsZeroNotNaN() {
        // A batcher that has drawn nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        ParticleDrawBatcher batcher = new ParticleDrawBatcher();

        assertEquals(0.0, batcher.savedPercent());
        assertEquals(0.0, batcher.callsPerParticle());
        assertEquals(0.0, batcher.msCalls());
        assertEquals(0L, batcher.added());
        assertEquals(0L, batcher.calls());
        assertEquals(0, batcher.buffered());
        assertEquals(1024, ParticleDrawBatcher.BATCH_SIZE);
    }
}
