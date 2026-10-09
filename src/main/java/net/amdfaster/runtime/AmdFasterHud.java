package net.amdfaster.runtime;

import java.util.ArrayList;
import java.util.List;

import net.amdfaster.mc.BlockStateCache;
import net.amdfaster.perf.FrameTimeRecorder;
import net.amdfaster.perf.StageTimer;

/**
 * Builds the lines of the statistics overlay.
 *
 * <p>Text building only -- no drawing, no Minecraft types. That split is not decoration: the overlay
 * exists so a player can see whether the mod is doing anything, and if the numbers it shows are wrong
 * the overlay is worse than absent, because it will be believed. Keeping the formatting separate from
 * the drawing means the numbers can be checked without starting a game.
 *
 * <p>The numbers shown are the ones the project's benchmark has to report anyway: 1 percent low,
 * frame-time variance, and the per-stage breakdown. Average frame rate alone is the least useful
 * figure there is, because it is the one that looks fine while the game stutters.
 */
public final class AmdFasterHud {

    private static final int DEFAULT_COLOUR = 0xFFE0E0E0;
    private static final int HEADER_COLOUR = 0xFFFFC040;

    /** One positioned, coloured line of overlay text. */
    public record Line(String text, int colour) {
    }

    private AmdFasterHud() {
    }

    /**
     * Builds the overlay.
     *
     * @param recorder frame timings, or null if none have been collected yet
     * @param stages   per-stage timings, or null
     * @param cache    the block state cache, or null if it is disabled
     * @param governor the frame governor, or null if it is disabled
     */
    public static List<Line> build(FrameTimeRecorder recorder, StageTimer stages, BlockStateCache cache,
            FrameGovernor governor) {
        List<Line> lines = new ArrayList<>(12);
        lines.add(new Line("AMD-Faster", HEADER_COLOUR));

        if (recorder != null && recorder.sampleCount() > 0) {
            lines.add(new Line(String.format("FPS  %.0f   1%% low  %.0f", recorder.fps(), recorder.onePercentLowFps()),
                    DEFAULT_COLOUR));
            lines.add(new Line(String.format("frame  mean %.2f ms  p95 %.2f ms  worst %.2f ms",
                    recorder.meanFrameNanos() / 1_000_000.0,
                    recorder.percentileFrameNanos(95.0) / 1_000_000.0,
                    recorder.worstFrameNanos() / 1_000_000.0), DEFAULT_COLOUR));
            // Standard deviation is the number that separates "steady 60" from "60 with hitches". Two
            // games with the same mean and different variance do not feel the same, and only this
            // figure says which one you are in.
            lines.add(new Line(String.format("variance  sd %.2f ms over %d frames",
                    recorder.stdDevFrameNanos() / 1_000_000.0, recorder.sampleCount()), DEFAULT_COLOUR));
        } else {
            lines.add(new Line("no frame timings yet", DEFAULT_COLOUR));
        }

        if (cache != null) {
            int hits = cache.hits();
            int misses = cache.misses();
            long total = (long) hits + misses;
            lines.add(new Line(String.format("block cache  %d / %d  (%.0f%% hit)", hits, total,
                    total == 0 ? 0.0 : 100.0 * hits / total), DEFAULT_COLOUR));
        }

        if (governor != null) {
            lines.add(new Line(String.format("governor  %s  saved %d frames",
                    governor.state(), governor.savedFrames()), DEFAULT_COLOUR));
        }

        if (stages != null && stages.stageCount() > 0) {
            lines.add(new Line("stages", HEADER_COLOUR));
            // Totals by name rather than the formatted report string, because the overlay needs one
            // line per stage and the report is a paragraph.
            for (var entry : stages.totalsByName().entrySet()) {
                lines.add(new Line(String.format("  %s  %.2f ms", entry.getKey(), entry.getValue() / 1_000_000.0),
                        DEFAULT_COLOUR));
            }
        }
        return lines;
    }

    /** Vertical spacing between lines, in pixels. */
    public static int lineHeight() {
        return 10;
    }

    /** X offset from the left edge, in pixels. */
    public static int marginX() {
        return 4;
    }

    /** Y offset from the top edge, in pixels. */
    public static int marginY() {
        return 4;
    }
}
