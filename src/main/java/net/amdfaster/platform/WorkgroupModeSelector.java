package net.amdfaster.platform;

/**
 * Chooses whether a workgroup runs across two compute units or inside one.
 *
 * <p>On RDNA, two compute units form a workgroup processor and share a pool of local memory between them. A
 * workgroup can be launched in one of two modes: inside a single compute unit, or spread across both halves
 * of the processor. The second mode doubles the local memory a workgroup can use, which is the whole reason
 * to want it -- a kernel that needs forty kilobytes of shared memory cannot fit in one unit's thirty-two.
 *
 * <p>But the two modes are not simply "more memory" and "less memory". Coupling the units changes how the
 * workgroup's waves interact, and for some kernels that costs more than the memory gains.
 *
 * <h2>Atomics and inter-wave traffic argue for one unit</h2>
 *
 * <p>Local memory atomics and heavy communication between waves are fastest when the waves share one unit's
 * memory, because that is a single arbitration point. Spread across a processor, the same traffic crosses
 * between the two halves and is serialised against the other unit's waves, which can cost more than the
 * doubled capacity is worth. A kernel that spends its time in atomics should stay in one unit even if it
 * would fit in less memory.
 *
 * <h2>Occupancy is the stricter of the two limits</h2>
 *
 * <p>Local memory and vector registers limit occupancy independently, and whichever is tighter decides. A
 * kernel that halves its local memory to double its occupancy gains nothing if the registers were already the
 * binding constraint -- which is a common way to make a change that measures slower and looks like it should
 * have been faster.
 *
 * <h2>The capacity is not the whole pool</h2>
 *
 * <p>A workgroup processor's shared memory is what one workgroup can address, not what the whole processor
 * holds for all its workgroups. Reserving more per workgroup means fewer of them fit at once, so the choice
 * is between a few large workgroups and many small ones, and the right answer depends on whether the kernel
 * is limited by how much it can share or by how much latency it has to hide.
 *
 * <p>Thread safety: the choice is made at pipeline creation, on the thread that builds the pipelines.
 *
 * <p>Reference: AMD's FP16 GEMM optimisation guide for RDNA 3 and RDNA 4 describes CU mode and WGP mode and
 * the trade between them. This is an independent implementation of the reasoning it describes.
 */
public final class WorkgroupModeSelector {

    /** Local memory in one compute unit. */
    public static final int LDS_PER_CU = 64 * 1024;

    /** How a workgroup is launched. */
    public enum Mode {
        /** Inside one compute unit. */
        CU,
        /** Across both halves of a workgroup processor. */
        WGP
    }

    /**
     * The local memory a workgroup may use in each mode.
     *
     * @param mode how the workgroup is launched
     */
    public static int capacity(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("a mode is required");
        }
        return mode == Mode.WGP ? LDS_PER_CU * 2 : LDS_PER_CU;
    }

    /**
     * How many workgroups of a given size fit on one compute unit.
     *
     * @param ldsBytesPerGroup how much local memory one workgroup reserves
     */
    public static int groupsPerCu(Mode mode, int ldsBytesPerGroup) {
        if (ldsBytesPerGroup < 1) {
            throw new IllegalArgumentException(
                    "a workgroup reserves at least one byte: " + ldsBytesPerGroup);
        }
        if (ldsBytesPerGroup > capacity(mode)) {
            throw new IllegalArgumentException(
                    ldsBytesPerGroup + " bytes does not fit in " + mode + " mode's "
                            + capacity(mode));
        }
        return capacity(mode) / ldsBytesPerGroup;
    }

    /**
     * Whether a kernel should use the wider mode.
     *
     * <p>It should when it needs the memory and does not spend its time in atomics or inter-wave traffic,
     * because that traffic is fastest inside one unit's memory, where it is a single arbitration point.
     * Spread across a processor it crosses between the halves and is serialised against the other unit's
     * waves.
     *
     * @param ldsBytesNeeded how much local memory the kernel needs
     * @param heavyAtomics whether the kernel spends significant time in local memory atomics
     * @param heavyInterWave whether its waves communicate heavily through local memory
     */
    public static Mode choose(int ldsBytesNeeded, boolean heavyAtomics, boolean heavyInterWave) {
        if (ldsBytesNeeded < 1) {
            throw new IllegalArgumentException(
                    "a kernel needs at least one byte: " + ldsBytesNeeded);
        }
        if (ldsBytesNeeded > capacity(Mode.CU)) {
            // It does not fit in one unit at all, so the wider mode is the only option.
            return Mode.WGP;
        }
        if (heavyAtomics || heavyInterWave) {
            // Atomics and heavy inter-wave communication are fastest when the waves share one unit's
            // memory, and crossing between the halves can cost more than the doubled capacity is worth.
            return Mode.CU;
        }
        return Mode.CU;
    }

    /**
     * Whether widening the mode would actually raise occupancy.
     *
     * <p>Local memory and vector registers limit occupancy independently and whichever is tighter decides. A
     * kernel that halves its local memory to double its occupancy gains nothing if the registers were
     * already the binding constraint.
     *
     * @param wavesFromRegisters how many waves the register budget allows per unit
     * @param wavesFromLds how many the local memory budget allows per unit
     */
    public static boolean ldsIsTheBindingLimit(int wavesFromRegisters, int wavesFromLds) {
        if (wavesFromRegisters < 1 || wavesFromLds < 1) {
            throw new IllegalArgumentException(
                    "at least one wave is required: " + wavesFromRegisters + ", " + wavesFromLds);
        }
        return wavesFromLds < wavesFromRegisters;
    }

    /**
     * The occupancy a plan achieves, as a fraction of the unit's wave slots.
     *
     * @param wavesFromRegisters how many waves the register budget allows
     * @param wavesFromLds how many the local memory budget allows
     * @param waveSlots how many waves the unit can hold at once
     */
    public static double occupancy(int wavesFromRegisters, int wavesFromLds, int waveSlots) {
        if (waveSlots < 1) {
            throw new IllegalArgumentException("at least one wave slot: " + waveSlots);
        }
        return (double) Math.min(Math.min(wavesFromRegisters, wavesFromLds), waveSlots) / waveSlots;
    }
}
