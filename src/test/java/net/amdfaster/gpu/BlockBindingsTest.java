package net.amdfaster.gpu;

import net.amdfaster.light.LightValue;
import net.amdfaster.light.VertexLight;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link BlockBindings} to the GLSL it describes.
 *
 * <p>A descriptor binding that does not match the shader is the nastiest class of Vulkan bug there
 * is: validation may pass, the pipeline may be created, and the shader then reads whatever memory
 * the mismatched binding happens to point at. It shows up as flickering, wrong colours or nothing,
 * depending on the driver and what else was resident. Same for a vertex attribute location, and
 * same for a bit shift in a light unpack.
 *
 * <p>So rather than trusting that the two files were written consistently, these tests read the
 * shader source and check the Java constants against it. A shader change that moves a binding
 * without moving the constant fails here rather than on a player's machine.
 */
class BlockBindingsTest {

    /**
     * Removes GLSL comments. The frame block and the push constant block both carry trailing
     * comments, and one of those comments contains a semicolon, so any check that counts
     * statements or matches a field sequence has to run over the stripped source.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//[^\\n]*", " ");
    }

    private static String read(String path) {
        try (InputStream in = BlockBindingsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "shader resource missing: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String vertexSource() {
        return read(BlockBindings.VERTEX_SHADER_PATH);
    }

    private static String fragmentSource() {
        return read(BlockBindings.FRAGMENT_SHADER_PATH);
    }

    private static final Pattern LAYOUT_LINE = Pattern.compile("layout\\s*\\(([^)]*)\\)([^;{]*)");

    /** Every {@code binding = N} in a source, keyed by the uniform or sampler name that follows. */
    private static Map<String, String> bindings(String source) {
        Map<String, String> found = new HashMap<>();
        Matcher m = LAYOUT_LINE.matcher(stripComments(source));
        while (m.find()) {
            String layout = m.group(1);
            String rest = m.group(2).trim();
            Matcher b = Pattern.compile("binding\\s*=\\s*(\\d+)").matcher(layout);
            if (!b.find()) {
                continue;
            }
            Matcher name = Pattern.compile("(\\w+)\\s*\\{?$").matcher(rest.replace("{", " {"));
            assertTrue(name.find(), "no name after layout(...): " + rest);
            found.put(name.group(1), layout.replaceAll("\\s+", ""));
        }
        return found;
    }

    private static int layoutNumber(String layout, String key) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*(\\d+)").matcher(layout);
        assertTrue(m.find(), key + " missing from layout(" + layout + ")");
        return Integer.parseInt(m.group(1));
    }

    @Test
    void theShadersUseTheBlockSetAndNotTheCullingSet() {
        // Set 0 is the culling pipeline's and is declared compute-only. A graphics shader reading
        // set 0 would force that layout to name the graphics stages, which it must not.
        for (String source : new String[] {vertexSource(), fragmentSource()}) {
            for (Map.Entry<String, String> e : bindings(source).entrySet()) {
                assertEquals(BlockBindings.SET, layoutNumber(e.getValue(), "set"),
                        e.getKey() + " must be in set " + BlockBindings.SET);
            }
        }
    }

    @Test
    void everyDeclaredBindingMatchesTheJavaConstants() {
        Map<String, String> combined = new HashMap<>();
        combined.putAll(bindings(vertexSource()));
        combined.putAll(bindings(fragmentSource()));

        for (BlockBindings.Binding binding : BlockBindings.bindings()) {
            String layout = combined.get(binding.name());
            assertNotNull(layout, binding.name() + " is not declared in either block shader");
            assertEquals(binding.binding(), layoutNumber(layout, "binding"),
                    binding.name() + " binding index");
        }
        assertEquals(BlockBindings.BINDING_COUNT, BlockBindings.bindings().size());
    }

    @Test
    void noShaderDeclaresABindingTheJavaConstantsDoNotKnowAbout() {
        Map<String, String> combined = new HashMap<>();
        combined.putAll(bindings(vertexSource()));
        combined.putAll(bindings(fragmentSource()));
        for (String name : combined.keySet()) {
            assertTrue(BlockBindings.bindings().stream().anyMatch(b -> b.name().equals(name)),
                    name + " is declared in GLSL but has no BlockBindings constant");
        }
    }

    @Test
    void theTexturesAreFragmentOnlyAndTheFrameBlockIsVisibleToBothStages() {
        // A descriptor no stage reads still costs validation, and a descriptor a stage reads but
        // does not name in its stage flags is a validation error at bind time.
        Map<String, String> vertex = bindings(vertexSource());
        Map<String, String> fragment = bindings(fragmentSource());

        assertTrue(vertex.containsKey("Frame"), "the vertex shader needs the view-projection matrix");
        assertTrue(!vertex.containsKey("blockAtlas"), "the vertex shader must not sample the atlas");
        assertTrue(!vertex.containsKey("lightMap"), "the vertex shader must not sample the lightmap");
        assertTrue(fragment.containsKey("blockAtlas"));
        assertTrue(fragment.containsKey("lightMap"));

        for (BlockBindings.Binding binding : BlockBindings.bindings()) {
            int expected = binding.name().equals("Frame")
                    ? BlockBindings.STAGE_VERTEX_FRAGMENT
                    : BlockBindings.STAGE_FRAGMENT;
            assertEquals(expected, binding.stageFlags(), binding.name() + " stage flags");
        }
    }

    @Test
    void theVertexInputLocationsMatchTheShaderInputs() {
        Map<String, Integer> declared = new HashMap<>();
        Matcher m = Pattern.compile("layout\\s*\\(location\\s*=\\s*(\\d+)\\)\\s*in\\s+\\w+\\s+(\\w+)\\s*;")
                .matcher(vertexSource());
        while (m.find()) {
            declared.put(m.group(2), Integer.parseInt(m.group(1)));
        }
        assertEquals(BlockBindings.POSITION_LOCATION,
                declared.getOrDefault("inPosition", -1), "position location");
        assertEquals(BlockBindings.UV_LOCATION,
                declared.getOrDefault("inUV", -1), "uv location");
        assertEquals(BlockBindings.LIGHT_LOCATION,
                declared.getOrDefault("inLight", -1), "light location");
        assertEquals(3, declared.size(), "exactly three vertex inputs, one per stream");
    }

    @Test
    void theVertexFormatsAreTheVulkanConstantsTheyClaimToBe() {
        // The formats are plain ints in BlockBindings so that class stays usable without LWJGL.
        // This is what stops them being wrong: each is checked against the binding itself.
        assertEquals(VK10.VK_FORMAT_R16G16B16A16_SINT, BlockBindings.FORMAT_POSITION);
        assertEquals(VK10.VK_FORMAT_R32G32_SFLOAT, BlockBindings.FORMAT_ATTRIBUTE);
        assertEquals(VK10.VK_FORMAT_R32_UINT, BlockBindings.FORMAT_LIGHT);
    }

    @Test
    void theStreamStridesMatchTheFormats() {
        // A format whose size does not match Meshlet's stride silently walks off the end of the
        // buffer on the last vertex of every meshlet, which is a GPU fault rather than a bad pixel.
        assertEquals(net.amdfaster.mesh.Meshlet.POSITION_STRIDE, 8, "4 x int16");
        assertEquals(net.amdfaster.mesh.Meshlet.ATTRIBUTE_STRIDE, 8, "2 x float32");
        assertEquals(net.amdfaster.mesh.Meshlet.LIGHT_STRIDE, 4, "1 x uint32");
    }

    @Test
    void theFrameBlockHasTheSameOffsetsAsTheCullingPipeline() {
        // One buffer serves both pipelines, so the two declarations of the same block must agree on
        // every offset. Checking the Java constants against each other is not enough -- the GLSL is
        // what the GPU reads -- so the field order is checked in the source too.
        assertEquals(CullBindings.FRAME_UBO_BYTES, BlockBindings.FRAME_UBO_BYTES);
        assertEquals(CullBindings.FRAME_OFFSET_VIEW_PROJECTION,
                BlockBindings.FRAME_OFFSET_VIEW_PROJECTION);
        assertEquals(CullBindings.FRAME_OFFSET_FRUSTUM_PLANES,
                BlockBindings.FRAME_OFFSET_FRUSTUM_PLANES);
        assertEquals(CullBindings.FRAME_OFFSET_CAMERA_ORIGIN,
                BlockBindings.FRAME_OFFSET_CAMERA_ORIGIN);
        assertEquals(CullBindings.FRAME_OFFSET_MESHLET_COUNT,
                BlockBindings.FRAME_OFFSET_MESHLET_COUNT);

        String vertex = stripComments(vertexSource());
        String cull = stripComments(read(CullBindings.CULL_SHADER_PATH));
        String frameBody = "mat4\\s+viewProjection;\\s*vec4\\s+frustumPlanes\\[6\\];\\s*vec4\\s+cameraOrigin;";
        assertTrue(Pattern.compile(frameBody).matcher(vertex).find(),
                "block.vert does not declare the frame block in the documented order");
        assertTrue(Pattern.compile(frameBody).matcher(cull).find(),
                "meshlet_cull.comp does not declare the frame block in the documented order");
    }

    @Test
    void thePushConstantBlockIsOneVec4() {
        Matcher m = Pattern.compile("push_constant\\)\\s*uniform\\s+Push\\s*\\{([^}]*)\\}",
                Pattern.DOTALL).matcher(vertexSource());
        assertTrue(m.find(), "block.vert declares no push constant block");
        String body = stripComments(m.group(1));
        assertEquals(1, body.split(";", -1).length - 1, "one member only");
        assertTrue(body.contains("vec4"), "the section origin is a vec4");
        assertTrue(body.contains("sectionOrigin"), "and it is named sectionOrigin");
        assertEquals(16, BlockBindings.PUSH_CONSTANT_BYTES, "one vec4 is 16 bytes");
    }

    @Test
    void theShadersUnpackLightTheWayJavaPacksIt() {
        // The GLSL reads block from bits 4..7, sky from 20..23 and occlusion from 24..25. Replaying
        // those exact shifts on values Java packed is the only check that can catch a shift that
        // moved on one side of the language boundary.
        int[][] cases = {{0, 0, 0}, {15, 15, 3}, {7, 3, 2}, {0, 15, 1}, {15, 0, 3}, {4, 11, 0}};
        for (int[] c : cases) {
            int packed = VertexLight.packLight(LightValue.pack(c[0], c[1]), c[2]);
            long unsigned = Integer.toUnsignedLong(packed);
            assertEquals(c[0], (int) ((unsigned >> 4) & 0xF), "block of " + c[0]);
            assertEquals(c[1], (int) ((unsigned >> 20) & 0xF), "sky of " + c[1]);
            assertEquals(c[2], (int) ((unsigned >> 24) & 0x3), "ao of " + c[2]);
            // And the same via the unpack accessors, so a change to one side has to change both.
            assertEquals(c[0], LightValue.block(VertexLight.unpackLight(packed)));
            assertEquals(c[1], LightValue.sky(VertexLight.unpackLight(packed)));
            assertEquals(c[2], VertexLight.unpackAo(packed));
        }

        String vertex = vertexSource();
        assertTrue(vertex.contains(">>  4u) & 0xFu"), "block shift missing from block.vert");
        assertTrue(vertex.contains(">> 20u) & 0xFu"), "sky shift missing from block.vert");
        assertTrue(vertex.contains(">> 24u) & 0x3u"), "occlusion shift missing from block.vert");
    }

    @Test
    void theShadeTableMatchesTheJavaOne() {
        Matcher m = Pattern.compile("float\\[4\\]\\(([^)]*)\\)").matcher(vertexSource());
        assertTrue(m.find(), "block.vert does not declare a four-entry shade table");
        String[] parts = m.group(1).split(",");
        assertEquals(4, parts.length);
        for (int ao = 0; ao < 4; ao++) {
            assertEquals(VertexLight.shadeFor(ao), Float.parseFloat(parts[ao].trim()), 0f,
                    "shade at occlusion level " + ao);
        }
    }

    @Test
    void theLightmapIsSampledAtTexelCentres() {
        // Off-by-half here is nearly invisible in daylight and obvious at night, where a level-0
        // corner picks up its neighbour's brightness and glows.
        String vertex = vertexSource();
        assertTrue(vertex.contains("LIGHTMAP_TEXELS = 16.0"), "the lightmap is 16x16");
        assertTrue(vertex.contains("+ 0.5) / LIGHTMAP_TEXELS"), "texel centres, not corners");
    }

    @Test
    void theFragmentShaderDoesNotDiscard() {
        // Cutout goes through a Z pre-pass instead; discard would disable early depth on the pass
        // that draws most of the scene. The shader explains why in a comment, which is why this
        // strips comments first: the word it is checking for appears in the explanation.
        assertTrue(!stripComments(fragmentSource()).contains("discard"),
                "block.frag must not discard; cutout is handled by the Z pre-pass");
    }

    @Test
    void theSpirvPathsAreDistinctFromTheGlslPathsAndFromEachOther() {
        // block.vert and block.frag both compile to a module; if they shared a name one would
        // overwrite the other in the jar and the graphics pipeline would link two copies of it.
        assertTrue(!BlockBindings.VERTEX_SPIRV_PATH.equals(BlockBindings.VERTEX_SHADER_PATH));
        assertTrue(!BlockBindings.FRAGMENT_SPIRV_PATH.equals(BlockBindings.FRAGMENT_SHADER_PATH));
        assertTrue(!BlockBindings.VERTEX_SPIRV_PATH.equals(BlockBindings.FRAGMENT_SPIRV_PATH));
        assertTrue(BlockBindings.VERTEX_SPIRV_PATH.endsWith(".spv"));
        assertTrue(BlockBindings.FRAGMENT_SPIRV_PATH.endsWith(".spv"));
    }
}
