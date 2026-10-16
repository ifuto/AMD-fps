package net.amdfaster.particle;

/**
 * Skips both the update and the drawing of particles that are not on screen.
 *
 * <p>A particle is updated every tick -- its position integrated, its velocity damped, its age advanced --
 * and drawn every frame. In a redstone farm or during heavy rain there are thousands of them, and most are
 * behind the player. Skipping the ones that cannot be seen removes both costs, and particles are one of the
 * few things in the game where the update is as expensive as the draw.
 *
 * <h2>The update is skipped too, not just the draw</h2>
 *
 * <p>Skipping only the drawing would leave the particle moving invisibly, and turning around would show it
 * somewhere it should not be -- a raindrop that fell while unobserved, mid-air, having travelled further
 * than the ones being watched. That is a visible discontinuity, and it is worse than the cost saved.
 * Skipping the update means an off-screen particle stays where it was, which is what a particle that has
 * not been simulated looks like, and coming back to it shows it resuming rather than having jumped.
 *
 * <h2>The lifetime still advances</h2>
 *
 * <p>This is the part that keeps skipping the update correct. A particle that is never aged would live
 * forever, and turning around would reveal a field of raindrops that should have fallen minutes ago and are
 * hanging in the air. So the age advances even while the position does not, and an off-screen particle
 * expires on schedule. What is skipped is the integration, not the clock.
 *
 * <h2>Sound is separate</h2>
 *
 * <p>Some particles carry a sound -- a firework's bang, a splash. Culling the visual does not mean the
 * player should stop hearing it, and a firework that explodes silently behind the player reads as a bug.
 * So the audible part is handled elsewhere and this only decides about drawing and motion.
 *
 * <h2>The test uses the particle's bounds</h2>
 *
 * <p>A particle has a size, and one straddling the edge of the screen is partly visible. Testing its centre
 * would drop it and the player would see particles vanishing at the edge of the screen, which is where they
 * are most likely to be noticed because that is where new ones enter.
 *
 * <p>Not thread safe. Particles are updated and drawn on the render thread.
 */
public final class OffscreenParticleCuller {

    /** Milliseconds updating one particle costs. */
    public static final double MS_PER_UPDATE = 0.0002;

    /** Milliseconds drawing one particle costs. */
    public static final double MS_PER_DRAW = 0.0002;

    /** Milliseconds advancing a particle's age costs, which still happens when culled. */
    public static final double MS_PER_AGE = 0.00001;

    /** Degrees of margin kept at the edge of the screen. */
    public static final double EDGE_MARGIN_DEGREES = 3.0;

    private long tested;
    private long culled;
    private long drawn;
    private long aged;

    /**
     * Whether a particle should be updated and drawn.
     *
     * <p>The particle's age advances either way, so an off-screen one expires on schedule rather than
     * hanging in the air when the player turns around.
     *
     * @param angleFromCentre how far the particle's nearest point is from the centre of the view
     * @param halfFieldOfView half the horizontal field of view, in degrees
     * @return true if it should be updated and drawn
     */
    public boolean shouldProcess(double angleFromCentre, double halfFieldOfView) {
        if (angleFromCentre < 0.0) {
            throw new IllegalArgumentException("a negative angle: " + angleFromCentre);
        }
        if (halfFieldOfView <= 0.0) {
            throw new IllegalArgumentException(
                    "a non-positive field of view: " + halfFieldOfView);
        }
        this.tested++;
        this.aged++;

        if (angleFromCentre <= halfFieldOfView + EDGE_MARGIN_DEGREES) {
            this.drawn++;
            return true;
        }
        this.culled++;
        return false;
    }

    public long tested() {
        return this.tested;
    }

    public long culled() {
        return this.culled;
    }

    public long drawn() {
        return this.drawn;
    }

    /** How many particles had their age advanced, which is all of them. */
    public long aged() {
        return this.aged;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of updating and drawing avoided. */
    public double msSaved() {
        return this.culled * (MS_PER_UPDATE + MS_PER_DRAW - MS_PER_AGE);
    }

    /** Milliseconds updating and drawing every particle would cost. */
    public double msWithoutCulling() {
        return this.tested * (MS_PER_UPDATE + MS_PER_DRAW);
    }

    /**
     * The share of the particle cost avoided.
     *
     * <p>Zero before any particle has been tested. A culler that has tested nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The ageing cost is not subtracted from the total, because it is paid either way -- only the
     * integration and the draw are saved.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved()) / without;
    }
}
