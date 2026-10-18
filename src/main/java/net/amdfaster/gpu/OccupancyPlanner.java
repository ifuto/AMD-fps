package net.amdfaster.gpu;

import net.amdfaster.platform.AmdArchitecture;

/**
 * Picks the wave size and workgroup size a compute shader should dispatch with, from its register
 * pressure and its LDS use, and reports the occupancy that follows.
 *
 * <p>The reason this is worth computing rather than hard-coding is that the answer flips on the register
 * count, and the flip is not gradual. An RDNA SIMD holds a fixed pool of vector registers and divides it
 * among the waves resident on it, so occupancy is a staircase: 96 registers per lane fills all sixteen
 * wave slots, and the next register past that costs four of them. Wave64 counts double in that pool,
 * because it has twice the lanes drawing from the same file, which means wave64 is strictly better while
 * the shader is cheap and strictly worse once it is not.
 *
 * <p>Both ends of that are real and measured. At 64 registers a wave64 dispatch puts 768 lanes in flight
 * against wave32's 512, and the extra lanes are what hide memory latency -- the lever is bytes per wave in
 * flight, not wave count. At 96 registers the two are level at 512 lanes each, and past that wave64 halves
 * the resident waves for no gain in lanes at all. A shader that dropped from 40 to 32 registers on older
 * AMD parts doubled its resident groups and ran 50 per cent faster with nothing else changed, which is the
 * size of the effect being left on the table by guessing.
 *
 * <p>Two further things push toward wave32 regardless of the arithmetic. A cross-lane reduction over 32
 * lanes is one tree level shorter and one barrier weaker than over 64, and on gfx12's 64 bank LDS a wave64
 * read at stride 2 hits 32 bank conflicts where wave32 hits none. And RDNA4's dynamic VGPR allocation is
 * wave32-only, so a kernel that wants it has no choice. The counterweight is that on RDNA 1 and 2 a wave64
 * dispatch is folded onto two cycles of a 32 lane SIMD, so it buys lanes in flight at the price of double
 * per-instruction latency; RDNA 3 removed that, running both sizes in one cycle.
 *
 * <p>The model below reproduces every published data point it is checked against: the RDNA whitepaper's
 * 4 waves at 256 registers and 16 at 64 on a 1024 register file, and the RDNA3/4 table's 16, 12, 8 and 6
 * waves at 96, 128, 192 and 256 on a 1536 register file.
 *
 * @see <a href="https://gpuopen.com/download/RDNA_Architecture_public.pdf">RDNA Architecture whitepaper --
 *      1024 physical registers per SIMD32 divided among waves, wave64 counts double, workgroup size a
 *      multiple of 64</a>
 * @see <a href="https://zolotukhin.ai/zinc/docs/amd-gpu-reference/">RDNA3/4 reference -- 1536 VGPRs per
 *      SIMD, 16 waves maximum, full occupancy at or below 96 registers, LDS 64 KB per CU</a>
 * @see <a href="https://zolotukhin.ai/blog/2026-05-11-the-wave32-commit-that-closes-rdna4-long-context-flash-attention-gap/">
 *      the wave32 commit -- a wave64 dispatch on RDNA folds onto two cycles of one 32 lane SIMD, dynamic
 *      VGPRs are wave32-only, and gfx1201's LDS has 64 banks of 4 bytes</a>
 */
public final class OccupancyPlanner {

    /** Wave slots per SIMD. Wave64 occupies two of them, so it caps at eight resident waves. */
    public static final int MAX_WAVES_PER_SIMD = 16;

    /** Vector registers per lane in one RDNA 1/2 SIMD. */
    public static final int VGPRS_RDNA1 = 1024;

    /** Vector registers per lane in one RDNA 3/4 SIMD: a 192 KB file over 32 lanes. */
    public static final int VGPRS_RDNA3 = 1536;

    /** The register count at or below which all sixteen wave slots fill, on an RDNA 3/4 SIMD. */
    public static final int VGPRS_FULL_OCCUPANCY = 96;

    /** LDS shared by the two CUs of one WGP on RDNA 1 and 2. */
    public static final int LDS_BYTES_PER_WGP = 128 * 1024;

    /** The most LDS a single workgroup may take. */
    public static final int LDS_MAX_PER_WORKGROUP = 64 * 1024;

    /** LDS banks on gfx12, four bytes each. */
    public static final int LDS_BANKS_GFX12 = 64;

    private OccupancyPlanner() {
    }

    /**
     * What a compute shader costs to run.
     *
     * @param vgprsPerLane        vector registers the compiler allocated per lane. This is the number the
     *                            driver reports, not the one the source suggests, and it is what decides
     *                            occupancy.
     * @param ldsBytesPerWorkgroup shared memory the workgroup reserves
     * @param workgroupSize       threads per workgroup
     * @param crossLaneReduction  whether the shader reduces across the wave, which makes wave32's shorter
     *                            tree worth more than the extra lanes wave64 offers
     * @param ldsStrideWords      the stride, in 4 byte words, at which lanes index LDS. One is conflict
     *                            free; a power of two that divides the bank count is the bad case.
     */
    public record Footprint(int vgprsPerLane, int ldsBytesPerWorkgroup, int workgroupSize,
                            boolean crossLaneReduction, int ldsStrideWords) {
    }

    /**
     * The occupancy that follows from a footprint at a chosen wave size.
     *
     * @param wavesPerSimd     resident waves, which is what hides latency
     * @param lanesInFlight    resident waves times the wave size -- the figure the two sizes actually
     *                         compete on, since a wave64 has twice the lanes but takes twice the slots
     * @param workgroupsPerCu  how many workgroups the LDS budget allows, which can bind before registers do
     * @param ldsBankConflicts lanes that collide on a bank, each costing an extra cycle of replay
     * @param fullOccupancy    whether every wave slot is filled
     */
    public record Occupancy(int waveSize, int wavesPerSimd, int lanesInFlight, int workgroupsPerCu,
                            int ldsBankConflicts, boolean fullOccupancy) {
    }

    /**
     * Resident waves for a register count.
     *
     * <p>A wave64 wave occupies two slots because its 64 lanes draw from the same per-lane pool as two
     * wave32 waves, which is the whitepaper's "wave64 counts double" and the reason the two sizes trade
     * against each other rather than one simply being larger.
     */
    public static int wavesPerSimd(int vgprsPerLane, int waveSize, int registersPerSimd) {
        require(vgprsPerLane >= 1, "a shader uses at least one register");
        require(waveSize == 32 || waveSize == 64, "RDNA runs wave32 or wave64");
        require(registersPerSimd > 0, "a register file must have registers");
        int multiplier = waveSize == 64 ? 2 : 1;
        return Math.min(MAX_WAVES_PER_SIMD, registersPerSimd / (vgprsPerLane * multiplier));
    }

    /** Lanes resident on one SIMD, which is what the two wave sizes are really compared on. */
    public static int lanesInFlight(int vgprsPerLane, int waveSize, int registersPerSimd) {
        return wavesPerSimd(vgprsPerLane, waveSize, registersPerSimd) * waveSize;
    }

    /**
     * Workgroups one CU can hold given their LDS use.
     *
     * <p>Checked separately from registers because either can bind first. A workgroup taking 32 KB limits
     * the CU to four of them no matter how few registers the shader uses, and a shader that wants both
     * high occupancy and a large tile has to give one up.
     */
    public static int workgroupsPerCu(int ldsBytesPerWorkgroup, int ldsBytesPerCu) {
        require(ldsBytesPerWorkgroup >= 0, "LDS use must not be negative");
        require(ldsBytesPerCu > 0, "a CU must have LDS");
        if (ldsBytesPerWorkgroup == 0) {
            return MAX_WAVES_PER_SIMD;
        }
        return ldsBytesPerCu / ldsBytesPerWorkgroup;
    }

    /**
     * Lanes of one wave that collide on an LDS bank at a given stride.
     *
     * <p>Lane {@code i} touches bank {@code (i * stride) mod banks}. A stride of one spreads a wave32 over
     * 32 distinct banks with no collisions; a stride of four folds it onto 16 banks twice over, so half
     * the lanes replay. Wave64 has twice the lanes to collide, which is why the same access pattern that
     * is fine at wave32 costs at wave64.
     */
    public static int ldsBankConflicts(int waveSize, int strideWords) {
        require(waveSize == 32 || waveSize == 64, "RDNA runs wave32 or wave64");
        require(strideWords >= 1, "a stride is at least one word");
        boolean[] used = new boolean[LDS_BANKS_GFX12];
        int distinct = 0;
        for (int i = 0; i < waveSize; i++) {
            int bank = (int) (((long) i * strideWords) % LDS_BANKS_GFX12);
            if (!used[bank]) {
                used[bank] = true;
                distinct++;
            }
        }
        return waveSize - distinct;
    }

    /** The register file size for an architecture, in registers per lane per SIMD. */
    public static int registersPerSimd(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        return switch (architecture) {
            case RDNA1, RDNA2 -> VGPRS_RDNA1;
            case RDNA3, RDNA35, RDNA4 -> VGPRS_RDNA3;
            // GCN 5 has a 256 register file per lane and only wave64, so the wave32 branch never applies
            // and the count is reported for completeness rather than used.
            case GCN5_VEGA -> 256;
            case UNKNOWN -> 0;
        };
    }

    /** Occupancy for a footprint at one wave size on one architecture. */
    public static Occupancy occupancy(Footprint footprint, int waveSize, AmdArchitecture architecture) {
        require(footprint != null, "a footprint is needed");
        require(footprint.workgroupSize() >= waveSize, "a workgroup holds at least one wave");
        require(footprint.ldsBytesPerWorkgroup() <= LDS_MAX_PER_WORKGROUP,
                "a workgroup may not take more than 64 KB of LDS");
        int registers = registersPerSimd(architecture);
        require(registers > 0, "an unknown architecture has no register file to plan against");
        int waves = wavesPerSimd(footprint.vgprsPerLane(), waveSize, registers);
        return new Occupancy(waveSize, waves, waves * waveSize,
                workgroupsPerCu(footprint.ldsBytesPerWorkgroup(), architecture.ldsBytesPerCu()),
                ldsBankConflicts(waveSize, footprint.ldsStrideWords()),
                waves == MAX_WAVES_PER_SIMD);
    }

    /**
     * Chooses the wave size for a shader.
     *
     * <p>Whichever puts more lanes in flight, with two overrides that outrank the count. A cross-lane
     * reduction prefers wave32 because its tree is one level shorter and its barrier weaker, and the
     * extra level is exactly what the wider wave was supposed to amortize. And on an architecture that
     * cannot run the other size there is nothing to choose: GCN 5 is wave64 only.
     *
     * <p>A tie goes to wave32. The lanes are the same, and wave32 has the shorter wave lifetime, ramps up
     * quicker after a barrier, wastes less on a partially filled tail, and keeps its LDS accesses inside
     * half as many banks -- so at equal occupancy it is the cheaper of the two.
     */
    public static int recommendWaveSize(Footprint footprint, AmdArchitecture architecture) {
        require(footprint != null, "a footprint is needed");
        require(architecture != null, "an architecture is needed");
        if (!architecture.wave32()) {
            return 64;
        }
        if (!architecture.wave64()) {
            return 32;
        }
        int registers = registersPerSimd(architecture);
        require(registers > 0, "an unknown architecture has no register file to plan against");
        if (footprint.crossLaneReduction()) {
            return 32;
        }
        int lanes32 = lanesInFlight(footprint.vgprsPerLane(), 32, registers);
        int lanes64 = lanesInFlight(footprint.vgprsPerLane(), 64, registers);
        return lanes64 > lanes32 ? 64 : 32;
    }

    /**
     * Chooses the workgroup size.
     *
     * <p>AMD's guide is explicit that it should be a multiple of 64 to perform well across every
     * generation, and 64 is the smallest one -- exactly one wave64 on GCN and two wave32s on RDNA, so it
     * is the single value that is right on every part this mod targets. Larger is only worth it when the
     * shader shares enough through LDS to pay for the smaller group count, and that is the caller's
     * decision to make with the occupancy it gets back rather than this one's to assume.
     */
    public static int recommendWorkgroupSize(Footprint footprint, AmdArchitecture architecture) {
        require(footprint != null, "a footprint is needed");
        require(architecture != null, "an architecture is needed");
        int wave = recommendWaveSize(footprint, architecture);
        return Math.max(AmdArchitecture.WORKGROUP_SIZE, wave);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
