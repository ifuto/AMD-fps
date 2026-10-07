package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles the GLSL to SPIR-V, so a shader typo is a test failure here rather than a driver error
 * on a player's machine.
 *
 * <p>The shaders are otherwise plain text in a jar. Nothing in the build looks at them, and
 * {@code CullShaderTest} only checks the parts that have to agree with Java -- bindings, offsets,
 * the work group size. Everything else, including whether the GLSL parses, was unchecked until
 * this. shaderc is the same compiler the Vulkan driver would run, so this is not an approximation.
 *
 * <p>shaderc is a test-only dependency. The mod will ship pre-compiled SPIR-V, so no player needs
 * a GLSL toolchain installed.
 */
class ShaderCompileTest {

    private static String read(String path) {
        try (InputStream in = ShaderCompileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "shader resource missing: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compiles one compute shader; returns null on success or the compiler's message on failure. */
    private static String compile(String path) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            return "shaderc_compiler_initialize returned null";
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, read(path),
                    Shaderc.shaderc_glsl_compute_shader, path, "main", options);
            if (result == 0L) {
                return "shaderc_compile_into_spv returned null";
            }
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                String message = Shaderc.shaderc_result_get_error_message(result);
                return "status " + status + ", " + Shaderc.shaderc_result_get_num_errors(result)
                        + " error(s): " + message;
            }
            return null;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /** Compiles and returns the SPIR-V, for tests that want to look at the output. */
    private static byte[] compileToSpirv(String path) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, read(path),
                    Shaderc.shaderc_glsl_compute_shader, path, "main", options);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                fail(path + " did not compile: " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
            assertNotNull(spv, path + " compiled but produced no bytes");
            byte[] out = new byte[spv.remaining()];
            spv.get(out);
            return out;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Test
    void theFrustumCullShaderCompiles() {
        String error = compile(CullBindings.CULL_SHADER_PATH);
        assertTrue(error == null, CullBindings.CULL_SHADER_PATH + ": " + error);
    }

    @Test
    void theOcclusionShaderCompiles() {
        String error = compile(CullBindings.OCCLUSION_SHADER_PATH);
        assertTrue(error == null, CullBindings.OCCLUSION_SHADER_PATH + ": " + error);
    }

    @Test
    void bothShadersProduceSpirvWithTheRightMagic() {
        for (String path : new String[] {CullBindings.CULL_SHADER_PATH,
                CullBindings.OCCLUSION_SHADER_PATH}) {
            byte[] spv = compileToSpirv(path);
            assertTrue(spv.length > 20, path + " produced a suspiciously short module: " + spv.length);
            // SPIR-V is little-endian and starts with 0x07230203.
            int magic = (spv[0] & 0xFF) | ((spv[1] & 0xFF) << 8)
                    | ((spv[2] & 0xFF) << 16) | ((spv[3] & 0xFF) << 24);
            assertTrue(magic == 0x07230203,
                    path + " does not start with the SPIR-V magic number: 0x" + Integer.toHexString(magic));
        }
    }
}
