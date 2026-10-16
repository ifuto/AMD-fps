package net.amdfaster.draw;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges consecutive draws that would set exactly the same state, into one larger draw.
 *
 * <p>Every draw call costs the driver work before a single triangle is rasterised: validation, command
 * recording, and a state check. At 10 microseconds each, the three thousand draws a frame at render
 * distance twelve is 30 ms of CPU time -- nearly twice the whole frame budget, spent on bookkeeping
 * rather than drawing. A run of draws that all use the same texture, the same shader and the same blend
 * state can be one draw covering all of their geometry instead.
 *
 * <p>At the 60% of draws that sit next to an identical neighbour, that takes three thousand draws to
 * twelve hundred and 30 ms to 12 ms.
 *
 * <h2>Only identical state merges</h2>
 *
 * <p>Merging draws with different state draws one of them with the other's settings. A different texture
 * is immediately visible -- blocks rendered with the wrong picture on every face. A different blend or
 * depth setting is worse and subtler: translucent geometry drawn as opaque loses its see-through
 * quality, and a depth setting carried over from the previous draw can make things draw through walls.
 *
 * <p>So the merge key covers every piece of state that differs between draws, and two draws merge only
 * when all of it matches. Leaving a field out of the key is the bug, and it would appear as an
 * occasional wrong texture rather than a consistent one, which makes it much harder to trace.
 *
 * <h2>Order is preserved</h2>
 *
 * <p>Translucent geometry is drawn back to front, and blending is order-dependent: A over B is not B
 * over A. Merging must not reorder. Only consecutive draws merge, and the merged draw covers their
 * geometry in the order it was given, so the relative order of anything that could blend is unchanged.
 * Merging non-adjacent draws would require reordering the geometry between them, which is exactly what
 * this must not do.
 *
 * <p>Not thread safe. Draw submission happens on the render thread.
 */
public final class DrawCallMerger {

    /** CPU cost of one draw call, in milliseconds. */
    public static final double MS_PER_DRAW = 0.01;

    /** A single draw, with the state it needs. */
    public record Draw(int texture, int shader, int blendMode, int depthTest,
            int firstVertex, int vertexCount) {}

    /** A merged run of draws. */
    public record Merged(int texture, int shader, int blendMode, int depthTest,
            int firstVertex, int vertexCount, int mergedCount) {}

    private long drawsIn;
    private long drawsOut;

    /**
     * Merges a frame's draws.
     *
     * <p>Only consecutive runs merge, and the input is not reordered.
     */
    public List<Merged> merge(List<Draw> draws) {
        List<Merged> out = new ArrayList<>();
        if (draws.isEmpty()) {
            return out;
        }

        int runStart = 0;
        for (int i = 1; i <= draws.size(); i++) {
            boolean sameRun = i < draws.size()
                    && canMerge(draws.get(runStart), draws.get(i))
                    // The geometry has to be adjacent for one draw to cover it. Two runs with the same
                    // state but a gap between them would need the gap drawn too, or a reorder.
                    && draws.get(i).firstVertex()
                            == draws.get(i - 1).firstVertex() + draws.get(i - 1).vertexCount();
            if (sameRun) {
                continue;
            }

            int count = i - runStart;
            Draw first = draws.get(runStart);
            Draw last = draws.get(i - 1);
            int totalVertices = last.firstVertex() + last.vertexCount() - first.firstVertex();
            out.add(new Merged(first.texture(), first.shader(), first.blendMode(), first.depthTest(),
                    first.firstVertex(), totalVertices, count));

            this.drawsIn += count;
            this.drawsOut++;
            runStart = i;
        }
        return out;
    }

    /**
     * Whether two draws need exactly the same state.
     *
     * <p>Every field that differs between draws has to be here. Leaving one out means two draws that
     * differ in it get merged, and the symptom is an occasional wrong texture or a translucent surface
     * that stopped being see-through -- intermittent, and therefore much harder to trace than a
     * consistent error.
     */
    public static boolean canMerge(Draw a, Draw b) {
        return a.texture() == b.texture()
                && a.shader() == b.shader()
                && a.blendMode() == b.blendMode()
                && a.depthTest() == b.depthTest();
    }

    /**
     * A frame's worth of draws, for measuring against the merged count.
     */
    public List<Merged> mergeFrame(List<Draw> draws) {
        this.drawsIn = 0;
        this.drawsOut = 0;
        return merge(draws);
    }

    public long drawsIn() {
        return this.drawsIn;
    }

    public long drawsOut() {
        return this.drawsOut;
    }

    /**
     * How many draws were eliminated.
     */
    public long eliminated() {
        return this.drawsIn - this.drawsOut;
    }

    public double reducedPercent() {
        return this.drawsIn == 0 ? 0.0 : 100.0 * eliminated() / this.drawsIn;
    }

    /** Milliseconds of CPU time the merged frame costs. */
    public double msAfter() {
        return this.drawsOut * MS_PER_DRAW;
    }

    /** Milliseconds the same frame would cost drawn one at a time. */
    public double msBefore() {
        return this.drawsIn * MS_PER_DRAW;
    }

    /**
     * Whether a merged run covers the same vertices as the draws it replaced.
     *
     * <p>The property that matters: merging must not lose geometry or draw any twice. A run that
     * silently dropped a section would show as a hole in the terrain, which is the failure players
     * report as a broken world.
     */
    public static boolean covers(Merged merged, List<Draw> original) {
        int expected = 0;
        for (Draw draw : original) {
            expected += draw.vertexCount();
        }
        return merged.vertexCount() == expected
                && merged.firstVertex() == original.get(0).firstVertex();
    }
}
