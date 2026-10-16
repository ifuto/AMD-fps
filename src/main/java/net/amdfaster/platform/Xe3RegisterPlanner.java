package net.amdfaster.platform;

/**
 * Plans register use on Intel Xe3, where allocation is flexible rather than a choice of two sizes.
 *
 * <p>Earlier Intel parts gave a shader one of two register budgets: the normal mode at 128 registers per
 * thread, or a "large" mode at 256 that halves how many threads fit, with nothing in between. A kernel that
 * needed 140 registers had to take the large mode and lose half its occupancy for twenty registers it did not
 * need, and a kernel that needed 120 got no benefit from the four it did not use.
 *
 * <p>Xe3 allocates in blocks of thirty-two entries out of a sixty-four kilobyte file per execution unit, so
 * the budget can be matched to what the kernel actually needs and occupancy follows continuously rather than
 * in one step.
 *
 * <h2>The request rounds up to a block</h2>
 *
 * <p>Allocation is in blocks, so asking for 100 registers costs the same as asking for 128, and asking for 129
 * costs the same as 160. A plan that reported the raw request would understate what the hardware reserves,
 * and the occupancy computed from it would be one the hardware cannot deliver.
 *
 * <h2>Occupancy is the file divided by what each thread holds</h2>
 *
 * <p>Not by what it asked for. The difference is the rounding, and on a kernel near a block boundary it is the
 * whole difference between two occupancy levels.
 *
 * <h2>The granularity is Intel's, not AMD's</h2>
 *
 * <p>AMD allocates vector registers in its own granules and RDNA4 can allocate them dynamically at all, so the
 * same shader compiled for the two vendors can land on different occupancies from the same source. A planner
 * that used one rule for both would report a number that is right on the machine it was written against and
 * wrong on the other, which is exactly the kind of error that survives testing because both machines pass.
 *
 * <h2>Why the large mode still matters</h2>
 *
 * <p>Flexible allocation is finer, not unlimited. A kernel that genuinely needs more than the file can give
 * one thread at full occupancy still has to trade, and on Xe3 that trade is a choice of how many blocks per
 * thread rather than a switch between two presets -- but it is still a trade, and a plan that assumes
 * otherwise would not launch.
 *
 * <p>Thread safety: planning happens at pipeline creation, on the thread that builds the pipelines.
 *
 * <p>Reference: chipsandcheese's look at Xe3 describes sixty-four kilobyte register files per execution unit
 * allocated in thirty-two entry blocks, against the 128/256 two-mode scheme of earlier parts. This is an
 * independent implementation of the planning those facts imply.
 */
public final class Xe3RegisterPlanner {

    /** Bytes in one execution unit's register file. */
    public static final int FILE_BYTES = 64 * 1024;

    /** Bytes in one register entry. */
    public static final int BYTES_PER_ENTRY = 32;

    /** Entries allocated at a time. */
    public static final int BLOCK_ENTRIES = 32;

    /** Threads an execution unit can hold at full occupancy. */
    public static final int MAX_THREADS = 64;

    /** Registers per thread in the old normal mode, for comparison. */
    public static final int LEGACY_NORMAL = 128;

    /** Registers per thread in the old large mode. */
    public static final int LEGACY_LARGE = 256;

    /** Entries in the whole file. */
    public static final int FILE_ENTRIES = FILE_BYTES / BYTES_PER_ENTRY;

    /**
     * How many entries the hardware reserves for a request.
     *
     * <p>Rounded up to a block, because asking for 100 costs the same as asking for 128. A plan that reported
     * the raw request would understate what is reserved and compute an occupancy the hardware cannot deliver.
     */
    public static int allocatedEntries(int requested) {
        if (requested < 1) {
            throw new IllegalArgumentException("at least one register is required: " + requested);
        }
        return ((requested + BLOCK_ENTRIES - 1) / BLOCK_ENTRIES) * BLOCK_ENTRIES;
    }

    /**
     * How many threads fit, given how many registers each needs.
     *
     * <p>Computed from the allocation, not the request, and capped by the thread slots.
     */
    public static int threadsPerEu(int requested) {
        int allocated = allocatedEntries(requested);
        int byRegisters = FILE_ENTRIES / allocated;
        return Math.min(byRegisters, MAX_THREADS);
    }

    /** The occupancy, as a fraction of the thread slots. */
    public static double occupancy(int requested) {
        return (double) threadsPerEu(requested) / MAX_THREADS;
    }

    /**
     * The occupancy the same kernel would have got on an earlier Intel part.
     *
     * <p>Those offered only the two modes, so a kernel needing more than the normal budget had to take the
     * large one and lose half its occupancy for registers it did not need.
     */
    public static double legacyOccupancy(int requested) {
        int mode = requested <= LEGACY_NORMAL ? LEGACY_NORMAL : LEGACY_LARGE;
        int byRegisters = FILE_ENTRIES / mode;
        return (double) Math.min(byRegisters, MAX_THREADS) / MAX_THREADS;
    }

    /**
     * The largest request that still fits at full occupancy.
     *
     * <p>Useful because a kernel that sheds a few registers to get under it gains a whole occupancy level,
     * while one that sheds a few without crossing it gains nothing.
     */
    public static int registersForFullOccupancy() {
        return (FILE_ENTRIES / MAX_THREADS / BLOCK_ENTRIES) * BLOCK_ENTRIES;
    }

    /**
     * How many registers would have to be shed to reach the next occupancy level.
     *
     * <p>Zero when the request is already at a level, so a change that would gain nothing is reported as
     * gaining nothing rather than as a small saving.
     *
     * @param requested how many registers the kernel asks for
     */
    public static int registersToShedForNextLevel(int requested) {
        int allocated = allocatedEntries(requested);
        int threads = FILE_ENTRIES / allocated;
        if (threads >= MAX_THREADS) {
            return 0;
        }
        // The next level needs one more thread, so the allocation has to drop to what that allows.
        int targetThreads = threads + 1;
        int targetAllocated = FILE_ENTRIES / targetThreads;
        int targetRounded = (targetAllocated / BLOCK_ENTRIES) * BLOCK_ENTRIES;
        int shed = allocated - targetRounded;
        return Math.max(0, shed - (allocated - requested));
    }
}
