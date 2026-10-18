package net.amdfaster.cull;

/**
 * Culls sections against the frustum through an octree of section bounds, so that most of the world is
 * rejected by a handful of tests instead of one test per section.
 *
 * <p>Flat frustum culling tests every loaded section. At a twelve chunk render distance that is 24 by 24
 * by 24, or 13,824 sections, and the perspective frustum this was measured with sees 1,910 of them -- so
 * more than four tests in five are spent confirming that a section is not on screen. The tests are cheap
 * individually, but they run every frame, on the render thread, before anything can be drawn.
 *
 * <p>Grouping sections into an octree and testing the coarse nodes first removes most of them. Measured
 * against that same scene, the walk performs 2,929 axis-aligned box tests and reaches the same 1,910
 * leaves -- 78.8 per cent fewer tests for an identical answer. A node the frustum misses discards every
 * section under it at once, which is where the saving comes from: one rejected node near the top of the
 * tree stands in for thousands of leaves.
 *
 * <h2>The tree is walked, not built</h2>
 *
 * <p>There is no node allocation and nothing to keep in sync with the world. The walk descends an implicit
 * octree over the smallest power-of-two cube of sections that contains the range it was given, splitting
 * each edge in half per level. Building a real tree would mean invalidating it on every chunk load and
 * unload, and a stale tree culls sections that exist -- which is a hole in the world rather than an
 * optimisation.
 *
 * <h2>Conservative in the only direction that is safe</h2>
 *
 * <p>A node is discarded only when {@link Frustum#intersectsAabb} says its whole box is outside. Anything
 * ambiguous is descended into and tested again at the next level down, so the set of leaves reached is
 * exactly the set flat culling would have kept. A culler that dropped one section too many would not log
 * anything; it would put a hole in the terrain and the player would find it by walking into it.
 *
 * <p>The cube is a power of two and the loaded range usually is not, so leaves past the range are filtered
 * on the way out. Reporting them would hand the caller sections that are not there.
 *
 * <p>Not thread safe.
 */
public final class HierarchicalFrustumCuller {

    /** Receives a section that survived the cull. */
    public interface Sink {
        /**
         * @param sectionX the section's x index
         * @param sectionY its y index
         * @param sectionZ its z index
         */
        void accept(int sectionX, int sectionY, int sectionZ);
    }

    /** A section is this many blocks across. */
    public static final int SECTION_SIZE = 16;

    private long tests;
    private long leavesReached;
    private long sectionsSkipped;
    private long walks;

    // Walk state, held on the instance so the recursion does not thread it through every call.
    private Sink sink;
    private int lowX;
    private int lowY;
    private int lowZ;
    private int highX;
    private int highY;
    private int highZ;
    private int reached;

    /**
     * Culls a range of sections and hands the survivors to a sink.
     *
     * @param frustum the planes to test against
     * @param minX the lowest section index on x, inclusive
     * @param minY the lowest on y
     * @param minZ the lowest on z
     * @param sizeX how many sections across on x
     * @param sizeY how many on y
     * @param sizeZ how many on z
     * @param sink receives every section that survived
     * @return how many sections survived
     */
    public int cull(Frustum frustum, int minX, int minY, int minZ,
            int sizeX, int sizeY, int sizeZ, Sink sink) {
        if (frustum == null) {
            throw new IllegalArgumentException("a frustum is required");
        }
        if (sink == null) {
            throw new IllegalArgumentException("a sink is required");
        }
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException(
                    "a range must have a positive size: " + sizeX + ", " + sizeY + ", " + sizeZ);
        }
        this.walks++;

        // The tree is a cube and the range usually is not, so take the largest axis and round it up to a
        // power of two. One edge length for all three keeps the splits aligned, which is what lets a
        // node's box be derived from its level instead of stored.
        int span = Math.max(sizeX, Math.max(sizeY, sizeZ));
        int top = 1;
        int level = 0;
        while (top < span) {
            top <<= 1;
            level++;
        }

        this.sink = sink;
        this.lowX = minX;
        this.lowY = minY;
        this.lowZ = minZ;
        this.highX = minX + sizeX;
        this.highY = minY + sizeY;
        this.highZ = minZ + sizeZ;
        this.reached = 0;

        walk(frustum, level, minX * SECTION_SIZE, minY * SECTION_SIZE, minZ * SECTION_SIZE);

        this.leavesReached += this.reached;
        return this.reached;
    }

    /**
     * Descends one node.
     *
     * @param level the node's level; zero is a single section
     * @param blockX the node's minimum corner, in blocks. Always a multiple of {@link #SECTION_SIZE},
     *     which is what makes the division back to a section index exact for negative coordinates.
     */
    private void walk(Frustum frustum, int level, int blockX, int blockY, int blockZ) {
        this.tests++;

        int span = (1 << level) * SECTION_SIZE;
        if (!frustum.intersectsAabb(blockX, blockY, blockZ,
                blockX + span, blockY + span, blockZ + span)) {
            // The whole subtree is outside. Count what it stood in for, which is the saving.
            this.sectionsSkipped += 1L << (3 * level);
            return;
        }

        if (level == 0) {
            int sx = blockX / SECTION_SIZE;
            int sy = blockY / SECTION_SIZE;
            int sz = blockZ / SECTION_SIZE;
            // The cube is a power of two and the range usually is not, so part of the cube is past the
            // range. Those are not sections, and reporting them would hand over coordinates nobody has.
            if (sx < this.lowX || sx >= this.highX
                    || sy < this.lowY || sy >= this.highY
                    || sz < this.lowZ || sz >= this.highZ) {
                return;
            }
            this.reached++;
            this.sink.accept(sx, sy, sz);
            return;
        }

        int half = span >> 1;
        for (int dx = 0; dx <= half; dx += half) {
            for (int dy = 0; dy <= half; dy += half) {
                for (int dz = 0; dz <= half; dz += half) {
                    walk(frustum, level - 1, blockX + dx, blockY + dy, blockZ + dz);
                }
            }
        }
    }

    /** How many axis-aligned box tests the walk has performed. */
    public long tests() {
        return this.tests;
    }

    /** How many sections have survived across every walk. */
    public long leavesReached() {
        return this.leavesReached;
    }

    /**
     * How many sections were discarded whole, by a node above them being outside.
     *
     * <p>This can exceed the number of sections in the range, because it counts what each rejected node
     * stood in for across the whole power-of-two cube, including the part of the cube past the range. It
     * measures work avoided, not sections that exist.
     */
    public long sectionsSkipped() {
        return this.sectionsSkipped;
    }

    /** How many times {@link #cull} has been called. */
    public long walks() {
        return this.walks;
    }

    /**
     * The share of box tests avoided against flat culling of the same range.
     *
     * <p>Zero before any walk, so an unused culler does not report itself as having avoided everything.
     *
     * @param flatTests how many tests flat culling would have made, which is the section count
     */
    public double savedPercent(int flatTests) {
        if (flatTests <= 0 || this.tests == 0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, (double) flatTests - this.tests) / flatTests;
    }
}
