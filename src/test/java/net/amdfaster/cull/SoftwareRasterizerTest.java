package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareRasterizerTest {

    private static SoftwareRasterizer on64x16() {
        return new SoftwareRasterizer(new TileDepthBuffer(64, 16));
    }

    @Test
    void occludersAreRasterisedFrontToBack() {
        // Order does not change the result -- the depth store keeps the nearer surface either way -- it
        // changes the cost. Front to back means a later, farther occluder finds the sub-tiles already
        // written and already covered, so the common case is a compare that fails rather than a write.
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(0, 0, 8, 4, 30.0f);
        rasteriser.addOccluder(0, 0, 8, 4, 10.0f);
        rasteriser.addOccluder(0, 0, 8, 4, 20.0f);
        rasteriser.flushLayer();

        float[] sorted = rasteriser.layerSnapshot();
        assertEquals(0, sorted.length, "the layer is empty after a flush");

        TileDepthBuffer buffer = rasteriser.buffer();
        assertEquals(10.0f, buffer.subDepthAt(0, 0), 0.0f, "the nearest surface wins");
        assertTrue(buffer.isCovered(0, 0));
    }

    @Test
    void theSortMovesWholeRecordsSoARectangleStaysWithItsDepth() {
        // Five floats per record. Moving them a field at a time, or by stride rather than as a block,
        // would separate a rectangle from its depth and every subsequent write would go to the wrong
        // place -- which looks like occlusion working and then randomly deleting chunks.
        float[] records = {
            0, 0, 8, 4, 30.0f,
            10, 0, 18, 4, 10.0f,
            20, 0, 28, 4, 20.0f,
        };
        SoftwareRasterizer.sortByDepthAscending(records, 3);

        assertArrayEquals(new float[] {
            10, 0, 18, 4, 10.0f,
            20, 0, 28, 4, 20.0f,
            0, 0, 8, 4, 30.0f,
        }, records, 0.0f, "each rectangle kept its own depth");
    }

    @Test
    void theSortIsStableForEqualDepths() {
        // Two occluders at the same depth have no defined order, but an unstable sort would make the
        // result depend on things that are not the input, which makes a rendering difference impossible
        // to reproduce.
        float[] records = {
            0, 0, 8, 4, 50.0f,
            10, 0, 18, 4, 50.0f,
            20, 0, 28, 4, 50.0f,
        };
        SoftwareRasterizer.sortByDepthAscending(records, 3);

        assertEquals(0.0f, records[0], 0.0f);
        assertEquals(10.0f, records[5], 0.0f);
        assertEquals(20.0f, records[10], 0.0f);
    }

    @Test
    void aSingleRecordAndAnEmptyLayerAreBothHandled() {
        float[] one = {0, 0, 8, 4, 5.0f};
        SoftwareRasterizer.sortByDepthAscending(one, 1);
        assertArrayEquals(new float[] {0, 0, 8, 4, 5.0f}, one, 0.0f);

        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.flushLayer();
        assertEquals(0, rasteriser.flushes(), "flushing an empty layer does nothing");
    }

    @Test
    void anEmptyRectangleIsNotAccepted() {
        // An empty rectangle occludes nothing. Accepting it would spend a layer slot on something that
        // can only ever cost time, and 64 slots is the whole working set.
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(10, 10, 10, 20, 5.0f);
        rasteriser.addOccluder(10, 20, 20, 10, 5.0f);
        rasteriser.addOccluder(10, 10, 0, 20, 5.0f);

        assertEquals(0, rasteriser.occludersSubmitted());
        assertEquals(0, rasteriser.pendingOccluders());
    }

    @Test
    void theWorkingLayerFlushesWhenItFills() {
        // The layer is bounded so a scene with a thousand occluders cannot make a frame allocate a
        // thousand rectangles. Sixty-four records of five floats is 1280 bytes, which stays in L1
        // alongside the slice of the depth buffer being written.
        SoftwareRasterizer rasteriser = on64x16();
        for (int i = 0; i < SoftwareRasterizer.WORKING_LAYER; i++) {
            rasteriser.addOccluder(0, 0, 8, 4, 100.0f - i);
        }

        assertEquals(SoftwareRasterizer.WORKING_LAYER, rasteriser.pendingOccluders());
        assertEquals(0, rasteriser.flushes(), "exactly full has not overflowed yet");

        rasteriser.addOccluder(0, 0, 8, 4, 1.0f);

        assertEquals(1, rasteriser.flushes(), "the 65th occluder pushed the layer out");
        assertEquals(1, rasteriser.pendingOccluders());
        assertEquals(SoftwareRasterizer.WORKING_LAYER + 1, rasteriser.occludersSubmitted());
    }

    @Test
    void aQueryDoesNotSeeOccludersStillInTheLayer() {
        // This is deliberate. The caller decides whether pending occluders count, because deciding
        // implicitly would be wrong more often than not: interleaving a rasterise with the queries
        // behind it is the whole point of there being no penalty for interleaving.
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(0, 0, 64, 16, 10.0f);

        assertFalse(rasteriser.test(0, 0, 64, 16, 20.0f), "the occluder is still pending");

        rasteriser.complete();

        assertTrue(rasteriser.test(0, 0, 64, 16, 20.0f), "and now it has been rasterised");
    }

    @Test
    void aBatchAgreesWithIndividualTests() {
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(0, 0, 32, 16, 10.0f);
        rasteriser.complete();

        float[] rects = {
            0, 0, 32, 16, 20.0f,     // behind the occluder
            0, 0, 32, 16, 5.0f,      // in front of it
            32, 0, 64, 16, 20.0f,    // outside the covered half
            8, 4, 24, 12, 15.0f,     // behind, inside the covered half
        };
        boolean[] visible = new boolean[4];
        int kept = rasteriser.testBatch(rects, 4, visible);

        assertArrayEquals(new boolean[] {false, true, true, false}, visible);
        assertEquals(2, kept);
    }

    @Test
    void aBatchRejectsMismatchedBuffers() {
        // A silent truncation would cull the tail of the candidate list every frame, and the missing
        // geometry would always be the same geometry.
        SoftwareRasterizer rasteriser = on64x16();
        float[] rects = new float[4 * 5];

        assertThrows(IllegalArgumentException.class,
                () -> rasteriser.testBatch(rects, 4, new boolean[2]));
        assertThrows(IllegalArgumentException.class,
                () -> rasteriser.testBatch(new float[5], 4, new boolean[4]));
    }

    @Test
    void theCullRateIsZeroWhenNothingHasBeenTested() {
        // Zero divided by zero is NaN, which prints as NaN in the HUD and reads like a crash rather than
        // like an empty frame.
        SoftwareRasterizer rasteriser = on64x16();
        assertEquals(0.0, rasteriser.cullRatePercent(), 0.0);
    }

    @Test
    void theCullRateCountsWhatTheFrameActuallyDecided() {
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(0, 0, 64, 16, 10.0f);
        rasteriser.complete();

        rasteriser.test(0, 0, 64, 16, 20.0f);
        rasteriser.test(0, 0, 64, 16, 5.0f);
        rasteriser.test(0, 0, 64, 16, 25.0f);
        rasteriser.test(0, 0, 64, 16, 30.0f);

        assertEquals(4, rasteriser.candidatesTested());
        assertEquals(3, rasteriser.candidatesCulled());
        assertEquals(75.0, rasteriser.cullRatePercent(), 1e-9);
    }

    @Test
    void beginFrameClearsTheBufferAndEveryCounter() {
        SoftwareRasterizer rasteriser = on64x16();
        rasteriser.addOccluder(0, 0, 64, 16, 10.0f);
        rasteriser.complete();
        rasteriser.test(0, 0, 64, 16, 20.0f);

        rasteriser.beginFrame();

        assertEquals(0, rasteriser.occludersSubmitted());
        assertEquals(0, rasteriser.flushes());
        assertEquals(0, rasteriser.pendingOccluders());
        assertEquals(0, rasteriser.candidatesTested());
        assertEquals(0, rasteriser.candidatesCulled());
        assertEquals(TileDepthBuffer.EMPTY, rasteriser.buffer().subDepthAt(0, 0), 0.0f);
        assertFalse(rasteriser.test(0, 0, 64, 16, 20.0f), "last frame's occluder is gone");
    }
}
