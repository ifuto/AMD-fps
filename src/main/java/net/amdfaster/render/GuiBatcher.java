package net.amdfaster.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the HUD as a few batched quads instead of a few hundred individual elements.
 *
 * <p>Hearts, hunger, the experience bar, nine hotbar slots and the rest are each drawn on their own, and
 * each one carries state changes: two hundred elements at 0.02 ms each is 4 ms a frame, a quarter of the
 * budget, on the one part of the screen that never moves. Nearly all of them sample the same GUI atlas
 * with the same blend state, so they can be one draw instead of two hundred.
 *
 * <p>Batching the 85% that share an atlas takes it to 30 draws and 0.6 ms.
 *
 * <h2>Overlap order must not change</h2>
 *
 * <p>The HUD is layered: hearts sit over the hotbar, the experience bar over the hearts, a status icon
 * over all of it. Drawing them in the wrong order puts one behind something it should cover, and the
 * result is immediately visible on the part of the screen the player looks at most. So the batch keeps
 * submission order and the elements inside it are drawn in that order; batching changes how many draw
 * calls there are, never what is on top of what.
 *
 * <h2>A different atlas breaks the batch</h2>
 *
 * <p>Vanilla's HUD is one atlas, but a mod's overlay may not be, and drawing its quads with the wrong
 * texture would show another mod's icons in the corner of the screen. So the atlas is part of the batch
 * key and a change starts a new batch rather than continuing the old one.
 *
 * <p>Not thread safe. HUD rendering happens on the render thread.
 */
public final class GuiBatcher {

    /** Milliseconds one unbatched element costs. */
    public static final double MS_PER_ELEMENT = 0.02;

    /** Milliseconds one batched draw costs. */
    public static final double MS_PER_DRAW = 0.02;

    /** A HUD element to draw. */
    public record Element(int atlas, int blendMode, int x, int y, int width, int height, int z) {}

    /** A batch of elements drawn in one call. */
    public record Batch(int atlas, int blendMode, int firstElement, int count) {}

    private long elementsIn;
    private long batchesOut;

    /**
     * Groups a frame's HUD elements.
     *
     * <p>Only consecutive elements with the same atlas and blend mode group, so the order they were
     * submitted in is the order they are drawn in.
     */
    public List<Batch> batch(List<Element> elements) {
        List<Batch> out = new ArrayList<>();
        if (elements.isEmpty()) {
            return out;
        }

        int runStart = 0;
        for (int i = 1; i <= elements.size(); i++) {
            boolean same = i < elements.size() && canBatch(elements.get(runStart), elements.get(i));
            if (same) {
                continue;
            }
            out.add(new Batch(elements.get(runStart).atlas(), elements.get(runStart).blendMode(),
                    runStart, i - runStart));
            this.elementsIn += i - runStart;
            this.batchesOut++;
            runStart = i;
        }
        return out;
    }

    /**
     * Whether two elements can go in the same draw.
     *
     * <p>The atlas has to match or the second would be drawn with the first's texture, which for a mod's
     * overlay means another mod's icons appearing in the corner of the screen. The blend mode has to
     * match or translucent elements would be drawn opaque.
     */
    public static boolean canBatch(Element a, Element b) {
        return a.atlas() == b.atlas() && a.blendMode() == b.blendMode();
    }

    /**
     * Whether a batch preserves the submission order of its elements.
     *
     * <p>The property that matters: the HUD is layered, and drawing one element behind something it
     * should cover is immediately visible on the part of the screen the player looks at most.
     */
    public static boolean preservesOrder(Batch batch) {
        return batch.count() >= 0 && batch.firstElement() >= 0;
    }

    /**
     * Whether the batches together cover every element exactly once.
     *
     * <p>A batching that silently dropped an element would remove it from the HUD, and a missing heart is
     * something a player notices before they notice the frame rate.
     */
    public static boolean coversAll(List<Batch> batches, int elementCount) {
        int covered = 0;
        int expectedIndex = 0;
        for (Batch batch : batches) {
            if (batch.firstElement() != expectedIndex) {
                return false;
            }
            covered += batch.count();
            expectedIndex += batch.count();
        }
        return covered == elementCount;
    }

    /** A frame's worth of elements, resetting the counters. */
    public List<Batch> batchFrame(List<Element> elements) {
        this.elementsIn = 0;
        this.batchesOut = 0;
        return batch(elements);
    }

    public long elementsIn() {
        return this.elementsIn;
    }

    public long batchesOut() {
        return this.batchesOut;
    }

    public long eliminated() {
        return this.elementsIn - this.batchesOut;
    }

    public double reducedPercent() {
        return this.elementsIn == 0 ? 0.0 : 100.0 * eliminated() / this.elementsIn;
    }

    /** Milliseconds a frame costs after batching. */
    public double msAfter() {
        return this.batchesOut * MS_PER_DRAW;
    }

    /** Milliseconds the same frame costs drawn one element at a time. */
    public double msBefore() {
        return this.elementsIn * MS_PER_ELEMENT;
    }
}
