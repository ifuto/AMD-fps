package net.amdfaster.cull;

/**
 * Skips drawing what is entirely behind the camera, judged by its whole box.
 *
 * <p>About half of everything in a loaded world is behind the player at any moment, and the renderer draws
 * it anyway because the chunk it belongs to is partly in view. Skipping the parts that are entirely behind
 * the camera removes that work, and unlike culling by distance it costs nothing visible -- the player cannot
 * see what is behind them.
 *
 * <h2>The whole box, not the centre</h2>
 *
 * <p>Something long -- a belt, a wall, a row of chests -- can have its origin behind the camera and still
 * extend into view. Testing the origin would drop it and the player would see a machine disappear as they
 * turned, which is exactly the artefact culling is supposed to remove. Testing the whole bounding box means
 * it is dropped only when no part of it can be on screen, which is the only case where dropping it is
 * correct.
 *
 * <h2>It is switched off during a shadow pass</h2>
 *
 * <p>Shadows are cast by geometry the player is not looking at -- that is what a shadow is. Culling behind
 * the camera while the shadow map is being drawn would remove the shadows of everything behind the player,
 * and the player would turn around to find their own base unlit. So the culler reports that everything
 * should be drawn when it is asked during a shadow pass, and the two passes are told apart explicitly rather
 * than by guessing from the camera.
 *
 * <h2>A box that straddles the plane is drawn</h2>
 *
 * <p>A box with one corner in front of the camera and the rest behind it is partly visible, and dropping it
 * would put a hard edge across the screen where the box stopped. So the test is whether any part is in
 * front, and a straddling box passes.
 *
 * <h2>Why this is not the same as frustum culling</h2>
 *
 * <p>A frustum has four side planes and testing against all of them is the general case. This tests the one
 * plane that matters most -- the near plane, behind which nothing can be seen at any distance -- and it is
 * cheaper because it needs no projection, only a comparison of depth. Sections that pass it are then tested
 * against the full frustum by {@link SoaFrustumCuller}.
 *
 * <p>Not thread safe. Culling runs on the render thread.
 *
 * <p>Reference: Create Tick Control tests long machines by their full box so nothing visible disappears, and
 * disables the check during Iris shadow passes. This is an independent implementation of the same idea.
 */
public final class BehindCameraCuller {

    /** Milliseconds drawing one element costs. */
    public static final double MS_PER_DRAW = 0.02;

    /** Milliseconds testing one box against the near plane costs. */
    public static final double MS_PER_TEST = 0.0005;

    private long tested;
    private long culled;
    private long drawn;
    private long shadowPasses;

    /**
     * Whether an element should be drawn.
     *
     * @param nearestDepth the depth of the closest point of the box, relative to the camera. Negative means
     *     behind it.
     * @param shadowPass whether the shadow map is being drawn rather than the scene
     * @return true if it should be drawn
     */
    public boolean shouldDraw(double nearestDepth, boolean shadowPass) {
        this.tested++;

        if (shadowPass) {
            // Shadows are cast by geometry the player is not looking at -- that is what a shadow is.
            // Culling behind the camera while the shadow map is drawn would remove the shadows of
            // everything behind the player, and they would turn around to find their own base unlit.
            this.shadowPasses++;
            this.drawn++;
            return true;
        }
        if (nearestDepth < 0.0) {
            this.culled++;
            return false;
        }
        this.drawn++;
        return true;
    }

    /**
     * The depth of the nearest point of a box.
     *
     * <p>The whole box, not its centre. Something long can have its origin behind the camera and still
     * extend into view, and testing the origin would drop it so the player saw a machine disappear as they
     * turned.
     *
     * @param centreDepth the depth of the box's centre, relative to the camera
     * @param halfExtent the box's half size along the depth axis
     */
    public static double nearestDepth(double centreDepth, double halfExtent) {
        if (halfExtent < 0.0) {
            throw new IllegalArgumentException("a negative extent: " + halfExtent);
        }
        return centreDepth - halfExtent;
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

    /** How many tests happened while the shadow map was being drawn. */
    public long shadowPasses() {
        return this.shadowPasses;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of drawing avoided. */
    public double msSaved() {
        return this.culled * MS_PER_DRAW;
    }

    /** Milliseconds the tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /** Milliseconds drawing everything would cost. */
    public double msWithoutCulling() {
        return this.tested * MS_PER_DRAW;
    }

    /**
     * The share of the drawing cost avoided, net of the tests.
     *
     * <p>Zero before anything has been tested. A culler that has tested nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The tests are subtracted, because comparing a depth is not free -- a culler whose test cost as
     * much as the draw would save nothing and should report that.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved() - msTesting()) / without;
    }
}
