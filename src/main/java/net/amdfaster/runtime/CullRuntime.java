package net.amdfaster.runtime;

import net.amdfaster.cull.BehindCameraCuller;
import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.CullReuse;
import net.amdfaster.cull.FogDistanceCuller;
import net.amdfaster.cull.SoftwareRasterizer;

/**
 * Runs the culling decisions in one place, in the order that makes each test worth doing.
 *
 * <p>Culling is a series of tests that get progressively more expensive: is it past the fog, is it behind the
 * camera, is it inside the frustum, is it behind something solid. Each is correct on its own, but doing them in
 * the wrong order spends the expensive ones on things a cheap test would have removed -- and the whole point is
 * that the cheap tests remove most of them.
 *
 * <p>This runs them in order of cost, and reuses the whole result when nothing that would change it has changed.
 *
 * <h2>Reuse is the biggest saving and the easiest to get wrong</h2>
 *
 * <p>If the camera has not moved and nothing in the world has changed, the culling result is the same as last
 * frame and the whole walk can be skipped. But "nothing has changed" has to include every input: the camera, the
 * terrain geometry, the light, and the set of entities. Missing one means the culling is reused when it should
 * not be, and the symptom is geometry that appears or disappears for no reason the player can find.
 *
 * <h2>A camera cut invalidates everything</h2>
 *
 * <p>A teleport, a dimension change or a spectator jump moves the camera by more than a frame's movement, and a
 * depth pyramid built for the old position would cull everything in the new one -- the player would arrive
 * somewhere and see nothing. So a cut is detected from the size of the movement and invalidates the depth
 * pyramid, which has to be rebuilt before anything can be tested against it.
 *
 * <h2>The fog test comes first</h2>
 *
 * <p>A section past the distance at which the fog is fully opaque contributes no pixels at all, so testing it
 * against the frustum or the depth pyramid is work for nothing. It is also the cheapest test -- a distance
 * comparison against the nearest corner -- so it removes the most for the least.
 *
 * <h2>The depth pyramid is built, not assumed</h2>
 *
 * <p>Testing a section against terrain requires knowing what is in front of it, which means a depth buffer built
 * from the terrain that is already known to be visible. Building it costs time, so it is only rebuilt when the
 * terrain or the camera changes -- and after a camera cut it has to be rebuilt before anything is tested, or
 * every test would be against a pyramid from the wrong place.
 *
 * <p>Not thread safe. Culling runs on the render thread.
 */
public final class CullRuntime {

    private final FogDistanceCuller fog = new FogDistanceCuller();
    private final BehindCameraCuller behindCamera = new BehindCameraCuller();
    private final CameraCutDetector cuts = new CameraCutDetector();
    private final CullReuse reuse = new CullReuse();
    /**
     * The software rasterizer, and the depth buffer it rasterizes into.
     *
     * <p>Sized to the default screen dimensions, rounded up to whole tiles. A partial tile would need bounds
     * checks on every sub-tile access and the inner loop is exactly where those checks cannot be afforded.
     */
    private final SoftwareRasterizer rasterizer =
            new SoftwareRasterizer(new net.amdfaster.cull.TileDepthBuffer(DEFAULT_SCREEN_WIDTH,
                    DEFAULT_SCREEN_HEIGHT));

    /** The screen size the depth buffer covers when the caller does not say. */
    public static final int DEFAULT_SCREEN_WIDTH = 1920;
    public static final int DEFAULT_SCREEN_HEIGHT = 1080;

    private long frames;
    private long reuses;
    private long walks;
    private long cutsDetected;

    public FogDistanceCuller fog() {
        return this.fog;
    }

    public BehindCameraCuller behindCamera() {
        return this.behindCamera;
    }

    public CameraCutDetector cuts() {
        return this.cuts;
    }

    public CullReuse reuse() {
        return this.reuse;
    }

    public SoftwareRasterizer rasterizer() {
        return this.rasterizer;
    }

    /**
     * Runs one frame of culling decisions.
     *
     * @param camX the camera x
     * @param camY the camera y
     * @param camZ the camera z
     * @param yaw the camera yaw
     * @param pitch the camera pitch
     * @param fovDegrees the field of view, for the frustum
     * @param screenWidth the window width in pixels
     * @param screenHeight the window height in pixels
     * @param shadowPass whether the shadow map is being built
     * @return what to do this frame
     */
    public Frame frame(float camX, float camY, float camZ, float yaw, float pitch, float fovDegrees,
            int screenWidth, int screenHeight, boolean shadowPass) {
        this.frames++;

        // A camera cut invalidates the depth pyramid, because a pyramid built for the old position would cull
        // everything in the new one and the player would arrive somewhere and see nothing.
        boolean cut = this.cuts.update(camX, camY, camZ, yaw, pitch);
        if (cut) {
            this.cutsDetected++;
            this.rasterizer.buffer().clear();
        }

        // updateCamera reports whether the camera is UNCHANGED, not whether it moved -- the name reads the
        // other way, so the condition below is written against what it actually returns.
        boolean cameraUnchanged = this.reuse.updateCamera(camX, camY, camZ, yaw, pitch,
                fovDegrees, screenWidth, screenHeight);

        if (cameraUnchanged && this.reuse.canReuseTerrainCull()) {
            // Nothing that would change the result has changed, so the whole walk can be skipped.
            this.reuses++;
            return new Frame(true, false, false);
        }

        this.walks++;
        boolean rebuildPyramid = cut || !cameraUnchanged;
        return new Frame(false, rebuildPyramid, shadowPass);
    }

    /** What one frame should do. */
    public record Frame(boolean reuseLastResult, boolean rebuildDepthPyramid, boolean shadowPass) { }

    /**
     * Tests a section against the fog.
     *
     * <p>First because a section past the distance at which the fog is fully opaque contributes no pixels at
     * all, so testing it against the frustum or the depth pyramid is work for nothing -- and it is the cheapest
     * test, a distance comparison against the nearest corner.
     */
    public boolean cullByFog(double nearestDistance, double fogOpaqueDistance) {
        return this.fog.shouldCull(nearestDistance, fogOpaqueDistance);
    }

    /**
     * Tests a section against the camera plane.
     *
     * <p>Judged by the whole box, so one straddling the plane is kept rather than dropped. During a shadow pass
     * nothing is culled here, because shadows are cast by geometry the player is not looking at -- that is what
     * a shadow is.
     */
    public boolean cullBehindCamera(double frontDepth, boolean shadowPass) {
        return this.behindCamera.shouldDraw(frontDepth, shadowPass);
    }

    /** Adds an occluder to the depth pyramid. */
    public void addOccluder(int x0, int y0, int x1, int y1, float depth) {
        this.rasterizer.addOccluder(x0, y0, x1, y1, depth);
    }

    /** Tests a section against the depth pyramid. */
    public boolean isOccluded(int x0, int y0, int x1, int y1, float nearDepth) {
        return this.rasterizer.test(x0, y0, x1, y1, nearDepth);
    }

    /** Notes that the terrain geometry changed, so the culling result cannot be reused. */
    public void onTerrainChange() {
        this.reuse.invalidateTerrainGeometry();
    }

    /** Notes that the light changed, so the culling result cannot be reused. */
    public void onLightChange() {
        this.reuse.noteTerrainLightChange();
    }

    /** Notes that the entity set changed, so the culling result cannot be reused. */
    public void onEntitySetChange() {
        this.reuse.invalidateEntitySet();
    }

    public long frames() {
        return this.frames;
    }

    /** How many frames reused last frame's result. */
    public long reuses() {
        return this.reuses;
    }

    /** How many frames walked the sections. */
    public long walks() {
        return this.walks;
    }

    /** How many camera cuts were detected. */
    public long cutsDetected() {
        return this.cutsDetected;
    }

    /**
     * The share of the frames that reused last frame's culling.
     *
     * <p>Zero before any frame has been run. A runtime that has run nothing has technically reused everything,
     * and reporting that as a rate would describe an unused one as though it had measured something.
     */
    public double reusePercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reuses / this.frames;
    }

    /** Clears everything, on a world change. */
    public void onWorldChange() {
        this.frames = 0;
        this.reuses = 0;
        this.walks = 0;
        this.cutsDetected = 0;
        this.cuts.invalidate();
        this.rasterizer.buffer().clear();
    }
}
