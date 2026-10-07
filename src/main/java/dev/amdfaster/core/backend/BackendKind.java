package dev.amdfaster.core.backend;

/**
 * Which GPU backend AMD-Faster renders through.
 *
 * <p>The mod is deliberately layered so that the AMD tuning knowledge is shared between backends and
 * only the submission mechanism differs. That mirrors how AMD's own stack is built - PAL underneath
 * both the GL and Vulkan drivers, LLPC/ACO as the shader compiler - and it means a device that
 * cannot run the Vulkan path still gets the AMD tuned CPU side work.
 */
public enum BackendKind {
    /**
     * Vanilla Blaze3D OpenGL path with AMD tuned submission layered on top: persistent mapped
     * upload rings, multi-draw indirect batching and hierarchical CPU side culling. This is the
     * default because it is non-destructive: any failure here degrades to vanilla rendering.
     */
    GL_TUNED("OpenGL (AMD tuned submission)", /*requiresVulkan*/ false),

    /**
     * The full AMD-Faster Vulkan renderer: GPU driven terrain with indirect draws, bindless
     * descriptors and (on GFX10.3+) task/mesh shaders.
     */
    VULKAN_INDIRECT("Vulkan (GPU driven indirect)", true),

    /**
     * Vulkan with task/mesh shaders, the direct analogue of what Nvidium does with
     * {@code GL_NV_mesh_shader} - except this path is available on AMD too, because
     * {@code VK_EXT_mesh_shader} is exposed on RDNA 2 and newer.
     */
    VULKAN_MESH("Vulkan (task/mesh shader)", true),

    /** Nothing enabled; vanilla rendering with no interference. Used when detection fails. */
    VANILLA("vanilla (AMD-Faster inactive)", false);

    private final String label;
    private final boolean requiresVulkan;

    BackendKind(String label, boolean requiresVulkan) {
        this.label = label;
        this.requiresVulkan = requiresVulkan;
    }

    public String label() {
        return label;
    }

    public boolean requiresVulkan() {
        return requiresVulkan;
    }

    public boolean isVulkan() {
        return this == VULKAN_INDIRECT || this == VULKAN_MESH;
    }
}
