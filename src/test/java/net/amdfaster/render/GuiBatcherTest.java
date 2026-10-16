package net.amdfaster.render;

import net.amdfaster.render.GuiBatcher.Batch;
import net.amdfaster.render.GuiBatcher.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiBatcherTest {

    private static Element element(int atlas) {
        return new Element(atlas, 0, 0, 0, 9, 9, 0);
    }

    @Test
    void elementsSharingAnAtlasBecomeOneDraw() {
        // Hearts, hunger, the experience bar and nine hotbar slots are each drawn on their own, and each
        // carries state changes: two hundred at 0.02 ms is 4 ms a frame, a quarter of the budget, on the
        // one part of the screen that never moves.
        GuiBatcher batcher = new GuiBatcher();

        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            elements.add(element(1));
        }
        List<Batch> out = batcher.batchFrame(elements);

        assertEquals(1, out.size());
        assertEquals(200, out.get(0).count());
        assertEquals(99.5, batcher.reducedPercent(), 0.01);
        assertTrue(GuiBatcher.coversAll(out, 200));
    }

    @Test
    void aDifferentAtlasStartsANewBatch() {
        // Vanilla's HUD is one atlas, but a mod's overlay may not be, and drawing its quads with the
        // wrong texture would show another mod's icons in the corner of the screen.
        GuiBatcher batcher = new GuiBatcher();

        List<Batch> out = batcher.batchFrame(List.of(
                element(1), element(1), element(2), element(1)));

        assertEquals(3, out.size());
        assertEquals(2, out.get(0).count());
        assertEquals(1, out.get(1).count());
        assertEquals(2, out.get(1).atlas());
    }

    @Test
    void aDifferentBlendModeStartsANewBatch() {
        // Translucent elements drawn opaque would lose their see-through quality.
        GuiBatcher batcher = new GuiBatcher();

        List<Batch> out = batcher.batchFrame(List.of(
                new Element(1, 0, 0, 0, 9, 9, 0),
                new Element(1, 1, 0, 0, 9, 9, 0)));

        assertEquals(2, out.size());
    }

    @Test
    void submissionOrderIsTheDrawOrder() {
        // The HUD is layered: hearts over the hotbar, the experience bar over the hearts, a status icon
        // over all of it. Drawing them in the wrong order puts one behind something it should cover, on
        // the part of the screen the player looks at most.
        GuiBatcher batcher = new GuiBatcher();
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            elements.add(new Element(1, 0, i, 0, 9, 9, i));
        }

        List<Batch> out = batcher.batchFrame(elements);

        assertEquals(1, out.size());
        assertEquals(0, out.get(0).firstElement(), "the batch starts at the first element");
        assertEquals(10, out.get(0).count(), "and covers them in order");
        assertTrue(GuiBatcher.preservesOrder(out.get(0)));
    }

    @Test
    void everyElementIsCoveredExactlyOnce() {
        // A batching that silently dropped an element would remove it from the HUD, and a missing heart
        // is something a player notices before they notice the frame rate.
        GuiBatcher batcher = new GuiBatcher();
        List<Element> elements = List.of(
                element(1), element(1), element(2), element(2), element(2), element(1));

        List<Batch> out = batcher.batchFrame(elements);

        assertEquals(3, out.size());
        assertTrue(GuiBatcher.coversAll(out, 6));
    }

    @Test
    void aGapInTheCoverageIsDetected() {
        GuiBatcher batcher = new GuiBatcher();

        // Two batches that skip element 1.
        List<Batch> broken = List.of(new Batch(1, 0, 0, 1), new Batch(1, 0, 2, 1));

        assertFalse(GuiBatcher.coversAll(broken, 3), "element 1 was dropped");
    }

    @Test
    void anEmptyFrameProducesNoBatches() {
        GuiBatcher batcher = new GuiBatcher();

        assertTrue(batcher.batchFrame(List.of()).isEmpty());
        assertEquals(0.0, batcher.reducedPercent());
    }

    @Test
    void aSingleElementIsItsOwnBatch() {
        GuiBatcher batcher = new GuiBatcher();

        List<Batch> out = batcher.batchFrame(List.of(element(1)));

        assertEquals(1, out.size());
        assertEquals(0.0, batcher.reducedPercent(), 0.01);
    }

    @Test
    void aRealisticHudIsReducedByAboutEightyFivePercent() {
        GuiBatcher batcher = new GuiBatcher();

        // Two hundred elements, 85% of them on the vanilla atlas, in runs.
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            elements.add(element(i < 170 ? 1 : (i % 3 == 0 ? 2 : 3)));
        }

        List<Batch> out = batcher.batchFrame(elements);

        assertTrue(batcher.reducedPercent() > 80.0, "was " + batcher.reducedPercent());
        assertEquals(4.0, batcher.msBefore(), 1e-9);
        assertTrue(batcher.msAfter() < 1.0, "was " + batcher.msAfter());
    }

    @Test
    void canBatchComparesAtlasAndBlend() {
        Element base = new Element(1, 0, 0, 0, 9, 9, 0);

        assertTrue(GuiBatcher.canBatch(base, base));
        assertFalse(GuiBatcher.canBatch(base, new Element(2, 0, 0, 0, 9, 9, 0)), "atlas");
        assertFalse(GuiBatcher.canBatch(base, new Element(1, 1, 0, 0, 9, 9, 0)), "blend");
        assertTrue(GuiBatcher.canBatch(base, new Element(1, 0, 50, 50, 9, 9, 7)),
                "position and depth do not matter to the state");
    }

    @Test
    void aFreshBatcherReportsZeroNotNaN() {
        GuiBatcher batcher = new GuiBatcher();

        assertEquals(0.0, batcher.reducedPercent());
        assertEquals(0L, batcher.elementsIn());
        assertEquals(0.0, batcher.msAfter());
        assertEquals(0.02, GuiBatcher.MS_PER_ELEMENT, 1e-9);
    }
}
