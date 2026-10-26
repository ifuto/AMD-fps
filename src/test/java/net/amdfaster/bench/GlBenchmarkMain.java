package net.amdfaster.bench;

import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * The frame rate, measured through a real OpenGL driver.
 *
 * <p>Runs in a child JVM. A JVM cannot set its own environment, and {@code DISPLAY} has to exist before
 * GLFW initialises, so the JUnit test starts {@code Xvfb} and starts this with {@code DISPLAY=:99}. The
 * context that comes back is GL 4.5 core on Mesa's llvmpipe: a real driver, a real state machine, real
 * draw calls, and a software rasteriser shading the pixels on the other cores.
 *
 * <p>Why that last part matters. On a single-threaded software rasteriser the pixels are the frame, and
 * the pixels are the one thing the two pipelines cannot differ on -- occlusion culling removes geometry
 * that was never going to paint anything. Every CPU-side saving is then divided by a fill cost that does
 * not shrink, which is why {@code CpuRenderBenchmark} tops out near 1.5x. Here the fill belongs to
 * llvmpipe's worker threads and the CPU is left with what a client's CPU actually does: cull, sort,
 * batch, compose matrices, build buffers, and hand the driver draws.
 *
 * <p>What it is not: a GPU, and not AMD's OpenGL driver. llvmpipe's per-draw overhead is far below the
 * one this mod is written against, so the ratio here is a floor. Saying so is the honest option; letting
 * the number stand for hardware it never ran on is not.
 */
final class GlBenchmarkMain {

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    /** Frames run before the clock starts, so the measured loop is compiled code. */
    static final int WARMUP_FRAMES = 6;

    /** How long each pipeline is timed for. */
    static final long BUDGET_NANOS = 1_000_000_000L;

    /** A ceiling, so a fast machine does not turn this into a long test. */
    static final int MAX_FRAMES = 200;

    private GlBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) {
            System.out.println("GLBENCH error=glfwInit failed");
            return;
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        // The window is the framebuffer. It is created at the render size rather than 64x64 because
        // nothing is presented to it; it exists to own a colour and a depth buffer.
        GLFW.glfwWindowHint(GLFW.GLFW_RED_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_GREEN_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_BLUE_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_DOUBLEBUFFER, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "amd-faster-bench", 0L, 0L);
        if (window == 0L) {
            System.out.println("GLBENCH error=no window");
            return;
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GLBENCH gl=" + GL11.glGetString(GL11.GL_VERSION)
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        // Draw to and read from the back buffer, so the frame is complete without swapping and the
        // readback sees what was just drawn.
        GL11.glDrawBuffer(GL11.GL_BACK);
        GL11.glReadBuffer(GL11.GL_BACK);
        reportSurface();

        Assets assets = Assets.load();
        ByteBuffer baseImage = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        ByteBuffer fastImage = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);

        for (int scene : new int[] {Scene.PLAINS, Scene.VILLAGE_CAVE, Scene.TNT}) {
            run(scene, assets, baseImage, fastImage);
        }
        MemoryUtil.memFree(baseImage);
        MemoryUtil.memFree(fastImage);
        GLFW.glfwTerminate();
        System.out.flush();
    }

    /**
     * Says what the default framebuffer offers, and whether clearing its depth buffer takes.
     *
     * <p>This replaces a framebuffer object of my own, which reported itself complete with a depth
     * attachment of either kind and no error from any call that built it -- and would not be cleared by
     * glClear or by glClearBufferfv. Every fragment in the world then failed a depth test against a buffer
     * holding what the frame before it had left there. The window's buffer is allocated by the driver
     * through the window system rather than attached by me, so it is a different allocation and a
     * different clear path, and the readback here says whether the value survives.
     */
    private static void reportSurface() {
        System.out.println("GLBENCHFBO surface=default err=0x"
                + Integer.toHexString(GL11.glGetError()));
    }

    private static void run(int kind, Assets assets, ByteBuffer baseImage, ByteBuffer fastImage) {
        Scene scene = Scene.build(kind, assets);
        float aspect = WIDTH / (float) HEIGHT;
        Scene.View view = new Scene.View();
        GlRaster renderer = new GlRaster(WIDTH, HEIGHT, assets);
        renderer.buildTerrain(scene.sections);

        double baseFps = measure(new BaselinePipeline(), scene, assets, renderer, aspect, view);
        renderer.readBack(baseImage);
        long baseDraws = renderer.glDraws;
        long baseMulti = renderer.glMultiDraws;
        long baseRanges = renderer.glRanges;
        long baseTriangles = renderer.glTriangles;
        renderer.resetGlCounters();

        double fastFps = measure(new AmdFasterPipeline(), scene, assets, renderer, aspect, view);
        // Read here and not at the bottom of this method. Everything after this point -- the two
        // clipped runs and the seven diagnostic frames -- draws through the same renderer and adds to
        // the same counters, and a report that reads them last reports the sum of all of it as one
        // pipeline's frame. That is what made AMD-Faster look like it issued three times the
        // baseline's draws.
        long fastDraws = renderer.glDraws;
        long fastMulti = renderer.glMultiDraws;
        long fastRanges = renderer.glRanges;
        long fastTriangles = renderer.glTriangles;
        renderer.readBack(fastImage);

        // The same two pipelines again with rasterisation clipped to one pixel. Every draw call, every
        // vertex and every state change still happens; only the touching of pixels is gone. The gap
        // between these numbers and the ones above is the fill, measured on this driver rather than
        // assumed, and the ratio between them is what a client sees when its GPU does the fill for free.
        renderer.debugScissorTo1x1 = true;
        renderer.resetGlCounters();
        double baseNoFill = measure(new BaselinePipeline(), scene, assets, renderer, aspect, view);
        long baseNoFillDraws = renderer.glDraws;
        long baseNoFillRanges = renderer.glRanges;
        renderer.resetGlCounters();
        // Held on to rather than thrown away inside measure(), so what its CPU side costs on the driver
        // path can be reported next to what the driver costs. On the software rasteriser the two are the
        // same number; here they are not, and the difference is the part a GPU would take off the frame.
        AmdFasterPipeline clippedFast = new AmdFasterPipeline();
        clippedFast.prepare(scene, assets);
        double fastNoFill = measure(clippedFast, scene, assets, renderer, aspect, view);
        long fastNoFillDraws = renderer.glDraws;
        long fastNoFillRanges = renderer.glRanges;
        renderer.debugScissorTo1x1 = false;

        int sky = 0xFF78A7FF & 0xFFFFFF;
        int basePainted = GlRaster.painted(baseImage, WIDTH, HEIGHT, sky);
        int fastPainted = GlRaster.painted(fastImage, WIDTH, HEIGHT, sky);
        double diff = GlRaster.differencePercent(baseImage, fastImage, WIDTH, HEIGHT);

        // The same frame on the CPU rasteriser, once, so a GL frame that came out blank or upside down
        // cannot be reported as a fast one. Coverage is compared rather than pixels because the GL
        // framebuffer has its origin at the bottom left and the software one at the top.
        SoftRenderer software = new SoftRenderer(WIDTH, HEIGHT);
        int softBase = softwareCoverage(new BaselinePipeline(), scene, assets, software, aspect, view);
        int softFast = softwareCoverage(new AmdFasterPipeline(), scene, assets, software, aspect, view);

        System.out.println(String.format(Locale.ROOT,
                "GLBENCH scene=%d baseFps=%.1f fastFps=%.1f ratio=%.2f"
                        + " baseDraws=%d fastDraws=%d baseMulti=%d fastMulti=%d"
                        + " baseRanges=%d fastRanges=%d baseTris=%d fastTris=%d uploads=%d",
                kind, baseFps, fastFps, fastFps / Math.max(1e-9, baseFps),
                baseDraws, fastDraws, baseMulti, fastMulti,
                baseRanges, fastRanges, baseTriangles, fastTriangles, renderer.glUploads));
        // Frame zero four ways. Culling, the depth test and the multi-draw have each been suspected and
        // each cleared in isolation, but never against each other with correct ranges: every earlier
        // combination was measured while the ranges were still counted in floats. Coverage is compared
        // against the software rasteriser's frame zero, so the configurations are compared with each
        // other and not with a frame from a different camera angle.
        int allOn = diagFrame(scene, assets, renderer, view, aspect, true, true, true, 0);
        int noDepth = diagFrame(scene, assets, renderer, view, aspect, true, false, true, 0);
        int bare = diagFrame(scene, assets, renderer, view, aspect, false, false, false, 0);
        // The same frame with the depth test enabled but the comparison set to something that cannot
        // fail, and to something that cannot pass. Those two bracket the whole question.
        int always = diagFrame(scene, assets, renderer, view, aspect, true, true, true, GL11.GL_ALWAYS);
        int never = diagFrame(scene, assets, renderer, view, aspect, true, true, true, GL11.GL_NEVER);
        // And the two inclusive ones. GL_LESS and GL_GREATER both rejecting the world says the depth
        // being tested is not on either side of what is in the buffer, which for a finite value is
        // impossible. Inclusive comparisons separate that from a buffer that merely equals the fragment.
        int lequal = diagFrame(scene, assets, renderer, view, aspect, true, true, true, GL11.GL_LEQUAL);
        int gequal = diagFrame(scene, assets, renderer, view, aspect, true, true, true, GL11.GL_GEQUAL);
        ByteBuffer diagImage = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        renderer.readBack(diagImage);
        int lastDiag = GlRaster.painted(diagImage, WIDTH, HEIGHT, sky);
        MemoryUtil.memFree(diagImage);

        System.out.println(String.format(Locale.ROOT,
                "GLBENCHPAINT scene=%d glBase=%d glFast=%d softBase=%d softFast=%d diffPercent=%.1f"
                        + " diag=%d mvpLoc=%d err=0x%x tv0=%d batch=%d range0=%d,%d"
                        + " d(all)=%d d(noDepth)=%d d(bare)=%d d(ALWAYS)=%d d(NEVER)=%d"
                        + " d(LEQUAL)=%d d(GEQUAL)=%d %s",
                kind, basePainted, fastPainted, softBase, softFast, diff,
                lastDiag, renderer.mvpLocation(), GL11.glGetError(),
                renderer.terrainVertices(0), renderer.lastBatchSize,
                renderer.lastRangeFirst, renderer.lastRangeCount,
                allOn, noDepth, bare, always, never, lequal, gequal, renderer.debugDepthState()));
        System.out.println(String.format(Locale.ROOT,
                "GLBENCHFILL scene=%d fullBase=%.1f fullFast=%.1f full=%.2fx"
                        + " noFillBase=%.1f noFillFast=%.1f noFill=%.2fx fillShareBase=%.0f%%"
                        + " clippedDraws %d->%d clippedRanges %d->%d",
                kind, baseFps, fastFps, fastFps / Math.max(1e-9, baseFps),
                baseNoFill, fastNoFill, fastNoFill / Math.max(1e-9, baseNoFill),
                100.0 * (baseNoFill - baseFps) / Math.max(1e-9, baseNoFill),
                baseNoFillDraws, fastNoFillDraws, baseNoFillRanges, fastNoFillRanges));
        // The stage timers run through the warmup frames as well, so they are divided by every frame
        // this pipeline rendered and not only the timed ones.
        long stageFrames = lastFrames + WARMUP_FRAMES;
        System.out.println(String.format(Locale.ROOT,
                "GLBENCHSTAGE scene=%d frames=%d terrain=%.0f of which cull+sort=%.0f"
                        + " occluder=%.0f submit=%.0f translucent=%.0f | entities=%.0f particles=%.0f"
                        + " clouds=%.0f gui=%.0f",
                kind, lastFrames,
                clippedFast.nsTerrain / 1000.0 / stageFrames,
                clippedFast.nsCullSort / 1000.0 / stageFrames,
                clippedFast.nsOccluder / 1000.0 / stageFrames,
                clippedFast.nsSubmit / 1000.0 / stageFrames,
                clippedFast.nsTranslucent / 1000.0 / stageFrames,
                clippedFast.nsEntities / 1000.0 / stageFrames,
                clippedFast.nsParticles / 1000.0 / stageFrames,
                clippedFast.nsClouds / 1000.0 / stageFrames,
                clippedFast.nsGui / 1000.0 / stageFrames));
        float[] probe = renderer.probeVertex();
        System.out.println("GLBENCHMATRIX scene=" + kind
                + " vertex=" + probe[0] + "," + probe[1] + "," + probe[2]
                + " " + renderer.debugMatrix()
                + " " + renderer.debugClip(probe[0], probe[1], probe[2]));
        renderer.close();
    }

    /** Frames the last {@link #measure} timed, so a pipeline's accumulated stage times can be per frame. */
    private static long lastFrames;

    private static double measure(Pipeline pipeline, Scene scene, Assets assets, GlRaster renderer,
            float aspect, Scene.View view) {
        pipeline.prepare(scene, assets);
        for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
            scene.view(frame, aspect, view);
            renderer.setCamera(view.x, view.y, view.z,
                    view.rightX, view.rightY, view.rightZ,
                    view.upX, view.upY, view.upZ,
                    view.fwdX, view.fwdY, view.fwdZ,
                    view.nearHalfWidth, view.nearHalfHeight, view.near);
            renderer.setViewProjection(view.viewProjection);
            renderer.setProjection(view.near, view.nearHalfWidth, view.nearHalfHeight, view.far);
            pipeline.renderFrame(scene, assets, renderer, view, frame);
            renderer.endFrame();
        }
        long start = System.nanoTime();
        int frames = 0;
        do {
            scene.view(frames, aspect, view);
            renderer.setCamera(view.x, view.y, view.z,
                    view.rightX, view.rightY, view.rightZ,
                    view.upX, view.upY, view.upZ,
                    view.fwdX, view.fwdY, view.fwdZ,
                    view.nearHalfWidth, view.nearHalfHeight, view.near);
            renderer.setViewProjection(view.viewProjection);
            renderer.setProjection(view.near, view.nearHalfWidth, view.nearHalfHeight, view.far);
            pipeline.renderFrame(scene, assets, renderer, view, frames);
            renderer.endFrame();
            frames++;
        } while (System.nanoTime() - start < BUDGET_NANOS && frames < MAX_FRAMES);
        long elapsed = System.nanoTime() - start;
        lastFrames = frames;
        return frames * 1_000_000_000.0 / elapsed;
    }

    private static int softwareCoverage(Pipeline pipeline, Scene scene, Assets assets,
            SoftRenderer renderer, float aspect, Scene.View view) {
        pipeline.prepare(scene, assets);
        scene.view(0, aspect, view);
        renderer.setCamera(view.x, view.y, view.z,
                view.rightX, view.rightY, view.rightZ,
                view.upX, view.upY, view.upZ,
                view.fwdX, view.fwdY, view.fwdZ,
                view.nearHalfWidth, view.nearHalfHeight, view.near);
        pipeline.renderFrame(scene, assets, renderer, view, 0);
        return renderer.drawnPixels();
    }

    /** Renders frame zero once under one combination of the three switches and says how much it painted. */
    private static int diagFrame(Scene scene, Assets assets, GlRaster renderer, Scene.View view,
            float aspect, boolean cull, boolean depth, boolean multi, int depthFunc) {
        renderer.debugNoCull = !cull;
        renderer.debugNoDepth = !depth;
        renderer.debugNoMultiDraw = !multi;
        renderer.debugDepthFunc = depthFunc;
        renderer.invalidateState();
        BaselinePipeline pipeline = new BaselinePipeline();
        pipeline.prepare(scene, assets);
        scene.view(0, aspect, view);
        applyView(renderer, view);
        pipeline.renderFrame(scene, assets, renderer, view, 0);
        renderer.endFrame();
        return renderer.paintedNow();
    }

    private static void applyView(GlRaster renderer, Scene.View view) {
        renderer.setCamera(view.x, view.y, view.z,
                view.rightX, view.rightY, view.rightZ,
                view.upX, view.upY, view.upZ,
                view.fwdX, view.fwdY, view.fwdZ,
                view.nearHalfWidth, view.nearHalfHeight, view.near);
        renderer.setViewProjection(view.viewProjection);
        renderer.setProjection(view.near, view.nearHalfWidth, view.nearHalfHeight, view.far);
    }
}
