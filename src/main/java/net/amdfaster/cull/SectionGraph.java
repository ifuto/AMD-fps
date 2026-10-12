package net.amdfaster.cull;

/**
 * Finds which sections can be seen from the camera's section by walking face adjacency, stopping at
 * sealed faces.
 *
 * <p>This is the graph half of occlusion culling, and it is exact rather than conservative in the useful
 * direction. {@link SectionBoundaryMask} says whether one face blocks sight. Walking the adjacency graph
 * from the camera's section and refusing to cross a sealed face answers a much larger question: whether a
 * section is reachable at all. A section behind three walls of solid stone is not, and no amount of depth
 * buffer will tell you that more cheaply than six integer tests per section.
 *
 * <p>In a built world -- underground, indoors, in a city -- most of the loaded sections are unreachable
 * this way. That is the case where this pays for itself many times over, and it is also the case a
 * distance-based culler cannot help with at all, because those sections are close.
 *
 * <h2>Why the walk is bounded twice</h2>
 *
 * <p>By depth and by visit count. Depth because visibility through a long chain of doorways falls off and
 * the cost of finding the last few does not; visits because a pathological world with no sealed faces at
 * all would otherwise walk every loaded section every frame, which is more work than the culling saves.
 * Either bound being hit is reported, so it is possible to tell a world that is genuinely open from a
 * budget that is too small.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not consider partial openings. A face with one glass block in it is not sealed, so the walk
 * continues through it as though it were an open doorway. That makes the result conservative in the safe
 * direction: it can keep a section that is not actually visible, and it cannot drop one that is.
 *
 * <p>Not thread safe.
 */
public final class SectionGraph {

    /** Supplies the boundary mask for a section, or null if it is not loaded. */
    public interface SectionSource {
        SectionBoundaryMask maskAt(int sectionX, int sectionY, int sectionZ);
    }

    /**
     * How far the walk will go.
     *
     * <p>Eight sections is 128 blocks, which is further than most sight lines through buildings reach.
     */
    public static final int DEFAULT_MAX_DEPTH = 8;

    /**
     * How many sections the walk will visit in one query.
     *
     * <p>Two thousand is generous for a bounded depth and cheap enough to run every frame.
     */
    public static final int DEFAULT_MAX_VISITS = 2048;

    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};

    private final int maxDepth;
    private final int maxVisits;

    private int visits;
    private int reached;
    private int blockedBySealedFace;
    private int blockedByUnloaded;
    private int hitDepthLimit;
    private int hitVisitLimit;

    public SectionGraph() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_VISITS);
    }

    public SectionGraph(int maxDepth, int maxVisits) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("depth limit must be at least one: " + maxDepth);
        }
        if (maxVisits < 1) {
            throw new IllegalArgumentException("visit limit must be at least one: " + maxVisits);
        }
        this.maxDepth = maxDepth;
        this.maxVisits = maxVisits;
    }

    public int maxDepth() {
        return this.maxDepth;
    }

    public int maxVisits() {
        return this.maxVisits;
    }

    /**
     * Marks every section reachable from the camera's section.
     *
     * @param source where to read boundary masks from
     * @param originX the camera's section
     * @param originY the camera's section
     * @param originZ the camera's section
     * @param sink receives one call per reachable section, including the origin
     * @return how many sections were reached
     */
    public int walk(SectionSource source, int originX, int originY, int originZ, Sink sink) {
        resetCounters();
        if (source.maskAt(originX, originY, originZ) == null) {
            this.blockedByUnloaded++;
            return 0;
        }

        // A breadth-first walk with the depth carried alongside each entry. A depth-ordered queue would
        // let the depth be inferred from position, but the queue would then have to be sized by depth
        // rather than by the visit budget, and the visit budget is the bound that has to hold.
        int[] queueX = new int[this.maxVisits + 1];
        int[] queueY = new int[this.maxVisits + 1];
        int[] queueZ = new int[this.maxVisits + 1];
        int[] queueDepth = new int[this.maxVisits + 1];
        int head = 0;
        int tail = 0;

        queueX[tail] = originX;
        queueY[tail] = originY;
        queueZ[tail] = originZ;
        queueDepth[tail] = 0;
        tail++;

        // Visited tracking is a hash set of packed coordinates. Sections outside the coordinate range the
        // packing supports are simply revisited, which costs time and not correctness.
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        visited.add(pack(originX, originY, originZ));

        while (head < tail) {
            if (this.visits >= this.maxVisits) {
                this.hitVisitLimit++;
                break;
            }
            int x = queueX[head];
            int y = queueY[head];
            int z = queueZ[head];
            int depth = queueDepth[head];
            head++;
            this.visits++;

            sink.accept(x, y, z, depth);
            this.reached++;

            if (depth >= this.maxDepth) {
                this.hitDepthLimit++;
                continue;
            }

            SectionBoundaryMask mask = source.maskAt(x, y, z);
            if (mask == null) {
                continue;
            }

            for (int face = 0; face < SectionBoundaryMask.FACES; face++) {
                if (mask.hidesNeighbour(face)) {
                    this.blockedBySealedFace++;
                    continue;
                }
                int nx = x + DX[face];
                int ny = y + DY[face];
                int nz = z + DZ[face];
                long key = pack(nx, ny, nz);
                if (!visited.add(key)) {
                    continue;
                }
                if (source.maskAt(nx, ny, nz) == null) {
                    this.blockedByUnloaded++;
                    continue;
                }
                if (tail == queueX.length) {
                    this.hitVisitLimit++;
                    break;
                }
                queueX[tail] = nx;
                queueY[tail] = ny;
                queueZ[tail] = nz;
                queueDepth[tail] = depth + 1;
                tail++;
            }
        }
        return this.reached;
    }

    /** Receives one reachable section. */
    public interface Sink {
        void accept(int sectionX, int sectionY, int sectionZ, int depth);
    }

    /**
     * Packs section coordinates into a long for the visited set.
     *
     * <p>21 bits per axis, the same width the rest of the mod uses, which covers a little over a million
     * blocks in each direction.
     */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    public int visits() {
        return this.visits;
    }

    public int reached() {
        return this.reached;
    }

    /** Face transitions refused because the face was sealed. This is the work the culling saved. */
    public int blockedBySealedFace() {
        return this.blockedBySealedFace;
    }

    public int blockedByUnloaded() {
        return this.blockedByUnloaded;
    }

    /** Non-zero if the depth limit stopped the walk, which may mean it is set too low. */
    public int hitDepthLimit() {
        return this.hitDepthLimit;
    }

    /** Non-zero if the visit limit stopped the walk, which may mean it is set too low. */
    public int hitVisitLimit() {
        return this.hitVisitLimit;
    }

    public void resetCounters() {
        this.visits = 0;
        this.reached = 0;
        this.blockedBySealedFace = 0;
        this.blockedByUnloaded = 0;
        this.hitDepthLimit = 0;
        this.hitVisitLimit = 0;
    }
}
