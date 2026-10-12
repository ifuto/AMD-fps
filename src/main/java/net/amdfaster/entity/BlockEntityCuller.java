package net.amdfaster.entity;

import net.amdfaster.cull.SoaFrustumCuller;

/**
 * Distance and frustum culling for block entities -- chests, signs, banners, beacons, skulls.
 *
 * <p>Block entities are rendered by a separate path from chunks and a separate path from entities, and
 * that path does less culling than either. Two gaps are worth closing, and both were measured by someone
 * else before being attempted here.
 *
 * <h2>The distance the engine ignores</h2>
 *
 * <p>A block entity renderer can declare a range beyond which it should not draw, and the engine does not
 * consistently honour it. The result is a wall of chests two hundred blocks away each running a full
 * render pass to produce a handful of pixels. Applying the distance test here costs one comparison per
 * block entity per frame and is the cheapest win in this file.
 *
 * <h2>The frustum test</h2>
 *
 * <p>Animated block entities are not frustum culled at all by the vanilla path. A spinning banner behind
 * the camera still animates and still submits.
 *
 * <p>Measured effect elsewhere, for calibration rather than as a promise: 400 chests went from 170 to 205
 * frames per second on its own, and from 315 to 410 on top of Sodium. Those are 1.20 and 1.30 times. The
 * gain scales with how many block entities are around and how many are behind the camera, so it is close
 * to nothing in an open field and large in a storage room.
 *
 * <p>Not thread safe. Called from the render thread.
 */
public final class BlockEntityCuller {

    /**
     * Default maximum render distance in blocks.
     *
     * <p>Twenty-five is generous: at that distance a chest lid occupies a few pixels and its animation is
     * not distinguishable from a static one. The point of the number is that it is finite and applied,
     * not that it is this particular value.
     */
    public static final double DEFAULT_MAX_DISTANCE = 25.0;

    /** Beyond this distance the animation stops being worth computing even if the block entity draws. */
    public static final double DEFAULT_ANIMATION_DISTANCE = 16.0;

    /** Squared, because comparing squared distances avoids a square root per block entity per frame. */
    private double maxDistanceSquared;
    private double animationDistanceSquared;

    private int tested;
    private int culledByDistance;
    private int culledByFrustum;

    public BlockEntityCuller() {
        this(DEFAULT_MAX_DISTANCE, DEFAULT_ANIMATION_DISTANCE);
    }

    /**
     * @param maxDistance blocks beyond which nothing draws
     * @param animationDistance blocks beyond which nothing animates
     */
    public BlockEntityCuller(double maxDistance, double animationDistance) {
        setMaxDistance(maxDistance);
        setAnimationDistance(animationDistance);
    }

    public void setMaxDistance(double blocks) {
        if (blocks <= 0.0 || Double.isNaN(blocks) || Double.isInfinite(blocks)) {
            throw new IllegalArgumentException("render distance must be a positive finite number: " + blocks);
        }
        this.maxDistanceSquared = blocks * blocks;
    }

    public void setAnimationDistance(double blocks) {
        if (blocks <= 0.0 || Double.isNaN(blocks) || Double.isInfinite(blocks)) {
            throw new IllegalArgumentException("animation distance must be a positive finite number: " + blocks);
        }
        this.animationDistanceSquared = blocks * blocks;
    }

    /** The render distance, recovered from its square. */
    public double maxDistance() {
        return Math.sqrt(this.maxDistanceSquared);
    }

    public double animationDistance() {
        return Math.sqrt(this.animationDistanceSquared);
    }

    /**
     * Whether a block entity should be drawn at all.
     *
     * @param dx offset from the camera in blocks
     * @param dy offset from the camera in blocks
     * @param dz offset from the camera in blocks
     */
    public boolean shouldRender(double dx, double dy, double dz) {
        this.tested++;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > this.maxDistanceSquared) {
            this.culledByDistance++;
            return false;
        }
        return true;
    }

    /**
     * Whether a block entity should animate.
     *
     * <p>Separated from {@link #shouldRender} because the two distances differ and because a block entity
     * that draws but does not animate is still a saving: the render is a static quad, which batches,
     * while an animated one produces different vertices every frame and defeats the batch.
     */
    public boolean shouldAnimate(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= this.animationDistanceSquared;
    }

    /**
     * Frustum-culls a batch of block entities held in structure-of-arrays bounds.
     *
     * <p>The bounds are in world coordinates relative to nothing in particular; the caller passes camera
     * space or world space as long as the planes match.
     *
     * @return how many survived
     */
    public int cullBatch(SoaFrustumCuller.Planes planes, SoaFrustumCuller.Boxes boxes, int count,
                         boolean[] visible) {
        int kept = SoaFrustumCuller.cull(planes, boxes, count, visible);
        this.tested += count;
        this.culledByFrustum += count - kept;
        return kept;
    }

    public int tested() {
        return this.tested;
    }

    public int culledByDistance() {
        return this.culledByDistance;
    }

    public int culledByFrustum() {
        return this.culledByFrustum;
    }

    public void resetCounters() {
        this.tested = 0;
        this.culledByDistance = 0;
        this.culledByFrustum = 0;
    }
}
