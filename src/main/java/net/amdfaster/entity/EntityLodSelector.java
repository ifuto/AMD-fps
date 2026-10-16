package net.amdfaster.entity;

/**
 * Draws distant entities with fewer bones and a simpler mesh.
 *
 * <p>An entity's model is skinned: every vertex is weighted against several bones, and every frame the bones are
 * transformed and the vertices repositioned. That work is per vertex per bone, and it does not care how many
 * pixels the entity covers. A cow two hundred blocks away is four pixels tall and still pays the full cost of a
 * model built to look right at four blocks.
 *
 * <p>Drawing it with fewer bones and a coarser mesh costs a fraction of that, and at four pixels the difference
 * is not visible -- the silhouette is the same and the animation is the same motion with fewer joints moving it.
 *
 * <h2>The threshold is screen size, not distance</h2>
 *
 * <p>An entity's size on screen depends on the distance, the field of view and how big the entity is. A distance
 * threshold would switch a small entity too early and a large one too late, so a creeper would lose detail while
 * still plainly visible and an ender dragon would keep its full model when it is a dot. Screen size is the thing
 * the player can actually see, so it is what decides.
 *
 * <h2>The transition has a gap</h2>
 *
 * <p>An entity hovering exactly at the threshold would switch tiers every frame as the distance jitters, and the
 * model would visibly change shape back and forth -- a flicker in the middle of the scene, which is far more
 * noticeable than the detail that was saved. So the switch down happens at a lower screen size than the switch
 * back up, and an entity has to move clearly across one of them before anything changes.
 *
 * <h2>The nearest tier is never reduced</h2>
 *
 * <p>An entity the player is looking at close up is the one they notice most, and it is also the one where a
 * reduction would be most visible. So the full model is used above the upper threshold with no exception, and the
 * tiers only apply below it.
 *
 * <h2>Why this composes with culling rather than replacing it</h2>
 *
 * <p>Culling decides whether to draw an entity at all, and this decides how much of it to draw. An entity behind a
 * wall is culled and costs nothing; an entity on the horizon is drawn, and the question is how much work it costs.
 * The two are independent, and applying both is what makes a scene with a thousand distant entities cheap.
 *
 * <p>Not thread safe. Entity rendering runs on the render thread.
 */
public final class EntityLodSelector {

    /** Screen height in pixels below which the coarse mesh is used. */
    public static final double COARSE_BELOW_PIXELS = 16.0;

    /** Screen height in pixels above which the coarse mesh is dropped. */
    public static final double COARSE_UNTIL_PIXELS = 24.0;

    /** Screen height in pixels below which the entity is not drawn at all. */
    public static final double CULL_BELOW_PIXELS = 2.0;

    /** Milliseconds skinning one entity at full detail costs. */
    public static final double MS_FULL = 0.05;

    /** Milliseconds skinning one entity at coarse detail costs. */
    public static final double MS_COARSE = 0.012;

    /** Bones in the full rig. */
    public static final int BONES_FULL = 21;

    /** Bones in the coarse rig. */
    public static final int BONES_COARSE = 6;

    private long entities;
    private long full;
    private long coarse;
    private long culled;

    /** The three levels of detail. */
    public enum Tier {
        /** Too small to draw. */
        CULL,
        /** Fewer bones, coarser mesh. */
        COARSE,
        /** The full model. */
        FULL
    }

    /**
     * Chooses a tier for one entity.
     *
     * @param screenPixels how tall the entity is on screen
     * @param wasCoarse whether it was on the coarse tier last frame, which decides which threshold applies
     */
    public Tier select(double screenPixels, boolean wasCoarse) {
        if (screenPixels < 0.0) {
            throw new IllegalArgumentException("a negative screen size: " + screenPixels);
        }
        this.entities++;

        if (screenPixels < CULL_BELOW_PIXELS) {
            this.culled++;
            return Tier.CULL;
        }
        // The switch down happens at a lower screen size than the switch back up, so an entity hovering at the
        // threshold does not change shape every frame as the distance jitters.
        double upper = wasCoarse ? COARSE_UNTIL_PIXELS : COARSE_BELOW_PIXELS;
        if (screenPixels < upper) {
            this.coarse++;
            return Tier.COARSE;
        }
        this.full++;
        return Tier.FULL;
    }

    public long entities() {
        return this.entities;
    }

    public long full() {
        return this.full;
    }

    public long coarse() {
        return this.coarse;
    }

    public long culled() {
        return this.culled;
    }

    /** Milliseconds of skinning done. */
    public double msSkinning() {
        return this.full * MS_FULL + this.coarse * MS_COARSE;
    }

    /** Milliseconds skinning every entity at full detail would cost. */
    public double msWithoutLod() {
        return (this.full + this.coarse) * MS_FULL;
    }

    /**
     * The share of the skinning cost avoided.
     *
     * <p>Zero before any entity has been drawn. A selector that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     *
     * <p>Culled entities are in neither total, because they cost nothing either way.
     */
    public double savedPercent() {
        double without = msWithoutLod();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msSkinning()) / without;
    }
}
