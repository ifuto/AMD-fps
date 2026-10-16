package net.amdfaster.render;

/**
 * Keeps the cracking cube and moves it to the block being broken, instead of rebuilding it every frame.
 *
 * <p>While a block is being broken, a cube of cracking texture is drawn over it. Vanilla rebuilds that
 * cube every frame: six quads, one per face, generated and uploaded again for a shape that is always the
 * same cube. It is small work per block, but it happens every frame for as long as the player holds the
 * button down, and in creative mode several blocks at once.
 *
 * <p>Caching the cube and supplying the position and stage as uniforms takes the rebuild to the frames
 * where the stage actually advanced.
 *
 * <h2>The stage has to advance</h2>
 *
 * <p>The ten crack stages are how the player knows the block is breaking and roughly how much is left.
 * A cached overlay stuck on one stage would look like the block had stopped responding, and the player
 * would let go and start again. So the stage is part of the cache key and a change either rebuilds or is
 * supplied as a uniform; either way the cracks follow the progress.
 *
 * <h2>The cube has to follow the block</h2>
 *
 * <p>Starting to break a different block has to move the overlay there immediately. Baking the position
 * into the mesh would mean rebuilding to move, which is the cost this exists to remove -- and leaving it
 * behind would show cracks on a block the player is no longer touching, which reads as a broken game.
 * So the position is a uniform updated every frame.
 *
 * <p>Both things that change often -- the position every frame and the stage as progress advances -- are
 * cheap to change. The thing that is expensive to change, the cube, does not change at all.
 *
 * <p>Not thread safe. The overlay is drawn on the render thread.
 */
public final class BreakingOverlayCache {

    /** Faces on the cracking cube. */
    public static final int FACES = 6;

    /** Crack stages a block goes through. */
    public static final int STAGES = 10;

    /** Milliseconds one cube costs to rebuild. */
    public static final double MS_PER_REBUILD = FACES * 0.005;

    /** Milliseconds setting the uniforms costs. */
    public static final double MS_PER_UNIFORM = 0.0005;

    private long cachedPosition;
    private int cachedStage = -1;

    private long frames;
    private long rebuilt;
    private long reused;

    /** Packs a block position into a key. */
    public static long positionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | ((long) (z & 0x3FFFFFF));
    }

    /**
     * Whether the cube has to be rebuilt this frame.
     *
     * <p>Only a stage change rebuilds. A position change does not, because the cube is the same shape
     * wherever it is drawn and the position is a uniform.
     *
     * @param stage the crack stage, 0 to 9
     * @return true if the caller has to rebuild
     */
    public boolean needsRebuild(int x, int y, int z, int stage) {
        if (stage < 0 || stage >= STAGES) {
            throw new IllegalArgumentException(
                    "stage " + stage + " is not one of " + STAGES);
        }
        this.frames++;
        this.cachedPosition = positionKey(x, y, z);

        if (this.cachedStage == stage) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedStage = stage;
        return true;
    }

    /**
     * The position the cube is drawn at.
     *
     * <p>A uniform updated every frame. Baking it into the mesh would mean rebuilding to move, and
     * leaving it behind would show cracks on a block the player is no longer touching.
     */
    public long position() {
        return this.cachedPosition;
    }

    /** The crack stage currently shown. */
    public int stage() {
        return this.cachedStage;
    }

    /** Drops the cached cube, for when the player stops breaking. */
    public void invalidate() {
        this.cachedStage = -1;
    }

    public long frames() {
        return this.frames;
    }

    public long rebuilt() {
        return this.rebuilt;
    }

    public long reused() {
        return this.reused;
    }

    public double reusedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reused / this.frames;
    }

    /** Milliseconds a frame costs with the cache. */
    public double msPerFrame() {
        if (this.frames == 0) {
            return 0.0;
        }
        double rebuildShare = (double) this.rebuilt / this.frames;
        return rebuildShare * MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    /** Milliseconds a frame costs rebuilding every time. */
    public static double msPerFrameWithoutCache() {
        return MS_PER_REBUILD + MS_PER_UNIFORM;
    }

    /**
     * The share of the rebuild avoided.
     *
     * <p>Zero before any frame has been drawn. A cache that has done nothing has technically avoided all
     * of it, but reporting a saving for an unused cache reads as a result when it is the absence of one.
     */
    public double savedPercent() {
        double without = msPerFrameWithoutCache();
        if (without == 0.0 || this.frames == 0) {
            return 0.0;
        }
        return 100.0 * (without - msPerFrame()) / without;
    }
}
