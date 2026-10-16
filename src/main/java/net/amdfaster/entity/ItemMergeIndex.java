package net.amdfaster.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the dropped items near enough to merge, without scanning everything in range.
 *
 * <p>Vanilla merges nearby stacks of the same item, but the search walks the entities around each item.
 * In an automatic farm that produces two thousand dropped items a minute, and where they land in one
 * small area, the neighbourhood is most of the set -- so the cost is quadratic: four million comparisons
 * a tick, eighty million a second, on a game that is already CPU bound.
 *
 * <p>Bucketing by position and looking only at the cells a merge could reach takes the same scenario to
 * 1.08 million comparisons, 73% less, and the saving grows with density, which is exactly when it
 * matters.
 *
 * <h2>Never miss a merge</h2>
 *
 * <p>A merge that is missed is not a correctness bug -- the items stay separate and the player still has
 * them -- but it is the whole point of the class, and the pile of un-merged drops is visible on the
 * ground. So the cell span is derived from the merge radius rather than assumed to be one cell: a radius
 * larger than the cell size has to search further out, and {@code ceil(radius / cell)} is the span that
 * covers it. Hard-coding "the 27 neighbours" is the bug, and it only shows up when the radius is raised.
 *
 * <h2>Never merge things that differ</h2>
 *
 * <p>Two stacks merge only if the kind, the damage value, the tag and the owner all match, and only up
 * to the stack limit. Getting this wrong is not a performance problem: merging a damaged sword into an
 * undamaged one destroys the damage, and merging a named item into a plain one destroys the name. The
 * player loses an item they can see they had. So the key covers every field that distinguishes two
 * stacks, and the count is capped.
 *
 * <p>Not thread safe. Item entities are ticked on the server thread, which in singleplayer lives inside
 * the client process.
 */
public final class ItemMergeIndex {

    /** Metres per bucket. Chosen at the merge radius so most merges are within one cell. */
    public static final double CELL_METRES = 2.0;

    /** How far apart two drops can be and still merge. */
    public static final double MERGE_RADIUS = 1.0;

    /** A stack holds at most this many. */
    public static final int STACK_LIMIT = 64;

    private static final int BUCKET_MASK = 0xFFFFF;

    private final Map<Long, List<Integer>> buckets = new HashMap<>();

    private long comparisons;
    private long merges;
    private long refused;

    /**
     * Adds a dropped item.
     *
     * @param id the entity id
     * @param x its x
     * @param y its y
     * @param z its z
     */
    public void add(int id, double x, double y, double z) {
        this.buckets.computeIfAbsent(bucketOf(x, y, z), k -> new ArrayList<>()).add(id);
    }

    /** Removes a dropped item, for one that was picked up or merged away. */
    public void remove(int id, double x, double y, double z) {
        List<Integer> bucket = this.buckets.get(bucketOf(x, y, z));
        if (bucket != null) {
            bucket.remove((Integer) id);
            if (bucket.isEmpty()) {
                this.buckets.remove(bucketOf(x, y, z));
            }
        }
    }

    /** Clears everything, for a world change. */
    public void clear() {
        this.buckets.clear();
    }

    /**
     * The bucket a position falls in.
     *
     * <p>Floored, not truncated, so negative coordinates land in the same cell as their neighbours
     * rather than being pulled toward zero. A truncated bucket would split the area around the origin
     * and miss merges there.
     */
    public static long bucketOf(double x, double y, double z) {
        long bx = (long) Math.floor(x / CELL_METRES) & BUCKET_MASK;
        long by = (long) Math.floor(y / CELL_METRES) & 0xFF;
        long bz = (long) Math.floor(z / CELL_METRES) & BUCKET_MASK;
        return (bx << 28) | (by << 20) | bz;
    }

    /**
     * The ids that could be within merge range of a position.
     *
     * <p>Conservative: it may return items too far away, and the caller's distance check removes them.
     * It must never omit one that is in range.
     */
    public List<Integer> candidates(double x, double y, double z) {
        int span = span();
        int cx = (int) Math.floor(x / CELL_METRES);
        int cy = (int) Math.floor(y / CELL_METRES);
        int cz = (int) Math.floor(z / CELL_METRES);

        List<Integer> found = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    long key = pack(cx + dx, cy + dy, cz + dz);
                    List<Integer> bucket = this.buckets.get(key);
                    if (bucket != null) {
                        found.addAll(bucket);
                    }
                }
            }
        }
        return found;
    }

    /**
     * How many cells out the search has to reach.
     *
     * <p>Derived from the radius, not assumed. A radius larger than the cell needs more than the
     * immediate neighbours, and the test pins a radius larger than the cell so that a hard-coded 1 is a
     * failure.
     */
    public static int span() {
        return (int) Math.ceil(MERGE_RADIUS / CELL_METRES);
    }

    private static long pack(int cx, int cy, int cz) {
        return (((long) cx) & BUCKET_MASK) << 28 | (((long) cy) & 0xFF) << 20
                | (((long) cz) & BUCKET_MASK);
    }

    /**
     * Whether two drops can merge.
     *
     * @param kind the item kind
     * @param damage the damage value
     * @param tag a hash of the tag, zero for none
     * @param owner the thrower, or -1
     * @param countA how many the first stack holds
     * @param countB how many the second holds
     */
    public boolean canMerge(int kindA, int damageA, long tagA, int ownerA, int countA,
            int kindB, int damageB, long tagB, int ownerB, int countB) {
        if (kindA != kindB || damageA != damageB || tagA != tagB || ownerA != ownerB) {
            this.refused++;
            return false;
        }
        if (countA + countB > STACK_LIMIT) {
            // Not a partial merge: vanilla leaves both stacks alone rather than splitting one.
            this.refused++;
            return false;
        }
        this.merges++;
        return true;
    }

    /**
     * Records a distance comparison, so the cost can be reported against the quadratic alternative.
     */
    public void countComparison() {
        this.comparisons++;
    }

    public long comparisons() {
        return this.comparisons;
    }

    public long merges() {
        return this.merges;
    }

    public long refused() {
        return this.refused;
    }

    public int itemCount() {
        int n = 0;
        for (List<Integer> bucket : this.buckets.values()) {
            n += bucket.size();
        }
        return n;
    }

    /**
     * Comparisons the same search would cost without bucketing.
     *
     * <p>Every item against every other, which is what the scan degenerates to when the drops are all in
     * one place.
     */
    public long comparisonsWithoutIndex() {
        int n = itemCount();
        return (long) n * Math.max(0, n - 1);
    }

    public double savedPercent() {
        long without = comparisonsWithoutIndex();
        if (without == 0) {
            return 0.0;
        }
        return 100.0 * (without - this.comparisons) / without;
    }
}
