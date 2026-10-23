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
        int errAfterStorage = GL11.glGetError();
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER, depth);
        int errAfterAttach = GL11.glGetError();
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        // "Complete" does not mean "has a depth buffer". A framebuffer with a colour attachment and
        // nothing else is complete too, so this check on its own proved nothing for five runs while every
        // fragment in the world failed a depth test against a buffer that was never cleared -- and a read
        // of that buffer returned the same two values in two different scenes, which is what uninitialised
        // memory looks like rather than what a depth buffer looks like.
        int depthType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (depthType == GL11.GL_NONE) {
            // No depth buffer, so fall back to a depth texture. A renderbuffer that did not take is not
            // worth another round trip to find out about, and the report says which of the two is in use.
            int depthTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, 0x81A6, WIDTH, HEIGHT, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depthTexture, 0);
            depthType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        }
        int depthName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        System.out.println("GLBENCHFBO status=0x" + Integer.toHexString(status)
                + " errAfterStorage=0x" + Integer.toHexString(errAfterStorage)
                + " errAfterAttach=0x" + Integer.toHexString(errAfterAttach)
                + " depthType=0x" + Integer.toHexString(depthType)
                + " depthName=" + depthName);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer incomplete: 0x" + Integer.toHexString(status));
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
        // Frame zero four ways. Culling, the depth test and the multi-draw have each been suspected and
        // each cleared in isolation, but never against each other with correct ranges: every earlier
        // combination was measured while the ranges were still counted in floats. Coverage is compared
        // against the software rasteriser's frame zero, so the configurations are compared with each
        // other and not with a frame from a different camera angle.
        int allOn = diagFrame(scene, assets, renderer, view, aspect, true, true, true);
        int noMulti = diagFrame(scene, assets, renderer, view, aspect, true, true, false);
        int noDepth = diagFrame(scene, assets, renderer, view, aspect, true, false, true);
        int bare = diagFrame(scene, assets, renderer, view, aspect, false, false, false);
        ByteBuffer diagImage = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        renderer.readBack(diagImage);
        int lastDiag = GlRaster.painted(diagImage, WIDTH, HEIGHT, sky);
        MemoryUtil.memFree(diagImage);

        System.out.println(String.format(Locale.ROOT,
                "GLBENCHPAINT scene=%d glBase=%d glFast=%d softBase=%d softFast=%d diffPercent=%.1f"
                        + " diag=%d mvpLoc=%d err=0x%x tv0=%d batch=%d range0=%d,%d"
                        + " d(cull,depth,multi)=%d d(noMulti)=%d d(noDepth)=%d d(bare)=%d %s",
                kind, basePainted, fastPainted, softBase, softFast, diff,
                lastDiag, renderer.mvpLocation(), GL11.glGetError(),
                renderer.terrainVertices(0), renderer.lastBatchSize,
                renderer.lastRangeFirst, renderer.lastRangeCount,
                allOn, noMulti, noDepth, bare, renderer.debugDepthState()));
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

    /** Renders frame zero once under one combination of the three switches and says how much it painted. */
    private static int diagFrame(Scene scene, Assets assets, GlRaster renderer, Scene.View view,
            float aspect, boolean cull, boolean depth, boolean multi) {
        renderer.debugNoCull = !cull;
        renderer.debugNoDepth = !depth;
        renderer.debugNoMultiDraw = !multi;
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
