package net.amdfaster.platform;

/**
 * Decides whether a compute kernel should use RDNA4's dynamic register allocation.
 *
 * <p>On every RDNA generation before the fourth, a shader declares how many vector registers it needs and
 * the hardware reserves that many for every wave of it, whether or not the wave is using them at that moment.
 * A kernel whose register pressure varies -- heavy during one phase, light during another -- pays for its
 * worst case the whole time, and occupancy is set by that worst case rather than by the average.
 *
 * <p>RDNA4 can instead start a wave with a minimum allocation and grow and shrink it during its lifetime,
 * with a chip-wide register setting the active wave count directly. That decouples occupancy from the
 * declared register count, which is exactly what a kernel with varying pressure wants.
 *
 * <h2>It is wave32 only</h2>
 *
 * <p>The ISA documents the feature as available in wave32 mode and not wave64, so a kernel that runs wave64
 * cannot use it at all. Asking for it there would either fail to launch or silently fall back, and a planner
 * that assumed otherwise would report an occupancy the hardware cannot deliver.
 *
 * <h2>It is compute only</h2>
 *
 * <p>Graphics stages cannot use dynamic allocation, so a kernel that shares a pipeline with a vertex or
 * fragment shader cannot take the mode without being separated from it. That is a real constraint rather
 * than a detail: the culling compute pass and the mesh drawing pass would have to be launched separately.
 *
 * <h2>Dynamic and static waves cannot share a core</h2>
 *
 * <p>A workgroup launched in dynamic mode takes over the whole workgroup processor, so a kernel using it
 * cannot run alongside one that does not on the same core. Two kernels that would otherwise overlap have to
 * be serialised, or neither may use the mode. Assuming they can coexist would produce a launch that stalls
 * waiting for a core that is never free.
 *
 * <h2>It can deadlock</h2>
 *
 * <p>Dynamic allocation lets a wave ask for more registers while it runs. If every register on the core is
 * already handed out and every resident wave needs another one to make progress, none of them can, and the
 * core hangs. So the planner has to leave headroom: the maximum a wave may grow to, times the number of
 * resident waves, must fit in the file. A plan that fills the file exactly is not a tight plan, it is a
 * hang.
 *
 * <p>Thread safety: planning happens at pipeline creation, on the thread that builds the pipelines.
 *
 * <p>Reference: AMD's gfx1201 ISA notes document dynamic VGPRs as compute-only and wave32-only, and
 * chipsandcheese's teardown describes the WGP take-over and the deadlock condition. This is an independent
 * implementation of the planning those constraints imply.
 */
public final class DynamicVgprPlanner {

    /** Vector registers in an RDNA4 SIMD's file. */
    public static final int FILE_VGPRS = 1536;

    /** Wave slots in an RDNA4 SIMD. */
    public static final int WAVE_SLOTS = 16;

    /** Registers a wave starts with in dynamic mode. */
    public static final int INITIAL_VGPRS = 24;

    /**
     * Whether a kernel can use dynamic register allocation at all.
     *
     * @param wave32 whether the kernel runs in wave32 mode
     * @param compute whether it is a compute shader rather than a graphics stage
     */
    public static boolean isEligible(boolean wave32, boolean compute) {
        // The ISA documents the feature as available in wave32 mode and not wave64, and as compute-only.
        // Asking for it otherwise would either fail to launch or silently fall back, and a planner that
        // assumed otherwise would report an occupancy the hardware cannot deliver.
        return wave32 && compute;
    }

    /**
     * The most waves that can be resident, given how far a wave may grow.
     *
     * <p>The maximum rather than the initial allocation, because a wave that can grow has to be able to reach
     * its maximum with every other resident wave also at theirs. Planning against the initial count would
     * fill the file and then hang the moment they all grew.
     *
     * @param maxVgprsPerWave the largest a single wave may grow to
     */
    public static int residentWaves(int maxVgprsPerWave) {
        if (maxVgprsPerWave < INITIAL_VGPRS) {
            throw new IllegalArgumentException(
                    "a wave cannot shrink below its initial allocation: " + maxVgprsPerWave);
        }
        int byRegisters = FILE_VGPRS / maxVgprsPerWave;
        return Math.min(byRegisters, WAVE_SLOTS);
    }

    /**
     * Whether a plan can deadlock.
     *
     * <p>If every register on the core is handed out and every resident wave needs another one to make
     * progress, none of them can and the core hangs. So a plan that fills the file exactly is not a tight
     * plan, it is a hang.
     *
     * @param waves how many waves the plan keeps resident
     * @param maxVgprsPerWave the largest a single wave may grow to
     */
    public static boolean canDeadlock(int waves, int maxVgprsPerWave) {
        if (waves < 1) {
            throw new IllegalArgumentException("at least one wave is required: " + waves);
        }
        return (long) waves * maxVgprsPerWave > FILE_VGPRS;
    }

    /**
     * The occupancy a plan achieves, as a fraction of the wave slots.
     *
     * @param waves how many waves are resident
     */
    public static double occupancy(int waves) {
        if (waves < 0) {
            throw new IllegalArgumentException("a negative wave count: " + waves);
        }
        return (double) Math.min(waves, WAVE_SLOTS) / WAVE_SLOTS;
    }

    /**
     * The occupancy the same kernel would get with static allocation.
     *
     * <p>Static allocation reserves the declared count for every wave whether or not it is in use, so the
     * declared worst case sets the occupancy for the whole run.
     *
     * @param declaredVgprs the register count the kernel declares
     */
    public static double staticOccupancy(int declaredVgprs) {
        if (declaredVgprs < 1) {
            throw new IllegalArgumentException("at least one register is required: " + declaredVgprs);
        }
        return occupancy(FILE_VGPRS / declaredVgprs);
    }

    /**
     * Whether dynamic allocation is worth it for a kernel.
     *
     * <p>It is worth it when the kernel's pressure varies enough that its worst case costs more occupancy
     * than its average needs -- and only if it is eligible and the plan cannot deadlock.
     *
     * @param wave32 whether the kernel runs wave32
     * @param compute whether it is a compute shader
     * @param declaredVgprs the count it would declare statically
     * @param maxVgprsPerWave the largest it may grow to dynamically
     * @param waves the number of waves the dynamic plan keeps resident
     */
    public static boolean isWorthIt(boolean wave32, boolean compute, int declaredVgprs,
            int maxVgprsPerWave, int waves) {
        if (!isEligible(wave32, compute)) {
            return false;
        }
        if (canDeadlock(waves, maxVgprsPerWave)) {
            // A plan that fills the file exactly hangs the moment every resident wave grows at once.
            return false;
        }
        return occupancy(waves) > staticOccupancy(declaredVgprs);
    }
}
