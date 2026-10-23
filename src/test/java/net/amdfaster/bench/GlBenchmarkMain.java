package net.amdfaster.bench;

import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
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
        long window = GLFW.glfwCreateWindow(64, 64, "amd-faster-bench", 0L, 0L);
        if (window == 0L) {
            System.out.println("GLBENCH error=no window");
            return;
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GLBENCH gl=" + GL11.glGetString(GL11.GL_VERSION)
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        attachFramebuffer();

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

    private static void attachFramebuffer() {
        int framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        int colour = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colour);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colour, 0);
        int depth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        // 0x81A6 is GL_DEPTH_COMPONENT24, which is a GL 1.4 constant rather than a GL 1.1 one.
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, 0x81A6, WIDTH, HEIGHT);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER, depth);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer incomplete: 0x"
                    + Integer.toHexString(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)));
        }
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
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
        renderer.readBack(fastImage);

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
                baseDraws, renderer.glDraws, baseMulti, renderer.glMultiDraws,
                baseRanges, renderer.glRanges, baseTriangles, renderer.glTriangles, renderer.glUploads));
        // One frame with back-face culling switched off. If coverage jumps, the winding is wrong and
        // culling was eating the world; if it does not, the geometry never reached the framebuffer and
        // the winding was never the question.
        renderer.debugNoCull = true;
        renderer.debugNoMultiDraw = true;
        renderer.debugNoDepth = true;
        renderer.invalidateState();
        BaselinePipeline diagnostic = new BaselinePipeline();
        diagnostic.prepare(scene, assets);
        scene.view(0, aspect, view);
        renderer.setCamera(view.x, view.y, view.z,
                view.rightX, view.rightY, view.rightZ,
                view.upX, view.upY, view.upZ,
                view.fwdX, view.fwdY, view.fwdZ,
                view.nearHalfWidth, view.nearHalfHeight, view.near);
        renderer.setViewProjection(view.viewProjection);
        renderer.setProjection(view.near, view.nearHalfWidth, view.nearHalfHeight, view.far);
        diagnostic.renderFrame(scene, assets, renderer, view, 0);
        renderer.endFrame();
        ByteBuffer noCullImage = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        renderer.readBack(noCullImage);
        int noCullPainted = GlRaster.painted(noCullImage, WIDTH, HEIGHT, sky);
        // The same range again, through a vertex array made now rather than at startup.
        renderer.debugRedrawOpaqueRange();
        renderer.readBack(noCullImage);
        int redrawPainted = GlRaster.painted(noCullImage, WIDTH, HEIGHT, sky);
        MemoryUtil.memFree(noCullImage);

        System.out.println(String.format(Locale.ROOT,
                "GLBENCHPAINT scene=%d glBase=%d glFast=%d softBase=%d softFast=%d diffPercent=%.1f"
                        + " diag=%d mvpLoc=%d err=0x%x tv0=%d batch=%d range0=%d,%d"
                        + " redraw=%d opqRange=%d,%d",
                kind, basePainted, fastPainted, softBase, softFast, diff,
                noCullPainted, renderer.mvpLocation(), GL11.glGetError(),
                renderer.terrainVertices(0), renderer.lastBatchSize,
                renderer.lastRangeFirst, renderer.lastRangeCount,
                redrawPainted, renderer.opaqueProbeFirst, renderer.opaqueProbeCount));
        float[] probe = renderer.probeVertex();
        System.out.println("GLBENCHMATRIX scene=" + kind
                + " vertex=" + probe[0] + "," + probe[1] + "," + probe[2]
                + " " + renderer.debugMatrix()
                + " " + renderer.debugClip(probe[0], probe[1], probe[2]));
        renderer.close();
    }

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
}
