package net.amdfaster.render;

/**
 * Decides whether a frame should render a depth pre-pass, and which of the alternatives to use instead.
 *
 * <p>The design was already settled: cutout geometry is handled with a Z pre-pass and
 * {@code DEPTH_TEST_EQUAL} in the colour pass, rather than with {@code discard}. What was missing is the
 * choice. A pre-pass is not free -- it transforms every vertex twice and submits every draw twice -- and
 * turning it on unconditionally makes some frames slower, which is the opposite of what it is for.
 *
 * <h2>The break-even is arithmetic, not a tuning constant</h2>
 *
 * <p>Forward shading costs {@code V + O*N*F}: the geometry once, and the fragment shader on every pixel
 * for every layer of overdraw. A pre-pass costs {@code 2V + N*F}: the geometry twice, and the fragment
 * shader once per pixel because {@code DEPTH_TEST_EQUAL} rejects everything that is not the visible
 * surface. So the pre-pass wins exactly when
 *
 * <pre>
 *   V &lt; (O - 1) * N * F * (1 - cutout)
 * </pre>
 *
 * <p>which rearranges to an overdraw threshold of {@code 1 + V / (N * F * (1 - cutout))}. At 1080p with a
 * mid-cost terrain shader that lands near 3.9x, and it moves a long way with the shader: a trivially cheap
 * shader needs 58x overdraw before a pre-pass is worth anything, because shading the same pixel six times
 * is still cheaper than transforming the world twice.
 *
 * <h2>Cutout geometry cannot go in the pre-pass, and that shrinks the benefit</h2>
 *
 * <p>A depth-only shader that writes depth for a leaf texture has to guess at the pixels the real shader
 * would discard, and guessing wrong writes depth where there is no surface -- which the colour pass then
 * accepts with {@code EQUAL} and draws nothing, leaving a hole. So cutout geometry stays out of the
 * pre-pass, and the fraction of the frame it covers is the fraction of the overdraw the pre-pass cannot
 * remove. That is what the {@code (1 - cutout)} term is: not a fudge factor, but the part of the saving
 * that was never available.
 *
 * <h2>Three cases where the arithmetic is not the question</h2>
 *
 * <p>A tile-based renderer does hidden surface removal in hardware before shading anything, so a manual
 * pre-pass is doing for free what the tile already did, twice. {@code VK_AMD_shader_early_and_late_fragment_tests}
 * lets one shader test depth early for most fragments and late for the ones that discard, which solves
 * cutout without a second pass at all. And on RDNA4 the driver's default HiZ workaround suppresses
 * early-Z, so the forward pass is shading more than its measured overdraw suggests -- which makes the
 * pre-pass worth it sooner, and is the reason this decision is per-device rather than one rule.
 *
 * <p>Not thread safe.
 */
public final class DepthPrepassSelector {

    /** What to do for a frame. */
    public enum Decision {
        /** One pass. The pre-pass would cost more than the overdraw it removes. */
        SINGLE_PASS,
        /** Two passes: depth only, then colour with {@code DEPTH_TEST_EQUAL}. */
        PREPASS,
        /**
         * One pass using early-and-late fragment tests. Cutout is handled inside the shader, so there is
         * no second pass and nothing to decide per frame.
         */
        EARLY_AND_LATE,
        /**
         * One pass on tile-based hardware, which removes hidden surfaces before shading. A manual
         * pre-pass here would repeat work the tile already did.
         */
        HARDWARE_HSR,
    }

    /**
     * What the decision needs.
     *
     * @param overdraw how many times the average pixel is covered, at least 1.0
     * @param vertexCostPerFrame the cost of transforming and rasterizing the frame's geometry once, in
     *     the same units as {@code pixels * fragmentCostPerPixel}
     * @param fragmentCostPerPixel the cost of shading one pixel once
     * @param pixels the framebuffer's pixel count
     * @param cutoutFraction the share of covered pixels whose shader discards, in [0, 1). Those cannot
     *     go in the pre-pass.
     * @param tileBasedDeferred true on tile-based hardware, which does hidden surface removal itself
     * @param earlyAndLateFragmentTests true when {@code VK_AMD_shader_early_and_late_fragment_tests} is
     *     available and the terrain shader uses it
     * @param earlyZSuppressed true when the driver or the shader has defeated early-Z in the forward
     *     pass, which is the RDNA4 HiZ workaround case
     */
    public record Inputs(double overdraw,
                         double vertexCostPerFrame,
                         double fragmentCostPerPixel,
                         long pixels,
                         double cutoutFraction,
                         boolean tileBasedDeferred,
                         boolean earlyAndLateFragmentTests,
                         boolean earlyZSuppressed) {
    }

    /**
     * How much of the overdraw a pre-pass recovers when early-Z is suppressed.
     *
     * <p>Not the whole of it. Suppressing early-Z means the forward pass shades fragments it would
     * otherwise have rejected, so the measured overdraw understates the work; but the pre-pass cannot
     * recover shading that a depth test was never going to remove either. Two thirds is a conservative
     * reading of the RDNA4 case rather than a measured one, and it only moves the threshold -- it never
     * turns a pre-pass on where the geometry would make it a loss.
     */
    public static final double EARLY_Z_SUPPRESSION_FACTOR = 2.0 / 3.0;

    private long decisions;
    private long prepasses;
    private long singlePasses;
    private long earlyAndLate;
    private long hardwareHsr;

    /**
     * Chooses for one frame.
     *
     * @return what to do, never null
     */
    public Decision decide(Inputs in) {
        if (in == null) {
            throw new IllegalArgumentException("inputs are required");
        }
        if (Double.isNaN(in.overdraw()) || in.overdraw() < 1.0) {
            throw new IllegalArgumentException(
                    "overdraw is at least 1.0 -- a pixel is covered once or more: " + in.overdraw());
        }
        if (!(in.fragmentCostPerPixel() > 0.0) || Double.isNaN(in.fragmentCostPerPixel())) {
            throw new IllegalArgumentException(
                    "fragment cost must be positive: " + in.fragmentCostPerPixel());
        }
        if (in.pixels() <= 0) {
            throw new IllegalArgumentException("pixels must be positive: " + in.pixels());
        }
        if (Double.isNaN(in.cutoutFraction()) || in.cutoutFraction() < 0.0
                || in.cutoutFraction() >= 1.0) {
            throw new IllegalArgumentException(
                    "cutout fraction must be in [0, 1): " + in.cutoutFraction());
        }
        if (Double.isNaN(in.vertexCostPerFrame()) || in.vertexCostPerFrame() < 0.0) {
            throw new IllegalArgumentException(
                    "vertex cost must be non-negative: " + in.vertexCostPerFrame());
        }
        this.decisions++;

        // The two hardware answers come first, because neither depends on the arithmetic. A tile-based
        // renderer already removed the hidden surfaces, and the extension solves cutout in one pass.
        if (in.tileBasedDeferred()) {
            this.hardwareHsr++;
            return Decision.HARDWARE_HSR;
        }
        if (in.earlyAndLateFragmentTests()) {
            this.earlyAndLate++;
            return Decision.EARLY_AND_LATE;
        }

        double effective = in.overdraw();
        if (in.earlyZSuppressed()) {
            // The forward pass is shading more than the measured overdraw says, so the pre-pass is worth
            // it at a lower measured figure. Scaled rather than forced: geometry that would make the
            // pre-pass a loss still makes it a loss.
            effective = 1.0 + (effective - 1.0) / EARLY_Z_SUPPRESSION_FACTOR;
        }

        boolean prepass = effective > breakEvenOverdraw(
                in.vertexCostPerFrame(), in.fragmentCostPerPixel(), in.pixels(), in.cutoutFraction());
        if (prepass) {
            this.prepasses++;
            return Decision.PREPASS;
        }
        this.singlePasses++;
        return Decision.SINGLE_PASS;
    }

    /**
     * The overdraw at which a pre-pass starts to pay.
     *
     * <p>Below this, one pass. Above it, two. At exactly this, the two cost the same and one pass is
     * chosen, because the pre-pass also costs a second round of command submission that this arithmetic
     * does not price.
     *
     * @param vertexCostPerFrame the geometry cost, once
     * @param fragmentCostPerPixel the shading cost, per pixel per layer
     * @param pixels the framebuffer's pixel count
     * @param cutoutFraction the share of coverage the pre-pass cannot help with, in [0, 1)
     * @return the break-even overdraw, always greater than 1.0
     */
    public static double breakEvenOverdraw(double vertexCostPerFrame, double fragmentCostPerPixel,
            long pixels, double cutoutFraction) {
        if (!(fragmentCostPerPixel > 0.0) || Double.isNaN(fragmentCostPerPixel)) {
            throw new IllegalArgumentException(
                    "fragment cost must be positive: " + fragmentCostPerPixel);
        }
        if (pixels <= 0) {
            throw new IllegalArgumentException("pixels must be positive: " + pixels);
        }
        if (Double.isNaN(cutoutFraction) || cutoutFraction < 0.0 || cutoutFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "cutout fraction must be in [0, 1): " + cutoutFraction);
        }
        if (Double.isNaN(vertexCostPerFrame) || vertexCostPerFrame < 0.0) {
            throw new IllegalArgumentException(
                    "vertex cost must be non-negative: " + vertexCostPerFrame);
        }
        return 1.0 + vertexCostPerFrame
                / ((double) pixels * fragmentCostPerPixel * (1.0 - cutoutFraction));
    }

    /**
     * What a frame costs one way, for comparing the two rather than trusting the threshold.
     *
     * @param prepass true for two passes, false for one
     * @return the cost, in the same units as the inputs
     */
    public static double frameCost(boolean prepass, Inputs in) {
        if (in == null) {
            throw new IllegalArgumentException("inputs are required");
        }
        double geometry = in.vertexCostPerFrame();
        double shade = (double) in.pixels() * in.fragmentCostPerPixel();
        if (prepass) {
            // The geometry twice, and the fragment shader once per pixel: DEPTH_TEST_EQUAL rejects
            // everything that is not the visible surface, so overdraw drops to one regardless of how
            // many layers the scene actually has.
            return 2.0 * geometry + shade;
        }
        return geometry + in.overdraw() * shade;
    }

    /** How many frames have been decided. */
    public long decisions() {
        return this.decisions;
    }

    public long prepasses() {
        return this.prepasses;
    }

    public long singlePasses() {
        return this.singlePasses;
    }

    public long earlyAndLate() {
        return this.earlyAndLate;
    }

    public long hardwareHsr() {
        return this.hardwareHsr;
    }
}
