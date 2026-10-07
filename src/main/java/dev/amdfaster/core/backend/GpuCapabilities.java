package dev.amdfaster.core.backend;

import dev.amdfaster.core.arch.GpuIdentity;

/**
 * Everything the tuning core needs to know about the API and driver capabilities of the current
 * device. Probed once at startup (GL) and once per physical device (Vulkan), then treated as
 * immutable - no tuning decision in AMD-Faster is allowed to depend on mutable render state.
 *
 * @param glBufferStorage        {@code ARB_buffer_storage} / GL 4.4: persistent mapped buffers
 * @param glMultiDrawIndirect    {@code ARB_multi_draw_indirect} / GL 4.3
 * @param glIndirectParameters   {@code ARB_indirect_parameters}: GPU-written draw counts
 * @param glShaderStorageBuffer  GL 4.3 SSBOs
 * @param glComputeShader        GL 4.3 compute shaders (needed for GPU side culling on the GL path)
 * @param glClipControl          {@code ARB_clip_control}: required for reversed-Z depth
 * @param glBindlessTexture      {@code ARB_bindless_texture}
 * @param glAmdPinnedMemory      {@code AMD_pinned_memory}: zero-copy uploads from a pinned host buffer
 * @param glDirectStateAccess    GL 4.5 DSA/`glGetTextureSubImage` - used to trim state validation
 * @param vkAvailable            a Vulkan loader and a usable physical device were found
 * @param vkMeshShader           {@code VK_EXT_mesh_shader} (RDNA 2 / GFX10.3 and newer)
 * @param vkDescriptorIndexing   {@code VK_EXT_descriptor_indexing}: bindless, non-uniform indexing
 * @param vkDynamicRendering     {@code VK_KHR_dynamic_rendering}: no render pass objects to manage
 * @param vkSynchronization2     {@code VK_KHR_synchronization2}: cheaper barrier batching
 * @param vkDrawIndirectCount    {@code VK_KHR_draw_indirect_count}: GPU driven draw counts
 * @param vkSubgroupSizeControl  {@code VK_EXT_subgroup_size_control}: force wave32 or wave64 per pipeline
 * @param vkExtendedDynamicState {@code VK_EXT_extended_dynamic_state}: fewer pipeline objects
 * @param vkCalibratedTimestamps {@code VK_EXT_calibrated_timestamps}: GPU timestamp correlation
 * @param vkAmdBufferMarker      {@code VK_AMD_buffer_marker}: pipeline stage markers (a poor man's RGP)
 * @param vkPipelineStatistics   pipeline statistics queries (primitives, vertex invocations, ...)
 * @param vkMemoryBudget         {@code VK_EXT_memory_budget}
 * @param driver                 classified driver stack
 * @param dedicatedVideoMemoryMiB dedicated VRAM in MiB, {@code -1} unknown (APUs report the UMA aperture here)
 * @param barSizeMiB             PCIe BAR window size in MiB; 256 means no Resizable BAR
 * @param systemRamMiB           system RAM in MiB, {@code -1} unknown
 * @param apiVersion             API version string for logs, e.g. {@code "4.6 (Core Profile) Mesa 24.2"}
 */
public record GpuCapabilities(
        boolean glBufferStorage,
        boolean glMultiDrawIndirect,
        boolean glIndirectParameters,
        boolean glShaderStorageBuffer,
        boolean glComputeShader,
        boolean glClipControl,
        boolean glBindlessTexture,
        boolean glAmdPinnedMemory,
        boolean glDirectStateAccess,
        boolean vkAvailable,
        boolean vkMeshShader,
        boolean vkDescriptorIndexing,
        boolean vkDynamicRendering,
        boolean vkSynchronization2,
        boolean vkDrawIndirectCount,
        boolean vkSubgroupSizeControl,
        boolean vkExtendedDynamicState,
        boolean vkCalibratedTimestamps,
        boolean vkAmdBufferMarker,
        boolean vkPipelineStatistics,
        boolean vkMemoryBudget,
        DriverKind driver,
        int dedicatedVideoMemoryMiB,
        int barSizeMiB,
        int systemRamMiB,
        String apiVersion) {

    public GpuCapabilities {
        if (driver == null) {
            driver = DriverKind.UNKNOWN;
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = "unknown";
        }
    }

    /** True when the GL path can upload geometry through persistent mapped rings. */
    public boolean canUsePersistentStaging() {
        return glBufferStorage;
    }

    /** True when the GL path can batch section draws into one indirect call. */
    public boolean canUseMultiDrawIndirect() {
        return glMultiDrawIndirect;
    }

    /** True when the GL path can let the GPU write the surviving draw count. */
    public boolean canUseGpuDrivenDrawCount() {
        return glIndirectParameters && glComputeShader && glShaderStorageBuffer;
    }

    /** True when reversed-Z depth is possible (AMD supports ARB_clip_control on every GCN/RDNA part). */
    public boolean canUseReversedZ() {
        return glClipControl || vkAvailable;
    }

    /**
     * True when a Vulkan mesh shader terrain path is technically possible: GFX10.3+ silicon plus
     * the {@code VK_EXT_mesh_shader} extension.
     */
    public boolean canUseMeshShaders(GpuIdentity identity) {
        return vkAvailable && vkMeshShader && identity.arch().supportsMeshShader();
    }

    public boolean canUseBindless() {
        return vkDescriptorIndexing || glBindlessTexture;
    }

    /**
     * True when the PCIe BAR is big enough to hold upload rings in VRAM and write to them directly.
     * AMD ships a 256 MiB BAR by default, and the full frame buffer with Resizable BAR /
     * Smart Access Memory enabled, which is exactly the 256 MiB number WMMA/VMA guidance revolves
     * around.
     */
    public boolean hasLargeBar() {
        return barSizeMiB > 256;
    }

    public static Builder builder(DriverKind driver) {
        return new Builder(driver);
    }

    /** Mutable builder; probes fill it in, then {@link #build()} freezes it. */
    public static final class Builder {
        private boolean glBufferStorage;
        private boolean glMultiDrawIndirect;
        private boolean glIndirectParameters;
        private boolean glShaderStorageBuffer;
        private boolean glComputeShader;
        private boolean glClipControl;
        private boolean glBindlessTexture;
        private boolean glAmdPinnedMemory;
        private boolean glDirectStateAccess;
        private boolean vkAvailable;
        private boolean vkMeshShader;
        private boolean vkDescriptorIndexing;
        private boolean vkDynamicRendering;
        private boolean vkSynchronization2;
        private boolean vkDrawIndirectCount;
        private boolean vkSubgroupSizeControl;
        private boolean vkExtendedDynamicState;
        private boolean vkCalibratedTimestamps;
        private boolean vkAmdBufferMarker;
        private boolean vkPipelineStatistics;
        private boolean vkMemoryBudget;
        private final DriverKind driver;
        private int dedicatedVideoMemoryMiB = -1;
        private int barSizeMiB = -1;
        private int systemRamMiB = -1;
        private String apiVersion = "unknown";

        private Builder(DriverKind driver) {
            this.driver = driver;
        }

        public Builder glBufferStorage(boolean v) {
            this.glBufferStorage = v;
            return this;
        }

        public Builder glMultiDrawIndirect(boolean v) {
            this.glMultiDrawIndirect = v;
            return this;
        }

        public Builder glIndirectParameters(boolean v) {
            this.glIndirectParameters = v;
            return this;
        }

        public Builder glShaderStorageBuffer(boolean v) {
            this.glShaderStorageBuffer = v;
            return this;
        }

        public Builder glComputeShader(boolean v) {
            this.glComputeShader = v;
            return this;
        }

        public Builder glClipControl(boolean v) {
            this.glClipControl = v;
            return this;
        }

        public Builder glBindlessTexture(boolean v) {
            this.glBindlessTexture = v;
            return this;
        }

        public Builder glAmdPinnedMemory(boolean v) {
            this.glAmdPinnedMemory = v;
            return this;
        }

        public Builder glDirectStateAccess(boolean v) {
            this.glDirectStateAccess = v;
            return this;
        }

        public Builder vkAvailable(boolean v) {
            this.vkAvailable = v;
            return this;
        }

        public Builder vkMeshShader(boolean v) {
            this.vkMeshShader = v;
            return this;
        }

        public Builder vkDescriptorIndexing(boolean v) {
            this.vkDescriptorIndexing = v;
            return this;
        }

        public Builder vkDynamicRendering(boolean v) {
            this.vkDynamicRendering = v;
            return this;
        }

        public Builder vkSynchronization2(boolean v) {
            this.vkSynchronization2 = v;
            return this;
        }

        public Builder vkDrawIndirectCount(boolean v) {
            this.vkDrawIndirectCount = v;
            return this;
        }

        public Builder vkSubgroupSizeControl(boolean v) {
            this.vkSubgroupSizeControl = v;
            return this;
        }

        public Builder vkExtendedDynamicState(boolean v) {
            this.vkExtendedDynamicState = v;
            return this;
        }

        public Builder vkCalibratedTimestamps(boolean v) {
            this.vkCalibratedTimestamps = v;
            return this;
        }

        public Builder vkAmdBufferMarker(boolean v) {
            this.vkAmdBufferMarker = v;
            return this;
        }

        public Builder vkPipelineStatistics(boolean v) {
            this.vkPipelineStatistics = v;
            return this;
        }

        public Builder vkMemoryBudget(boolean v) {
            this.vkMemoryBudget = v;
            return this;
        }

        public Builder dedicatedVideoMemoryMiB(int v) {
            this.dedicatedVideoMemoryMiB = v;
            return this;
        }

        public Builder barSizeMiB(int v) {
            this.barSizeMiB = v;
            return this;
        }

        public Builder systemRamMiB(int v) {
            this.systemRamMiB = v;
            return this;
        }

        public Builder apiVersion(String v) {
            this.apiVersion = v;
            return this;
        }

        public GpuCapabilities build() {
            return new GpuCapabilities(glBufferStorage, glMultiDrawIndirect, glIndirectParameters,
                    glShaderStorageBuffer, glComputeShader, glClipControl, glBindlessTexture,
                    glAmdPinnedMemory, glDirectStateAccess, vkAvailable, vkMeshShader,
                    vkDescriptorIndexing, vkDynamicRendering, vkSynchronization2, vkDrawIndirectCount,
                    vkSubgroupSizeControl, vkExtendedDynamicState, vkCalibratedTimestamps,
                    vkAmdBufferMarker, vkPipelineStatistics, vkMemoryBudget, driver,
                    dedicatedVideoMemoryMiB, barSizeMiB, systemRamMiB, apiVersion);
        }
    }
}
