package net.amdfaster.gpu;

/**
 * Decides whether material variants belong in one shader with branches, or in separate pipelines.
 *
 * <p>A material difference is normally a pipeline difference: a different shader, and a bind to switch to it. A
 * scene with many materials therefore has many binds, and the binds are CPU work that a GPU-bound scene does not
 * need. The alternative is one shader that reads the material from a buffer and branches on it, so a hundred
 * materials draw in one pipeline with no switches.
 *
 * <p>The branch is not free, and which side wins depends on the scene. This makes the choice per variant and
 * measures it, rather than picking a global style.
 *
 * <h2>A branch that the whole warp takes is nearly free</h2>
 *
 * <p>GPUs execute lanes in lockstep, so a branch all the lanes take costs one test and no divergence. A batch
 * where every object uses the same material is that case, and the branch costs less than the bind it replaced. A
 * batch where half the objects use one material and half the other is the opposite: both sides execute for every
 * lane and the shader does the work of both variants, which is worse than either separate pipeline.
 *
 * <h2>What can be static is static</h2>
 *
 * <p>A difference that is known when the pipeline is created -- whether the pass needs alpha testing, whether it
 * writes depth -- belongs in the pipeline rather than in a branch. Baking it in lets the compiler remove the code
 * for the case that does not happen, so the shader is shorter and the branch is gone. Leaving it dynamic to save
 * a pipeline would trade a one-time compilation saving for a per-fragment branch, which is the wrong way round for
 * anything that runs a million times a frame.
 *
 * <h2>The variant count has a limit</h2>
 *
 * <p>Each static combination is a pipeline, and pipelines cost compile time and memory. A material with eight
 * independent boolean properties is two hundred and fifty six pipelines, most of which are never used. So the
 * static properties are limited to the ones that actually differ in the scene, and the rest go in the buffer --
 * which is the same trade measured from the other side.
 *
 * <h2>Why this composes with bindless textures</h2>
 *
 * <p>{@link BindlessTextureTable} removes the texture binds, which are the most common state change. This removes
 * the pipeline binds, which are the more expensive one. A renderer with neither has to switch state between every
 * pair of different materials; with both, a scene of many materials draws in one call.
 *
 * <p>Not thread safe. Pipelines are created on the client thread.
 */
public final class UberShaderSelector {

    /** Milliseconds one pipeline bind costs. */
    public static final double MS_PER_BIND = 0.006;

    /** Milliseconds one taken branch costs per draw. */
    public static final double MS_PER_BRANCH = 0.0004;

    /** Milliseconds one divergent branch costs per draw, doing the work of both sides. */
    public static final double MS_PER_DIVERGENT = 0.002;

    /** The most static properties worth baking in before the pipeline count gets out of hand. */
    public static final int MAX_STATIC_PROPERTIES = 4;

    /**
     * Frames a pipeline's compile cost is amortised over.
     *
     * <p>Creating a pipeline is a one-time cost, so comparing it against a per-frame branch cost means any
     * property that runs at all eventually wins. Naming the horizon makes the trade explicit rather than
     * leaving it in an unexplained divisor.
     */
    public static final int PIPELINE_AMORTISATION_FRAMES = 1000;

    private long draws;
    private long bound;
    private long branched;
    private long diverged;

    /**
     * Decides how a batch of draws should be issued.
     *
     * @param drawCount how many draws the batch has
     * @param variants how many different materials they use
     * @param mixed whether the materials are interleaved within warps rather than grouped
     * @return true if one shader with branches is cheaper than binding a pipeline per variant
     */
    public boolean useUberShader(int drawCount, int variants, boolean mixed) {
        if (drawCount < 0 || variants < 0) {
            throw new IllegalArgumentException(
                    "a negative count: " + drawCount + ", " + variants);
        }
        if (variants == 0) {
            throw new IllegalArgumentException("a batch has at least one material");
        }
        this.draws += drawCount;

        double binds = (variants - 1) * MS_PER_BIND;
        double branches = mixed
                ? drawCount * MS_PER_DIVERGENT
                : drawCount * MS_PER_BRANCH;

        boolean uber = branches < binds;
        if (uber) {
            if (mixed) {
                this.diverged++;
            } else {
                this.branched++;
            }
        } else {
            this.bound += variants - 1;
        }
        return uber;
    }

    /**
     * Whether a property belongs in the pipeline rather than in a branch.
     *
     * <p>A difference known when the pipeline is created lets the compiler remove the code for the case that
     * does not happen, so the shader is shorter and the branch is gone. Leaving it dynamic to save a pipeline
     * would trade a one-time compilation saving for a per-fragment branch, which is the wrong way round for
     * anything that runs a million times a frame.
     *
     * @param combinations how many pipelines the property would add
     * @param timesPerFrame how often the branch would run
     */
    public boolean makeStatic(int combinations, int timesPerFrame) {
        if (combinations < 1 || timesPerFrame < 0) {
            throw new IllegalArgumentException(
                    "a bad pair: " + combinations + ", " + timesPerFrame);
        }
        // Worth a pipeline when the branch would cost more over a frame than the extra pipelines cost to
        // create, and the combination count stays within the limit.
        return combinations <= (1 << MAX_STATIC_PROPERTIES)
                && timesPerFrame * MS_PER_BRANCH
                        > combinations * MS_PER_BIND / (double) PIPELINE_AMORTISATION_FRAMES;
    }

    public long draws() {
        return this.draws;
    }

    /** How many pipeline binds were issued. */
    public long bound() {
        return this.bound;
    }

    /** How many batches used one shader with taken branches. */
    public long branched() {
        return this.branched;
    }

    /** How many of those were divergent, which is the expensive kind. */
    public long diverged() {
        return this.diverged;
    }

    /**
     * The share of the state-change cost avoided.
     *
     * <p>Zero before any batch has been decided. A selector that has decided nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     */
    public double savedPercent() {
        if (this.draws == 0) {
            return 0.0;
        }
        double avoided = this.bound * MS_PER_BIND;
        double total = avoided + (this.branched + this.diverged) * MS_PER_BRANCH;
        if (total == 0.0) {
            return 0.0;
        }
        return 100.0 * avoided / total;
    }
}
