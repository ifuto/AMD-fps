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

    /**
     * The work-group size every compute shader in AMD-Faster is written for.
     *
     * <p>AMD's RDNA Performance Guide: <em>"Make the workgroup size a multiple of 64 to obtain
     * best performance across all GPU generations."</em> 64 is the smallest multiple that is
     * whole-wavefronts on both GCN (1 x wave64) and RDNA (2 x wave32).
     */
    public static final int WORKGROUP_SIZE = 64;

    /**
     * Threads per work group for work that shares data through LDS. The guide's rule of thumb
     * is an 8x8 thread group writing an 8x8 block of pixels, with a swizzled (Morton) thread
     * layout so the wavefronts coalesce; FidelityFX calls the helper {@code ARmpRed8x8()}.
     * Note 8x8 is already a multiple of 64.
     */
    public static final int WORKGROUP_TILED_8X8 = 64;

    /**
     * LDS is banked on both GCN and RDNA: 32 banks of 32 bits. A float4 array read along X takes
     * 8 bank conflicts; the same data as an array of floats takes 2. Hence struct-of-arrays
     * everywhere in the culling and sorting shaders.
     */
    public static final int LDS_BANKS = 32;
    public static final int LDS_BANK_BITS = 32;

    /** Root-signature / push-constant budget: the guide says stay below 13 DWORDs. */
    public static final int ROOT_SIGNATURE_DWORD_BUDGET = 13;

    /** A command buffer should hold at least this many draws or dispatches. */
    public static final int MIN_WORK_PER_COMMAND_BUFFER = 10;


    UNKNOWN("Unknown / unsupported", "", 0, false, false, 0, 0,
            "Not recognised. AMD-Faster will fall back to whatever the driver reports."),

    GCN5_VEGA("Vega (GCN 5)", "gfx9", 64, false, true, 64 * 1024, WORKGROUP_SIZE,
            "Wave64 only, 4 shader engines x 11 CUs, 64 KB LDS/CU. No mesh shaders, no sampler feedback."),

    RDNA1("RDNA 1 (Navi 1x)", "gfx101", 32, true, true, 128 * 1024, WORKGROUP_SIZE,
            "First WGP generation: 2 CUs + 128 KB LDS shared per WGP. Wave32 native, wave64 mode available."),

    RDNA2("RDNA 2 (Navi 2x)", "gfx103", 32, true, true, 128 * 1024, WORKGROUP_SIZE,
            "WGP + Infinity Cache. Ray accelerators. Still no mesh shaders."),

    RDNA3("RDNA 3 (Navi 3x)", "gfx110", 32, true, true, 64 * 1024, WORKGROUP_SIZE,
            "Chiplet (GCD+MCD). Dual-issue VALU. WMMA. LDS is back to 64 KB per CU."),

    RDNA35("RDNA 3.5 (Strix / Krackan)", "gfx115", 32, true, true, 64 * 1024, WORKGROUP_SIZE,
            "Mobile RDNA 3 refresh (gfx1150/1151). Fewer CUs, same ISA family as RDNA 3."),

    RDNA4("RDNA 4 (Navi 4x)", "gfx120", 32, true, true, 64 * 1024, WORKGROUP_SIZE,
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
     * <p>AMD's own guidance, verbatim from the RDNA Performance Guide: <em>"Make the workgroup
     * size a multiple of 64 to obtain best performance across all GPU generations."</em> 64 is
     * the smallest such value and it is one wave64 on GCN or two wave32s on RDNA, so it is the
     * one number that is right on every AMD part this mod targets. It is also the size AMD
     * recommends for work dispatched on the async queue: <em>"Smaller workgroups (64 threads)
     * usually perform better than larger workgroups when run async."</em>
     *
     * <p>Source: {@code fetched_content/gpuopen.com_learn_rdna-performance-guide_}, section
     * "Compute shaders" and section "Async compute".
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
