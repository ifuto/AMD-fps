package dev.amdfaster.core.plan;

import dev.amdfaster.core.arch.AmdArch;
import dev.amdfaster.core.arch.GpuIdentity;
import dev.amdfaster.core.backend.BackendKind;
import dev.amdfaster.core.backend.GpuCapabilities;
import dev.amdfaster.core.occupancy.OccupancyModel;

/**
 * The policy engine. Turns "what hardware and driver am I on" plus "what can this API do" into a
 * concrete {@link TuningPlan}, and records why.
 *
 * <p>The rules encode AMD specific knowledge from the ISA guides, the GPUOpen performance guides and
 * the open driver sources, and they are deliberately conservative: when the architecture is not
 * identified with {@link GpuIdentity#CONFIDENT} confidence only the vendor-neutral parts of the plan
 * are enabled.
 */
public final class AmdTuner {

    private AmdTuner() {
    }

    /**
     * Caller supplied switches, mapping 1:1 onto mod config options.
     *
     * @param allowVulkanBackend  the Vulkan renderer is opt-in (it is a full backend replacement)
     * @param allowMeshShaders    task/mesh shader terrain is opt-in on top of the Vulkan backend
     * @param enableAmdTuning     master switch for AMD specific behaviour
     */
    public record Options(boolean allowVulkanBackend, boolean allowMeshShaders, boolean enableAmdTuning) {
        public static Options defaults() {
            // Vulkan is a replacement backend, so it stays opt-in until it has proven itself on the
            // user's machine; the GL path is what a fresh install gets.
            return new Options(false, false, true);
        }
    }

    public static TuningPlan plan(GpuIdentity identity, GpuCapabilities caps) {
        return plan(identity, caps, Options.defaults());
    }

    public static TuningPlan plan(GpuIdentity identity, GpuCapabilities caps, Options options) {
        AmdArch arch = identity.arch();
        boolean amd = options.enableAmdTuning() && identity.isArchConfident();
        boolean integrated = identity.isIntegratedGpu() || arch.isIntegrated();

        BackendKind backend = selectBackend(identity, caps, options);

        TuningPlan.Builder plan = TuningPlan.builder(arch, backend).amdTuning(amd);

        plan.reason(identity.describe());
        plan.reason(amd
                ? "architecture confidently identified, AMD specific tuning enabled"
                : "architecture not identified with >= " + Math.round(GpuIdentity.CONFIDENT * 100)
                        + "% confidence: only vendor neutral techniques are enabled (please report this GPU)");

        // --- upload path ---------------------------------------------------------------------
        if (caps.canUsePersistentStaging()) {
            int ringMiB = stagingRingMiB(identity, caps, integrated);
            int coalesceKiB = caps.driver().isWeakGlSubmission() ? 128 : 64;
            plan.persistentStaging(true, ringMiB, coalesceKiB);
            plan.reason("ARB_buffer_storage available: " + ringMiB + " MiB persistent mapped upload ring per frame");
            if (caps.driver().isWeakGlSubmission()) {
                plan.reason("Windows AMD OpenGL driver detected: uploads are coalesced into >= " + coalesceKiB
                        + " KiB batches because this driver pays a high fixed cost per small glBufferSubData");
            }
            if (caps.glAmdPinnedMemory() && !integrated) {
                plan.pinnedMemory(true);
                plan.reason("AMD_pinned_memory available: chunk data can be uploaded zero copy from pinned host memory");
            }
        } else {
            plan.reason("no ARB_buffer_storage: falling back to the vanilla upload path");
        }

        // --- submission ----------------------------------------------------------------------
        if (caps.canUseMultiDrawIndirect()) {
            int batch = maxCommandsPerBatch(arch);
            plan.multiDrawIndirect(true, batch);
            plan.reason("multi draw indirect supported: terrain sections are packed into batches of up to "
                    + batch + " commands" + (amd && arch.supportsVopd()
                            ? " (RDNA 3+ command processor prefers long bursts over many short draws)" : ""));
            if (caps.canUseGpuDrivenDrawCount()) {
                plan.gpuDrivenDrawCount(true);
                plan.reason("ARB_indirect_parameters + compute + SSBO: the surviving draw count is written on the GPU,"
                        + " removing the culling -> draw CPU round trip");
            }
        } else {
            plan.reason("no multi draw indirect: per section draws stay as they are");
        }

        // --- visibility ----------------------------------------------------------------------
        // Regions of 8x4x8 sections (128 x 64 x 128 blocks) keep the region graph small enough to
        // traverse breadth first every frame while still pruning aggressively. The same region shape
        // is what Nvidium arrived at for its GPU traversal, which is a good sanity check.
        int visitBudget = 131072;
        plan.region(8, 4, 8, visitBudget);
        plan.reason("region BFS visibility with 8x4x8 section regions ("
                + (8 * 4 * 8) + " sections/region), traversal budget " + visitBudget + " section visits/frame");

        boolean reversedZ = caps.canUseReversedZ() && (amd || identity.vendor() == dev.amdfaster.core.arch.GpuVendor.NVIDIA);
        plan.reversedZ(reversedZ);
        if (reversedZ) {
            plan.reason("clip control available: reversed-Z float depth, which improves early-Z rejection and"
                    + " allows a tighter near plane (AMD supports ARB_clip_control on all GCN/RDNA parts)");
        }

        float occlusionBias = reversedZ ? 5.0E-4f : 2.0E-3f;
        plan.depthPyramid(true, 5, occlusionBias);
        plan.reason("depth pyramid occlusion test with " + 5 + " levels and bias " + occlusionBias
                + (reversedZ ? " (bias kept small because reversed-Z keeps precision near the far plane)" : ""));

        // --- queues and shaders --------------------------------------------------------------
        if (amd && arch.asyncComputeQueues() >= 2) {
            plan.asyncComputeCulling(true);
            plan.reason(arch.displayName() + " exposes independent compute queues: culling runs overlapped with"
                    + " the previous frame's translucent pass");
        }

        boolean mesh = backend == BackendKind.VULKAN_MESH;
        plan.meshShaderTerrain(mesh, 128, 256);
        if (mesh) {
            plan.reason("task/mesh shader terrain enabled (VK_EXT_mesh_shader on " + arch.gfxIp()
                    + "): meshlet culling happens in the task shader");
        } else if (caps.vkAvailable() && caps.vkMeshShader() && arch.supportsMeshShader()) {
            plan.reason("mesh shaders are available but disabled in config; the indirect path is used instead");
        }

        boolean bindless = caps.canUseBindless() && (backend.isVulkan() || caps.driver().isAmdStack());
        plan.bindlessDescriptors(bindless, 4096);
        if (bindless) {
            plan.reason("bindless descriptors available: texture changes no longer need descriptor writes");
        }

        boolean waveControl = caps.vkSubgroupSizeControl();
        int preferredWave = arch.supportsWave32() ? 32 : 64;
        plan.subgroupSizeControl(waveControl, preferredWave);
        if (arch.supportsWave32()) {
            plan.reason("wave32 preferred: " + arch.displayName() + " has " + arch.maxWavesPerSimd()
                    + " wave32 slots/SIMD with a " + arch.vgprGranuleWave32()
                    + " VGPR allocation granularity, so occupancy steps are"
                    + " " + OccupancyModel.budgetTable(arch, 32).length + " deep");
        } else {
            plan.reason("wave64 only (GCN): wave size stays at 64 and the " + arch.vgprGranuleWave64()
                    + " VGPR granularity drives occupancy steps");
        }
        if (waveControl) {
            plan.reason("VK_EXT_subgroup_size_control available: culling kernels are pinned to wave32 and bulk"
                    + " terrain shading to wave64");
        }

        plan.pipelinePrecompile(true, true);
        plan.reason("pipeline precompilation and on disk pipeline cache: ShaderStutter is avoided by compiling"
                + " terrain pipeline variants during the loading screen");

        if (arch.supportsDynamicVgpr()) {
            plan.dynamicVgprAllocation(true);
            plan.reason(arch.gfxIp() + " supports dynamic VGPR reallocation, so register pressure affects"
                    + " occupancy less than on earlier parts");
        }

        // --- memory ---------------------------------------------------------------------------
        if (caps.hasLargeBar()) {
            int heap = Math.min(Math.max(caps.barSizeMiB() / 8, 64), 512);
            plan.largeBarHostVisible(true, heap);
            plan.reason("PCIe BAR window is " + caps.barSizeMiB() + " MiB (> 256 MiB default): " + heap
                    + " MiB of DEVICE_LOCAL|HOST_VISIBLE heap is used for upload rings (Resizable BAR / SAM)");
        } else {
            plan.reason("BAR window is " + (caps.barSizeMiB() > 0 ? caps.barSizeMiB() + " MiB" : "unknown")
                    + " (no Resizable BAR): uploads go through a staging buffer in host visible system memory");
        }

        plan.compactTerrainVertex(true);
        plan.reason("compact terrain vertex format: AMD parts are usually bandwidth limited at high render"
                + " distance, and APUs always are because they share the DDR bus with the CPU");

        plan.instancedVegetation(false);
        plan.reason("instanced vegetation is planned but not enabled in this build");

        return plan.build();
    }

    private static BackendKind selectBackend(GpuIdentity identity, GpuCapabilities caps, Options options) {
        if (!caps.vkAvailable()) {
            return BackendKind.GL_TUNED;
        }
        if (options.allowVulkanBackend() && options.allowMeshShaders()
                && caps.canUseMeshShaders(identity)) {
            return BackendKind.VULKAN_MESH;
        }
        if (options.allowVulkanBackend()) {
            return BackendKind.VULKAN_INDIRECT;
        }
        return BackendKind.GL_TUNED;
    }

    /**
     * Upload ring sizing. Chunk meshes are uploaded in bursts when the player moves, so the ring has
     * to absorb a few frames of burst without forcing a full pipeline stall; but on an APU every MiB
     * reserved for staging is a MiB of system RAM the game cannot use.
     */
    static int stagingRingMiB(GpuIdentity identity, GpuCapabilities caps, boolean integrated) {
        if (integrated) {
            int ram = caps.systemRamMiB() > 0 ? caps.systemRamMiB() : 8192;
            return clamp(ram / 128, 32, 128);
        }
        int vram = caps.dedicatedVideoMemoryMiB() > 0
                ? caps.dedicatedVideoMemoryMiB()
                : identity.dedicatedVideoMemoryMiB();
        if (vram <= 0) {
            return 64;
        }
        return clamp(vram / 64, 64, 512);
    }

    /**
     * Commands per indirect batch. Larger batches mean fewer submissions, but each batch also has to
     * be built and sorted; the numbers here keep a single batch inside a few hundred KiB of indirect
     * command buffer (32 bytes per command).
     */
    static int maxCommandsPerBatch(AmdArch arch) {
        return switch (arch) {
            case GCN1, GCN2, GCN3, POLARIS, VEGA, VEGA_APU, CDNA, UNKNOWN -> 4096;
            case RDNA1, RDNA2, RDNA2_APU -> 8192;
            case RDNA3, RDNA3_APU, RDNA4 -> 16384;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
