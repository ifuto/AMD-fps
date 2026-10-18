package net.amdfaster.mesh;

/**
 * Picks the level of detail a section is streamed at, and the order the streaming queue drains in.
 *
 * <p>From Aokana: A GPU-Driven Voxel Rendering Framework for Open World Games, arXiv:2505.02017 (2025),
 * ACM DOI 10.1145/3728299, equation 1. The paper is a GPU-driven voxel renderer that names Minecraft as
 * its point of comparison, and its streaming rule is the part that transfers cleanly:
 *
 * <pre>
 *   LOD Error = (ChunkSize x StreamingFactor) - ||ChunkCenterPos - CameraPos||
 * </pre>
 *
 * <p>A positive error means refine -- the node is close enough that its size on screen justifies spending
 * on children. A negative error means this level is good enough and the node is a load target. Aokana
 * aggregates eight level {@code n-1} nodes into one level {@code n}, so a node's size doubles per level,
 * and the error grows with the level: refining from coarse stops at the first level whose error is not
 * positive.
 *
 * <h2>Why this is not the same decision as the merge level</h2>
 *
 * <p>{@link LodMerger#levelForDistance} answers a different question. It picks how coarsely to merge a
 * section's face into quads, and its thresholds -- 0, 96, 192, 320, 512 -- are hand tuned and not a
 * geometric progression. Equation 1's thresholds are {@code 16 x 2^L x StreamingFactor}, which is a
 * progression, because it comes from a node hierarchy rather than from a table. Feeding one into the
 * other would change the merge behaviour this class has no business touching. This one decides what to
 * <em>load</em> and at what size; that one decides how many quads a loaded section emits.
 *
 * <h2>Coarse first, and why that is the point</h2>
 *
 * <p>One level-1 node stands in for eight level-0 sections, one level-2 for sixty-four, one level-4 for
 * four thousand ninety-six. Loading the coarsest pending node therefore frees the most memory per load,
 * which is what Aokana means by prioritising high LOD levels: releasing a parent's children in one move
 * rather than one at a time. A queue that drains nearest-first does the opposite -- it fills in fine
 * detail at the player's feet while the horizon stays empty and the memory that would have shown the
 * horizon is held by nodes nobody can resolve.
 *
 * <h2>The node the camera is inside</h2>
 *
 * <p>The paper is explicit that this case inverts. For every node except the one containing the camera,
 * the camera is outside the bounding box, so the depth of the intersection with the box is <em>less</em>
 * than the real depth, which culls conservatively and cannot remove anything visible. For the node the
 * camera is inside, the box intersection depth is <em>greater</em> than the real depth, which culls
 * tiles that are in fact on screen. So the self node is reported separately and must never be tested
 * against the bounding box -- it is the one case where the cheap test is wrong rather than merely
 * pessimistic.
 *
 * <p>Not thread safe.
 */
public final class LodStreamScheduler {

    /** A level-0 node is one section. */
    public static final int SECTION_SIZE = 16;

    /** The deepest node, matching {@link LodMerger#MAX_LEVEL}. */
    public static final int MAX_LEVEL = 4;

    /** Children one node aggregates: eight, being 2 x 2 x 2. */
    public static final int CHILDREN_PER_NODE = 8;

    /**
     * How much further than a node's own size it is still worth refining.
     *
     * <p>A factor of 1 refines exactly to the node's edge, which pops: a node refines the moment the
     * player crosses a boundary and the children appear in one frame. Two holds the coarse level out to
     * twice its size, so the change happens while the node is small on screen. Larger trades memory for
     * a longer view of fine detail.
     */
    public static final double DEFAULT_STREAMING_FACTOR = 2.0;

    private final double streamingFactor;

    private long refinements;
    private long loadTargets;
    private long selfNodes;
    private long sectionsReplaced;

    public LodStreamScheduler() {
        this(DEFAULT_STREAMING_FACTOR);
    }

    /**
     * @param streamingFactor how many times a node's size it is still worth refining, from
     *     {@link #DEFAULT_STREAMING_FACTOR}'s reasoning. Must be positive: zero or less makes every
     *     error negative and nothing ever refines, which is a world that stays coarse at any distance.
     */
    public LodStreamScheduler(double streamingFactor) {
        if (!(streamingFactor > 0.0) || Double.isNaN(streamingFactor)
                || Double.isInfinite(streamingFactor)) {
            throw new IllegalArgumentException(
                    "the streaming factor must be a positive number: " + streamingFactor);
        }
        this.streamingFactor = streamingFactor;
    }

    /** The streaming factor this scheduler was built with. */
    public double streamingFactor() {
        return this.streamingFactor;
    }

    /** How wide a node at this level is, in blocks. */
    public static int chunkSize(int level) {
        checkLevel(level);
        return SECTION_SIZE << level;
    }

    /**
     * Equation 1, in blocks rather than in pixels.
     *
     * <p>Positive means refine, negative means this level is good enough. Zero is treated as
     * "good enough" and not as "refine", because refining at exactly the threshold would make the level
     * change on the frame the player crosses it, and holding one frame longer costs nothing visible.
     *
     * @param level the node's level
     * @param distance how far the node's centre is from the camera, in blocks
     * @return the LOD error in blocks
     */
    public double error(int level, double distance) {
        checkLevel(level);
        if (Double.isNaN(distance) || distance < 0.0) {
            throw new IllegalArgumentException("distance must be a real, non-negative number: " + distance);
        }
        return (double) chunkSize(level) * this.streamingFactor - distance;
    }

    /** Whether a node at this level and distance should be split into its children. */
    public boolean shouldRefine(int level, double distance) {
        boolean refine = error(level, distance) > 0.0;
        if (refine) {
            this.refinements++;
        } else {
            this.loadTargets++;
        }
        return refine;
    }

    /**
     * The level to stream a section at, given how far away it is.
     *
     * <p>The coarsest level whose error is still not positive. Errors grow with the level, so this is the
     * point where refining stops rather than the point where it starts, and it is clamped to
     * {@link #MAX_LEVEL} because the hierarchy has no deeper node to refine into.
     *
     * @param distance how far the section is, in blocks
     * @return the level, 0 at close range rising to {@link #MAX_LEVEL}
     */
    public int levelFor(double distance) {
        if (Double.isNaN(distance) || distance < 0.0) {
            throw new IllegalArgumentException("distance must be a real, non-negative number: " + distance);
        }
        int selected = 0;
        for (int level = 0; level <= MAX_LEVEL; level++) {
            if ((double) chunkSize(level) * this.streamingFactor <= distance) {
                selected = level;
            }
        }
        return selected;
    }

    /**
     * How many level-0 sections one node at this level stands in for.
     *
     * <p>Eight to the level. This is the number that makes coarse-first worth doing: loading one level-4
     * node retires four thousand ninety-six sections' worth of streaming work, and the memory they would
     * have held goes back before the player can resolve any of it.
     */
    public static int sectionsReplaced(int level) {
        checkLevel(level);
        return 1 << (level * 3);
    }

    /**
     * Records that a node at this level was loaded, retiring the sections it stands in for.
     *
     * @param level the node's level
     * @return how many level-0 sections it retired
     */
    public int load(int level) {
        int replaced = sectionsReplaced(level);
        this.sectionsReplaced += replaced;
        return replaced;
    }

    /**
     * Marks a node as the one the camera is inside.
     *
     * <p>Callers must not test it against a bounding box afterwards. Every other node's box intersection
     * is nearer than the geometry, which is conservative; the self node's is farther, which culls tiles
     * that are visible. The count is kept so the wiring can be checked rather than trusted.
     */
    public void markSelf() {
        this.selfNodes++;
    }

    /**
     * The order to drain a pending queue in: coarsest first, and nearest first within a level.
     *
     * <p>Returned as a single comparable so a priority queue can use it directly. Level dominates, so a
     * distant coarse node outranks a near fine one -- which is the inversion a nearest-first queue gets
     * wrong, and the reason the horizon stays empty while the ground fills in.
     *
     * @param level the node's level
     * @param distance how far it is, in blocks
     * @return a key where a smaller value means "load sooner"
     */
    public static long streamPriority(int level, double distance) {
        checkLevel(level);
        if (Double.isNaN(distance) || distance < 0.0) {
            throw new IllegalArgumentException("distance must be a real, non-negative number: " + distance);
        }
        // Level in the high bits, inverted so coarse sorts first; distance below it, scaled to an int so
        // a float does not end up in a key that has to compare exactly.
        long coarseFirst = (long) (MAX_LEVEL - level);
        long near = (long) Math.min(distance, (double) Integer.MAX_VALUE);
        return (coarseFirst << 32) | near;
    }

    /** How many times a node was judged worth refining. */
    public long refinements() {
        return this.refinements;
    }

    /** How many times a node was judged good enough to load at its own level. */
    public long loadTargets() {
        return this.loadTargets;
    }

    /** How many nodes were marked as containing the camera. */
    public long selfNodes() {
        return this.selfNodes;
    }

    /** How many level-0 sections' worth of streaming the loaded nodes retired. */
    public long sectionsReplaced() {
        return this.sectionsReplaced;
    }

    private static void checkLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be 0.." + MAX_LEVEL + ": " + level);
        }
    }
}
