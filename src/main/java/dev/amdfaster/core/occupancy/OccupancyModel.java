package dev.amdfaster.core.occupancy;

import dev.amdfaster.core.arch.AmdArch;
import dev.amdfaster.core.arch.AmdFamily;

/**
 * AMD occupancy model - a faithful Java port of the maths AMD's own LLVM based compiler (LLPC) uses
 * through the AMDGPU backend:
 *
 * <pre>
 *   wavesPerSimd = clamp(TotalNumVGPRs / alignTo(NumVGPRs, Granule), 1, MaxWavesPerSimd)
 * </pre>
 *
 * <p>Source of the formulas and constants (read together with the ISA reference guides):
 * <ul>
 *   <li>{@code llvm/lib/Target/AMDGPU/Utils/AMDGPUBaseInfo.cpp} -
 *       {@code getNumWavesPerEUWithNumVGPRs()}, {@code getVGPRAllocGranule()},
 *       {@code getOccupancyWithNumSGPRs()}, {@code isSGPROccupancyLimited()}.</li>
 *   <li>{@code llvm/lib/Target/AMDGPU/AMDGPU.td} - {@code FeatureMaxWavesPerEU{8,10,16,20}},
 *       {@code Feature1536VGPRs} (GFX11+/GFX12 register file).</li>
 *   <li>AMD <i>RDNA ISA Reference Guides</i> for the register file, allocation granularity and LDS
 *       sizes, and the <i>RDNA Architecture Whitepaper</i> for the wave32/wave64 execution model.</li>
 * </ul>
 *
 * <p>Why AMD-Faster cares: on AMD hardware, occupancy does not degrade smoothly with register
 * pressure, it falls off a cliff every time the register allocator crosses an allocation block
 * boundary (16 VGPRs on RDNA 2 wave32, 24 on RDNA 3 wave32, 8 on RDNA 1). Being able to tell the
 * shader compiler "this kernel costs you 2 waves because it uses 97 VGPRs instead of 96" is worth
 * more than almost any other static analysis number.
 */
public final class OccupancyModel {

    private OccupancyModel() {
    }

    /** What limits occupancy: always report this, it is the actionable part of the analysis. */
    public enum Limiter {
        VGPR,
        SGPR,
        LDS,
        WAVE_SLOTS,
        NONE
    }

    /**
     * A compute kernel description in the units AMD's hardware uses.
     *
     * @param vgprs         vector registers used per lane (per thread)
     * @param sgprs         scalar registers used per wave, 0 when unknown
     * @param ldsBytes      LDS bytes per workgroup, 0 when the kernel does not use LDS
     * @param waveSize      32 or 64
     * @param workgroupSize threads per workgroup (1..1024)
     */
    public record Kernel(int vgprs, int sgprs, int ldsBytes, int waveSize, int workgroupSize) {
        public Kernel {
            if (waveSize != 32 && waveSize != 64) {
                throw new IllegalArgumentException("wave size must be 32 or 64, got " + waveSize);
            }
            if (workgroupSize < 1 || workgroupSize > 1024) {
                throw new IllegalArgumentException("workgroup size out of range: " + workgroupSize);
            }
            vgprs = Math.max(1, vgprs);
            sgprs = Math.max(0, sgprs);
            ldsBytes = Math.max(0, ldsBytes);
        }
    }

    /**
     * Occupancy analysis result.
     *
     * @param wavesPerSimd               resident waves ("wavefronts") per SIMD unit
     * @param wavesPerCu                 resident waves per compute unit ({@code wavesPerSimd * simdPerCu})
     * @param maxWavesPerSimd            hardware maximum for this arch and wave size
     * @param occupancy                  {@code wavesPerSimd / maxWavesPerSimd}, 0..1
     * @param limitingFactor             which resource caps the occupancy
     * @param maxVgprsForCurrentWaves    highest VGPR count that still achieves {@code wavesPerSimd}
     * @param maxVgprsForNextWaveStep    largest VGPR count that would fit one more wave, or -1 if at max
     * @param vgprsToReleaseForNextStep  how many VGPRs must be saved to gain a wave, or -1 if at max
     * @param vgprGranule                allocation granularity used for this wave size
     * @param spillsExpected             true when the register pressure exceeds the whole file
     */
    public record Result(
            int wavesPerSimd,
            int wavesPerCu,
            int maxWavesPerSimd,
            double occupancy,
            Limiter limitingFactor,
            int maxVgprsForCurrentWaves,
            int maxVgprsForNextWaveStep,
            int vgprsToReleaseForNextStep,
            int vgprGranule,
            boolean spillsExpected,
            int wavesPerWorkgroup,
            int maxWorkgroupsPerCu) {

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(wavesPerSimd).append('/').append(maxWavesPerSimd).append(" waves/SIMD (")
                    .append(Math.round(occupancy * 100)).append("%), limited by ").append(limitingFactor);
            sb.append(", ").append(wavesPerWorkgroup).append(" waves/workgroup, max ")
                    .append(maxWorkgroupsPerCu).append(" workgroups/CU");
            if (vgprsToReleaseForNextStep > 0) {
                sb.append("; freeing ").append(vgprsToReleaseForNextStep)
                        .append(" VGPRs (to ").append(maxVgprsForNextWaveStep).append(") gains a wave");
            } else if (wavesPerSimd < maxWavesPerSimd) {
                sb.append("; cannot gain a wave by register reduction alone");
            }
            if (spillsExpected) {
                sb.append("; WARNING: register pressure exceeds the file, spills likely");
            }
            return sb.toString();
        }
    }

    /** Rounds {@code value} up to the next multiple of {@code granule}. */
    static int alignTo(int value, int granule) {
        if (granule <= 1) {
            return value;
        }
        return ((value + granule - 1) / granule) * granule;
    }

    /** Rounds {@code value} down to the previous multiple of {@code granule}. */
    static int alignDown(int value, int granule) {
        if (granule <= 1) {
            return value;
        }
        return (value / granule) * granule;
    }

    /** Maximum resident waves per SIMD for the requested wave size. */
    public static int maxWavesPerSimd(AmdArch arch, int waveSize) {
        int slots = Math.max(1, arch.maxWavesPerSimd());
        if (waveSize == 64 && arch.family() == AmdFamily.RDNA) {
            // RDNA's wave slot limit is counted in wave32 units (16 on GFX10+, LLVM's
            // MaxWavesPerEU / getMaxWavesPerEU), so a wave64 kernel consumes two slots and can
            // therefore only ever reach half the wave count.
            return Math.max(1, slots / 2);
        }
        // GCN and CDNA slots are already counted in wave64 (10 waves/SIMD on GFX6-GFX9).
        return slots;
    }

    /** VGPR allocation granularity the hardware uses for this wave size. */
    public static int vgprGranule(AmdArch arch, int waveSize) {
        return waveSize == 64 ? arch.vgprGranuleWave64() : arch.vgprGranuleWave32();
    }

    /** Total vector register file per SIMD, expressed in this wave size's registers. */
    public static int totalVgprs(AmdArch arch, int waveSize) {
        return waveSize == 64 ? arch.totalVgprsWave64() : arch.totalVgprsWave32();
    }

    /**
     * The core LLVM formula: waves that fit per SIMD given a VGPR count.
     * Mirrors {@code AMDGPUBaseInfo.cpp::getNumWavesPerEUWithNumVGPRs()}.
     */
    public static int wavesForVgprs(AmdArch arch, int waveSize, int vgprs) {
        int granule = vgprGranule(arch, waveSize);
        int maxWaves = maxWavesPerSimd(arch, waveSize);
        if (vgprs < granule) {
            return maxWaves;
        }
        int rounded = alignTo(vgprs, granule);
        int waves = totalVgprs(arch, waveSize) / rounded;
        return Math.min(Math.max(waves, 1), maxWaves);
    }

    /** Mirrors {@code getOccupancyWithNumSGPRs()}; returns {@code maxWaves} on GFX10+ where SGPRs never limit. */
    public static int wavesForSgprs(AmdArch arch, int sgprs) {
        int maxWaves = maxWavesPerSimd(arch, 32);
        if (!arch.sgprsLimitOccupancy() || sgprs <= 0) {
            return maxWaves;
        }
        int granule = arch.sgprGranule();
        // A small reserve exists for the trap handler; it counts against every wave.
        int trapReserve = arch.family() == AmdFamily.GCN ? 1 : 0;
        int perWave = alignTo(sgprs, granule) + trapReserve;
        return Math.max(1, Math.min(arch.totalSgprs() / perWave, maxWaves));
    }

    /**
     * LDS limit: the whole per-CU LDS can be claimed by a single workgroup on GFX10+, so the wave
     * count is limited by how many workgroups' worth of LDS fit in the CU's LDS.
     */
    public static int wavesForLds(AmdArch arch, Kernel kernel) {
        if (kernel.ldsBytes() <= 0) {
            return maxWavesPerSimd(arch, kernel.waveSize());
        }
        int granule = arch.family() == AmdFamily.RDNA ? 128 : 512;
        int perWorkgroup = alignTo(kernel.ldsBytes(), granule);
        if (perWorkgroup > arch.ldsBytesPerWorkgroupMax()) {
            // The kernel cannot even be launched; report the smallest possible residency.
            return 1;
        }
        int workgroupsPerCu = Math.max(1, arch.ldsBytesPerCu() / perWorkgroup);
        int wavesPerWorkgroup = Math.max(1,
                (kernel.workgroupSize() + kernel.waveSize() - 1) / kernel.waveSize());
        return Math.max(1, workgroupsPerCu * wavesPerWorkgroup);
    }

    /** Full analysis for one kernel. */
    public static Result evaluate(AmdArch arch, Kernel kernel) {
        int waveSize = kernel.waveSize();
        int maxWaves = maxWavesPerSimd(arch, waveSize);
        int granule = vgprGranule(arch, waveSize);

        int vgprWaves = wavesForVgprs(arch, waveSize, kernel.vgprs());
        int sgprWaves = wavesForSgprs(arch, kernel.sgprs());
        int ldsWaves = wavesForLds(arch, kernel);

        int waves = Math.min(Math.min(vgprWaves, sgprWaves), ldsWaves);

        Limiter limiter;
        if (waves >= maxWaves) {
            limiter = Limiter.WAVE_SLOTS;
        } else if (waves == vgprWaves) {
            limiter = Limiter.VGPR;
        } else if (waves == sgprWaves) {
            limiter = Limiter.SGPR;
        } else {
            limiter = Limiter.LDS;
        }

        int maxVgprsForWaves = maxVgprsForWaves(arch, waveSize, waves);
        int nextWaves = Math.min(waves + 1, maxWaves);
        int maxVgprsForNextWaveStep = nextWaves == waves ? -1 : maxVgprsForWaves(arch, waveSize, nextWaves);
        int vgprsToRelease = maxVgprsForNextWaveStep < 0
                ? -1
                : Math.max(0, kernel.vgprs() - maxVgprsForNextWaveStep);

        boolean spills = kernel.vgprs() > totalVgprs(arch, waveSize);

        int wavesPerWorkgroup = wavesPerWorkgroup(kernel);
        int wavesPerCu = waves * Math.max(1, arch.simdPerCu());
        int maxWorkgroupsPerCu = Math.max(1, Math.min(
                hardwareWorkgroupsPerCu(arch),
                wavesPerCu / Math.max(1, wavesPerWorkgroup)));

        return new Result(
                waves,
                wavesPerCu,
                maxWaves,
                (double) waves / maxWaves,
                limiter,
                maxVgprsForWaves,
                maxVgprsForNextWaveStep,
                vgprsToRelease,
                granule,
                spills,
                wavesPerWorkgroup,
                maxWorkgroupsPerCu);
    }

    /** Waves a single workgroup occupies. */
    public static int wavesPerWorkgroup(Kernel kernel) {
        return Math.max(1, (kernel.workgroupSize() + kernel.waveSize() - 1) / kernel.waveSize());
    }

    /** Hardware cap on resident workgroups per compute unit (16 on GCN, 32 from GFX10 on). */
    public static int hardwareWorkgroupsPerCu(AmdArch arch) {
        return arch.family() == AmdFamily.RDNA ? 32 : 16;
    }

    /**
     * Inverse of {@link #wavesForVgprs}: the largest VGPR count that still reaches {@code waves}.
     * This is the number to hand to a shader author: "you need to be under 96 VGPRs to hit 8 waves".
     */
    public static int maxVgprsForWaves(AmdArch arch, int waveSize, int waves) {
        int granule = vgprGranule(arch, waveSize);
        int maxWaves = maxWavesPerSimd(arch, waveSize);
        int wanted = Math.min(Math.max(waves, 1), maxWaves);
        int budget = alignDown(totalVgprs(arch, waveSize) / wanted, granule);
        return Math.max(granule, budget);
    }

    /**
     * Occupancy step table for a wave size: every entry says "using at most N VGPRs gives you W
     * waves". Used by the HUD and by the shader optimiser to pick register targets.
     */
    public static int[][] budgetTable(AmdArch arch, int waveSize) {
        int maxWaves = maxWavesPerSimd(arch, waveSize);
        int[][] table = new int[maxWaves][2];
        for (int i = 0; i < maxWaves; i++) {
            int waves = i + 1;
            table[i][0] = waves;
            table[i][1] = maxVgprsForWaves(arch, waveSize, waves);
        }
        return table;
    }
}
