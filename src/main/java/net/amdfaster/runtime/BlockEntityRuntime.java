package net.amdfaster.runtime;

import net.amdfaster.blockentity.BlockEntityOcclusionCuller;
import net.amdfaster.blockentity.SleepTracker;
import net.amdfaster.blockentity.TickScheduler;
import net.amdfaster.blockentity.AnimationSkipper;
import net.amdfaster.blockentity.ContainerScreenUpdater;
import net.amdfaster.blockentity.ModRendererCuller;
import net.amdfaster.blockentity.StaticGeometryBaker;
import net.amdfaster.blockentity.BlockEntityFrustumCuller;

/**
 * Runs the block entity decisions in one place, in the order that makes each stage worth doing.
 *
 * <p>A block entity costs four things: a tick, a renderer, an animation, and -- for a container the player is
 * looking at -- a screen refresh. Each has a component that decides whether it is needed, and each is correct on
 * its own, but they are not independent: a block entity whose renderer is culled does not need its animation
 * either, and a block entity whose static geometry was baked into the chunk mesh does not need its renderer at
 * all.
 *
 * <p>So the order is: frustum, occlusion, then the renderer, then the animation. Each stage only sees the block
 * entities the previous one kept.
 *
 * <h2>Baked geometry removes the renderer, not the block entity</h2>
 *
 * <p>A chest body baked into the chunk mesh still has a lid that opens, so the block entity still exists and
 * still ticks -- it just has less to draw. Treating a baked block entity as gone would drop the lid, the
 * contents and the open animation with it, which is not an optimisation but a chest that does not open.
 *
 * <h2>Mods are culled by where they are, not by what they are</h2>
 *
 * <p>A culler that recognised known block entity types would miss every mod released after it was written,
 * which is most of them. Position and occlusion are properties of the world rather than of the mod, so the same
 * test covers a machine from a mod added yesterday and a chest from the vanilla game.
 *
 * <h2>Oversized geometry is not culled</h2>
 *
 * <p>A machine whose model is three blocks tall would be culled as a single block while its geometry is actually
 * visible, and the player would see it half missing with no way to know why. So a block entity that declares
 * bounds larger than its block is drawn, and the test applies only to the ones that fit.
 *
 * <h2>The exclusion list is the one the entity path uses</h2>
 *
 * <p>A player who adds a beacon to it has added it everywhere, so this takes the list from the entity runtime
 * rather than keeping a private copy that would silently disagree about whether the same thing should be drawn.
 *
 * <p>Not thread safe. Block entities are ticked and drawn on the client thread.
 */
public final class BlockEntityRuntime {

    private final BlockEntityFrustumCuller frustum = new BlockEntityFrustumCuller();
    private final ModRendererCuller renderers = new ModRendererCuller();
    private final AnimationSkipper animations = new AnimationSkipper();
    private final ContainerScreenUpdater containers = new ContainerScreenUpdater();
    private final StaticGeometryBaker baker = new StaticGeometryBaker();

    private long seen;
    private long drawn;
    private long culledFrustum;
    private long culledOcclusion;

    /** Skips block entities that are provably hidden. */
    private final BlockEntityOcclusionCuller occlusion = new BlockEntityOcclusionCuller();
    /** Tracks which block entities are awake, so a sleeping one is not animated. */
    private final SleepTracker sleep = new SleepTracker(true);
    /** Spreads block-entity ticks instead of doing them all in one tick. */
    private final TickScheduler beTicks = new TickScheduler();

    /**
     * Decides what a block entity costs this frame.
     *
     * @param type its type, for the exclusion list
     * @param angleFromCentre how far the nearest point of its box is from the centre of the view, in degrees
     * @param halfFieldOfView half the field of view, in degrees
     * @param occluded whether it is behind solid terrain
     * @param excluded whether its type is on the exclusion list
     * @param geometryExceedsBlock whether its model is larger than the block it sits in
     * @param open whether the player has its container screen open
     * @param contentsChanged whether the contents changed since the last refresh
     * @return what to do with it
     */
    public Plan plan(String type, double angleFromCentre, double halfFieldOfView, boolean occluded,
            boolean excluded, boolean geometryExceedsBlock, boolean open, boolean contentsChanged) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("a type is required");
        }
        this.seen++;

        // 1. Frustum, the cheapest test. Judged by the whole box, so one straddling the edge of the view is
        // kept rather than dropped.
        if (!this.frustum.shouldDraw(angleFromCentre, halfFieldOfView)) {
            this.culledFrustum++;
            return new Plan(false, false, false);
        }

        // 2. Occlusion, then the renderer. An excluded type is drawn whatever the occlusion test says.
        if (!this.renderers.shouldRender(occluded, excluded, geometryExceedsBlock)) {
            this.culledOcclusion++;
            return new Plan(false, false, this.containers.shouldRefresh(open, contentsChanged));
        }

        this.drawn++;
        return new Plan(true, true, this.containers.shouldRefresh(open, contentsChanged));
    }

    /** What to do with one block entity this frame. */
    public record Plan(boolean drawn, boolean rendered, boolean refreshScreen) { }

    /**
     * Decides whether an animation should be evaluated this frame.
     *
     * <p>Called only for block entities that are being drawn, because an animation nobody can see is not worth
     * evaluating and skipping it is what the component is for.
     */
    public boolean shouldAnimate(double position, double previous) {
        return this.animations.shouldAnimate(position, previous);
    }

    /**
     * Bakes a block entity's static parts into the chunk mesh.
     *
     * <p>The block entity still exists and still ticks afterwards -- a chest body baked into the mesh still has
     * a lid that opens. Treating a baked block entity as gone would drop the lid, the contents and the open
     * animation with it, which is not an optimisation but a chest that does not open.
     */
    public int bake(String type, java.util.List<StaticGeometryBaker.Part> staticParts,
            java.util.List<StaticGeometryBaker.Part> animatedParts) {
        return this.baker.bake(type, staticParts, animatedParts);
    }

    /** Whether a block entity type's static geometry was baked. */
    public boolean isBaked(String type) {
        return this.baker.isBaked(type);
    }

    public BlockEntityFrustumCuller frustum() {
        return this.frustum;
    }

    public ModRendererCuller renderers() {
        return this.renderers;
    }

    public AnimationSkipper animations() {
        return this.animations;
    }

    public ContainerScreenUpdater containers() {
        return this.containers;
    }

    public StaticGeometryBaker baker() {
        return this.baker;
    }

    public long seen() {
        return this.seen;
    }

    public long drawn() {
        return this.drawn;
    }

    public long culledFrustum() {
        return this.culledFrustum;
    }

    public long culledOcclusion() {
        return this.culledOcclusion;
    }

    /** How many were culled in total. */
    public long culled() {
        return this.culledFrustum + this.culledOcclusion;
    }

    /**
     * The share of the block entities seen that were not drawn.
     *
     * <p>Zero before any block entity has been seen. A runtime that has seen nothing has technically culled
     * nothing, and reporting that as a rate would describe an unused one as though it had measured something.
     */
    public double culledPercent() {
        return this.seen == 0 ? 0.0 : 100.0 * culled() / this.seen;
    }

    /** Milliseconds of renderer work avoided. */
    public double msSaved() {
        return this.renderers.msSaved();
    }

    /** Clears the counters, on a world change. */
    public void reset() {
        this.seen = 0;
        this.drawn = 0;
        this.culledFrustum = 0;
        this.culledOcclusion = 0;
    }

    /** The block-entity occlusion culler. */
    public BlockEntityOcclusionCuller occlusion() {
        return this.occlusion;
    }

    /** The sleep tracker. */
    public SleepTracker sleep() {
        return this.sleep;
    }

    /** The block-entity tick scheduler. */
    public TickScheduler beTicks() {
        return this.beTicks;
    }
}
