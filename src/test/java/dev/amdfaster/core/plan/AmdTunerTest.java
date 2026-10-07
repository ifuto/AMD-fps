package dev.amdfaster.core.plan;

import dev.amdfaster.core.arch.AmdArch;
import dev.amdfaster.core.arch.GpuIdentity;
import dev.amdfaster.core.arch.GpuVendor;
import dev.amdfaster.core.backend.BackendKind;
import dev.amdfaster.core.backend.DriverKind;
import dev.amdfaster.core.backend.GpuCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tuning policy is the heart of the mod, so its decisions are asserted explicitly. */
class AmdTunerTest {

    private static GpuIdentity rdna2() {
        return new GpuIdentity(GpuVendor.AMD, "AMD Radeon RX 6800 XT (0x73BF)", AmdArch.RDNA2, 0.95,
                0x73BF, "Mesa 24.2.0", false, 16384, 16384);
    }

    private static GpuIdentity rdna1() {
        return new GpuIdentity(GpuVendor.AMD, "AMD Radeon RX 5700 XT", AmdArch.RDNA1, 0.95, 0x731F,
                "23.9.1", false, 8192, 256);
    }

    private static GpuIdentity nvidia() {
        return new GpuIdentity(GpuVendor.NVIDIA, "NVIDIA GeForce RTX 4090", AmdArch.UNKNOWN, 0.0, -1,
                "555.99", false, 24576, 32768);
    }

    private static GpuCapabilities gl46(DriverKind driver, int vramMiB, int barMiB) {
        return GpuCapabilities.builder(driver)
                .glBufferStorage(true)
                .glMultiDrawIndirect(true)
                .glIndirectParameters(true)
                .glShaderStorageBuffer(true)
                .glComputeShader(true)
                .glClipControl(true)
                .glDirectStateAccess(true)
                .vkAvailable(true)
                .vkMeshShader(true)
                .vkDescriptorIndexing(true)
                .vkDynamicRendering(true)
                .vkSynchronization2(true)
                .vkDrawIndirectCount(true)
                .vkSubgroupSizeControl(true)
                .vkExtendedDynamicState(true)
                .vkCalibratedTimestamps(true)
                .vkAmdBufferMarker(true)
                .vkPipelineStatistics(true)
                .vkMemoryBudget(true)
                .dedicatedVideoMemoryMiB(vramMiB)
                .barSizeMiB(barMiB)
                .systemRamMiB(32768)
                .apiVersion("4.6 (Core Profile) Mesa 24.2.0")
                .build();
    }

    @Test
    @DisplayName("default plan keeps the non-destructive GL path and turns on the AMD tuned submission")
    void defaultPlanForRdna2() {
        TuningPlan plan = AmdTuner.plan(rdna2(), gl46(DriverKind.MESA_RADEONSI, 16384, 16384));
        assertEquals(BackendKind.GL_TUNED, plan.backend());
        assertTrue(plan.amdTuning());
        assertTrue(plan.persistentStaging());
        assertEquals(256, plan.stagingRingMiB(), "16384 MiB VRAM / 64");
        assertEquals(64, plan.stagingCoalesceKiB());
        assertTrue(plan.multiDrawIndirect());
        assertEquals(8192, plan.maxCommandsPerBatch(), "RDNA 2 batch size");
        assertTrue(plan.gpuDrivenDrawCount());
        assertTrue(plan.regionBfs());
        assertEquals(8, plan.regionSizeX());
        assertEquals(4, plan.regionSizeY());
        assertEquals(8, plan.regionSizeZ());
        assertEquals(256, plan.sectionsPerRegion());
        assertTrue(plan.depthPyramid());
        assertTrue(plan.reversedZ());
        assertTrue(plan.pipelinePrecompile());
        assertTrue(plan.largeBarHostVisible(), "a 16384 MiB BAR window is Resizable BAR");
        assertEquals(32, plan.preferredWaveSize());
        assertFalse(plan.instancedVegetation(), "not implemented yet, so it must not claim to be on");
        assertTrue(plan.rationale().size() >= 8, "every decision must be documented");
        assertTrue(plan.describe().contains("RDNA 2"));
    }

    @Test
    @DisplayName("mesh shaders are only used when silicon, extension and config all agree")
    void meshShaderSelection() {
        GpuCapabilities caps = gl46(DriverKind.RADV, 16384, 16384);

        assertEquals(BackendKind.VULKAN_MESH,
                AmdTuner.plan(rdna2(), caps, new AmdTuner.Options(true, true, true)).backend());
        assertEquals(BackendKind.VULKAN_INDIRECT,
                AmdTuner.plan(rdna2(), caps, new AmdTuner.Options(true, false, true)).backend());
        // RDNA 1 has no mesh shader support at all (VK_EXT_mesh_shader needs GFX10.3+), so asking for
        // it must fall back to the indirect path rather than producing an unusable plan.
        assertEquals(BackendKind.VULKAN_INDIRECT,
                AmdTuner.plan(rdna1(), caps, new AmdTuner.Options(true, true, true)).backend());
        assertFalse(rdna1().arch().supportsMeshShader());
    }

    @Test
    @DisplayName("a Windows AMD OpenGL driver gets bigger coalesced uploads and no pinned memory")
    void windowsGlDriverTuning() {
        GpuCapabilities caps = GpuCapabilities.builder(DriverKind.AMD_WINDOWS_GL)
                .glBufferStorage(true)
                .glMultiDrawIndirect(true)
                .glClipControl(true)
                .glAmdPinnedMemory(true)
                .dedicatedVideoMemoryMiB(8192)
                .barSizeMiB(256)
                .systemRamMiB(32768)
                .apiVersion("4.6.0 AMD 23.9.1")
                .build();

        TuningPlan plan = AmdTuner.plan(rdna2(), caps);
        assertEquals(128, plan.stagingCoalesceKiB(), "the ATI-era GL path pays a high fixed cost per upload");
        assertTrue(plan.persistentStaging(), "ARB_buffer_storage is available on modern AMD GL drivers");
        assertTrue(plan.pinnedMemory(), "a discrete card with AMD_pinned_memory available uploads zero copy");
        assertFalse(plan.largeBarHostVisible(), "a 256 MiB BAR is the non-ReBAR default");
        assertTrue(DriverKind.AMD_WINDOWS_GL.isWeakGlSubmission());
    }

    @Test
    @DisplayName("integrated parts get a small ring because they share the DDR bus")
    void integratedGpuSizing() {
        GpuIdentity apu = new GpuIdentity(GpuVendor.AMD, "AMD Radeon 680M", AmdArch.RDNA2_APU, 0.9,
                0x164C, "Mesa 24.2.0", true, -1, -1);
        GpuCapabilities caps = GpuCapabilities.builder(DriverKind.MESA_RADEONSI)
                .glBufferStorage(true)
                .glMultiDrawIndirect(true)
                .dedicatedVideoMemoryMiB(-1)
                .barSizeMiB(-1)
                .systemRamMiB(16384)
                .apiVersion("4.6 (Core Profile) Mesa 24.2.0")
                .build();

        TuningPlan plan = AmdTuner.plan(apu, caps);
        assertEquals(128, plan.stagingRingMiB(), "16384 MiB RAM / 128, capped at 128");
        assertTrue(plan.compactTerrainVertex(), "bandwidth is the wall on an APU");
        assertTrue(plan.rationale().stream().anyMatch(r -> r.contains("DDR")));
        assertTrue(AmdArch.RDNA2_APU.isIntegrated());
    }

    @Test
    @DisplayName("non-AMD and unidentified devices only get vendor neutral tuning")
    void nonAmdAndUnknownDevices() {
        TuningPlan nvidiaPlan = AmdTuner.plan(nvidia(), gl46(DriverKind.NVIDIA_PROPRIETARY, 24576, 32768));
        assertFalse(nvidiaPlan.amdTuning());
        assertTrue(nvidiaPlan.persistentStaging(), "persistent staging is a vendor neutral win");
        assertFalse(nvidiaPlan.asyncComputeCulling(), "AMD queue model only");
        assertTrue(nvidiaPlan.rationale().stream().anyMatch(r -> r.contains("not identified")));

        GpuIdentity unknownAmd = new GpuIdentity(GpuVendor.AMD, "AMD Radeon Mystery", AmdArch.UNKNOWN,
                0.3, -1, "driver", false, 8192, 256);
        TuningPlan unknownPlan = AmdTuner.plan(unknownAmd, gl46(DriverKind.MESA_RADEONSI, 8192, 256));
        assertFalse(unknownPlan.amdTuning(), "an unidentified architecture must not drive aggressive tuning");
        assertTrue(unknownPlan.regionBfs(), "region BFS is conservative and stays on");
    }

    @Test
    @DisplayName("no Vulkan at all still produces a usable GL plan")
    void noVulkanFallback() {
        GpuCapabilities glOnly = GpuCapabilities.builder(DriverKind.MESA_RADEONSI)
                .glBufferStorage(true)
                .glMultiDrawIndirect(true)
                .dedicatedVideoMemoryMiB(8192)
                .barSizeMiB(256)
                .apiVersion("4.6 (Core Profile) Mesa 24.2.0")
                .build();

        TuningPlan plan = AmdTuner.plan(rdna2(), glOnly, new AmdTuner.Options(true, true, true));
        assertEquals(BackendKind.GL_TUNED, plan.backend());
        assertFalse(plan.gpuDrivenDrawCount(), "no ARB_indirect_parameters on this driver");
        assertFalse(plan.meshShaderTerrain());
    }

    @Test
    @DisplayName("RDNA 4 reports dynamic VGPR allocation and bigger batches")
    void rdna4Plan() {
        GpuIdentity rdna4 = new GpuIdentity(GpuVendor.AMD, "AMD Radeon RX 9070 XT", AmdArch.RDNA4, 0.95,
                -1, "25.3.1", false, 16384, 16384);
        TuningPlan plan = AmdTuner.plan(rdna4, gl46(DriverKind.AMD_WINDOWS_VULKAN, 16384, 16384));
        assertTrue(plan.dynamicVgprAllocation());
        assertEquals(16384, plan.maxCommandsPerBatch());
        assertTrue(AmdArch.RDNA4.supportsDynamicVgpr());
        assertTrue(AmdArch.RDNA4.supportsMeshShader());
    }

    @Test
    @DisplayName("GCN parts stay on wave64 and do not claim RDNA-only features")
    void gcnPlan() {
        GpuIdentity vega = new GpuIdentity(GpuVendor.AMD, "AMD Radeon RX Vega 64", AmdArch.VEGA, 0.95,
                0x687F, "23.9.1", false, 8192, 256);
        TuningPlan plan = AmdTuner.plan(vega, gl46(DriverKind.AMD_WINDOWS_GL, 8192, 256));
        assertEquals(64, plan.preferredWaveSize());
        assertFalse(plan.meshShaderTerrain());
        assertFalse(plan.dynamicVgprAllocation());
        assertEquals(4096, plan.maxCommandsPerBatch(), "GCN command processor gets smaller batches");
        assertFalse(AmdArch.VEGA.supportsWave32());
    }
}
