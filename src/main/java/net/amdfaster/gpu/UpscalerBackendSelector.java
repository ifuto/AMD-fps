package net.amdfaster.gpu;

/**
 * Chooses the instruction path an upscaler runs on, because the fastest one is not available everywhere.
 *
 * <p>An upscaler is a small neural network, and its inner loop is a dot product between a tile of pixels and a
 * block of weights. Intel's matrix extensions run that in one instruction on the hardware that has them, which
 * is several times faster than doing it with scalar multiplies -- but the hardware that has them is a subset,
 * and a mod that requires them will not run on the rest.
 *
 * <p>Every GPU this project targets can do an eight-bit integer dot product across four or eight lanes at
 * once, so that is the path everything can share. Choosing the matrix path where it exists and falling back to
 * the dot product where it does not means the same upscaler runs on all of them, faster on some.
 *
 * <h2>The fallback is not a worse result</h2>
 *
 * <p>Both paths compute the same dot product; they differ in how many instructions it takes. So the output is
 * identical and the choice is a speed choice, not a quality one -- which is what makes it safe to decide at
 * runtime from what the device reports rather than shipping two upscalers.
 *
 * <h2>It is keyed on the capability, not the vendor</h2>
 *
 * <p>Asking "is this Intel" would be wrong twice: some Intel parts do not have the matrix extensions, and
 * other vendors are adding equivalent instructions. Asking what the device reports means a part that gains the
 * capability starts using it without a new build, and one that never had it is not offered a path it cannot
 * run.
 *
 * <h2>The dot product is eight-bit, not floating point</h2>
 *
 * <p>An upscaler's weights fit in eight bits, and an integer dot product runs at full rate where a
 * floating-point one on the same lanes would be several times slower. Quantising the weights is what makes the
 * fast path available at all, and the loss is below what the player can see in an image that is being
 * reconstructed from a lower resolution anyway.
 *
 * <h2>Why this is decided once</h2>
 *
 * <p>The capability does not change while the game runs, so choosing per frame would repeat a lookup that
 * cannot give a different answer, and could in principle give a different answer if something reported
 * inconsistently -- which would make the image change between frames for no reason the player could see.
 *
 * <p>Thread safety: the choice is made at pipeline creation, on the thread that builds the pipelines.
 *
 * <p>Reference: Intel's XeSS and XeLL documentation describes the cross-vendor path built on the eight-bit
 * integer dot product, used where the matrix extensions are not available. This is an independent
 * implementation of that selection logic.
 */
public final class UpscalerBackendSelector {

    /** Lanes an eight-bit integer dot product covers at once. */
    public static final int DOT_LANES = 8;

    /** How many times faster the matrix path is than the dot product, where it exists. */
    public static final double MATRIX_SPEEDUP = 4.0;

    /** Milliseconds one upscaler frame costs on the dot product path. */
    public static final double MS_DOT_PATH = 1.6;

    /** The instruction path an upscaler runs on. */
    public enum Backend {
        /** The vendor's matrix extensions, where they exist. */
        MATRIX,
        /** The eight-bit integer dot product, which every target GPU has. */
        DOT_PRODUCT
    }

    /**
     * Which path to use.
     *
     * @param hasMatrixExtensions whether the device reports them
     */
    public static Backend choose(boolean hasMatrixExtensions) {
        // Keyed on the capability rather than the vendor: some Intel parts do not have the matrix extensions,
        // and other vendors are adding equivalent instructions. Asking what the device reports means a part
        // that gains the capability starts using it without a new build, and one that never had it is not
        // offered a path it cannot run.
        return hasMatrixExtensions ? Backend.MATRIX : Backend.DOT_PRODUCT;
    }

    /**
     * Milliseconds one upscaler frame costs on a path.
     *
     * @param backend which path the device uses
     */
    public static double msPerFrame(Backend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("a backend is required");
        }
        return backend == Backend.MATRIX ? MS_DOT_PATH / MATRIX_SPEEDUP : MS_DOT_PATH;
    }

    /**
     * Whether upscaling is worth enabling at a given frame budget.
     *
     * <p>It is worth it when the frame it saves by rendering fewer pixels is worth more than the frame the
     * upscaler costs -- and on the dot product path that is a higher bar than on the matrix one.
     *
     * @param backend which path the device uses
     * @param msSavedByLowerResolution how much rendering at a lower resolution saves
     */
    public static boolean worthEnabling(Backend backend, double msSavedByLowerResolution) {
        if (msSavedByLowerResolution < 0.0) {
            throw new IllegalArgumentException(
                    "a non-negative saving is required: " + msSavedByLowerResolution);
        }
        return msSavedByLowerResolution > msPerFrame(backend);
    }

    /**
     * The net gain per frame, which can be negative.
     *
     * <p>Reported as it is rather than clamped, because a mod that quietly refuses to enable an upscaler
     * leaves the player wondering why the setting does nothing -- and the number explains it.
     */
    public static double netMsPerFrame(Backend backend, double msSavedByLowerResolution) {
        if (msSavedByLowerResolution < 0.0) {
            throw new IllegalArgumentException(
                    "a non-negative saving is required: " + msSavedByLowerResolution);
        }
        return msSavedByLowerResolution - msPerFrame(backend);
    }

    /**
     * The lowest rendering resolution saving at which the upscaler pays for itself.
     *
     * <p>Useful because it is one number per path, and a caller can compare it against what the resolution
     * scale actually saves rather than trying the setting and measuring.
     */
    public static double breakevenSaving(Backend backend) {
        return msPerFrame(backend);
    }
}
