package net.amdfaster.gpu;

import net.amdfaster.platform.AmdArchitecture;

import java.util.ArrayList;
import java.util.List;

/**
 * The descriptor layout shared by the culling shaders and the Java that binds them.
 *
 * <p>A descriptor binding that drifts between the shader and the pipeline layout does not fail to
 * compile -- it fails to draw, or draws the wrong buffer, and only on some drivers. Keeping the
 * numbers here and asserting the shader text against them turns that into a test failure.
 *
 * <p>Note that the view-projection matrix is <b>not</b> a push constant. It is 64 bytes, which is
 * 16 DWORDs, and AMD's root signature budget is 13. Push constants carry only the eight bytes of
 * per-dispatch state below, so the whole root signature is 2 + 2 = 4 DWORDs.
 */
public final class CullBindings {

    /** Per-frame uniforms: matrices, the extracted planes, the camera-relative origin. */
    public static final int FRAME_UBO_BINDING = 0;

    /** One {@code uvec4} per meshlet: packed bounds plus the section origin. */
    public static final int MESHLET_BUFFER_BINDING = 1;

    /** {@code VkDrawIndexedIndirectCommand} array, compacted by the shader. */
    public static final int DRAW_COMMAND_BUFFER_BINDING = 2;

    /** Atomic counters: visible count, and the count the next dispatch reads. */
    public static final int COUNTER_BUFFER_BINDING = 3;

    /**
     * The hierarchical depth pyramid, read by the occlusion pass only, as a combined image
     * sampler. A bare {@code texture2D} would need GL_EXT_samplerless_texture_functions for
     * {@code textureSize} and {@code texelFetch}, and the sampler states what the fetch wants
     * anyway: nearest filtering, so no texel is averaged across the reduction.
     */
    public static final int HIZ_IMAGE_BINDING = 5;

    /**
     * One {@code uint} per meshlet: the orientation bucket in the low three bits and the meshlet's
     * quad count above them.
     *
     * <p>A separate buffer rather than three more bits in the packed bounds. Six orientations need
     * three bits and the bounds word has exactly two free, and the alternatives were all worse --
     * stealing a bit from a bounds field would cap meshlets at 8 blocks wide, and hiding it in the
     * high bits of a section origin word would break the day the world gets taller. Four bytes per
     * meshlet is small next to the 5 332 bytes of mesh data.
     *
     * <p>The quad count is packed alongside rather than given its own buffer because the cull shader
     * needs it to write an exact {@code indexCount} and it is the only per-meshlet data left that
     * the shader does not already have. See {@code Meshlet#writeSideData}.
     *
     * <p>Numbered 4, ahead of the pyramid, so that the frustum pass is 0..4 and the occlusion pass
     * is 0..5. Both stay dense and in order, which is what lets the two share a descriptor pool with
     * one layout prefixing the other.
     */
    public static final int ORIENTATION_BUFFER_BINDING = 4;

    /**
     * Threads per work group. 64 is the size AMD recommends across every generation: it is two
     * full wave32s on RDNA and one full wave64 on GCN, so no lane is masked out on either.
     */
    public static final int WORKGROUP_SIZE = AmdArchitecture.WORKGROUP_SIZE;

    /** Bytes per meshlet record: packed bounds and three section-origin words. */
    public static final int MESHLET_RECORD_BYTES = 16;

    /** Bytes per meshlet in the orientation buffer. */
    public static final int ORIENTATION_BYTES_PER_MESHLET = 4;

    /** Bytes in one {@code VkDrawIndexedIndirectCommand}. */
    public static final int DRAW_COMMAND_BYTES = 20;

    /** Bytes in the push constant block: base meshlet index plus flags. */
    public static final int PUSH_CONSTANT_BYTES = 8;

    /** Bit of the push-constant flags word that selects the occlusion pass. */
    public static final int FLAG_OCCLUSION_PASS = 1;

    /** Bit that makes the shader clear the counter before compacting, i.e. the first pass. */
    public static final int FLAG_RESET_COUNTER = 2;

    /** Path of the frustum-and-cull shader source inside the jar. */
    public static final String CULL_SHADER_PATH = "/shaders/meshlet_cull.comp";

    /** Path of the occlusion pass source. */
    public static final String OCCLUSION_SHADER_PATH = "/shaders/meshlet_occlusion.comp";

    /**
     * Path of the pyramid reduction source.
     *
     * <p>Min-reduced, ceil-halved, edge-clamped. All three are correctness requirements rather than
     * choices; see {@code net.amdfaster.cull.PyramidGeometry} and the shader header.
     */
    public static final String HIZ_REDUCE_SHADER_PATH = "/shaders/hiz_reduce.comp";

    /** Path of the entity occlusion pass source, the GPU mirror of {@code EntityOcclusionCuller}. */
    public static final String ENTITY_OCCLUSION_SHADER_PATH = "/shaders/entity_occlusion.comp";

    /** Compiled SPIR-V for the frustum pass, produced by the {@code compileShaders} build task. */
    public static final String CULL_SPIRV_PATH = "/shaders/meshlet_cull.spv";

    /** Compiled SPIR-V for the occlusion pass. */
    public static final String OCCLUSION_SPIRV_PATH = "/shaders/meshlet_occlusion.spv";

    // Mirrors VkDescriptorType.
    public static final int DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
    public static final int DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
    public static final int DESCRIPTOR_TYPE_STORAGE_BUFFER = 7;

    /** {@code VK_SHADER_STAGE_COMPUTE_BIT}. */
    public static final int STAGE_COMPUTE = 0x00000020;

    /** One binding of the culling descriptor set layout. */
    public record Binding(int binding, int descriptorType, int count, int stageFlags, String name) {
    }

    /**
     * The descriptor set layout, in one place, because the shader text, the pipeline layout and
     * the code that writes the descriptors all have to agree and none of them can see the others.
     *
     * <p>Binding 4 is a combined image sampler: a bare {@code texture2D} would need
     * GL_EXT_samplerless_texture_functions for {@code textureSize} and {@code texelFetch}.
     */
    public static List<Binding> descriptorBindings() {
        return List.of(
                new Binding(FRAME_UBO_BINDING, DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, STAGE_COMPUTE, "frame"),
                new Binding(MESHLET_BUFFER_BINDING, DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, STAGE_COMPUTE, "meshlets"),
                new Binding(DRAW_COMMAND_BUFFER_BINDING, DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, STAGE_COMPUTE, "draws"),
                new Binding(COUNTER_BUFFER_BINDING, DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, STAGE_COMPUTE, "counters"),
                new Binding(ORIENTATION_BUFFER_BINDING, DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, STAGE_COMPUTE,
                        "sideData"));   // 0,1,2,3,4 -- dense, and a prefix of the occlusion set
    }

    /**
     * The occlusion pass adds the depth pyramid. Kept separate so the frustum pass can be created
     * without a sampler it does not use.
     */
    public static List<Binding> occlusionDescriptorBindings() {
        List<Binding> base = new ArrayList<>(descriptorBindings());
        base.add(new Binding(HIZ_IMAGE_BINDING, DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, STAGE_COMPUTE,
                "hiZDepth"));
        return List.copyOf(base);
    }

    /**
     * Offsets inside the frame uniform block, which is {@code std140} and therefore padded to
     * 16-byte boundaries. A struct member that crosses one of those boundaries reads garbage, and
     * the shader will not complain.
     */
    public static final int FRAME_OFFSET_VIEW_PROJECTION = 0;
    public static final int FRAME_OFFSET_FRUSTUM_PLANES = 64;
    public static final int FRAME_OFFSET_CAMERA_ORIGIN = 160;
    public static final int FRAME_OFFSET_MESHLET_COUNT = 176;
    public static final int FRAME_OFFSET_HIZ_WIDTH = 180;
    public static final int FRAME_OFFSET_HIZ_HEIGHT = 184;
    public static final int FRAME_UBO_BYTES = 192;

    private CullBindings() {
    }

    /** The number of work groups a dispatch needs to cover {@code count} meshlets. */
    public static int workGroups(int count) {
        if (count <= 0) {
            return 0;
        }
        return (count + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
    }

    /**
     * Packs a section-local 5-bit-per-axis AABB and a section origin into the 16-byte record the
     * shader reads, as four ints.
     */
    public static void packMeshlet(int[] out, int offset, int packedBounds,
                                   int originX, int originY, int originZ) {
        out[offset] = packedBounds;
        out[offset + 1] = originX;
        out[offset + 2] = originY;
        out[offset + 3] = originZ;
    }
}
