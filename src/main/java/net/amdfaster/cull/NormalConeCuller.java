package net.amdfaster.cull;

/**
 * Rejects a meshlet whose normals all point away from the camera, using the cone that bounds them.
 *
 * <p>A meshlet is a small cluster of triangles, and each triangle has a normal. If every normal in the cluster
 * points away from the camera then every triangle is back-facing, and the whole cluster can be dropped before
 * any of its vertices are shaded. Testing that per triangle would cost as much as the shading, so the cluster
 * is described by a cone that contains all its normals -- an axis and how far the normals spread from it --
 * and the test becomes one dot product against the cone.
 *
 * <p>On average about half the meshlets in a scene face away, so this is worth up to a factor of two on
 * geometry-bound passes.
 *
 * <h2>The cone has to contain every normal</h2>
 *
 * <p>A cone that is too narrow leaves a normal outside it, and a meshlet that should have been drawn is
 * dropped -- a hole in the world, in a place that changes as the camera moves, which is the hardest kind of
 * rendering bug to pin down. So the cone is built to bound the normals it was given and is never tightened to
 * make the culling look better.
 *
 * <h2>A wide cone is never culled</h2>
 *
 * <p>A meshlet whose normals point in every direction -- a rock, a tree, a lump of ore -- has a cone so wide
 * that no view direction makes all of them face away. The test correctly refuses to cull it, every time. That
 * is not a failure of the technique: it is the honest result for content that has no back, and a scene made of
 * such meshes gains nothing from cone culling however well it is implemented.
 *
 * <h2>Why this is not the same as the face mask</h2>
 *
 * <p>{@link MeshletFaceMaskCuller} works because voxel faces come in six known directions, so the bounding
 * question has a six-bit answer. A cone works on arbitrary normals and is the right tool for smooth or
 * irregular geometry, where the six directions do not describe the shape. Both apply: the mask is exact and
 * cheap for block faces, the cone covers what the mask cannot express.
 *
 * <h2>The test is one dot product</h2>
 *
 * <p>A sphere test needs a comparison against a projected radius and a cone test needs the axis dotted with
 * the view direction and compared against a threshold. Both are cheap; the cone is the one that answers a
 * question the sphere cannot, which is whether the surface is turned away rather than merely off to one side.
 *
 * <p>Not thread safe. Culling runs on worker threads, so a caller using this from several needs one instance
 * per worker.
 */
public final class NormalConeCuller {

    /** Milliseconds one cone test costs. */
    public static final double MS_PER_TEST = 0.00002;

    /** Milliseconds shading one meshlet's vertices costs, which is what culling avoids. */
    public static final double MS_PER_MESHLET = 0.004;

    private long tested;
    private long culled;
    private long kept;
    private long tooWide;

    /**
     * Whether a meshlet should be drawn.
     *
     * <p>The meshlet is dropped when the angle between the cone axis and the direction to the camera exceeds
     * ninety degrees plus the cone's half-angle, which is when every normal it contains must point away.
     *
     * @param axisX the cone axis, as a unit vector
     * @param coneCutoff the cosine of the cone's half-angle, so every normal satisfies
     *     {@code dot(normal, axis) >= coneCutoff}
     * @param viewX the unit vector from the meshlet toward the camera
     */
    public boolean shouldDraw(double axisX, double axisY, double axisZ, double coneCutoff,
            double viewX, double viewY, double viewZ) {
        if (coneCutoff < -1.0 || coneCutoff > 1.0) {
            throw new IllegalArgumentException("a cosine is between -1 and 1: " + coneCutoff);
        }
        this.tested++;

        if (coneCutoff <= 0.0) {
            // A half-angle of ninety degrees or more means the normals point in every direction -- a rock, a
            // tree, a lump of ore. No view direction makes all of them face away, so the answer is always to
            // draw it. That is not a failure of the technique, it is the honest result for content that has
            // no back.
            this.tooWide++;
            this.kept++;
            return true;
        }

        double dot = axisX * viewX + axisY * viewY + axisZ * viewZ;
        // The threshold is cos(90 + halfAngle), which is -sin(halfAngle), and sin is the root of one minus the
        // squared cosine.
        double threshold = -Math.sqrt(1.0 - coneCutoff * coneCutoff);

        if (dot <= threshold) {
            this.culled++;
            return false;
        }
        this.kept++;
        return true;
    }

    /**
     * The cone cutoff for a set of normals, as the cosine of the half-angle that bounds them.
     *
     * <p>Built to contain every normal given, and never tightened to make the culling look better -- a cone
     * that is too narrow leaves a normal outside it and a meshlet that should have been drawn is dropped,
     * which is a hole in the world that moves with the camera.
     *
     * @param axisX the cone axis, as a unit vector
     * @param normals a flat array of unit normal vectors
     */
    public static double coneCutoff(double axisX, double axisY, double axisZ, double[] normals) {
        if (normals.length == 0 || normals.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "normals come in threes, but there were " + normals.length + " values");
        }
        double min = 1.0;
        for (int i = 0; i < normals.length; i += 3) {
            double dot = axisX * normals[i] + axisY * normals[i + 1] + axisZ * normals[i + 2];
            if (dot < min) {
                min = dot;
            }
        }
        return min;
    }

    public long tested() {
        return this.tested;
    }

    public long culled() {
        return this.culled;
    }

    public long kept() {
        return this.kept;
    }

    /** How many were kept because their cone was too wide to ever be culled. */
    public long tooWide() {
        return this.tooWide;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of vertex shading avoided. */
    public double msSaved() {
        return this.culled * MS_PER_MESHLET;
    }

    /** Milliseconds the tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /** Milliseconds drawing every meshlet would cost. */
    public double msWithoutCulling() {
        return this.tested * MS_PER_MESHLET;
    }

    /**
     * The share of the meshlet shading avoided, net of the tests.
     *
     * <p>Zero before any meshlet has been tested. A culler that has tested nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved() - msTesting()) / without;
    }
}
