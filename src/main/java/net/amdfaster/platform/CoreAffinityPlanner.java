package net.amdfaster.platform;

/**
 * Keeps the render thread on the fast cores of a hybrid CPU, and lets the rest go where they fit.
 *
 * <p>A hybrid processor has two kinds of core: fast ones for work that has to finish soon, and efficient ones
 * for work that does not. The scheduler moves threads between them as load changes, which is usually right --
 * but a game's frame is not usually. If the render thread is moved to an efficient core partway through a
 * frame, that frame takes longer, and the player sees it as a hitch that happens every so often for no reason
 * they can find. The average frame rate barely moves; the worst frames get much worse.
 *
 * <p>Keeping the render thread on the fast cores removes that, and putting the work that does not care --
 * chunk meshing, texture uploads, sound -- on the efficient ones leaves the fast cores free for the frame.
 *
 * <h2>Priority is not placement</h2>
 *
 * <p>Raising a thread's priority makes the scheduler prefer it, but it does not decide which kind of core it
 * runs on, and on a system without hardware guidance for the scheduler the two are decided independently. So a
 * thread can be the highest priority thing running and still be on a slow core. Affinity is a separate thing
 * from priority and has to be set separately.
 *
 * <h2>Only the threads that care are pinned</h2>
 *
 * <p>Pinning everything to the fast cores would put more threads there than there are cores, and they would
 * contend with each other for the very resource the pinning was protecting. Meshing a chunk is not on the
 * critical path of any single frame, so it belongs on the efficient cores, and keeping it off the fast ones is
 * most of the benefit.
 *
 * <h2>It is a hybrid-only concern</h2>
 *
 * <p>A processor with one kind of core has nothing to choose between, and setting an affinity there would
 * restrict the thread to a subset of identical cores for no reason -- which on a small part could make things
 * worse by preventing the scheduler from balancing at all. So the planner reports that there is nothing to do
 * rather than applying a rule that only makes sense on hybrid hardware.
 *
 * <h2>Why this matters more on Windows 10</h2>
 *
 * <p>The hardware that tells the scheduler which core suits a thread arrived after Windows 10 shipped, so on
 * that system the scheduler is guessing, and threads migrate mid-frame more often than they should. The
 * pinning is a workaround for a missing signal rather than an optimisation in the usual sense, which is why it
 * is worth doing even though it does no work itself.
 *
 * <p>Thread safety: affinity is set once at startup, on the thread that creates the others.
 *
 * <p>Reference: Intel's Enhanced Hardware Feedback Interface and the thread director documentation describe
 * the signal the scheduler uses, and its absence on earlier systems. This is an independent implementation of
 * the placement that follows from it.
 */
public final class CoreAffinityPlanner {

    /** The kinds of core a hybrid processor has. */
    public enum CoreKind {
        /** Fast, for work that has to finish soon. */
        PERFORMANCE,
        /** Efficient, for work that does not. */
        EFFICIENT
    }

    /** The threads a game runs. */
    public enum ThreadRole {
        /** Produces frames. */
        RENDER,
        /** Runs the integrated server's ticks. */
        SERVER,
        /** Builds chunk meshes. */
        MESHING,
        /** Uploads textures and buffers. */
        UPLOAD,
        /** Handles sound. */
        SOUND,
        /** Anything else. */
        OTHER
    }

    /**
     * Which cores a thread should be allowed to run on.
     *
     * @param role what the thread does
     * @param hybrid whether the processor has two kinds of core
     * @param performanceCores how many fast cores there are
     * @param efficientCores how many efficient cores there are
     * @return the kind to pin to, or null if it should not be pinned
     */
    public static CoreKind placement(ThreadRole role, boolean hybrid,
            int performanceCores, int efficientCores) {
        if (role == null) {
            throw new IllegalArgumentException("a role is required");
        }
        if (performanceCores < 0 || efficientCores < 0) {
            throw new IllegalArgumentException(
                    "a non-negative core count is required: " + performanceCores + ", "
                            + efficientCores);
        }
        if (!hybrid) {
            // A processor with one kind of core has nothing to choose between, and setting an affinity there
            // would restrict the thread to a subset of identical cores for no reason -- which on a small part
            // could make things worse by preventing the scheduler from balancing at all.
            return null;
        }

        switch (role) {
            case RENDER:
            case SERVER:
                // Both are on the critical path of a frame: the render thread produces it and the integrated
                // server's tick has to finish before the frame can use its state.
                return CoreKind.PERFORMANCE;
            case MESHING:
            case UPLOAD:
            case SOUND:
            case OTHER:
            default:
                // Meshing a chunk is not on the critical path of any single frame, so it belongs on the
                // efficient cores, and keeping it off the fast ones is most of the benefit.
                return efficientCores > 0 ? CoreKind.EFFICIENT : CoreKind.PERFORMANCE;
        }
    }

    /**
     * How many threads of a role may be pinned to the fast cores at once.
     *
     * <p>Not more than there are cores. Pinning everything to the fast cores would put more threads there
     * than there are cores, and they would contend with each other for the very resource the pinning was
     * protecting.
     *
     * @param performanceCores how many fast cores there are
     */
    public static int maxPinnedToPerformance(int performanceCores) {
        if (performanceCores < 1) {
            throw new IllegalArgumentException(
                    "at least one performance core is required: " + performanceCores);
        }
        // The render thread and the integrated server, and nothing else.
        return Math.min(2, performanceCores);
    }

    /**
     * Whether raising a thread's priority would be enough on its own.
     *
     * <p>It would not. Priority makes the scheduler prefer a thread but does not decide which kind of core it
     * runs on, and on a system without hardware guidance the two are decided independently -- so a thread can
     * be the highest priority thing running and still be on a slow core.
     *
     * @param hybrid whether the processor has two kinds of core
     */
    public static boolean priorityAloneSuffices(boolean hybrid) {
        return !hybrid;
    }

    /**
     * The share of the fast cores the frame-critical threads take.
     *
     * <p>Useful because it is the number that decides whether pinning leaves room for anything else, and on a
     * two-performance-core part it is all of them.
     */
    public static double performanceCoreShare(int performanceCores) {
        if (performanceCores < 1) {
            throw new IllegalArgumentException(
                    "at least one performance core is required: " + performanceCores);
        }
        return (double) maxPinnedToPerformance(performanceCores) / performanceCores;
    }
}
