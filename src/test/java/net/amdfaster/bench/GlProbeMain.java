package net.amdfaster.bench;

import java.io.PrintStream;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Runs in a child JVM, because a JVM cannot change its own environment and {@code DISPLAY} has to be set
 * before GLFW initialises.
 *
 * <p>The build machine has {@code libGL.so.1}, {@code libGLX.so.0}, Mesa 25.2.8 with its gallium DRI
 * drivers and {@code Xvfb}, but no {@code libEGL.so.1} and no {@code libOSMesa}. So the only way to a
 * real OpenGL context is GLX against a virtual X server. That is a real driver doing real work: llvmpipe
 * compiles the shaders and rasterises the pixels, and the draw calls go through Mesa's state tracking.
 * What it is not is a GPU, and the report says so rather than letting the number stand for hardware.
 *
 * <p>For now this only proves the context exists and reports what it is. The benchmark itself comes next.
 */
final class GlProbeMain {

    private GlProbeMain() {
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        out.println("GLPROBE2 display=" + System.getenv("DISPLAY")
                + " java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        try {
            GLFWErrorCallback errors = GLFWErrorCallback.createPrint(out);
            GLFW.glfwSetErrorCallback(errors);
            if (!GLFW.glfwInit()) {
                out.println("GLPROBE2 glfwInit=false");
                return;
            }
            out.println("GLPROBE2 glfwInit=true version=" + GLFW.glfwGetVersionString());
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            long window = GLFW.glfwCreateWindow(64, 64, "probe", 0L, 0L);
            if (window == 0L) {
                out.println("GLPROBE2 window=0");
                return;
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            out.println("GLPROBE2 gl=" + GL11.glGetString(GL11.GL_VERSION)
                    + " renderer=" + GL11.glGetString(GL11.GL_RENDERER)
                    + " vendor=" + GL11.glGetString(GL11.GL_VENDOR));

            int framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            int colour = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colour);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1280, 720, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colour, 0);
            int depth = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            // 0x81A6 is GL_DEPTH_COMPONENT24, which lives in GL14 rather than GL11.
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, 0x81A6, 1280, 720);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, depth);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GL11.glViewport(0, 0, 1280, 720);
            GL11.glClearColor(0.1f, 0.2f, 0.3f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4);
            GL11.glReadPixels(640, 360, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            out.println("GLPROBE2 fbo=0x" + Integer.toHexString(status)
                    + " pixel=" + (pixel.get(0) & 0xFF) + "," + (pixel.get(1) & 0xFF)
                    + "," + (pixel.get(2) & 0xFF)
                    + " error=0x" + Integer.toHexString(GL11.glGetError()));
            GLFW.glfwTerminate();
        } catch (Throwable t) {
            out.println("GLPROBE2 threw " + t);
        }
        out.flush();
    }
}
