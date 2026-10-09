package net.amdfaster.gpu;

import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.vk.RootSignatureBudget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the GLSL against the Java that binds it.
 *
 * <p>A binding number that drifts between the two does not fail to compile: it fails to draw, or
 * reads the wrong buffer, and only on some drivers. These tests turn that class of bug into a
 * failure here, including the uniform block layout, where a member crossing a std140 padding
 * boundary silently reads garbage.
 */
class CullShaderTest {

    private static String read(String path) {
        try (InputStream in = CullBindings.class.getResourceAsStream(path)) {
            assertNotNull(in, "shader resource missing from the jar: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern LAYOUT_LINE = Pattern.compile("layout\\s*\\(([^)]*)\\)([^;{]*)");

    /** Every "binding = N" in the source, paired with the uniform/buffer keyword that follows. */
    private static Map<String, Integer> bindings(String source) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = LAYOUT_LINE.matcher(source);
        while (m.find()) {
            String layout = m.group(1);
            Matcher b = Pattern.compile("binding\\s*=\\s*(\\d+)").matcher(layout);
            if (!b.find()) {
                continue;
            }
            // The declared block name is the last identifier before the brace or semicolon.
            String rest = m.group(2).trim();
            Matcher name = Pattern.compile("(\\w+)\\s*\\{?$").matcher(rest.replace("{", " {"));
            String key = name.find() ? name.group(1) : rest;
            out.put(key, Integer.parseInt(b.group(1)));
        }
        return out;
    }

    @Test
    void bothShadersShipInTheJar() {
        assertTrue(read(CullBindings.CULL_SHADER_PATH).contains("local_size_x"));
        assertTrue(read(CullBindings.OCCLUSION_SHADER_PATH).contains("local_size_x"));
    }

    @Test
    void bothShadersUseTheWorkGroupSizeAmdWants() {
        for (String path : List.of(CullBindings.CULL_SHADER_PATH, CullBindings.OCCLUSION_SHADER_PATH)) {
            String source = read(path);
            assertTrue(source.contains("local_size_x = " + CullBindings.WORKGROUP_SIZE),
                    path + " must dispatch " + CullBindings.WORKGROUP_SIZE + " threads: "
                            + "two wave32s on RDNA, one wave64 on GCN, no masked lanes either way");
        }
        assertEquals(AmdArchitecture.WORKGROUP_SIZE, CullBindings.WORKGROUP_SIZE);
        assertEquals(0, CullBindings.WORKGROUP_SIZE % 64, "AMD's guidance is a multiple of 64");
    }

    @Test
    void theCullShaderBindsWhatJavaBinds() {
        Map<String, Integer> found = bindings(read(CullBindings.CULL_SHADER_PATH));
        assertEquals(CullBindings.FRAME_UBO_BINDING, found.get("Frame"));
        assertEquals(CullBindings.MESHLET_BUFFER_BINDING, found.get("MeshletData"));
        assertEquals(CullBindings.DRAW_COMMAND_BUFFER_BINDING, found.get("DrawCommands"));
        assertEquals(CullBindings.COUNTER_BUFFER_BINDING, found.get("Counters"));
        assertEquals(CullBindings.ORIENTATION_BUFFER_BINDING, found.get("MeshletSideData"),
                "the back-face cull reads the per-meshlet orientation, and the draw command needs "
                        + "the quad count packed next to it");
    }

    @Test
    void theOcclusionShaderAlsoBindsThePyramid() {
        Map<String, Integer> found = bindings(read(CullBindings.OCCLUSION_SHADER_PATH));
        assertEquals(CullBindings.FRAME_UBO_BINDING, found.get("Frame"));
        assertEquals(CullBindings.MESHLET_BUFFER_BINDING, found.get("MeshletData"));
        assertEquals(CullBindings.DRAW_COMMAND_BUFFER_BINDING, found.get("DrawCommands"));
        assertEquals(CullBindings.COUNTER_BUFFER_BINDING, found.get("Counters"));
        assertEquals(CullBindings.HIZ_IMAGE_BINDING, found.get("hiZDepth"));
    }

    @Test
    void theFrustumPassDoesNotReadTheDepthPyramid() {
        // Pass one builds the pyramid, so reading it there would be reading last frame's depth
        // against this frame's geometry -- which is exactly the artefact two-pass culling avoids.
        String source = read(CullBindings.CULL_SHADER_PATH);
        assertTrue(!source.contains("hiZDepth"), "the frustum pass must not sample the pyramid");
    }

    @Test
    void thePushConstantBlockStaysInsideTheRootSignatureBudget() {
        String source = read(CullBindings.CULL_SHADER_PATH);
        Matcher m = Pattern.compile("push_constant\\)\\s*uniform\\s+Push\\s*\\{([^}]*)\\}",
                Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), "no push constant block found");
        long words = m.group(1).chars().filter(c -> c == ';').count();
        assertEquals(2, words, "baseMeshlet and flags, both uint");
        assertEquals(CullBindings.PUSH_CONSTANT_BYTES, words * 4);

        RootSignatureBudget.Verdict verdict = RootSignatureBudget.check(CullBindings.PUSH_CONSTANT_BYTES, 1);
        assertTrue(verdict.fits(), verdict.detail());
    }

    @Test
    void theViewProjectionMatrixIsNotAPushConstant() {
        // 64 bytes is 16 DWORDs, and the budget is 13. It has to live in the uniform block.
        String source = read(CullBindings.CULL_SHADER_PATH);
        Matcher push = Pattern.compile("push_constant\\)\\s*uniform\\s+Push\\s*\\{([^}]*)\\}",
                Pattern.DOTALL).matcher(source);
        assertTrue(push.find());
        assertTrue(!push.group(1).contains("mat4"), "a mat4 in push constants breaks the budget");
    }

    @Test
    void theUniformBlockLaysOutWhereJavaWritesIt() {
        Map<String, Integer> offsets = std140Offsets(read(CullBindings.CULL_SHADER_PATH));
        assertEquals(CullBindings.FRAME_OFFSET_VIEW_PROJECTION, offsets.get("viewProjection"));
        assertEquals(CullBindings.FRAME_OFFSET_FRUSTUM_PLANES, offsets.get("frustumPlanes"));
        assertEquals(CullBindings.FRAME_OFFSET_CAMERA_ORIGIN, offsets.get("cameraOrigin"));
        assertEquals(CullBindings.FRAME_OFFSET_MESHLET_COUNT, offsets.get("meshletCount"));
        assertEquals(CullBindings.FRAME_OFFSET_HIZ_WIDTH, offsets.get("hizWidth"));
        assertEquals(CullBindings.FRAME_OFFSET_HIZ_HEIGHT, offsets.get("hizHeight"));
    }

    @Test
    void theUniformBlockIsTheSizeJavaAllocates() {
        int size = std140Size(read(CullBindings.CULL_SHADER_PATH));
        assertEquals(CullBindings.FRAME_UBO_BYTES, size);
    }

    @Test
    void aDrawCommandIsFiveWords() {
        // indexCount, instanceCount, firstIndex, vertexOffset, firstInstance.
        assertEquals(20, CullBindings.DRAW_COMMAND_BYTES);
        String source = read(CullBindings.CULL_SHADER_PATH);
        // The slot is the wave's base plus this lane's offset within it; see the wave-cooperative
        // append at the end of main(). Both halves have to be there -- a wave base alone would put
        // every survivor of a wave on the same command, and a lane offset alone would make waves
        // overwrite each other.
        assertTrue(source.contains("(visibleBase + laneSlot) * 5u"), "the stride in the shader must match");
        assertTrue(source.contains("subgroupBallotExclusiveBitCount"), "the lane slot must come from an exclusive scan");
    }

    @Test
    void aMeshletRecordIsFourWords() {
        assertEquals(16, CullBindings.MESHLET_RECORD_BYTES);
        assertTrue(read(CullBindings.CULL_SHADER_PATH).contains("uvec4 meshlets[]"));
    }

    @Test
    void packingFillsTheRecordInTheOrderTheShaderReads() {
        int[] out = new int[8];
        CullBindings.packMeshlet(out, 4, 0x1234, -16, 32, -48);
        assertEquals(0x1234, out[4]);
        assertEquals(-16, out[5]);
        assertEquals(32, out[6]);
        assertEquals(-48, out[7]);
        assertEquals(0, out[0], "nothing before the record");
        assertEquals(0, out[3], "nothing between the offset and the record");
    }

    @Test
    void workGroupsCoverEveryMeshletExactlyOnce() {
        assertEquals(0, CullBindings.workGroups(0));
        assertEquals(0, CullBindings.workGroups(-5));
        assertEquals(1, CullBindings.workGroups(1));
        assertEquals(1, CullBindings.workGroups(64));
        assertEquals(2, CullBindings.workGroups(65));
        assertEquals(100, CullBindings.workGroups(6400));
        assertEquals(101, CullBindings.workGroups(6401));
    }

    @Test
    void bothShadersGuardTheTailOfWork() {
        // The dispatch is rounded up, so the last work group is partly empty and every invocation
        // past the end has to be excluded.
        //
        // The cull shader cannot return early any more: subgroup operations only count active
        // lanes, so a lane that returned would vanish from the ballot and the wave's total would
        // come up short. It folds the bound into the predicates it ballots instead, which is what
        // keeps a padded lane out of both the tested count and the survivor list -- and therefore
        // out of the indirect draw count, which is where an overstatement would show up as
        // geometry drawn from an uninitialised draw command.
        String cull = read(CullBindings.CULL_SHADER_PATH);
        assertTrue(cull.contains("bool inRange = index < meshletCount;"),
                "the cull shader must bound the tail of the dispatch");
        assertTrue(cull.contains("subgroupBallot(inRange)"),
                "padded lanes must be excluded from the tested count");

        assertTrue(read(CullBindings.OCCLUSION_SHADER_PATH).contains("if (slot >= visibleCount)"));
    }

    // ---------------------------------------------------------------------------------------

    /** Removes // comments; they can contain semicolons, which would break the member split. */
    private static String stripComments(String source) {
        return source.replaceAll("//[^\\n]*", "");
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** std140 offsets of the members of the Frame block, computed from the shader source. */
    private static Map<String, Integer> std140Offsets(String source) {
        Matcher block = Pattern.compile("uniform\\s+Frame\\s*\\{([^}]*)\\}", Pattern.DOTALL)
                .matcher(source);
        assertTrue(block.find(), "no Frame uniform block");

        Map<String, Integer> offsets = new LinkedHashMap<>();
        int cursor = 0;
        for (String line : stripComments(block.group(1)).split(";")) {
            String declaration = line.trim();
            if (declaration.isEmpty()) {
                continue;
            }
            String[] parts = declaration.split("\\s+");
            String type = parts[0];
            String name = parts[1].replaceAll("\\[.*", "");
            int elements = declaration.contains("[")
                    ? Integer.parseInt(declaration.replaceAll(".*\\[(\\d+)\\].*", "$1"))
                    : 1;

            int alignment;
            int elementSize;
            switch (type) {
                case "mat4" -> {
                    alignment = 16;
                    elementSize = 64;
                }
                case "vec4", "uvec4", "ivec4" -> {
                    alignment = 16;
                    elementSize = 16;
                }
                case "uint", "int", "float" -> {
                    alignment = 4;
                    elementSize = 4;
                }
                default -> throw new IllegalStateException("unhandled std140 type: " + type);
            }
            if (elements > 1) {
                alignment = 16;   // array elements are rounded up to a vec4 in std140
            }
            cursor = alignUp(cursor, alignment);
            offsets.put(name, cursor);
            cursor += elementSize * elements;
        }
        return offsets;
    }

    private static int std140Size(String source) {
        Map<String, Integer> ignored = std140Offsets(source);
        Matcher block = Pattern.compile("uniform\\s+Frame\\s*\\{([^}]*)\\}", Pattern.DOTALL)
                .matcher(source);
        block.find();
        List<String> members = new ArrayList<>();
        for (String line : stripComments(block.group(1)).split(";")) {
            String declaration = line.trim();
            if (!declaration.isEmpty()) {
                members.add(declaration);
            }
        }
        int cursor = 0;
        for (String declaration : members) {
            String[] parts = declaration.split("\\s+");
            int elements = declaration.contains("[")
                    ? Integer.parseInt(declaration.replaceAll(".*\\[(\\d+)\\].*", "$1")) : 1;
            int alignment = switch (parts[0]) {
                case "mat4" -> 16;
                case "vec4", "uvec4", "ivec4" -> 16;
                default -> 4;
            };
            int elementSize = switch (parts[0]) {
                case "mat4" -> 64;
                case "vec4", "uvec4", "ivec4" -> 16;
                default -> 4;
            };
            if (elements > 1) {
                alignment = 16;
            }
            cursor = alignUp(cursor, alignment) + elementSize * elements;
        }
        return alignUp(cursor, 16);
    }
}
