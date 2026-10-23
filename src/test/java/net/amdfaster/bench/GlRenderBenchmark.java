package net.amdfaster.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The frame rate through a real OpenGL driver, as opposed to {@code CpuRenderBenchmark}'s frame rate
 * through a software rasteriser written for this benchmark.
 *
 * <p>Both measure real rendering. They disagree about one thing, and it is the thing that decides the
 * answer: who pays for the pixels. A single-threaded software rasteriser puts fill and submission on the
 * same core, and fill is the one term culling cannot reduce -- the sections occlusion removes were never
 * going to paint anything. So every CPU-side saving gets divided by a fill cost that does not shrink, and
 * the ratio has a ceiling near 1.5x no matter what is implemented. A driver puts the fill elsewhere, and
 * what is left on the CPU is what a client's frame rate is actually made of.
 *
 * <p>The driver here is Mesa's llvmpipe on a headless runner, not a GPU and not AMD's OpenGL driver. Its
 * per-draw cost is far below the one this mod is written against, so what it reports is a floor. It also
 * has four cores to rasterise with, which is the part that matters: it is the only way to get the CPU and
 * the fill off the same thread without a GPU.
 *
 * <p>The correctness guard is different from the software one and stronger. Both pipelines' final frames
 * are read back and compared pixel by pixel, and each is compared for coverage against the same scene
 * drawn by the software rasteriser. A backend that drew nothing, or drew it upside down, or culled the
 * faces the player is looking at, would be very fast and would fail here.
 */
class GlRenderBenchmark {

    /** The acceptance criterion, the same one the software benchmark is held to. */
    static final double TARGET = 3.0;

    @Test
    void realDriverFrameRate() throws Exception {
        Process xvfb = VirtualDisplay.startXvfb();
        if (xvfb == null) {
            assertTrue(false, "GL no Xvfb on this machine, so no GLX and no context to measure");
        }
        String output = VirtualDisplay.run(GlBenchmarkMain.class.getName(), 900);

        double[] ratio = new double[3];
        long[] baseDraws = new long[3];
        long[] fastDraws = new long[3];
        long[] baseRanges = new long[3];
        long[] fastRanges = new long[3];
        long[] baseTris = new long[3];
        long[] fastTris = new long[3];
        int[] glBase = new int[3];
        int[] glFast = new int[3];
        int[] softBase = new int[3];
        double[] diff = new double[3];
        int[] mvpLoc = new int[3];
        int[] diag = new int[3];
        int[] tv0 = new int[3];
        int[] batch = new int[3];
        int[] rangeFirst = new int[3];
        int[] rangeCount = new int[3];
        String renderer = "?";
        int scenes = 0;

        for (String line : output.split("\n")) {
            String flat = line.trim();
            if (flat.startsWith("GLBENCH gl=")) {
                renderer = flat.substring("GLBENCH ".length());
                continue;
            }
            if (flat.startsWith("GLBENCH scene=")) {
                int scene = (int) number(flat, "scene=");
                ratio[scene] = number(flat, "ratio=");
                baseDraws[scene] = (long) number(flat, "baseDraws=");
                fastDraws[scene] = (long) number(flat, "fastDraws=");
                baseRanges[scene] = (long) number(flat, "baseRanges=");
                fastRanges[scene] = (long) number(flat, "fastRanges=");
                baseTris[scene] = (long) number(flat, "baseTris=");
                fastTris[scene] = (long) number(flat, "fastTris=");
                scenes++;
                continue;
            }
            if (flat.startsWith("GLBENCHPAINT scene=")) {
                int scene = (int) number(flat, "scene=");
                glBase[scene] = (int) number(flat, "glBase=");
                glFast[scene] = (int) number(flat, "glFast=");
                softBase[scene] = (int) number(flat, "softBase=");
                diff[scene] = number(flat, "diffPercent=");
                mvpLoc[scene] = (int) number(flat, "mvpLoc=");
                diag[scene] = (int) number(flat, "diag=");
                tv0[scene] = (int) number(flat, "tv0=");
                batch[scene] = (int) number(flat, "batch=");
                rangeFirst[scene] = (int) number(flat, "range0=");
                String tail = flat.substring(flat.indexOf("range0=") + 7);
                rangeCount[scene] = (int) number(tail.contains(",") ? tail.substring(tail.indexOf(',') + 1)
                        : tail, "");
            }
        }

        if (scenes != 3) {
            assertTrue(false, "GL child produced " + scenes + " of 3 scenes: "
                    + output.replaceAll("\\s+", " ").trim());
        }

        double worst = Math.min(ratio[0], Math.min(ratio[1], ratio[2]));
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "GL worst %.2fx (A %.2f B %.2f C %.2f) target %.1fx",
                worst, ratio[0], ratio[1], ratio[2], TARGET));
        report.append(" | ").append(shorten(renderer, 90));
        for (int scene = 0; scene < 3; scene++) {
            report.append(String.format(Locale.ROOT,
                    " | %c draws %d->%d ranges %d->%d glpx %d/%d softpx %d mvp %d diff %.1f%%",
                    (char) ('A' + scene), baseDraws[scene], fastDraws[scene],
                    baseRanges[scene], fastRanges[scene],
                    glBase[scene], glFast[scene], softBase[scene], mvpLoc[scene], diff[scene]));
            report.append(String.format(Locale.ROOT,
                    " | %c diag(noCull,noMulti)=%d terrainVerts=%d batch=%d range0=%d+%d",
                    (char) ('A' + scene), diag[scene], tv0[scene], batch[scene],
                    rangeFirst[scene], rangeCount[scene]));
        }

        // The picture has to survive the change of backend before the frame rate means anything. Coverage
        // is compared with the software rasteriser's because the two framebuffers have opposite origins,
        // and the two pipelines are compared with each other because they are drawing the same world.
        for (int scene = 0; scene < 3; scene++) {
            double coverage = glBase[scene] / (double) Math.max(1, softBase[scene]);
            if (coverage < 0.80 || coverage > 1.25) {
                assertTrue(false, report + " || GL painted " + glBase[scene]
                        + " pixels where the software rasteriser painted " + softBase[scene]
                        + ", so the GL backend is not drawing the same picture");
            }
            if (diff[scene] > 20.0) {
                assertTrue(false, report + " || the two pipelines' frames differ on " + diff[scene]
                        + "% of pixels, which is more than culling invisible geometry explains");
            }
        }

        assertTrue(worst >= TARGET, report.toString());
    }

    /** Reads the number that follows a key, so the report format is the only contract between the two JVMs. */
    private static double number(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return Double.NaN;
        }
        int start = at + key.length();
        int end = start;
        while (end < line.length() && "0123456789.-".indexOf(line.charAt(end)) >= 0) {
            end++;
        }
        return Double.parseDouble(line.substring(start, end));
    }

    private static String shorten(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
