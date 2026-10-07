package net.amdfaster.gpu;

import net.amdfaster.platform.AmdArchitecture;

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

    /** The hierarchical depth buffer, read by the occlusion pass only. */
    public static final int HIZ_IMAGE_BINDING = 4;

    /**
     * Threads per work group. 64 is the size AMD recommends across every generation: it is two
     * full wave32s on RDNA and one full wave64 on GCN, so no lane is masked out on either.
     */
    public static final int WORKGROUP_SIZE = AmdArchitecture.WORKGROUP_SIZE;

    /** Bytes per meshlet record: packed bounds and three section-origin words. */
    public static final int MESHLET_RECORD_BYTES = 16;

    /** Bytes in one {@code VkDrawIndexedIndirectCommand}. */
    public static final int DRAW_COMMAND_BYTES = 20;

    /** Bytes in the push constant block: base meshlet index plus flags. */
    public static final int PUSH_CONSTANT_BYTES = 8;

    /** Bit of the push-constant flags word that selects the occlusion pass. */
    public static final int FLAG_OCCLUSION_PASS = 1;

    /** Bit that makes the shader clear the counter before compacting, i.e. the first pass. */
    public static final int FLAG_RESET_COUNTER = 2;

    /** Path of the frustum-and-cull shader inside the jar. */
    public static final String CULL_SHADER_PATH = "/shaders/meshlet_cull.comp";

    /** Path of the occlusion pass. */
    public static final String OCCLUSION_SHADER_PATH = "/shaders/meshlet_occlusion.comp";

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
