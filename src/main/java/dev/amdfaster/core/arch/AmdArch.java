package dev.amdfaster.core.arch;

import java.util.Locale;

/**
 * AMD GPU generations (GFX IP levels) known to AMD-Faster together with the static hardware
 * facts that the tuning core needs. Everything here is <em>silicon</em> data: it never changes at
 * runtime and is independent of the graphics API, driver and operating system.
 *
 * <h2>Register file model</h2>
 * AMD documents the vector register file per SIMD. Because a "SIMD" on RDNA is 32 lanes wide while
 * on GCN it is 16 lanes wide, AMD-Faster stores the register file in <b>wave32-equivalent VGPRs</b>
 * ({@link #totalVgprsWave32()}) and additionally exposes the wave64 view used by the GCN parts
 * ({@link #totalVgprsWave64()}). These are the exact quantities LLVM calls
 * {@code TotalNumVGPRs}, and the occupancy maths in
 * {@code dev.amdfaster.core.occupancy.OccupancyModel} is a faithful port of LLVM's
 * {@code AMDGPUBaseInfo.cpp::getNumWavesPerEUWithNumVGPRs()}.
 *
 * <h2>Why the allocation granularity matters</h2>
 * The hardware hands out registers in blocks, so occupancy steps down in discrete jumps. A shader
 * that needs 65 VGPRs on RDNA2 (granularity 16, wave32) costs exactly as much occupancy as one that
 * needs 80. This is the single most useful feedback signal we can give the compiler, because on
 * AMD the win is usually "shave one register block" rather than "shave one register".
 */
public enum AmdArch {
    /** Unknown / not an AMD part. Never used for tuning, only as a safe default. */
    UNKNOWN("unknown", AmdFamily.OTHER, "unknown",
            /*simdPerCu*/ 0, /*cuPerWgp*/ 1, /*wave32*/ false, /*mesh*/ false,
            /*waves*/ 8, /*vgpr32*/ 512, /*vgpr64*/ 256, /*gran32*/ 8, /*gran64*/ 4,
            /*sgprs*/ 800, /*sgprGran*/ 8, /*lds*/ 64 * 1024,
            /*vopd*/ false, /*wmma*/ false, /*dynVgpr*/ false, /*asyncQueues*/ 1),
    /** GCN 1.0 (Tahiti, Pitcairn, Cape Verde) - Radeon HD 7000 series. */
    GCN1("gfx6", AmdFamily.GCN, "GCN 1.0",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 1),
    /** GCN 2.0 (Hawaii, Bonaire) - Radeon R9 290X. */
    GCN2("gfx7", AmdFamily.GCN, "GCN 2.0",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 1),
    /** GCN 3.0 (Tonga, Fiji, Fury) - Radeon R9 Fury X. */
    GCN3("gfx8", AmdFamily.GCN, "GCN 3.0",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 1),
    /** GCN 4.0 / "Polaris" (RX 470/480/570/580/590). Still very common in 2025-era budget PCs. */
    POLARIS("gfx8", AmdFamily.GCN, "Polaris (GCN 4)",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 2),
    /** Vega (GFX9) - RX Vega 56/64, Radeon VII. */
    VEGA("gfx9", AmdFamily.GCN, "Vega (GCN 5)",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 2),
    /**
     * Vega based APUs (GFX9): Vega 8 / Vega 11 in Ryzen 2000-5000G, "Radeon Graphics" in
     * Ryzen 4000/5000 mobile (Renoir/Cezanne, 6-8 CU). Unified memory, shares the DDR bus with the
     * CPU, so bandwidth - not shader throughput - is the wall.
     */
    VEGA_APU("gfx9", AmdFamily.GCN, "Vega APU",
            4, 1, false, false, 10, 256, 256, 4, 4, 800, 8, 64 * 1024, false, false, false, 2),
    /**
     * RDNA 1 (GFX10.1) - RX 5700/XT, RX 5500 XT, RX 5600 XT. First wave32 capable part.
     * <b>No mesh shader support</b> ({@code VK_EXT_mesh_shader} needs GFX10.3+).
     */
    RDNA1("gfx10", AmdFamily.RDNA, "RDNA 1",
            2, 2, true, false, 16, 512, 256, 8, 4, 800, 8, 128 * 1024, false, false, false, 2),
    /** RDNA 2 (GFX10.3) - RX 6600/6700/6800/6900, Radeon 660M/680M APUs. Mesh shaders arrive here. */
    RDNA2("gfx10.3", AmdFamily.RDNA, "RDNA 2",
            2, 2, true, true, 16, 1024, 512, 16, 8, 2048, 16, 128 * 1024, false, false, false, 4),
    /**
     * RDNA 2 APU (Rembrandt, 660M/680M in Ryzen 6000/7035 mobile; Van Gogh in the Steam Deck).
     * Same ISA as desktop RDNA 2 but with fewer CUs and a DDR memory system.
     */
    RDNA2_APU("gfx10.3", AmdFamily.RDNA, "RDNA 2 APU",
            2, 2, true, true, 16, 1024, 512, 16, 8, 2048, 16, 128 * 1024, false, false, false, 4),
    /** RDNA 3 (GFX11) - RX 7600/7700/7800/7900, Radeon 740M/760M/780M (Phoenix). VOPD dual issue. */
    RDNA3("gfx11", AmdFamily.RDNA, "RDNA 3",
            2, 2, true, true, 16, 1536, 768, 24, 12, 2048, 16, 128 * 1024, true, true, false, 4),
    /** RDNA 3 APU (Phoenix / Hawk Point / Strix Point / Strix Halo, "780M", "890M", "8060S"). */
    RDNA3_APU("gfx11/11.5", AmdFamily.RDNA, "RDNA 3 APU",
            2, 2, true, true, 16, 1536, 768, 24, 12, 2048, 16, 128 * 1024, true, true, false, 4),
    /** RDNA 4 (GFX12) - RX 9070 XT / 9070 / 9060 XT. Adds dynamic VGPR re-allocation. */
    RDNA4("gfx12", AmdFamily.RDNA, "RDNA 4",
            2, 2, true, true, 16, 1536, 768, 24, 12, 2048, 16, 128 * 1024, true, true, true, 4),
    /** CDNA (MI100/MI200/MI300) - compute only; included so the mod degrades cleanly on them. */
    CDNA("gfx908-gfx942", AmdFamily.CDNA, "CDNA",
            4, 1, false, false, 10, 512, 512, 8, 8, 800, 8, 64 * 1024, false, true, false, 4);

    private final String gfxIp;
    private final AmdFamily family;
    private final String displayName;
    private final int simdPerCu;
    private final int cuPerWgp;
    private final boolean supportsWave32;
    private final boolean supportsMeshShader;
    private final int maxWavesPerSimd;
    private final int totalVgprsWave32;
    private final int totalVgprsWave64;
    private final int vgprGranuleWave32;
    private final int vgprGranuleWave64;
    private final int totalSgprs;
    private final int sgprGranule;
    private final int ldsBytesPerCu;
    private final boolean supportsVopd;
    private final boolean supportsWmma;
    private final boolean supportsDynamicVgpr;
    private final int asyncComputeQueues;

    AmdArch(String gfxIp, AmdFamily family, String displayName,
            int simdPerCu, int cuPerWgp, boolean supportsWave32, boolean supportsMeshShader,
            int maxWavesPerSimd, int totalVgprsWave32, int totalVgprsWave64,
            int vgprGranuleWave32, int vgprGranuleWave64,
            int totalSgprs, int sgprGranule, int ldsBytesPerCu,
            boolean supportsVopd, boolean supportsWmma, boolean supportsDynamicVgpr,
            int asyncComputeQueues) {
        this.gfxIp = gfxIp;
        this.family = family;
        this.displayName = displayName;
        this.simdPerCu = simdPerCu;
        this.cuPerWgp = cuPerWgp;
        this.supportsWave32 = supportsWave32;
        this.supportsMeshShader = supportsMeshShader;
        this.maxWavesPerSimd = maxWavesPerSimd;
        this.totalVgprsWave32 = totalVgprsWave32;
        this.totalVgprsWave64 = totalVgprsWave64;
        this.vgprGranuleWave32 = vgprGranuleWave32;
        this.vgprGranuleWave64 = vgprGranuleWave64;
        this.totalSgprs = totalSgprs;
        this.sgprGranule = sgprGranule;
        this.ldsBytesPerCu = ldsBytesPerCu;
        this.supportsVopd = supportsVopd;
        this.supportsWmma = supportsWmma;
        this.supportsDynamicVgpr = supportsDynamicVgpr;
        this.asyncComputeQueues = asyncComputeQueues;
    }

    public String gfxIp() {
        return gfxIp;
    }

    public AmdFamily family() {
        return family;
    }

    public String displayName() {
        return displayName;
    }

    /** Number of 32-lane SIMD units per compute unit (GCN: 4x SIMD16 seen as SIMD units). */
    public int simdPerCu() {
        return simdPerCu;
    }

    /** Compute units per workgroup processor (RDNA: 2 CUs per WGP, GCN: 1 CU per CU group). */
    public int cuPerWgp() {
        return cuPerWgp;
    }

    /** True when wave32 is selectable (RDNA and later). */
    public boolean supportsWave32() {
        return supportsWave32;
    }

    /** True when {@code VK_EXT_mesh_shader} / task+mesh pipeline stages are usable (GFX10.3+). */
    public boolean supportsMeshShader() {
        return supportsMeshShader;
    }

    /** Hardware limit on resident waves per SIMD (wave32 units for RDNA). */
    public int maxWavesPerSimd() {
        return maxWavesPerSimd;
    }

    /** Vector register file per SIMD expressed in wave32 VGPRs (LLVM {@code TotalNumVGPRs}). */
    public int totalVgprsWave32() {
        return totalVgprsWave32;
    }

    /** Vector register file per SIMD expressed in wave64 VGPRs. */
    public int totalVgprsWave64() {
        return totalVgprsWave64;
    }

    /** VGPR allocation granularity for wave32 kernels. */
    public int vgprGranuleWave32() {
        return vgprGranuleWave32;
    }

    /** VGPR allocation granularity for wave64 kernels. */
    public int vgprGranuleWave64() {
        return vgprGranuleWave64;
    }

    public int totalSgprs() {
        return totalSgprs;
    }

    public int sgprGranule() {
        return sgprGranule;
    }

    /**
     * True when SGPR usage can actually reduce occupancy. From GFX10 onwards the scalar register
     * file is large enough that it never is the limit (LLVM: {@code isSGPROccupancyLimited()}).
     */
    public boolean sgprsLimitOccupancy() {
        return family == AmdFamily.GCN || family == AmdFamily.CDNA;
    }

    /** Local data share per compute unit, in bytes. 64 KiB on GCN, 128 KiB from GFX10 on. */
    public int ldsBytesPerCu() {
        return ldsBytesPerCu;
    }

    /** Largest LDS allocation a single workgroup may request. */
    public int ldsBytesPerWorkgroupMax() {
        // On GFX10+ the whole CU-local LDS can be claimed by one workgroup ("cu mode").
        return ldsBytesPerCu;
    }

    /** True when VOPD can co-issue two VALU ops (RDNA 3 and later). */
    public boolean supportsVopd() {
        return supportsVopd;
    }

    /** True when wave matrix multiply-accumulate (WMMA) instructions exist. */
    public boolean supportsWmma() {
        return supportsWmma;
    }

    /**
     * True when the register file can be re-partitioned between waves at runtime (GFX12).
     * This removes the classic "one register block costs you a whole wave" cliff.
     */
    public boolean supportsDynamicVgpr() {
        return supportsDynamicVgpr;
    }

    /** Number of hardware queues that can run graphics and compute work concurrently. */
    public int asyncComputeQueues() {
        return asyncComputeQueues;
    }

    /** True for the integrated (unified memory) parts where the DDR bus is the bottleneck. */
    public boolean isIntegrated() {
        return this == VEGA_APU || this == RDNA2_APU || this == RDNA3_APU;
    }

    /** Human readable one-liner used in logs, HUD and issue reports. */
    public String describe() {
        return displayName + " [" + gfxIp + ", "
                + (supportsWave32 ? "wave32/wave64" : "wave64")
                + ", max " + maxWavesPerSimd + " waves/SIMD]";
    }

    public static AmdArch fromGfxIp(String gfxIp) {
        if (gfxIp == null) {
            return UNKNOWN;
        }
        String needle = gfxIp.toLowerCase(Locale.ROOT).trim();
        for (AmdArch arch : values()) {
            if (arch == UNKNOWN) {
                continue;
            }
            for (String candidate : arch.gfxIp.split("/")) {
                if (candidate.equalsIgnoreCase(needle)) {
                    return arch;
                }
            }
        }
        return UNKNOWN;
    }
}
