package net.amdfaster.particle;

import net.amdfaster.cull.SoaFrustumCuller;

/**
 * Frustum culling and distance tiers for particles, as a {@link ParticleField.VisibilityTest}.
 *
 * <p>Particles are not draw-call bound, they are fill-rate bound. Measured elsewhere: half a million
 * particles cost 2.81 ms as billboards and 0.71 ms as cubes, and the difference is the pixels shaded,
 * not the submissions. That makes the usual instinct -- batch harder -- the wrong one, and it makes
 * <em>not drawing them</em> the right one. Two things here do that.
 *
 * <h2>The frustum test</h2>
 *
 * <p>A particle is a sphere for culling purposes, so the test is a sphere against six planes. It is
 * conservative: the sphere is rejected only when it lies entirely outside a half-space, which happens
 * when the plane's distance at its centre is more negative than the radius allows. The plane normals are
 * not necessarily unit length, so the allowance is the radius scaled by the normal's length, and those
 * lengths are computed once when the planes are set rather than once per particle per plane.
 *
 * <h2>The distance tiers</h2>
 *
 * <p>Three tiers rather than two, because the useful distinction is not visible against invisible but
 * detailed against cheap. A particle far enough away that its animation cannot be distinguished from a
 * static quad should draw as a static quad, which batches; a particle far enough away that it covers a
 * fraction of a pixel should not draw at all.
 *
 * <p>The last of those is the one that pays. A particle whose screen span is under about a pixel
 * contributes nothing to the image and everything to the fragment count, and in a rainstorm or a large
 * explosion most of the particles are in exactly that state.
 *
 * <p>Not thread safe. Called from the render thread.
 */
public final class ParticleLod implements ParticleField.VisibilityTest {

    /** What to draw a particle as. */
    public enum Tier {
        /** Fully animated, full resolution. */
        FULL,
        /** Static quad, no per-frame vertex work, batches with its neighbours. */
        SIMPLIFIED,
        /** Not drawn. */
        DROPPED
    }

    /** Beyond this many blocks a particle does not draw at all. */
    public static final float DEFAULT_DROP_DISTANCE = 48.0f;
    /** Beyond this many blocks a particle draws as a static quad. */
    public static final float DEFAULT_SIMPLIFY_DISTANCE = 24.0f;
    /**
     * Below this many pixels of screen span a particle does not draw.
     *
     * <p>A quarter pixel. Under that the sample the rasteriser takes is dominated by whatever is behind
     * the particle, so drawing it changes the image by less than the noise in it.
     */
    public static final float DEFAULT_MIN_PIXEL_SPAN = 0.25f;

    private final SoaFrustumCuller.Planes planes;
    private final float[] normalLength = new float[SoaFrustumCuller.PLANES];
    private boolean havePlanes;

    private float dropDistanceSquared;
    private float simplifyDistanceSquared;
    private float minPixelSpan;

    private int tested;
    private int culledByFrustum;
    private int droppedBySize;
    private int simplified;

    public ParticleLod() {
        this(new SoaFrustumCuller.Planes());
    }

    public ParticleLod(SoaFrustumCuller.Planes planes) {
        this.planes = planes;
        setDistances(DEFAULT_SIMPLIFY_DISTANCE, DEFAULT_DROP_DISTANCE);
        this.minPixelSpan = DEFAULT_MIN_PIXEL_SPAN;
    }

    /**
     * Sets the distances that separate the three tiers.
     *
     * @param simplify blocks beyond which a particle draws as a static quad
     * @param drop blocks beyond which a particle does not draw
     */
    public void setDistances(float simplify, float drop) {
        if (simplify <= 0.0f || drop <= 0.0f || Float.isNaN(simplify) || Float.isNaN(drop)) {
            throw new IllegalArgumentException("tier distances must be positive: " + simplify + ", " + drop);
        }
        if (simplify > drop) {
            // Simplifying farther than dropping would mean a particle that is too far to draw is still
            // being asked to draw cheaply, which is a contradiction and a sign the two were swapped.
            throw new IllegalArgumentException(
                    "simplify distance " + simplify + " exceeds drop distance " + drop);
        }
        this.simplifyDistanceSquared = simplify * simplify;
        this.dropDistanceSquared = drop * drop;
    }

    public void setMinPixelSpan(float pixels) {
        if (pixels < 0.0f || Float.isNaN(pixels)) {
            throw new IllegalArgumentException("minimum pixel span must not be negative: " + pixels);
        }
        this.minPixelSpan = pixels;
    }

    public float minPixelSpan() {
        return this.minPixelSpan;
    }

    public float simplifyDistance() {
        return (float) Math.sqrt(this.simplifyDistanceSquared);
    }

    public float dropDistance() {
        return (float) Math.sqrt(this.dropDistanceSquared);
    }

    /**
     * Recomputes the plane normal lengths.
     *
     * <p>Must be called after the planes change. Doing this per particle would be six square roots per
     * particle per frame, which on half a million particles is three million square roots to save
     * nothing.
     */
    public void refreshNormals() {
        for (int k = 0; k < SoaFrustumCuller.PLANES; k++) {
            float a = this.planes.a[k];
            float b = this.planes.b[k];
            float c = this.planes.c[k];
            this.normalLength[k] = (float) Math.sqrt(a * a + b * b + c * c);
        }
        this.havePlanes = true;
    }

    /**
     * Sphere against the frustum, conservative.
     *
     * <p>Used as the {@link ParticleField.VisibilityTest}. Before the planes have been refreshed the test
     * passes everything, because rejecting on uninitialised normals would empty the screen.
     */
    @Override
    public boolean isVisible(float centerX, float centerY, float centerZ, float radius) {
        this.tested++;
        if (!this.havePlanes) {
            return true;
        }
        for (int k = 0; k < SoaFrustumCuller.PLANES; k++) {
            float distance = this.planes.a[k] * centerX
                    + this.planes.b[k] * centerY
                    + this.planes.c[k] * centerZ
                    + this.planes.d[k];
            if (distance < -radius * this.normalLength[k]) {
                this.culledByFrustum++;
                return false;
            }
        }
        return true;
    }

    /**
     * Which tier a particle belongs in.
     *
     * @param distanceSquared squared distance from the camera, so the caller does not take a root
     * @param size the particle's world-space span
     * @param pixelsPerBlock screen pixels per world block at that distance
     */
    public Tier tierFor(float distanceSquared, float size, float pixelsPerBlock) {
        if (distanceSquared > this.dropDistanceSquared) {
            return Tier.DROPPED;
        }
        if (size * pixelsPerBlock < this.minPixelSpan) {
            this.droppedBySize++;
            return Tier.DROPPED;
        }
        if (distanceSquared > this.simplifyDistanceSquared) {
            this.simplified++;
            return Tier.SIMPLIFIED;
        }
        return Tier.FULL;
    }

    public int tested() {
        return this.tested;
    }

    public int culledByFrustum() {
        return this.culledByFrustum;
    }

    public int droppedBySize() {
        return this.droppedBySize;
    }

    public int simplified() {
        return this.simplified;
    }

    public void resetCounters() {
        this.tested = 0;
        this.culledByFrustum = 0;
        this.droppedBySize = 0;
        this.simplified = 0;
    }
}
