package net.amdfaster.platform;

/**
 * The AMD GPU micro-architectures AMD-Faster cares about, together with the handful of
 * hardware facts that change how the renderer has to be written.
 *
 * <p>The numbers here come from the RDNA architecture / ISA documentation and from AMD's own
 * optimisation guidance (see {@code docs/notes/01} and {@code docs/notes/02}). They are used as
 * <em>defaults</em>: wherever the driver can be asked directly (subgroup size, LDS size,
 * work-group limits) the probe result wins over this table.
 */
public enum AmdArchitecture {

    UNKNOWN("Unknown / unsupported", "", 0, false, false, 0, 0,
            "Not recognised. AMD-Faster will fall back to whatever the driver reports."),

    GCN5_VEGA("Vega (GCN 5)", "gfx9", 64, false, true, 64 * 1024, 256,
            "Wave64 only, 4 shader engines x 11 CUs, 64 KB LDS/CU. No mesh shaders, no sampler feedback."),

    RDNA1("RDNA 1 (Navi 1x)", "gfx101", 32, true, true, 128 * 1024, 128,
            "First WGP generation: 2 CUs + 128 KB LDS shared per WGP. Wave32 native, wave64 mode available."),

    RDNA2("RDNA 2 (Navi 2x)", "gfx103", 32, true, true, 128 * 1024, 128,
            "WGP + Infinity Cache. Ray accelerators. Still no mesh shaders."),

    RDNA3("RDNA 3 (Navi 3x)", "gfx110", 32, true, true, 64 * 1024, 128,
            "Chiplet (GCD+MCD). Dual-issue VALU. WMMA. LDS is back to 64 KB per CU."),

    RDNA35("RDNA 3.5 (Strix / Krackan)", "gfx115", 32, true, true, 64 * 1024, 128,
            "Mobile RDNA 3 refresh (gfx1150/1151). Fewer CUs, same ISA family as RDNA 3."),

    RDNA4("RDNA 4 (Navi 4x)", "gfx120", 32, true, true, 64 * 1024, 128,
            "Re-designed pipeline. NOTE: RADV currently forces a full Hi-Z mitigation on this "
                    + "generation, which weakens early-Z — prefer an explicit depth pre-pass here.");

    private final String displayName;
    private final String gfxPrefix;
    private final int nativeWaveSize;
    private final boolean wave32;
    private final boolean wave64;
    private final int ldsBytesPerCu;
    private final int recommendedWorkgroupSize;
    private final String notes;

    AmdArchitecture(String displayName, String gfxPrefix, int nativeWaveSize,
                    boolean wave32, boolean wave64, int ldsBytesPerCu,
                    int recommendedWorkgroupSize, String notes) {
        this.displayName = displayName;
        this.gfxPrefix = gfxPrefix;
        this.nativeWaveSize = nativeWaveSize;
        this.wave32 = wave32;
        this.wave64 = wave64;
        this.ldsBytesPerCu = ldsBytesPerCu;
        this.recommendedWorkgroupSize = recommendedWorkgroupSize;
        this.notes = notes;
    }

    public String displayName() {
        return this.displayName;
    }

    public String gfxPrefix() {
        return this.gfxPrefix;
    }

    public int nativeWaveSize() {
        return this.nativeWaveSize;
    }

    public boolean supportsWave32() {
        return this.wave32;
    }

    public boolean supportsWave64() {
        return this.wave64;
    }

    public int ldsBytesPerCu() {
        return this.ldsBytesPerCu;
    }

    /**
     * Work-group size the compute shaders of AMD-Faster are laid out for.
     *
     * <p>AMD's guidance is to make the work-group a multiple of four wavefronts so a whole
     * wavefront64-equivalent is kept busy: 4 x 32 = 128 on RDNA, 4 x 64 = 256 on GCN.
     */
    public int recommendedWorkgroupSize() {
        return this.recommendedWorkgroupSize;
    }

    public String notes() {
        return this.notes;
    }

    public boolean isRdna() {
        return this != UNKNOWN && this != GCN5_VEGA;
    }
}
