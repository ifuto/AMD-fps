package net.amdfaster.bench;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL12;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

/**
 * A diagnostic, not a benchmark: it reports what OpenGL the build machine actually has.
 *
 * <p>The software rasteriser in {@code CpuRenderBenchmark} cannot price a draw call, because a software
 * rasteriser has no driver. The plan is to run the same two pipelines through a real OpenGL
 * implementation, so that the 906 to 123 draw-call reduction becomes time rather than a count. Whether
 * that is possible on a headless runner is a fact about the machine and not something to guess at: no X
 * server, no GPU, and a CI log that cannot be downloaded from this environment.
 *
 * <p>So this class asks, and fails with the answer. Everything goes into one line, because the workflow
 * greps the build log and annotates only the lines matching its patterns -- a multi-line failure message
 * loses every line after the first. Once the facts are known this file is deleted.
 */
class GlProbeTest {

    /** {@code EGL_PLATFORM_SURFACELESS_MESA}, from {@code EGL_MESA_platform_surfaceless}. */
    private static final int EGL_PLATFORM_SURFACELESS_MESA = 0x31DD;
    /** {@code EGL_OPENGL_API}, from EGL 1.2. LWJGL's EGL12 does not export it. */
    private static final int EGL_OPENGL_API = 0x30A2;
    /** {@code EGL_OPENGL_BIT}, the desktop-OpenGL bit of {@code EGL_RENDERABLE_TYPE}. */
    private static final int EGL_OPENGL_BIT = 0x0008;

    @Test
    void probeOpenGLAvailability() {
        StringBuilder report = new StringBuilder("GLPROBE");
        report.append(" | libs=").append(shorten(sh(
                "ldconfig -p 2>/dev/null | grep -oE 'lib(EGL|GL|GLX|OSMesa|glapi|OpenGL)[.]so[0-9.]*'"
                        + " | sort -u"), 220));
        report.append(" | dri=").append(shorten(sh(
                "ls /usr/lib/x86_64-linux-gnu/dri /usr/lib/dri 2>&1"), 200));
        report.append(" | surfaceless=").append(shorten(eglReport(true), 500));
        report.append(" | native=").append(shorten(eglReport(false), 500));
        fail(report.toString());
    }

    /**
     * Tries to create a desktop OpenGL 3.3 core context, either on Mesa's surfaceless platform (no
     * display server and no device node needed) or on the native default display, and reports how far it
     * got. Getting a context is the whole question; everything after it is ordinary GL.
     */
    private static String eglReport(boolean surfaceless) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long display = surfaceless
                    ? EGL15.eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, 0L, (PointerBuffer) null)
                    : EGL10.eglGetDisplay(0L);
            if (display == 0L) {
                return "no display, eglGetError=0x" + Integer.toHexString(EGL10.eglGetError());
            }
            IntBuffer major = stack.ints(0);
            IntBuffer minor = stack.ints(0);
            if (!EGL10.eglInitialize(display, major, minor)) {
                return "display ok, eglInitialize failed, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError());
            }
            String egl = EGL10.eglQueryString(display, EGL10.EGL_VERSION) + " / "
                    + EGL10.eglQueryString(display, EGL10.EGL_VENDOR);
            if (!EGL12.eglBindAPI(EGL_OPENGL_API)) {
                return "egl=" + egl + " but eglBindAPI(OPENGL) failed";
            }
            IntBuffer configAttribs = stack.ints(
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL12.EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24,
                    EGL10.EGL_NONE);
            PointerBuffer configs = stack.mallocPointer(8);
            IntBuffer configCount = stack.ints(0);
            if (!EGL10.eglChooseConfig(display, configAttribs, configs, configCount)
                    || configCount.get(0) == 0) {
                return "egl=" + egl + " but no config, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError());
            }
            long config = configs.get(0);
            IntBuffer contextAttribs = stack.ints(
                    EGL15.EGL_CONTEXT_MAJOR_VERSION, 3,
                    EGL15.EGL_CONTEXT_MINOR_VERSION, 3,
                    EGL15.EGL_CONTEXT_OPENGL_PROFILE_MASK,
                    EGL15.EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                    EGL10.EGL_NONE);
            long context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs);
            if (context == 0L) {
                return "egl=" + egl + " but no GL3.3 core context, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError());
            }
            IntBuffer surfaceAttribs = stack.ints(
                    EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE);
            long surface = EGL10.eglCreatePbufferSurface(display, config, surfaceAttribs);
            if (!EGL10.eglMakeCurrent(display, surface, surface, context)) {
                return "egl=" + egl + " context made, makeCurrent failed, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError());
            }
            GL.createCapabilities();
            String gl = GL11.glGetString(GL11.GL_VERSION) + " ; "
                    + GL11.glGetString(GL11.GL_RENDERER) + " ; "
                    + GL11.glGetString(GL11.GL_VENDOR);
            return "egl=" + egl + " GL=" + gl + " maxRenderbuffer=" + GL11.glGetInteger(0x84E8);
        } catch (Throwable t) {
            return "threw " + t.getClass().getSimpleName() + ": "
                    + shorten(String.valueOf(t.getMessage()), 200);
        }
    }

    private static String sh(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            ByteBuffer sink = ByteBuffer.allocate(1 << 16);
            byte[] chunk = new byte[4096];
            java.io.InputStream in = process.getInputStream();
            int read;
            while ((read = in.read(chunk)) > 0) {
                sink.put(chunk, 0, Math.min(read, sink.remaining()));
                if (sink.remaining() == 0) {
                    break;
                }
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "(timeout)";
            }
            sink.flip();
            byte[] out = new byte[sink.remaining()];
            sink.get(out);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(sh failed: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "null";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
