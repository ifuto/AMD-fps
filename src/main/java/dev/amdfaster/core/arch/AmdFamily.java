package dev.amdfaster.core.arch;

/**
 * Broad AMD GPU product families. Used to pick tuning policy, not for feature
 * detection (that is always probed at runtime).
 *
 * <p>Reference material that this classification follows:
 * <ul>
 *   <li>AMD, <i>RDNA Architecture Whitepaper</i> (RDNA 1/2/3/4) &mdash; wave32 vs wave64,
 *       dual compute units, WGP layout.</li>
 *   <li>AMD, <i>RDNA / RDNA2 / RDNA3 / RDNA4 ISA Reference Guides</i> (GPUOpen) &mdash; GFX IP
 *       levels, register file sizes, LDS sizes, allocation granularities.</li>
 *   <li>AMD, <i>GCN3/GCN4(GCN 1.2)/Vega ISA</i> documentation for the Polaris/Vega era and
 *       the Vega based APUs (Vega 8/11, Radeon 680M/780M are <b>not</b> all Vega: see
 *       {@link AmdArch}).</li>
 *   <li>LLVM AMDGPU backend tables ({@code AMDGPU.td} {@code FeatureMaxWavesPerEU*},
 *       {@code Feature1536VGPRs}; {@code AMDGPUBaseInfo.cpp::getVGPRAllocGranule()}) which mirror
 *       the ISA guides and are used to cross-check the numbers here.</li>
 * </ul>
 */
public enum AmdFamily {
    /** Graphics Core Next (GFX6..GFX9), wave64 only, VALU width 16. */
    GCN("GFX6-GFX9"),
    /** Radeon DNA (GFX10..GFX13), wave32/wave64, VALU width 32, WGP based. */
    RDNA("GFX10-GFX13"),
    /** Compute DNA, CDNA1..CDNA4 (MI100/MI200/MI300). Compute only, no display scanout. */
    CDNA("GFX9-GFX9.4"),
    /** Not an AMD device, or an AMD device we could not classify. */
    OTHER("unknown");

    private final String gfxRange;

    AmdFamily(String gfxRange) {
        this.gfxRange = gfxRange;
    }

    public String gfxRange() {
        return gfxRange;
    }

    /** True when the family uses the GCN style 16-lane VALU / wave64 execution model. */
    public boolean isWave64Only() {
        return this == GCN;
    }
}
