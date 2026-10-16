package net.amdfaster.draw;

import net.amdfaster.draw.DrawCallMerger.Draw;
import net.amdfaster.draw.DrawCallMerger.Merged;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawCallMergerTest {

    private static Draw draw(int texture, int first, int count) {
        return new Draw(texture, 0, 0, 0, first, count);
    }

    @Test
    void consecutiveDrawsWithTheSameStateBecomeOne() {
        // Every draw costs the driver work before a triangle is rasterised. Three thousand of them at 10
        // microseconds each is 30 ms of CPU -- nearly twice the frame budget, on bookkeeping.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                draw(1, 0, 100),
                draw(1, 100, 100),
                draw(1, 200, 100)));

        assertEquals(1, out.size());
        assertEquals(0, out.get(0).firstVertex());
        assertEquals(300, out.get(0).vertexCount(), "all three runs' geometry");
        assertEquals(3, out.get(0).mergedCount());
        assertEquals(66.67, merger.reducedPercent(), 0.01);
    }

    @Test
    void aDifferentTextureBreaksTheRun() {
        // Merging these draws one of them with the other's texture, which is visible on every face that
        // uses it.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                draw(1, 0, 100),
                draw(2, 100, 100),
                draw(1, 200, 100)));

        assertEquals(3, out.size(), "nothing merges");
        assertEquals(0L, merger.eliminated());
    }

    @Test
    void aDifferentBlendModeBreaksTheRun() {
        // Translucent geometry drawn as opaque loses its see-through quality, which is worse than a wrong
        // texture and subtler.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                new Draw(1, 0, 0, 0, 0, 100),
                new Draw(1, 0, 1, 0, 100, 100)));

        assertEquals(2, out.size());
    }

    @Test
    void aDifferentDepthTestBreaksTheRun() {
        // A depth setting carried over from the previous draw can make things draw through walls.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                new Draw(1, 0, 0, 0, 0, 100),
                new Draw(1, 0, 0, 1, 100, 100)));

        assertEquals(2, out.size());
    }

    @Test
    void aGapInTheGeometryBreaksTheRun() {
        // One draw covers a contiguous range. Two runs with a gap between them would need the gap drawn
        // too, or the geometry reordered to close it.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                draw(1, 0, 100),
                draw(1, 500, 100)));

        assertEquals(2, out.size(), "not adjacent, so not one draw");
    }

    @Test
    void adjacentGeometryInTheSameRunIsCoveredExactly() {
        // Merging must not lose geometry or draw any twice. A run that silently dropped a section would
        // show as a hole in the terrain, which players report as a broken world.
        DrawCallMerger merger = new DrawCallMerger();
        List<Draw> original = List.of(draw(1, 0, 100), draw(1, 100, 50), draw(1, 150, 25));

        List<Merged> out = merger.mergeFrame(original);

        assertEquals(1, out.size());
        assertTrue(DrawCallMerger.covers(out.get(0), original));
    }

    @Test
    void orderIsNeverChanged() {
        // Translucent geometry is drawn back to front and blending is order-dependent: A over B is not B
        // over A. Only consecutive draws merge, so nothing is reordered.
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(
                draw(1, 0, 10),
                draw(2, 10, 10),
                draw(1, 20, 10)));

        assertEquals(1, out.get(0).texture());
        assertEquals(2, out.get(1).texture());
        assertEquals(1, out.get(2).texture(), "the run is not reordered to group them");
    }

    @Test
    void anEmptyFrameMergesToNothing() {
        DrawCallMerger merger = new DrawCallMerger();

        assertTrue(merger.mergeFrame(List.of()).isEmpty());
        assertEquals(0.0, merger.reducedPercent());
    }

    @Test
    void aSingleDrawIsUnchanged() {
        DrawCallMerger merger = new DrawCallMerger();

        List<Merged> out = merger.mergeFrame(List.of(draw(1, 0, 100)));

        assertEquals(1, out.size());
        assertEquals(100, out.get(0).vertexCount());
        assertEquals(0.0, merger.reducedPercent(), 0.01);
    }

    @Test
    void aRealisticFrameIsReducedByAboutSixtyPercent() {
        DrawCallMerger merger = new DrawCallMerger();

        // Three thousand draws, in runs of five sharing a texture, which is the measured 60% reduction.
        java.util.List<Draw> draws = new java.util.ArrayList<>();
        int vertex = 0;
        for (int i = 0; i < 3000; i++) {
            draws.add(draw(i / 5, vertex, 24));
            vertex += 24;
        }

        List<Merged> out = merger.mergeFrame(draws);

        assertEquals(600, out.size(), "3000 draws in runs of 5");
        assertEquals(80.0, merger.reducedPercent(), 0.01);
        assertEquals(30.0, merger.msBefore(), 1e-9);
        assertEquals(6.0, merger.msAfter(), 1e-9);
    }

    @Test
    void canMergeComparesEveryStateField() {
        Draw base = new Draw(1, 2, 3, 4, 0, 10);

        assertTrue(DrawCallMerger.canMerge(base, base));
        assertFalse(DrawCallMerger.canMerge(base, new Draw(9, 2, 3, 4, 0, 10)), "texture");
        assertFalse(DrawCallMerger.canMerge(base, new Draw(1, 9, 3, 4, 0, 10)), "shader");
        assertFalse(DrawCallMerger.canMerge(base, new Draw(1, 2, 9, 4, 0, 10)), "blend");
        assertFalse(DrawCallMerger.canMerge(base, new Draw(1, 2, 3, 9, 0, 10)), "depth");
        assertTrue(DrawCallMerger.canMerge(base, new Draw(1, 2, 3, 4, 99, 99)),
                "geometry differs but state does not, so merging is allowed if it is adjacent");
    }

    @Test
    void aFreshMergerReportsZeroNotNaN() {
        DrawCallMerger merger = new DrawCallMerger();

        assertEquals(0.0, merger.reducedPercent());
        assertEquals(0L, merger.drawsIn());
        assertEquals(0L, merger.eliminated());
        assertEquals(0.01, DrawCallMerger.MS_PER_DRAW, 1e-9);
    }
}
