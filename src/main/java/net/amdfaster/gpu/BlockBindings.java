package net.amdfaster.gpu;

import org.lwjgl.vulkan.VK10;

import java.util.List;

/**
 * Descriptor set, vertex input and push constant layout for drawing block geometry.
 *
 * <p>This lives in set 1 rather than set 0. Set 0 belongs to the culling pipeline and is declared
 * {@code STAGE_COMPUTE} only, because a descriptor that no stage uses still costs validation time
 * and a pipeline layout has to name every stage it might reach. Widening set 0 to cover the
 * graphics stages would mean the compute pipeline carrying a layout it cannot use. Two sets, each
 * visible to exactly the stages that read it, is both correct and cheaper.
 *
 * <p>The frame block is declared again here with the same field order and offsets as
 * {@code CullBindings}, so one buffer serves both pipelines. It is bound twice per frame, which
 * costs nothing measurable, and it means the CPU-side layout constants stay the single source of
 * truth for the frame block's size.
 *
 * <p>Source: {@code docs/notes/12} §4.
 */
public final class BlockBindings {

    /** Descriptor set index. Set 0 is the culling pipeline's. */
    public static final int SET = 1;

    public static final int FRAME_UBO_BINDING = 0;
    public static final int BLOCK_ATLAS_BINDING = 1;
    public static final int LIGHT_MAP_BINDING = 2;
    public static final int BINDING_COUNT = 3;

    /** Vertex and fragment, so the frame block and both textures are visible where they are read. */
    public static final int STAGE_VERTEX = 0x00000001;
    public static final int STAGE_FRAGMENT = 0x00000010;
    public static final int STAGE_VERTEX_FRAGMENT = STAGE_VERTEX | STAGE_FRAGMENT;

    /** One vec4: the section's origin in blocks, added to the meshlet's section-local positions. */
    public static final int PUSH_CONSTANT_BYTES = 16;

    // --- vertex input ---------------------------------------------------------------------
    //
    // Three streams, matching Meshlet's three write methods. Kept separate rather than interleaved
    // so the Z pre-pass can bind only the position stream, which is the whole reason position got
    // a stream of its own.

    public static final int POSITION_BINDING = 0;
    public static final int ATTRIBUTE_BINDING = 1;
    public static final int LIGHT_BINDING = 2;

    public static final int POSITION_LOCATION = 0;
    public static final int UV_LOCATION = 1;
    public static final int LIGHT_LOCATION = 2;

    // These reference VK10 rather than spelling out the enum values. The first version did spell
    // them out, on the theory that keeping this class free of LWJGL was worth something, and got
    // all three wrong -- the format enum is not laid out in tidy blocks of eight, because some
    // component widths have no SRGB variant and so the groups are uneven. CI reported the first
    // mismatch as "expected 96 but was 84". Referring to the binding is not a compromise here: it
    // is the only version that cannot drift, and LWJGL is on the classpath anyway.

    /**
     * {@code short x, short y, short z, short flags}. Signed integers rather than normalised:
     * block coordinates are integers, and normalising them to [-1,1] would lose the exact values
     * at the ends of the range, which is exactly where a section boundary is.
     */
    public static final int FORMAT_POSITION = VK10.VK_FORMAT_R16G16B16A16_SINT;

    /** {@code float u, float v}. */
    public static final int FORMAT_ATTRIBUTE = VK10.VK_FORMAT_R32G32_SFLOAT;

    /** One word: block in bits 4..7, sky in bits 20..23, occlusion in bits 24..25. */
    public static final int FORMAT_LIGHT = VK10.VK_FORMAT_R32_UINT;

    // --- resources ------------------------------------------------------------------------

    public static final String VERTEX_SHADER_PATH = "/shaders/block.vert";
    public static final String FRAGMENT_SHADER_PATH = "/shaders/block.frag";

    public static final String VERTEX_SPIRV_PATH = "/shaders/block_vert.spv";
    public static final String FRAGMENT_SPIRV_PATH = "/shaders/block_frag.spv";

    // Descriptor types, following CullBindings: the raw values keep this class usable from tests
    // that do not want to pull in LWJGL, and BlockBindingsTest checks them against VK10.
    public static final int DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
    public static final int DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;

    private BlockBindings() {
    }

    /**
     * @param binding   the binding index within {@link #SET}
     * @param type      a Vulkan descriptor type
     * @param count     descriptors at this binding, always 1 here
     * @param stageFlags which pipeline stages may read it
     * @param name      the block or uniform name in the GLSL, so a test can check the two agree
     */
    public record Binding(int binding, int type, int count, int stageFlags, String name) {
    }

    /** Every binding in the set, in binding order. */
    public static List<Binding> bindings() {
        return List.of(
                new Binding(FRAME_UBO_BINDING, DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                        STAGE_VERTEX_FRAGMENT, "Frame"),
                new Binding(BLOCK_ATLAS_BINDING, DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                        STAGE_FRAGMENT, "blockAtlas"),
                new Binding(LIGHT_MAP_BINDING, DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                        STAGE_FRAGMENT, "lightMap"));
    }

    /**
     * The frame block's offsets, repeated here so that a mismatch between the cull shader and the
     * block shader is a compile-time-visible difference rather than a shader reading the wrong
     * field of a shared buffer.
     */
    public static final int FRAME_OFFSET_VIEW_PROJECTION = 0;
    public static final int FRAME_OFFSET_FRUSTUM_PLANES = 64;
    public static final int FRAME_OFFSET_CAMERA_ORIGIN = 160;
    public static final int FRAME_OFFSET_MESHLET_COUNT = 176;
    public static final int FRAME_UBO_BYTES = 192;

    /** Offsets inside the push constant block. */
    public static final int PUSH_OFFSET_SECTION_ORIGIN = 0;
}
