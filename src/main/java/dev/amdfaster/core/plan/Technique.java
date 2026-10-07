package dev.amdfaster.core.plan;

/**
 * The catalogue of AMD specific rendering techniques AMD-Faster can turn on, with the reason each
 * one exists. The tuning core decides <em>which</em> of these are enabled for a given device and how
 * they are parameterised; the renderer code reads the resulting {@link TuningPlan}.
 *
 * <p>Each entry records the AMD specific justification, because that is the part that is easy to
 * lose track of: several of these are things that help on AMD specifically and are neutral (or
 * slightly harmful) elsewhere, which is why the tuning core gates them on a confident AMD
 * classification.
 */
public enum Technique {
    /**
     * Persistent mapped upload rings ({@code ARB_buffer_storage} +
     * {@code GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT}).
     *
     * <p>The Windows AMD OpenGL driver is markedly slower than the competition at a high rate of
     * small {@code glBufferSubData} calls (each one can force the driver to re-validate and copy).
     * Writing into one persistently mapped ring buffer and publishing with a single fence turns N
     * driver round trips into one memcpy plus one sync, which is what Sodium's
     * persistent-mapped/indirect design does for the same reason.
     */
    PERSISTENT_STAGING_RING("Persistent mapped upload ring"),

    /**
     * Multi-draw indirect batching: all visible sections of one terrain layer become one
     * {@code glMultiDrawElementsIndirect} submission. RGP shows the win immediately as fewer, much
     * longer draw bursts; the RDNA command processor is happiest when handed long command streams
     * rather than thousands of individual draws interleaved with state changes.
     */
    MULTI_DRAW_INDIRECT("Batched indirect terrain draws"),

    /**
     * Letting the GPU write the surviving draw count ({@code ARB_indirect_parameters} /
     * {@code VK_KHR_draw_indirect_count}). Removes the CPU readback stall that otherwise appears
     * between the culling pass and the draw pass.
     */
    GPU_DRIVEN_DRAW_COUNT("GPU written draw count"),

    /**
     * Region based breadth-first visibility traversal instead of a flat frustum sweep.
     *
     * <p>Sections are grouped into small regions (8x4x8 sections by default), visibility is
     * propagated region to region breadth-first from the camera, and any region that fails a
     * conservative occlusion test prunes its entire subtree. This is the same class of algorithm
     * Nvidium uses for its GPU traversal; doing it on the CPU in plain Java keeps it available on
     * every backend, including the GL one.
     */
    REGION_BFS_CULLING("Region based BFS visibility"),

    /**
     * Hierarchical-Z occlusion from the previous frame's depth buffer (a depth pyramid), tested
     * conservatively against each region's bounding box.
     */
    DEPTH_PYRAMID_OCCLUSION("Depth pyramid occlusion"),

    /**
     * Reversed-Z depth with a floating point depth buffer. AMD hardware supports
     * {@code ARB_clip_control} on every GCN/RDNA part, and reversed-Z plus float depth makes the
     * depth distribution near-uniform, which both improves early-Z rejection and lets the near
     * plane be pushed in tight without far-plane precision loss.
     */
    REVERSED_Z_DEPTH("Reversed-Z depth"),

    /**
     * Culling and mesh work scheduled on a separate compute queue (RDNA exposes independent
     * graphics/compute queues) so the terrain traversal overlaps with the previous frame's
     * translucent pass instead of serialising behind it.
     */
    ASYNC_COMPUTE_CULLING("Overlapped compute culling"),

    /**
     * Task/mesh shader terrain rendering ({@code VK_EXT_mesh_shader}, GFX10.3+). The AMD analogue of
     * {@code GL_NV_mesh_shader} in Nvidium, but available on a much wider range of AMD parts: RDNA 2
     * and newer expose mesh shaders, and the AMD task shader can do the per-meshlet culling that
     * would otherwise need a compute prepass.
     */
    MESH_SHADER_TERRAIN("Task/mesh shader terrain"),

    /**
     * Bindless descriptor tables ({@code VK_EXT_descriptor_indexing} with
     * {@code PARTIALLY_BOUND | UPDATE_AFTER_BIND}), so texture and buffer changes never require a
     * descriptor set write or a pipeline rebind.
     */
    BINDLESS_DESCRIPTORS("Bindless descriptor tables"),

    /**
     * Explicit wave size selection per pipeline ({@code VK_EXT_subgroup_size_control}).
     *
     * <p>On RDNA, wave32 and wave64 are a real trade-off: wave32 keeps both halves of a divergent
     * branch busy and fits more waves into the same register file, wave64 halves the per-wave fixed
     * cost and lets one scalar op feed 64 lanes. Culling kernels want wave32, bulk terrain shading
     * usually wants wave64.
     */
    SUBGROUP_SIZE_CONTROL("Per pipeline wave size"),

    /**
     * Offline pipeline precompilation with a persistent pipeline cache. AMD's drivers (LLPC on
     * Windows/AMDVLK, ACO on RADV) both have measurable compile times; doing it during the loading
     * screen instead of on first draw removes the classic stutter spike when new terrain pipelines
     * appear.
     */
    PIPELINE_PRECOMPILE("Pipeline precompilation and cache"),

    /**
     * GFX12 dynamic VGPR re-allocation: the register file can be re-partitioned between resident
     * waves at runtime, so a kernel is no longer forced to choose between its own register use and
     * the occupancy of everything else on the CU.
     */
    DYNAMIC_VGPR_ALLOCATION("Dynamic VGPR reallocation (GFX12)"),

    /**
     * Place upload rings in {@code DEVICE_LOCAL | HOST_VISIBLE} memory. This is only possible when
     * the PCIe BAR window covers the frame buffer (Resizable BAR / Smart Access Memory). AMD cards
     * default to a 256 MiB BAR, so this is explicitly detected rather than assumed.
     */
    LARGE_BAR_HOST_VISIBLE("ReBAR host visible heaps"),

    /**
     * Compact terrain vertex formats: quantised positions/normals/lighting packed into 32 bytes per
     * vertex instead of 36+ bytes. AMD parts are frequently bandwidth limited at high render
     * distance (and APUs always are, because they share the DDR bus with the CPU), so shrinking the
     * vertex stream helps more than shaving ALU work.
     */
    COMPACT_TERRAIN_VERTEX("Compact terrain vertex format"),

    /**
     * Instanced vegetation/billboard rendering: one draw call per instance set instead of expanded
     * geometry, saving both bandwidth and vertex shading.
     */
    INSTANCED_VEGETATION("Instanced vegetation");

    private final String label;

    Technique(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
