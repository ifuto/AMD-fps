package net.amdfaster.blockentity;

/**
 * Skips the draw call for a block entity that is behind solid terrain.
 *
 * <p>Chunk culling removes sections that are not visible, but a block entity is drawn by its own renderer
 * with its own draw call, and nothing checks whether the chest it is about to draw is behind a wall. In a
 * base built into a hillside, every chest in the storeroom behind the player is issued a draw call,
 * assembled and submitted, and then entirely covered by the terrain in front of it. A hundred chests
 * inside a mountain is a hundred wasted draw calls per frame.
 *
 * <p>Testing whether the block entity is occluded and skipping it when it is removes that work.
 *
 * <h2>A block entity in the open is always drawn</h2>
 *
 * <p>The test has to be conservative. A chest on a balcony, in a doorway, or on the surface is visible from
 * almost any angle, and culling it would make it vanish when the player looks at it -- which is not an
 * optimisation, it is the object disappearing. So the cull only happens when the block entity is provably
 * hidden, and "provably" means the occlusion test says so, not that it seems likely.
 *
 * <h2>Why block entities are not covered by chunk culling</h2>
 *
 * <p>Chunk culling works on whole sections and answers "is any part of this section visible". A section
 * containing one chest behind a wall and one block of exposed grass is visible, so the section is drawn,
 * and the chest goes with it. The chest needs its own test, which is why this is a separate culler rather
 * than something the existing one already does.
 *
 * <h2>The test is cheaper than the draw</h2>
 *
 * <p>An occlusion test against the depth buffer or a coarse voxel proxy costs far less than assembling the
 * geometry, binding the texture and issuing the call -- which is the whole reason this is worth doing. If
 * the test cost as much as the draw, skipping the draw would save nothing, and the honest answer would be
 * to leave block entities alone.
 *
 * <h2>What is culled is not what is invisible</h2>
 *
 * <p>A block entity can be off-screen and still need drawing, if its particles or its light extend into
 * view. So this culls on occlusion, which is a statement about geometry, rather than on screen bounds,
 * which is a statement about the camera. The two are different questions and only one of them is safe to
 * answer this way.
 *
 * <p>Not thread safe. Culling runs on the render thread.
 */
public final class BlockEntityOcclusionCuller {

    /** Milliseconds one occlusion test costs. */
    public static final double MS_PER_TEST = 0.001;

    /** Milliseconds drawing one block entity costs. */
    public static final double MS_PER_DRAW = 0.01;

    private long tested;
    private long culled;
    private long drawn;

    /**
     * Whether a block entity should be drawn.
     *
     * @param occluded whether the occlusion test found it hidden behind solid terrain
     * @return true if it should be drawn
     */
    public boolean shouldDraw(boolean occluded) {
        this.tested++;

        if (occluded) {
            this.culled++;
            return false;
        }
        this.drawn++;
        return true;
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

    public double culledPercent() {
        return this.tested == 0 ? 0.0 : 100.0 * this.culled / this.tested;
    }

    /** Milliseconds of drawing avoided. */
    public double msSaved() {
        return this.culled * MS_PER_DRAW;
    }

    /** Milliseconds the occlusion tests cost. */
    public double msTesting() {
        return this.tested * MS_PER_TEST;
    }

    /**
     * Milliseconds a frame costs drawing every block entity with no test.
     *
     * <p>The baseline this is measured against, and the reason the test has to be cheaper than the draw.
     */
    public double msWithoutCulling() {
        return this.tested * MS_PER_DRAW;
    }

    /**
     * The share of the block entity drawing cost avoided, net of the tests.
     *
     * <p>Zero before any block entity has been tested. A culler that has tested nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>The tests are subtracted, because a culler whose test cost as much as the draw would save nothing
     * and should report that.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        double net = msSaved() - msTesting();
        return 100.0 * Math.max(0.0, net) / without;
    }
}
