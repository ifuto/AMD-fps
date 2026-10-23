package net.amdfaster.bench;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A virtual X server and a child JVM to run in front of it.
 *
 * <p>The build machine has {@code libGL.so.1}, {@code libGLX.so.0} and Mesa's gallium drivers, but no
 * {@code libEGL.so.1} and no {@code libOSMesa}, so the only route to an OpenGL context is GLX against a
 * display. It also has {@code Xvfb}, so a display can be had -- but not by the test JVM, because a JVM
 * cannot change its own environment and {@code DISPLAY} has to be set before GLFW initialises. Hence a
 * child process, which is also what the mod's out-of-process backend would be.
 */
final class VirtualDisplay {

    static final String DISPLAY = ":99";

    private VirtualDisplay() {
    }

    /** Starts Xvfb on :99 and waits for its socket. Returns null if there is no Xvfb to start. */
    static Process startXvfb() {
        for (String binary : new String[] {"/usr/bin/Xvfb", "Xvfb"}) {
            try {
                Process process = new ProcessBuilder(binary, DISPLAY, "-screen", "0", "1280x720x24",
                        "-nolisten", "tcp")
                        .redirectErrorStream(true)
                        .redirectOutput(new File(System.getProperty("java.io.tmpdir"), "xvfb.log"))
                        .start();
                for (int attempt = 0; attempt < 100; attempt++) {
                    if (new File("/tmp/.X11-unix/X99").isFile()) {
                        return process;
                    }
                    if (!process.isAlive()) {
                        return null;
                    }
                    Thread.sleep(100);
                }
                return process;
            } catch (Exception e) {
                // try the next candidate
            }
        }
        return null;
    }

    /** What the child printed, prefixed with its exit status. */
    static String run(String mainClass, long timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-Xmx3G",
                "-cp", System.getProperty("java.class.path"),
                mainClass);
        builder.environment().put("DISPLAY", DISPLAY);
        // There is no GPU here, so Mesa needs to be told which rasteriser to load.
        builder.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
        builder.environment().put("GALLIUM_DRIVER", "llvmpipe");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        String text = new String(output, StandardCharsets.UTF_8);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "exit=timeout " + text;
        }
        return "exit=" + process.exitValue() + " " + text;
    }
}
