package net.amdfaster.bench;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * A diagnostic, not a benchmark: it reports what OpenGL the build machine can actually give us.
 *
 * <p>The software rasteriser in {@code CpuRenderBenchmark} cannot price a draw call, because a software
 * rasteriser has no driver. The plan is to run the same two pipelines through a real OpenGL
 * implementation so that the 906 to 123 draw-call reduction becomes time rather than a count.
 *
 * <p>The first probe settled the shape of it: the runner has {@code libGL.so.1}, {@code libGLX.so.0} and
 * Mesa 25.2.8 with its gallium DRI drivers, but no {@code libEGL.so.1} and no {@code libOSMesa}. So EGL
 * surfaceless is out and the only route to a context is GLX against a virtual X server, which needs
 * {@code DISPLAY} set before GLFW starts -- something a JVM cannot do to itself. Hence a child process.
 *
 * <p>Everything is reported on one line, because the workflow greps the build log and annotates only the
 * lines matching its patterns; a multi-line failure message loses every line after the first. Once the
 * facts are known this file is deleted.
 */
class GlProbeTest {

    @Test
    void probeOpenGLAvailability() throws Exception {
        StringBuilder report = new StringBuilder("GLPROBE");
        List<String> xLibs = new ArrayList<>();
        for (String library : new String[] {"libX11.so.6", "libXrandr.so.2", "libXinerama.so.1",
                "libXcursor.so.1", "libXi.so.6", "libXxf86vm.so.1", "libXfixes.so.3", "libXext.so.6"}) {
            if (new File("/usr/lib/x86_64-linux-gnu/" + library).isFile()) {
                xLibs.add(library);
            }
        }
        report.append(" | xlibs=").append(xLibs);

        Process xvfb = startXvfb();
        report.append(" | xvfb=").append(xvfb == null ? "not started" : "started");

        if (xvfb != null) {
            report.append(" | child=").append(shorten(runChild(), 1600));
        }
        fail(report.toString());
    }

    /** Starts a virtual X server on :99 and waits for its socket, which is how GLFW gets a display. */
    private static Process startXvfb() {
        for (String binary : new String[] {"/usr/bin/Xvfb", "Xvfb"}) {
            try {
                Process process = new ProcessBuilder(binary, ":99", "-screen", "0", "1280x720x24",
                        "-nolisten", "tcp")
                        .redirectErrorStream(true)
                        .redirectOutput(new File(System.getProperty("java.io.tmpdir"), "xvfb.log"))
                        .start();
                for (int attempt = 0; attempt < 100; attempt++) {
                    if (new File("/tmp/.X11-unix/X99").exists()) {
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

    /** Runs {@link GlProbeMain} in a child JVM with DISPLAY set, and returns what it printed. */
    private static String runChild() throws Exception {
        String javaHome = System.getProperty("java.home");
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                javaHome + File.separator + "bin" + File.separator + "java",
                "-cp", classpath,
                GlProbeMain.class.getName());
        builder.environment().put("DISPLAY", ":99");
        // No GPU is present, so Mesa would otherwise have nothing to load. This is what selects llvmpipe.
        builder.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
        builder.environment().put("GALLIUM_DRIVER", "llvmpipe");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "(child timed out) " + new String(output, StandardCharsets.UTF_8);
        }
        return "exit=" + process.exitValue() + " " + new String(output, StandardCharsets.UTF_8);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "null";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
