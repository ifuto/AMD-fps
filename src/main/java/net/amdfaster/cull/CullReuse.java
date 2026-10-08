package net.amdfaster.cull;

/**
 * Decides when the whole culling stage can be skipped and last frame's answer reused.
 *
 * <p>Standing still is common. So is looking at a wall while a machine hums. In both cases the
 * inputs to terrain culling -- the camera, the field of view, the viewport, the section meshes -- are
 * bit-for-bit what they were a frame ago, and the output of a deterministic function of unchanged
 * inputs is unchanged too. Reusing it is not an approximation and it cannot alter what the player
 * sees: it is the same answer the pass would have produced, just not paid for twice.
 *
 * <p>What that skips is the expensive part. Frustum plane extraction, the frustum dispatch over every
 * meshlet in range, the occlusion dispatch against the pyramid, and the rebuild of the indirect draw
 * commands -- the whole stage, every frame, for as long as nothing moves.
 *
 * <h2>What has to invalidate it</h2>
 * The list below is the whole contract, and missing an entry is a bug that shows up as a world that
 * stops updating.
 *
 * <ul>
 *   <li>Camera position or orientation, compared bit-exactly. {@code ==} would call {@code -0.0f}
 *       and {@code 0.0f} the same camera and would never call any {@code NaN} the same as itself.</li>
 *   <li>Field of view. A spyglass or a zoom mod changes the frustum without moving the camera.</li>
 *   <li>Viewport size. A resize changes both the frustum aspect and the pyramid.</li>
 *   <li>A section's <em>geometry</em> being rebuilt. Its meshlets move, appear or vanish.</li>
 * </ul>
 *
 * <p>Deliberately not on the list:
 *
 * <ul>
 *   <li>A light-only rebuild. It rewrites the light stream and leaves positions, texture coordinates
 *       and indices untouched, so nothing about visibility changes. This is the distinction the dirty
 *       scheduler already makes, and it matters: relighting is most of the rebuild traffic, and
 *       invalidating on it would throw the reuse away almost every frame for no reason.</li>
 *   <li>Entities moving. They are culled separately, on their own epoch, because they move every
 *       frame and would otherwise defeat the terrain reuse entirely.</li>
 * </ul>
 *
 * <p>Not thread-safe; call from the render thread.
 */
public final class CullReuse {

    private boolean cameraKnown;
    private int camXBits;
    private int camYBits;
    private int camZBits;
    private int yawBits;
    private int pitchBits;
    private int fovBits;
    private int viewWidth;
    private int viewHeight;

    private int terrainEpoch;
    private int lastCulledTerrainEpoch = -1;
    private boolean cameraUnchanged;

    private int entityEpoch;
    private int lastCulledEntityEpoch = -1;

    private long terrainReusedFrames;
    private long terrainCulledFrames;
    private long entityReusedFrames;
    private long entityCulledFrames;

    /**
     * Records this frame's camera and reports whether it is identical to the previous frame's.
     *
     * <p>Call once per frame, before either reuse decision.
     *
     * @return true when the camera, field of view and viewport are all bit-for-bit unchanged
     */
    public boolean updateCamera(float camX, float camY, float camZ, float yaw, float pitch,
                                float fovDegrees, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException(
                    "viewport must be positive: " + screenWidth + "x" + screenHeight);
        }
        int x = Float.floatToRawIntBits(camX);
        int y = Float.floatToRawIntBits(camY);
        int z = Float.floatToRawIntBits(camZ);
        int yawB = Float.floatToRawIntBits(yaw);
        int pitchB = Float.floatToRawIntBits(pitch);
        int fov = Float.floatToRawIntBits(fovDegrees);

        this.cameraUnchanged = this.cameraKnown
                && x == this.camXBits && y == this.camYBits && z == this.camZBits
                && yawB == this.yawBits && pitchB == this.pitchBits && fov == this.fovBits
                && screenWidth == this.viewWidth && screenHeight == this.viewHeight;

        this.camXBits = x;
        this.camYBits = y;
        this.camZBits = z;
        this.yawBits = yawB;
        this.pitchBits = pitchB;
        this.fovBits = fov;
        this.viewWidth = screenWidth;
        this.viewHeight = screenHeight;
        this.cameraKnown = true;
        return this.cameraUnchanged;
    }

    /** A section's geometry changed, so its meshlets are different. Forces a terrain re-cull. */
    public void invalidateTerrainGeometry() {
        this.terrainEpoch++;
    }

    /**
     * A section's light changed but not its shape.
     *
     * <p>Recorded for callers that need to know, and deliberately does not force a re-cull: light
     * lives in its own vertex stream and has no bearing on which meshlets are visible.
     */
    public void noteTerrainLightChange() {
        // No epoch bump. See the class documentation.
    }

    /** The entity set changed -- one spawned, despawned or moved. Forces an entity re-cull. */
    public void invalidateEntitySet() {
        this.entityEpoch++;
    }

    /**
     * Whether the terrain cull can be skipped this frame.
     *
     * <p>Call after {@link #updateCamera}. Taking the decision is what marks it: a second call in the
     * same frame returns the same answer, so it is safe to ask from more than one place.
     */
    public boolean canReuseTerrainCull() {
        return this.cameraUnchanged && this.lastCulledTerrainEpoch == this.terrainEpoch;
    }

    /** Whether the entity cull can be skipped this frame. */
    public boolean canReuseEntityCull() {
        return this.cameraUnchanged && this.lastCulledEntityEpoch == this.entityEpoch;
    }

    /**
     * Records that the terrain cull actually ran, so the next frame has something to reuse.
     *
     * <p>Must be called on every frame that does <em>not</em> reuse. Forgetting it means the epoch is
     * never marked as culled and the reuse never engages, which is silent: everything still renders,
     * nothing gets faster.
     */
    public void terrainCullRan() {
        this.lastCulledTerrainEpoch = this.terrainEpoch;
        this.terrainCulledFrames++;
    }

    /** Records that the terrain result was reused rather than recomputed. */
    public void terrainCullReused() {
        this.terrainReusedFrames++;
    }

    public void entityCullRan() {
        this.lastCulledEntityEpoch = this.entityEpoch;
        this.entityCulledFrames++;
    }

    public void entityCullReused() {
        this.entityReusedFrames++;
    }

    /** Frames the terrain cull was skipped. The number this class exists to make large. */
    public long terrainReusedFrames() {
        return this.terrainReusedFrames;
    }

    public long terrainCulledFrames() {
        return this.terrainCulledFrames;
    }

    public long entityReusedFrames() {
        return this.entityReusedFrames;
    }

    public long entityCulledFrames() {
        return this.entityCulledFrames;
    }

    /** True once a camera has been recorded at all; before that there is nothing to compare against. */
    public boolean hasCamera() {
        return this.cameraKnown;
    }

    /**
     * Forgets everything, so the next frame re-culls.
     *
     * <p>Use on a dimension change or a resize where the previous cull result is not merely stale but
     * meaningless -- it describes meshlets that no longer exist.
     */
    public void reset() {
        this.cameraKnown = false;
        this.cameraUnchanged = false;
        this.lastCulledTerrainEpoch = -1;
        this.lastCulledEntityEpoch = -1;
        this.terrainEpoch++;
        this.entityEpoch++;
    }
}
