package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndirectDrawBatcherTest {

    @Test
    void aBatchIsOneIndirectDraw() {
        // The CPU issues a handful of draws whatever the scene contains.
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        assertEquals(1, batcher.recordBatch(500));
        assertEquals(1L, batcher.draws());
        assertEquals(500L, batcher.objects());
    }

    @Test
    void everyBatchResetsItsCounter() {
        // Left at last frame's value, this frame's draws would include objects that are no longer visible, and
        // the count would grow until it overflowed.
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        for (int i = 0; i < 10; i++) {
            batcher.recordBatch(500);
        }

        assertEquals(10L, batcher.resets());
        assertEquals(10L, batcher.batches());
    }

    @Test
    void theBarriersAreIssuedInOrder() {
        // Without the barrier between compute and graphics, the draw can start before the write has landed,
        // and the result is a frame drawn from partially written arguments -- not a wrong frame but a different
        // wrong frame each time.
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        for (int i = 0; i < 10; i++) {
            batcher.recordBatch(500);
        }

        assertTrue(batcher.barriersInOrder());
        assertEquals(3, IndirectDrawBatcher.COMPUTE_TO_GRAPHICS + 1, "three stages");
    }

    @Test
    void aFreshBatcherHasNoBarriersToOrder() {
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        assertTrue(batcher.barriersInOrder(), "zero of each is still consistent");
    }

    @Test
    void aThousandSectionsCostAlmostNothingToSubmit() {
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        for (int i = 0; i < 10; i++) {
            batcher.recordBatch(500);
        }

        assertEquals(5000L, batcher.objects());
        assertEquals(10L, batcher.draws());
        assertEquals(0.62, batcher.msSubmitting(), 1e-9, "ten indirect draws plus ten dispatches");
        assertEquals(40.0, batcher.msWithoutIndirect(), 1e-9);
        assertEquals(98.45, batcher.savedPercent(), 0.01);
    }

    @Test
    void anEmptyBatchStillCostsADispatch() {
        // The honest end of the trade: a batch with nothing in it still pays for the dispatch, so a scene
        // split into many empty batches would cost more than it saved.
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        batcher.recordBatch(0);

        assertEquals(0.062, batcher.msSubmitting(), 1e-9);
        assertEquals(0.0, batcher.msWithoutIndirect(), 1e-12);
        assertEquals(0.0, batcher.savedPercent(), 1e-9, "nothing to compare against");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        assertThrows(IllegalArgumentException.class, () -> batcher.recordBatch(-1));
    }

    @Test
    void aFreshBatcherReportsZeroNotNaN() {
        // A batcher that has recorded nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        IndirectDrawBatcher batcher = new IndirectDrawBatcher();

        assertEquals(0.0, batcher.savedPercent());
        assertEquals(0.0, batcher.msSubmitting(), 1e-12);
        assertEquals(0L, batcher.batches());
        assertEquals(0L, batcher.draws());
        assertEquals(0L, batcher.objects());
        assertEquals(0L, batcher.resets());
    }
}
