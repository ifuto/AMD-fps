package net.amdfaster.entity;

import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.Frustum;
import net.amdfaster.cull.HiZ;
import net.amdfaster.cull.PyramidGeometry;

import java.util.Arrays;

/**
 * Hides entities that are behind terrain, using the same hierarchical depth buffer the meshlet cull
 * already builds.
 *
 * <p>This is the case that CPU ray-tracing cannot win. A thousand cows behind a stone wall cost a
 * full model, texture and light lookup each before anything decides they are invisible, and tracing
 * a ray per entity per frame to find that out spends real CPU on the thread that is already the
 * bottleneck. The depth pyramid is sitting there from the terrain pass; one fetch per entity at the
 * right level answers the question for free. Measured on other engines this is the difference
 * between 24 and 140 frames a second on exactly that scene.
 *
 * <h2>The pyramid must come from terrain only</h2>
 * Entities must not write depth into the pyramid this culls against. If they do, an entity that was
 * visible last frame leaves its own depth in the buffer, and the test below compares its position
 * this frame against where it was last frame. Move toward the camera and it survives; move away by a
 * fraction of a block and {@code boxNearest} drops below its own stale depth, it is culled, it stops
 * being drawn, it stops appearing in the pyramid, and it never comes back. Mobs vanish when they walk
 * away from you and the world stays that way.
 *
 * <p>The fix is structural rather than a bias or an epsilon: pass one renders terrain, the pyramid is
 * built from terrain, entities are tested against terrain. An entity can then never occlude itself,
 * and no fudge factor is needed to stop it trying. The cost is that entities do not occlude each
 * other either, which is the same limitation every other entity culler has and is not worth the
 * correctness risk of removing.
 *
 * <h2>The reduction is a minimum</h2>
 * Depth is reversed, so larger is nearer, and "fully hidden" means the box's closest point is behind
 * every surface its footprint covers. That is the minimum surface depth over the footprint, and the
 * pyramid has to be built by min-reduction for the comparison to mean anything. See
 * {@link PyramidGeometry} for why the levels are ceil-halved: a dropped texel makes a min reduction
 * larger, which culls visible geometry rather than merely missing a cull.
 *
 * <p>This is the CPU reference path. The shader mirrors it, and the mirror is the point -- the tests
 * below pin the arithmetic the shader has to reproduce.
 */
public final class EntityOcclusionCuller {

    /** Reads one texel of the hierarchical depth buffer. */
    public interface DepthPyramid {

        /**
         * The minimum depth over one texel, in reversed-Z where larger is nearer.
         *
         * @param level   pyramid level, zero being full resolution
         * @param texelX  texel column at that level
         * @param texelY  texel row at that level
         */
        float minDepth(int level, int texelX, int texelY);
    }

    private int[] survivors = new int[256];
    private int survivorCount;
    private int tested;
    private int culled;
    private int keptOffScreen;
    private int keptUnreliableProjection;
    private boolean passSkipped;

    /**
     * Filters the entities that survived the frustum and distance passes down to the ones that are
     * also not behind terrain.
     *
     * @param frustumPass the distance and frustum pass, whose survivor list is the input here
     * @param cuts        camera history; while it wants a depth prepass this pass keeps everything
     */
    public void cull(EntityBuffer entities, EntityCuller frustumPass,
                     float[] viewProjection, PyramidGeometry pyramid, DepthPyramid depth,
                     CameraCutDetector cuts) {
        if (viewProjection.length != 16) {
            throw new IllegalArgumentException("viewProjection needs 16 floats");
        }
        this.survivorCount = 0;
        this.tested = 0;
        this.culled = 0;
        this.keptOffScreen = 0;
        this.keptUnreliableProjection = 0;

        int candidates = frustumPass.survivorCount();
        ensureCapacity(candidates);

        // A stale pyramid is worse than no pyramid: it culls geometry that is in plain view, and
        // because the culled set never gets redrawn it never gets back into the pyramid, so the
        // mistake sticks. On a cut the caller renders a depth prepass instead and nothing is culled.
        this.passSkipped = cuts.needsDepthPrepass();

        for (int i = 0; i < candidates; i++) {
            int index = frustumPass.survivor(i);
            if (this.passSkipped || !isOccluded(entities, index, viewProjection, pyramid, depth)) {
                this.survivors[this.survivorCount++] = index;
            }
        }
    }

    private boolean isOccluded(EntityBuffer entities, int index, float[] viewProjection,
                               PyramidGeometry pyramid, DepthPyramid depth) {
        HiZ.ScreenRect rect = HiZ.projectToPixels(
                entities.minX(index), entities.minY(index), entities.minZ(index),
                entities.maxX(index), entities.maxY(index), entities.maxZ(index),
                viewProjection, pyramid.baseWidth(), pyramid.baseHeight());

        if (!rect.reliable()) {
            // A corner behind the camera makes the projection nonsense. Culling on nonsense is how
            // geometry disappears while the player is looking straight at it.
            this.keptUnreliableProjection++;
            return false;
        }
        if (rect.isOffScreen(pyramid.baseWidth(), pyramid.baseHeight())) {
            this.keptOffScreen++;
            return false;
        }

        int level = pyramid.selectLevel(rect.width(), rect.height());
        PyramidGeometry.TexelSpan span = pyramid.spanFor(level,
                rect.minX(), rect.minY(), rect.maxX(), rect.maxY());

        // Every texel the box overhangs, not just the centre one. Reading one texel tests the box
        // against a footprint that is not its own.
        float pyramidMin = Float.POSITIVE_INFINITY;
        for (int ty = span.minY(); ty <= span.maxY(); ty++) {
            for (int tx = span.minX(); tx <= span.maxX(); tx++) {
                pyramidMin = Math.min(pyramidMin, depth.minDepth(level, tx, ty));
            }
        }
        if (Float.isInfinite(pyramidMin)) {
            return false;
        }

        this.tested++;

        // Nearest point of the box in reversed-Z is the largest z over w across its corners, which is
        // the largest of the two ends of the projected depth range. Taking the min corner instead
        // would compare the box's far side against the surfaces and cull boxes that stick out.
        float nearest = nearestDepth(entities, index, viewProjection);
        if (nearest < pyramidMin) {
            this.culled++;
            return true;
        }
        return false;
    }

    private static float nearestDepth(EntityBuffer entities, int index, float[] viewProjection) {
        float nearest = Float.NEGATIVE_INFINITY;
        float minX = entities.minX(index);
        float minY = entities.minY(index);
        float minZ = entities.minZ(index);
        float maxX = entities.maxX(index);
        float maxY = entities.maxY(index);
        float maxZ = entities.maxZ(index);
        for (int corner = 0; corner < 8; corner++) {
            float x = (corner & 1) != 0 ? maxX : minX;
            float y = (corner & 2) != 0 ? maxY : minY;
            float z = (corner & 4) != 0 ? maxZ : minZ;
            float clipZ = viewProjection[2] * x + viewProjection[6] * y
                    + viewProjection[10] * z + viewProjection[14];
            float clipW = viewProjection[3] * x + viewProjection[7] * y
                    + viewProjection[11] * z + viewProjection[15];
            nearest = Math.max(nearest, clipZ / clipW);
        }
        return nearest;
    }

    private void ensureCapacity(int needed) {
        if (this.survivors.length < needed) {
            this.survivors = new int[Math.max(needed, this.survivors.length << 1)];
        }
    }

    /** Entities left after occlusion, as indices into the source {@link EntityBuffer}. */
    public int survivorCount() {
        return this.survivorCount;
    }

    public int survivor(int i) {
        return this.survivors[i];
    }

    /** How many entities were actually compared against the pyramid. */
    public int tested() {
        return this.tested;
    }

    /** How many were found to be behind terrain. */
    public int culled() {
        return this.culled;
    }

    /** Kept because their projection landed outside the viewport, which the frustum pass owns. */
    public int keptOffScreen() {
        return this.keptOffScreen;
    }

    /** Kept because a corner was behind the camera and the projection could not be trusted. */
    public int keptUnreliableProjection() {
        return this.keptUnreliableProjection;
    }

    /** True when a camera cut made this pass keep everything rather than risk a stale pyramid. */
    public boolean wasSkipped() {
        return this.passSkipped;
    }

    /**
     * A pyramid over a flat wall at one depth, for tests. Reversed-Z, so the value is the wall's
     * depth and anything with a smaller nearest depth is behind it.
     */
    public static DepthPyramid flatWall(float depthValue) {
        return (level, texelX, texelY) -> depthValue;
    }

    /** A pyramid with nothing in it: the far plane, which in reversed-Z is zero. */
    public static DepthPyramid empty() {
        return (level, texelX, texelY) -> 0f;
    }

    /** Wraps a level-zero depth image and reduces it on demand, so tests can supply real geometry. */
    public static DepthPyramid fromLevelZero(float[] depthImage, PyramidGeometry pyramid) {
        float[] copy = Arrays.copyOf(depthImage, depthImage.length);
        return (level, texelX, texelY) -> {
            int step = 1 << level;
            int x0 = texelX * step;
            int y0 = texelY * step;
            float min = Float.POSITIVE_INFINITY;
            for (int y = y0; y < Math.min(y0 + step, pyramid.baseHeight()); y++) {
                for (int x = x0; x < Math.min(x0 + step, pyramid.baseWidth()); x++) {
                    min = Math.min(min, copy[y * pyramid.baseWidth() + x]);
                }
            }
            return min;
        };
    }

    /** Convenience for callers that only have a frustum and want the whole pipeline in one call. */
    public static void cullAll(EntityBuffer entities, EntityCuller frustumPass, Frustum frustum,
                               float camX, float camY, float camZ, float[] viewProjection,
                               PyramidGeometry pyramid, DepthPyramid depth,
                               CameraCutDetector cuts, EntityOcclusionCuller out) {
        frustumPass.cull(entities, frustum, camX, camY, camZ);
        out.cull(entities, frustumPass, viewProjection, pyramid, depth, cuts);
    }
}
