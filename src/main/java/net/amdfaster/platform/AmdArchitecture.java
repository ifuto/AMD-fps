package net.amdfaster.platform;

/**
 * The AMD GPU micro-architectures AMD-Faster cares about, together with the handful of
 * hardware facts that change how the renderer has to be written.
 *
 * <p>The numbers here come from the RDNA architecture / ISA documentation and from AMD's own
 * optimisation guidance. Where the guide is the source, it is quoted verbatim and the copy of the
 * guide that {@code fetch.yml} pulls into {@code fetched_content/} is named, so a claim can always
 * be checked against the primary source rather than against a memory of it.
 *
 * <p>These are <em>defaults</em>. Wherever the driver can be asked directly — subgroup size, LDS
 * size, work-group limits — the probe result wins over this table.
 */
public enum AmdArchitecture {

    UNKNOWN("Unknown / unsupported", "", 0, false, false, 0,
            "Not recognised. AMD-Faster will fall back to whatever the driver reports."),

    GCN5_VEGA("Vega (GCN 5)", "gfx9", 64, false, true, 64 * 1024,
            "Wave64 only, 4 shader engines x 11 CUs, 64 KB LDS/CU. No mesh shaders, no sampler feedback."),

    RDNA1("RDNA 1 (Navi 1x)", "gfx101", 32, true, true, 128 * 1024,
            "First WGP generation: 2 CUs + 128 KB LDS shared per WGP. Wave32 native, wave64 mode available."),

    RDNA2("RDNA 2 (Navi 2x)", "gfx103", 32, true, true, 128 * 1024,
            "WGP + Infinity Cache. Ray accelerators. Still no mesh shaders."),

    RDNA3("RDNA 3 (Navi 3x)", "gfx110", 32, true, true, 64 * 1024,
            "Chiplet (GCD+MCD). Dual-issue VALU. WMMA. LDS is back to 64 KB per CU."),

    RDNA35("RDNA 3.5 (Strix / Krackan)", "gfx115", 32, true, true, 64 * 1024,
            "Mobile RDNA 3 refresh (gfx1150/1151). Fewer CUs, same ISA family as RDNA 3."),

    RDNA4("RDNA 4 (Navi 4x)", "gfx120", 32, true, true, 64 * 1024,
            "Re-designed pipeline. NOTE: RADV currently forces a full Hi-Z mitigation on this "
                    + "generation, which weakens early-Z \u2014 prefer an explicit depth pre-pass here.");

    /**
     * The work-group size every compute shader in AMD-Faster is written for.
     *
     * <p>AMD's RDNA Performance Guide, verbatim: <em>"Make the workgroup size a multiple of 64 to
     * obtain best performance across all GPU generations."</em> 64 is the smallest such multiple,
     * and it is exactly one wave64 on GCN or two wave32s on RDNA, so it is the one value that is
     * right on every AMD part this mod targets. The same guide recommends 64 specifically for the
     * async queue: <em>"Smaller workgroups (64 threads) usually perform better than larger
     * workgroups when run async."</em>
     *
     * <p>Source: {@code fetched_content/gpuopen.com_learn_rdna-performance-guide_}, sections
     * "Compute shaders" and "Async compute". See {@code docs/notes/10} for how an earlier
     * 128-on-RDNA / 256-on-GCN figure here was traced back to the WGP occupancy discussion and
     * corrected.
     */
    public static final int WORKGROUP_SIZE = 64;

    /**
     * Threads per work group for work that shares data through LDS. The guide's rule of thumb is
     * an 8x8 thread group writing an 8x8 block of pixels, with a swizzled thread layout so the
     * wavefronts coalesce; FidelityFX calls the helper {@code ARmpRed8x8()}. 8x8 is already a
     * multiple of 64. The guide also says to write images in coalesced 256-byte blocks per wave.
     *
     * <p>Note the ray tracing section asks for 8x4 instead, because that traversal system uses LDS
     * heavily - the tile shape follows the data sharing, not the other way round.
     */
    public static final int WORKGROUP_TILED_8X8 = 8 * 8;

    /**
     * LDS is banked on both GCN and RDNA: 32 banks of 32 bits. The guide's own example: reading X
     * from an array of float4 costs 8 bank conflicts, reading X from an array of floats costs 2.
     * Hence struct-of-arrays (or padding) in every culling and sorting shader.
     */
    public static final int LDS_BANKS = 32;
    public static final int LDS_BANK_BITS = 32;

    /** Root signature / push constant budget. The guide: "Try to stay below 13 DWORDs." */
    public static final int ROOT_SIGNATURE_DWORD_BUDGET = 13;

    /** A command buffer should hold at least this many draws or dispatches, per the guide. */
    public static final int MIN_WORK_PER_COMMAND_BUFFER = 10;

    /**
     * Depth format. "24-bit depth formats have the same cost as 32-bit formats on GCN and RDNA.
     * Use a 32-bit format on AMD to get higher precision for the same memory cost as 24-bit."
     * Paired with reversed-Z ("near plane at 1.0"), which also keeps the depth fast clear legal:
     * depth fast clears need a 1.0f or 0.0f value with stencil set to 0.
     */
    public static final String DEPTH_FORMAT = "D32_SFLOAT + reversed-Z";

    /** Render target colours that keep the ~100x fast clear path available. */
    public static final String FAST_CLEAR_COLOURS =
            "RGBA(0,0,0,0), RGBA(0,0,0,1), RGBA(1,1,1,0), RGBA(1,1,1,1)";

    private final String displayName;
    private final String gfxPrefix;
    private final int nativeWaveSize;
    private final boolean wave32;
    private final boolean wave64;
    private final int ldsBytesPerCu;
    private final String notes;

    AmdArchitecture(String displayName, String gfxPrefix, int nativeWaveSize,
                    boolean wave32, boolean wave64, int ldsBytesPerCu, String notes) {
        this.displayName = displayName;
        this.gfxPrefix = gfxPrefix;
        this.nativeWaveSize = nativeWaveSize;
        this.wave32 = wave32;
        this.wave64 = wave64;
        this.ldsBytesPerCu = ldsBytesPerCu;
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
     * The work-group size to dispatch with on this generation.
     *
     * <p>Uniformly {@link #WORKGROUP_SIZE} for every supported generation, because the guide's
     * "multiple of 64" advice is explicitly cross-generation. {@link #UNKNOWN} gets 0: AMD-Faster
     * must not invent a number for hardware it could not identify, it should use the driver's
     * reported limits instead.
     */
    public int recommendedWorkgroupSize() {
        return this == UNKNOWN ? 0 : WORKGROUP_SIZE;
    }

    public String notes() {
        return this.notes;
    }

    public boolean isRdna() {
        return this != UNKNOWN && this != GCN5_VEGA;
    }
}
