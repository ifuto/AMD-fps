package net.amdfaster.platform;

/**
 * Resident-wave occupancy for AMD architectures, and which of the three ceilings binds.
 *
 * <p>Why a model rather than a rule of thumb. Occupancy is a staircase, not a slope, and the steps are
 * in different places on different generations. The usual advice -- "keep VGPRs under 64" -- is a single
 * number that happens to be true-ish everywhere and exactly right nowhere. On RDNA4 the register file
 * holds 1536 wave32 VGPRs across 16 wave slots, so 96 VGPRs is the full-occupancy line and registers are
 * handed out in blocks of 24: a shader at 97 VGPRs does not lose a little occupancy, it rounds up to a
 * five-block 120-register allocation and drops from 16 waves to 12. On RDNA1 to 3 the file is 1024
 * registers across 20 slots, so the same shader lands somewhere else entirely.
 *
 * <p>The number that actually decides anything is not the occupancy but <em>how far it is from the next
 * step</em>. A kernel at 121 VGPRs on RDNA4 is one register above a two-wave step; shedding one register
 * is worth 25 percent more resident waves, and no amount of profiling the running kernel tells you that,
 * because the achieved occupancy looks fine. {@link #registersToShedForNextWave} answers it.
 *
 * <p>Three ceilings are composed rather than the register one alone: the register file, LDS per work
 * group, and the wave-slot count. Any of them can bind, and which one does changes the fix. Running out
 * of registers means rewriting the shader; running out of LDS means shrinking the tile; running out of
 * slots means the workgroup is too small. Naming the binding one is the difference between a diagnosis
 * and a guess.
 *
 * <p>A caution that applies to every number here: wave64 costs twice the register space of wave32,
 * because a VGPR is one dword per lane and wave64 has twice the lanes. Comparing a wave32 VGPR count
 * against a wave64 one without normalising overstates wave64 occupancy by a factor of two. Both sides
 * are normalised to dwords before being divided.
 */
public final class OccupancyModel {

    /**
     * What a generation's SIMD provides.
     *
     * @param name              label, for reports
     * @param waveSize          32 or 64
     * @param fileVgprsWave32   register file size, counted in wave32 VGPRs
     * @param waveSlots         hardware wave slots per SIMD
     * @param vgprGranule       registers are allocated in multiples of this
     * @param maxVgprsPerWave   per-wave cap
     * @param ldsBytesPerCu     LDS shared by the SIMDs of a CU
     * @param ldsBlockBytes     LDS is allocated in multiples of this
     */
    public record Arch(String name, int waveSize, int fileVgprsWave32, int waveSlots, int vgprGranule,
            int maxVgprsPerWave, int ldsBytesPerCu, int ldsBlockBytes) {

        /**
         * Registers a wave actually occupies once the allocator rounds up.
         *
         * <p>Note the ceiling division. The usual idiom for it is negating a negated floor division,
         * which works in languages whose integer division floors -- and silently does the wrong thing in
         * Java, where division truncates toward zero, so a 97-VGPR shader at a 24-register granule comes
         * out as four blocks of 96 rather than five of 120 and the model reports sixteen waves where the
         * hardware gives twelve. Every rounding-up division in this class is spelled
         * {@code (a + b - 1) / b}.
         */
        public int allocatedVgprs(int vgprsRequested) {
            int requested = Math.max(1, vgprsRequested);
            int blocks = (requested + this.vgprGranule - 1) / this.vgprGranule;
            return Math.min(blocks * this.vgprGranule, this.maxVgprsPerWave + this.vgprGranule);
        }

        /**
         * Register-file cost of one wave, in wave32 units.
         *
         * <p>The normalisation that keeps wave64 from looking twice as cheap as it is.
         */
        public int dwordsPerWave(int vgprsRequested) {
            return allocatedVgprs(vgprsRequested) * (this.waveSize / 32);
        }
    }

    /**
     * RDNA4. 192 KB register file per SIMD, 1536 wave32 VGPRs, 16 wave slots, 24-register blocks.
     *
     * <p>The 24-register granule is what makes this generation's staircase coarse: 96 VGPRs is full
     * occupancy and 97 is twelve waves.
     */
    public static final Arch RDNA4 = new Arch("RDNA4", 32, 1536, 16, 24, 256, 64 * 1024, 1024);

    /**
     * RDNA1 to RDNA3. 1024 VGPRs per SIMD32, up to 20 wave slots.
     *
     * <p>The architecture whitepaper's own examples: 16 wave32 waves at 64 VGPRs, 4 at 256. Both fall
     * out of 1024 divided by the allocation, which is the check that this table is right.
     */
    public static final Arch RDNA1_TO_3 = new Arch("RDNA1-3", 32, 1024, 20, 8, 256, 128 * 1024, 512);

    /** RDNA running wave64, which the hardware supports alongside wave32. */
    public static final Arch RDNA_WAVE64 = new Arch("RDNA1-3 wave64", 64, 1024, 10, 8, 256, 128 * 1024, 512);

    /**
     * GCN / Vega. Wave64 only, 10 wave slots per SIMD16, 4-cycle issue.
     *
     * <p>The file is quoted as 256 VGPRs per SIMD16, and a GCN VGPR is one dword per lane of a
     * <em>wave64</em>, so in the wave32 units this table counts in it is 512. Getting that conversion
     * wrong makes a 256-VGPR GCN shader look like it fits zero waves, when it fits exactly one.
     *
     * <p>Occupancy 4 here means 16 threads per lane, which is the same figure RDNA reaches with 16
     * wave32 waves -- the count is comparable, the shape is not.
     */
    public static final Arch GCN = new Arch("GCN", 64, 512, 10, 8, 256, 64 * 1024, 512);

    private OccupancyModel() {
    }

    /**
     * Resident waves per SIMD.
     *
     * @param vgprsPerWave registers the shader uses, before allocator rounding
     * @return waves that fit, capped by the hardware slot count
     */
    public static int wavesPerSimd(Arch arch, int vgprsPerWave) {
        int cost = arch.dwordsPerWave(vgprsPerWave);
        if (cost <= 0) {
            return arch.waveSlots();
        }
        return Math.min(arch.waveSlots(), arch.fileVgprsWave32() / cost);
    }

    /** Occupancy as a fraction of the slot count, so two generations can be compared directly. */
    public static double occupancy(Arch arch, int vgprsPerWave) {
        return (double) wavesPerSimd(arch, vgprsPerWave) / arch.waveSlots();
    }

    /** Which ceiling binds first. */
    public enum Limiter {
        /** The register file. Fix by using fewer registers. */
        REGISTERS,
        /** LDS per work group. Fix by shrinking the tile. */
        LDS,
        /** Hardware wave slots. Fix by making the workgroup bigger. */
        WAVE_SLOTS,
        /** Nothing fits at all. */
        NONE
    }

    /**
     * The binding ceiling, the per-SIMD wave ceiling, and the residency it actually allows.
     *
     * <p>{@code waves} and {@code groupsPerCu} are different quantities and are kept distinct on
     * purpose. {@code waves} is the per-SIMD ceiling set by registers and slots -- what a compiler
     * occupancy remark reports. {@code groupsPerCu} is residency for a particular launch shape, which
     * is what LDS constrains. Collapsing them into one number is how a model ends up reporting a
     * per-SIMD figure that no launch can achieve, or a residency that ignores the register file.
     *
     * @param threadsPerGroup workgroup size
     * @param ldsBytesPerGroup LDS the workgroup asks for
     */
    public record Verdict(int waves, Limiter limiter, int groupsPerCu, double occupancy) {
    }

    /**
     * Composes the three ceilings.
     *
     * <p>LDS residency is derived at group level rather than per SIMD. Computing a per-SIMD wave ceiling
     * from LDS and multiplying back by the SIMD count floors the answer and loses whole groups: a
     * 64-thread group using 40 KB across 4 SIMDs floors 6 waves to 1 per SIMD and reports 2 resident
     * groups where 3 fit.
     */
    public static Verdict evaluate(Arch arch, int vgprsPerWave, int threadsPerGroup, int ldsBytesPerGroup) {
        if (threadsPerGroup <= 0) {
            return new Verdict(0, Limiter.NONE, 0, 0.0);
        }
        // Four SIMDs per CU on every AMD architecture this covers: GCN's CU, RDNA's CU, and RDNA's
        // work-group processor are all four-wide. Written out rather than per-arch because a ternary
        // that returns 4 on both branches is not a choice, it is a place for a wrong number to hide.
        int simdsPerCu = 4;
        int wavesPerGroup = (threadsPerGroup + arch.waveSize() - 1) / arch.waveSize();

        // The per-SIMD ceiling. Registers first, then the slot count, whichever is smaller. This is
        // unaffected by LDS, because LDS constrains how many groups fit rather than how many waves a
        // SIMD can hold.
        int registerWaves = wavesPerSimd(arch, vgprsPerWave);
        Limiter limiter = Limiter.REGISTERS;
        int waves = registerWaves;
        if (arch.waveSlots() < waves) {
            waves = arch.waveSlots();
            limiter = Limiter.WAVE_SLOTS;
        }
        if (waves <= 0) {
            return new Verdict(0, Limiter.NONE, 0, 0.0);
        }

        // Residency, derived at group level. Doing it per SIMD and multiplying back is the wrong shape
        // and loses whole groups: a single group using 60 KB of a 64 KB CU gives 2 waves over 4 SIMDs,
        // which integer division floors to zero and reports as "nothing runs" when one group per CU
        // runs perfectly well.
        int groupsFromWaves = waves * simdsPerCu / wavesPerGroup;
        int ldsBlock = arch.ldsBlockBytes();
        int ldsRounded = ldsBytesPerGroup <= 0 ? 0
                : (ldsBytesPerGroup + ldsBlock - 1) / ldsBlock * ldsBlock;
        int groupsFromLds = ldsRounded <= 0 ? Integer.MAX_VALUE : arch.ldsBytesPerCu() / ldsRounded;

        int groupsPerCu = groupsFromWaves;
        if (groupsFromLds < groupsPerCu) {
            groupsPerCu = groupsFromLds;
            limiter = Limiter.LDS;
        }
        if (groupsPerCu <= 0) {
            return new Verdict(waves, Limiter.NONE, 0, 0.0);
        }
        return new Verdict(waves, limiter, groupsPerCu, (double) waves / arch.waveSlots());
    }

    /**
     * How many registers must be shed to gain one resident wave.
     *
     * <p>Returns 0 when the next step up is unreachable by shedding registers, which happens when the
     * wave-slot count or LDS binds instead. This is the number that makes an occupancy report
     * actionable: it says whether the rewrite is one register away or twenty.
     */
    public static int registersToShedForNextWave(Arch arch, int vgprsPerWave) {
        int current = wavesPerSimd(arch, vgprsPerWave);
        for (int target = vgprsPerWave - 1; target >= arch.vgprGranule(); target--) {
            if (wavesPerSimd(arch, target) > current) {
                return vgprsPerWave - target;
            }
        }
        return 0;
    }

    /**
     * The largest register budget that still reaches full occupancy.
     *
     * <p>The single most useful number for a shader author, and the one the "keep it under 64" rule of
     * thumb gets wrong on every generation: it is 96 on RDNA4, 51 on RDNA1 to 3, and 25 on GCN.
     */
    public static int vgprsForFullOccupancy(Arch arch) {
        for (int vgprs = arch.maxVgprsPerWave(); vgprs >= arch.vgprGranule(); vgprs--) {
            if (wavesPerSimd(arch, vgprs) == arch.waveSlots()) {
                return vgprs;
            }
        }
        return arch.vgprGranule();
    }
}
