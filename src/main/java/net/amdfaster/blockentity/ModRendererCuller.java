package net.amdfaster.blockentity;

/**
 * Skips other mods' block entity renderers when the block entity is behind solid terrain.
 *
 * <p>A mod that adds a machine, a storage block or a decorative piece usually adds a block entity renderer for
 * it, and that renderer runs every frame for every instance the game decides is in view. The game's own idea of
 * "in view" is the chunk, so a machine inside a wall is drawn every frame for as long as the chunk is loaded --
 * twenty mods with fifteen machines each is three hundred renderers a frame, most of them drawing into a wall.
 *
 * <p>Testing each one against the terrain and skipping the hidden ones removes that work, and it works for mods
 * this project has never heard of, because the test is about where the block entity is rather than what it is.
 *
 * <h2>It does not need to know the mod</h2>
 *
 * <p>A culler that worked by recognising known block entity types would miss every mod released after it was
 * written, which is most of them. Position and occlusion are properties of the world rather than of the mod, so
 * the same test covers a machine from a mod added yesterday and a chest from the vanilla game.
 *
 * <h2>The exclusion list is respected</h2>
 *
 * <p>Some things are meant to be seen through the world: a beacon's beam, a portal's glow, a marker a player
 * placed to find their way back. Culling those is not an optimisation, it is removing something the player is
 * relying on, so a block entity on the exclusion list is drawn whatever the occlusion test says. The list is
 * shared with {@link net.amdfaster.entity.CullingExclusionList} rather than duplicated, so a player who adds
 * one entry has added it everywhere.
 *
 * <h2>It fails open</h2>
 *
 * <p>A mod's block entity can have geometry that does not match its block -- a machine whose model is three
 * blocks tall, a decoration that extends past its own cell. Testing it as a single block would cull a renderer
 * whose geometry is actually visible, and the player would see a machine half missing with no way to know why.
 * So when the block entity declares bounds larger than its block, it is drawn, and the test is only applied to
 * the ones that fit.
 *
 * <h2>Why this is worth doing for other mods specifically</h2>
 *
 * <p>A mod's renderer is the most expensive kind: it is written for correctness and appearance rather than
 * speed, it runs on the render thread, and there can be hundreds of instances. The vanilla block entities are
 * already covered by their own culling, so what is left is exactly the mods -- which is why a modpack is where
 * this pays for itself.
 *
 * <p>Thread safety: the test runs on the render thread.
 *
 * <p>Reference: Entity Culling skips hidden block entities, and More Culling extends hidden-face culling to
 * mod blocks. This is an independent implementation aimed at third-party renderers.
 */
public final class ModRendererCuller {

    /** Milliseconds one mod block entity renderer costs per frame. */
    public static final double MS_PER_RENDER = 0.03;

    /** Milliseconds one occlusion test costs. */
    public static final double MS_PER_TEST = 0.001;

    private long tested;
    private long culled;
    private long drawn;
    private long exempt;
    private long oversized;
    private long failedOpen;

    /**
     * Whether a mod's block entity renderer should run this frame.
     *
     * @param occluded whether the block is behind solid terrain
     * @param excluded whether the type is on the exclusion list
     * @param geometryExceedsBlock whether its model is larger than the block it sits in
     * @return true if it should be drawn
     */
    public boolean shouldRender(boolean occluded, boolean excluded, boolean geometryExceedsBlock) {
        this.tested++;

        if (excluded) {
            // Some things are meant to be seen through the world: a beacon's beam, a portal's glow, a marker a
            // player placed to find their way back. Culling those is not an optimisation, it is removing
            // something the player is relying on.
            this.exempt++;
            this.drawn++;
            return true;
        }
        if (geometryExceedsBlock) {
            // A machine whose model is three blocks tall or a decoration that extends past its own cell would
            // be culled as a single block while its geometry is actually visible, and the player would see it
            // half missing with no way to know why.
            this.oversized++;
            this.drawn++;
            return true;
        }
        if (occluded) {
            this.culled++;
            return false;
        }
        this.drawn++;
        return true;
    }

    /**
     * Records that a test could not be completed, so the renderer ran anyway.
     *
     * <p>Failing open means a block entity is drawn when the answer is unknown. Drawing something hidden costs
     * a frame; not drawing something visible costs the player their machine.
     */
    public void failedTest() {
        this.failedOpen++;
        this.drawn++;
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

    /** How many were drawn because their type is on the exclusion list. */
    public long exempt() {
        return this.exempt;
    }

    /** How many were drawn because their geometry is larger than their block. */
    public long oversized() {
        return this.oversized;
    }

    /** How many were drawn because the test could not be completed. */
    public long failedOpen() {
        return this.failedOpen;
    }

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of rendering avoided. */
    public double msSaved() {
        return this.culled * MS_PER_RENDER;
    }

    /** Milliseconds the tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /** Milliseconds running every renderer would cost. */
    public double msWithoutCulling() {
        return this.tested * MS_PER_RENDER;
    }

    /**
     * The share of the renderer cost avoided, net of the tests.
     *
     * <p>Zero before anything has been tested. A culler that has tested nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     *
     * <p>The tests are subtracted, because testing is not free -- a culler whose test cost as much as the
     * render would save nothing and should report that.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved() - msTesting()) / without;
    }
}
