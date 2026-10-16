package net.amdfaster.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the points of interest near a villager, and makes sure two villagers never claim the same one.
 *
 * <p>Villagers look for a bed, a workstation and a bell every tick, and the vanilla search walks the
 * points of interest in range. Thirty villagers over ninety points is 2,700 checks a tick, 54,000 a
 * second, and most of them are for a point on the far side of the village that this villager was never
 * going to use.
 *
 * <p>Bucketing by position cuts the checks to the cells within reach, and the reservation table is what
 * makes the rest safe.
 *
 * <h2>Two failures, and neither is about speed</h2>
 *
 * <p><b>Missing a point that could have been claimed.</b> A villager that cannot find a bed does not
 * sleep, does not breed, and stands in the road all night. That is a gameplay bug the player will
 * notice and will not connect to a renderer mod. So the search span is derived from the radius as
 * {@code ceil(radius / cell)}, not assumed to be the immediate neighbours, and a radius larger than the
 * cell is covered.
 *
 * <p><b>Claiming the same point twice.</b> Two villagers reserved to one bed means one of them does not
 * sleep, and the visible symptom is a villager wandering at night with no obvious cause. The reservation
 * is therefore a claim with a single owner, refused for anyone else until released -- not a count, and
 * not a last-writer-wins map.
 *
 * <p>Not thread safe. Villager AI runs on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class PoiIndex {

    /** Blocks per bucket. */
    public static final int CELL_BLOCKS = 16;

    /** How far a villager searches for a point of interest. */
    public static final int SEARCH_RADIUS = 32;

    private static final int BUCKET_MASK = 0xFFFFF;

    /** Point of interest kinds. */
    public static final int KIND_BED = 0;
    public static final int KIND_WORKSTATION = 1;
    public static final int KIND_OTHER = 2;

    private final Map<Long, List<Poi>> buckets = new HashMap<>();
    private final Map<Integer, Integer> claimedBy = new HashMap<>();

    private long checks;
    private long claims;
    private long refusedClaims;

    /** A point of interest: where it is, what it is, and who holds it. */
    public record Poi(int id, int kind, int x, int y, int z) {}

    public void add(Poi poi) {
        this.buckets.computeIfAbsent(bucketOf(poi.x(), poi.y(), poi.z()), k -> new ArrayList<>())
                .add(poi);
    }

    public void remove(Poi poi) {
        long key = bucketOf(poi.x(), poi.y(), poi.z());
        List<Poi> bucket = this.buckets.get(key);
        if (bucket != null) {
            bucket.removeIf(p -> p.id() == poi.id());
            if (bucket.isEmpty()) {
                this.buckets.remove(key);
            }
        }
        release(poi.id());
    }

    /**
     * The points of interest within search range of a position.
     *
     * <p>Conservative: it may return points too far away, and the caller's distance check removes them.
     * It must never omit one that is in range.
     */
    public List<Poi> near(int x, int y, int z, int kind) {
        int span = span();
        int cx = Math.floorDiv(x, CELL_BLOCKS);
        int cy = Math.floorDiv(y, CELL_BLOCKS);
        int cz = Math.floorDiv(z, CELL_BLOCKS);

        List<Poi> found = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    List<Poi> bucket = this.buckets.get(pack(cx + dx, cy + dy, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (Poi poi : bucket) {
                        this.checks++;
                        if (poi.kind() == kind) {
                            found.add(poi);
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * How many cells out the search reaches.
     *
     * <p>Derived from the radius. A radius of 32 over 16-block cells has to reach two cells out, and
     * hard-coding one would silently drop every bed just past the first ring -- which is most of a
     * village.
     */
    public static int span() {
        return (int) Math.ceil((double) SEARCH_RADIUS / CELL_BLOCKS);
    }

    /**
     * Claims a point for a villager.
     *
     * @return false if another villager already holds it. The caller must then keep looking; taking it
     *     anyway is how two villagers end up on one bed.
     */
    public boolean claim(int poiId, int villagerId) {
        Integer holder = this.claimedBy.get(poiId);
        if (holder != null && holder != villagerId) {
            this.refusedClaims++;
            return false;
        }
        this.claimedBy.put(poiId, villagerId);
        if (holder == null) {
            this.claims++;
        }
        return true;
    }

    /** Releases a claim, for a villager that died, moved away or lost its profession. */
    public void release(int poiId) {
        this.claimedBy.remove(poiId);
    }

    /** Releases everything a villager held, which is what a death or a departure needs. */
    public void releaseAllBy(int villagerId) {
        this.claimedBy.values().removeIf(holder -> holder == villagerId);
    }

    public boolean isClaimed(int poiId) {
        return this.claimedBy.containsKey(poiId);
    }

    /** The villager holding a point, or -1. */
    public int holderOf(int poiId) {
        return this.claimedBy.getOrDefault(poiId, -1);
    }

    /** A point nobody holds, nearest first among the candidates given. */
    public Poi nearestFree(List<Poi> candidates, int x, int y, int z) {
        Poi best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Poi poi : candidates) {
            if (isClaimed(poi.id())) {
                continue;
            }
            long dx = poi.x() - x;
            long dy = poi.y() - y;
            long dz = poi.z() - z;
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = poi;
            }
        }
        return best;
    }

    public static long bucketOf(int x, int y, int z) {
        // floorDiv, not truncation, so negative coordinates share a bucket with their neighbours rather
        // than being pulled toward the origin.
        long bx = Math.floorDiv(x, CELL_BLOCKS) & BUCKET_MASK;
        long by = Math.floorDiv(y, CELL_BLOCKS) & 0xFF;
        long bz = Math.floorDiv(z, CELL_BLOCKS) & BUCKET_MASK;
        return (bx << 28) | (by << 20) | bz;
    }

    private static long pack(int cx, int cy, int cz) {
        return (((long) cx) & BUCKET_MASK) << 28 | (((long) cy) & 0xFF) << 20
                | (((long) cz) & BUCKET_MASK);
    }

    public void clear() {
        this.buckets.clear();
        this.claimedBy.clear();
    }

    public int poiCount() {
        int n = 0;
        for (List<Poi> bucket : this.buckets.values()) {
            n += bucket.size();
        }
        return n;
    }

    public long checks() {
        return this.checks;
    }

    public long claims() {
        return this.claims;
    }

    public long refusedClaims() {
        return this.refusedClaims;
    }

    /** Checks a scan of every point for every villager would cost. */
    public long checksWithoutIndex(int villagers) {
        return (long) villagers * poiCount();
    }

    public double savedPercent(int villagers) {
        long without = checksWithoutIndex(villagers);
        if (without == 0) {
            return 0.0;
        }
        return 100.0 * (without - this.checks) / without;
    }
}
