package dev.amdfaster.core.plan;

import dev.amdfaster.core.arch.AmdArch;
import dev.amdfaster.core.backend.BackendKind;

import java.util.List;
import java.util.Locale;

/**
 * The immutable result of the tuning core: which AMD-Faster techniques are active and with which
 * parameters, plus the reasoning for each decision.
 *
 * <p>Every field has a {@link Technique} behind it and every change carries a rationale string, so
 * that a bug report ("my FPS got worse") can be answered from a log line rather than by guesswork.
 * The renderer is not allowed to invent its own parameters: if it needs a different value it has to
 * come from here, which keeps the policy in one testable place.
 *
 * @param arch                  architecture the plan was built for
 * @param backend               chosen rendering backend
 * @param amdTuning             true when the architecture was identified confidently enough to use
 *                              AMD specific behaviour
 * @param persistentStaging     {@link Technique#PERSISTENT_STAGING_RING}
 * @param stagingRingMiB        size of each per frame upload ring
 * @param stagingCoalesceKiB    minimum size of a coalesced upload batch
 * @param pinnedMemory          use {@code AMD_pinned_memory} for zero copy uploads
 * @param multiDrawIndirect     {@link Technique#MULTI_DRAW_INDIRECT}
 * @param maxCommandsPerBatch   upper bound on draw commands packed into one indirect call
 * @param gpuDrivenDrawCount    {@link Technique#GPU_DRIVEN_DRAW_COUNT}
 * @param regionBfs             {@link Technique#REGION_BFS_CULLING}
 * @param regionSizeX           region extent in sections (16 block units)
 * @param regionSizeY           region extent in sections
 * @param regionSizeZ           region extent in sections
 * @param maxSectionVisitsPerFrame traversal budget per frame
 * @param depthPyramid          {@link Technique#DEPTH_PYRAMID_OCCLUSION}
 * @param pyramidLevels         mip levels of the depth pyramid
 * @param occlusionBias         conservative depth bias used by the occlusion test
 * @param reversedZ             {@link Technique#REVERSED_Z_DEPTH}
 * @param asyncComputeCulling   {@link Technique#ASYNC_COMPUTE_CULLING}
 * @param meshShaderTerrain     {@link Technique#MESH_SHADER_TERRAIN}
 * @param meshWorkgroupSize     workgroup size for task/mesh pipelines
 * @param meshletQuadsPerTask   quads a single task shader invocation covers
 * @param bindlessDescriptors   {@link Technique#BINDLESS_DESCRIPTORS}
 * @param bindlessMaxTextures   size of the bindless texture table
 * @param subgroupSizeControl   {@link Technique#SUBGROUP_SIZE_CONTROL}
 * @param preferredWaveSize     preferred wave size for this device (32 or 64)
 * @param pipelinePrecompile    {@link Technique#PIPELINE_PRECOMPILE}
 * @param pipelineCachePersist  persist the pipeline cache to disk between launches
 * @param dynamicVgprAllocation {@link Technique#DYNAMIC_VGPR_ALLOCATION}
 * @param largeBarHostVisible   {@link Technique#LARGE_BAR_HOST_VISIBLE}
 * @param hostVisibleHeapMiB    size of the host visible device-local heap to reserve
 * @param compactTerrainVertex  {@link Technique#COMPACT_TERRAIN_VERTEX}
 * @param instancedVegetation   {@link Technique#INSTANCED_VEGETATION}
 * @param rationale             human readable reasons, one per decision
 */
public record TuningPlan(
        AmdArch arch,
        BackendKind backend,
        boolean amdTuning,
        boolean persistentStaging,
        int stagingRingMiB,
        int stagingCoalesceKiB,
        boolean pinnedMemory,
        boolean multiDrawIndirect,
        int maxCommandsPerBatch,
        boolean gpuDrivenDrawCount,
        boolean regionBfs,
        int regionSizeX,
        int regionSizeY,
        int regionSizeZ,
        int maxSectionVisitsPerFrame,
        boolean depthPyramid,
        int pyramidLevels,
        float occlusionBias,
        boolean reversedZ,
        boolean asyncComputeCulling,
        boolean meshShaderTerrain,
        int meshWorkgroupSize,
        int meshletQuadsPerTask,
        boolean bindlessDescriptors,
        int bindlessMaxTextures,
        boolean subgroupSizeControl,
        int preferredWaveSize,
        boolean pipelinePrecompile,
        boolean pipelineCachePersist,
        boolean dynamicVgprAllocation,
        boolean largeBarHostVisible,
        int hostVisibleHeapMiB,
        boolean compactTerrainVertex,
        boolean instancedVegetation,
        List<String> rationale) {

    public TuningPlan {
        rationale = List.copyOf(rationale);
    }

    /** Total number of sections in one region. */
    public int sectionsPerRegion() {
        return regionSizeX * regionSizeY * regionSizeZ;
    }

    public boolean isEnabled(Technique technique) {
        return switch (technique) {
            case PERSISTENT_STAGING_RING -> persistentStaging;
            case MULTI_DRAW_INDIRECT -> multiDrawIndirect;
            case GPU_DRIVEN_DRAW_COUNT -> gpuDrivenDrawCount;
            case REGION_BFS_CULLING -> regionBfs;
            case DEPTH_PYRAMID_OCCLUSION -> depthPyramid;
            case REVERSED_Z_DEPTH -> reversedZ;
            case ASYNC_COMPUTE_CULLING -> asyncComputeCulling;
            case MESH_SHADER_TERRAIN -> meshShaderTerrain;
            case BINDLESS_DESCRIPTORS -> bindlessDescriptors;
            case SUBGROUP_SIZE_CONTROL -> subgroupSizeControl;
            case PIPELINE_PRECOMPILE -> pipelinePrecompile;
            case DYNAMIC_VGPR_ALLOCATION -> dynamicVgprAllocation;
            case LARGE_BAR_HOST_VISIBLE -> largeBarHostVisible;
            case COMPACT_TERRAIN_VERTEX -> compactTerrainVertex;
            case INSTANCED_VEGETATION -> instancedVegetation;
        };
    }

    /** One line summary for logs and the HUD. */
    public String summary() {
        return String.format(Locale.ROOT, "[%s] %s%s | staging %s%s | indirect %s (batch %d) | region %dx%dx%d | reversedZ %s | mesh %s",
                arch.gfxIp(),
                backend.label(),
                amdTuning ? "" : " (generic tuning: architecture not confidently detected)",
                persistentStaging ? stagingRingMiB + " MiB" : "off",
                pinnedMemory ? " +pinned" : "",
                multiDrawIndirect ? "on" : "off",
                maxCommandsPerBatch,
                regionSizeX, regionSizeY, regionSizeZ,
                reversedZ ? "on" : "off",
                meshShaderTerrain ? meshWorkgroupSize + "-thread workgroups" : "off");
    }

    /** Multi line description including every rationale entry, used by the debug command and logs. */
    public String describe() {
        StringBuilder sb = new StringBuilder(summary());
        sb.append('\n');
        for (String reason : rationale) {
            sb.append("  - ").append(reason).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public static Builder builder(AmdArch arch, BackendKind backend) {
        return new Builder(arch, backend);
    }

    /** Builder used by {@link AmdTuner}; defaults are the "do nothing surprising" configuration. */
    public static final class Builder {
        private final AmdArch arch;
        private final BackendKind backend;
        private boolean amdTuning;
        private boolean persistentStaging;
        private int stagingRingMiB = 64;
        private int stagingCoalesceKiB = 64;
        private boolean pinnedMemory;
        private boolean multiDrawIndirect;
        private int maxCommandsPerBatch = 4096;
        private boolean gpuDrivenDrawCount;
        private boolean regionBfs = true;
        private int regionSizeX = 8;
        private int regionSizeY = 4;
        private int regionSizeZ = 8;
        private int maxSectionVisitsPerFrame = 131072;
        private boolean depthPyramid = true;
        private int pyramidLevels = 5;
        private float occlusionBias = 5.0E-4f;
        private boolean reversedZ;
        private boolean asyncComputeCulling;
        private boolean meshShaderTerrain;
        private int meshWorkgroupSize = 128;
        private int meshletQuadsPerTask = 256;
        private boolean bindlessDescriptors;
        private int bindlessMaxTextures = 4096;
        private boolean subgroupSizeControl;
        private int preferredWaveSize = 64;
        private boolean pipelinePrecompile = true;
        private boolean pipelineCachePersist = true;
        private boolean dynamicVgprAllocation;
        private boolean largeBarHostVisible;
        private int hostVisibleHeapMiB = 128;
        private boolean compactTerrainVertex = true;
        private boolean instancedVegetation;
        private final java.util.List<String> rationale = new java.util.ArrayList<>();

        private Builder(AmdArch arch, BackendKind backend) {
            this.arch = arch;
            this.backend = backend;
        }

        public Builder amdTuning(boolean v) {
            this.amdTuning = v;
            return this;
        }

        public Builder persistentStaging(boolean v, int ringMiB, int coalesceKiB) {
            this.persistentStaging = v;
            this.stagingRingMiB = ringMiB;
            this.stagingCoalesceKiB = coalesceKiB;
            return this;
        }

        public Builder pinnedMemory(boolean v) {
            this.pinnedMemory = v;
            return this;
        }

        public Builder multiDrawIndirect(boolean v, int maxCommands) {
            this.multiDrawIndirect = v;
            this.maxCommandsPerBatch = maxCommands;
            return this;
        }

        public Builder gpuDrivenDrawCount(boolean v) {
            this.gpuDrivenDrawCount = v;
            return this;
        }

        public Builder region(int x, int y, int z, int visitBudget) {
            this.regionSizeX = x;
            this.regionSizeY = y;
            this.regionSizeZ = z;
            this.maxSectionVisitsPerFrame = visitBudget;
            return this;
        }

        public Builder depthPyramid(boolean v, int levels, float bias) {
            this.depthPyramid = v;
            this.pyramidLevels = levels;
            this.occlusionBias = bias;
            return this;
        }

        public Builder reversedZ(boolean v) {
            this.reversedZ = v;
            return this;
        }

        public Builder asyncComputeCulling(boolean v) {
            this.asyncComputeCulling = v;
            return this;
        }

        public Builder meshShaderTerrain(boolean v, int workgroupSize, int quadsPerTask) {
            this.meshShaderTerrain = v;
            this.meshWorkgroupSize = workgroupSize;
            this.meshletQuadsPerTask = quadsPerTask;
            return this;
        }

        public Builder bindlessDescriptors(boolean v, int maxTextures) {
            this.bindlessDescriptors = v;
            this.bindlessMaxTextures = maxTextures;
            return this;
        }

        public Builder subgroupSizeControl(boolean v, int preferredWaveSize) {
            this.subgroupSizeControl = v;
            this.preferredWaveSize = preferredWaveSize;
            return this;
        }

        public Builder pipelinePrecompile(boolean v, boolean persist) {
            this.pipelinePrecompile = v;
            this.pipelineCachePersist = persist;
            return this;
        }

        public Builder dynamicVgprAllocation(boolean v) {
            this.dynamicVgprAllocation = v;
            return this;
        }

        public Builder largeBarHostVisible(boolean v, int heapMiB) {
            this.largeBarHostVisible = v;
            this.hostVisibleHeapMiB = heapMiB;
            return this;
        }

        public Builder compactTerrainVertex(boolean v) {
            this.compactTerrainVertex = v;
            return this;
        }

        public Builder instancedVegetation(boolean v) {
            this.instancedVegetation = v;
            return this;
        }

        public Builder reason(String reason) {
            this.rationale.add(reason);
            return this;
        }

        public TuningPlan build() {
            return new TuningPlan(arch, backend, amdTuning, persistentStaging, stagingRingMiB,
                    stagingCoalesceKiB, pinnedMemory, multiDrawIndirect, maxCommandsPerBatch,
                    gpuDrivenDrawCount, regionBfs, regionSizeX, regionSizeY, regionSizeZ,
                    maxSectionVisitsPerFrame, depthPyramid, pyramidLevels, occlusionBias, reversedZ,
                    asyncComputeCulling, meshShaderTerrain, meshWorkgroupSize, meshletQuadsPerTask,
                    bindlessDescriptors, bindlessMaxTextures, subgroupSizeControl, preferredWaveSize,
                    pipelinePrecompile, pipelineCachePersist, dynamicVgprAllocation,
                    largeBarHostVisible, hostVisibleHeapMiB, compactTerrainVertex,
                    instancedVegetation, rationale);
        }
    }
}
