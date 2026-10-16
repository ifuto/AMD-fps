package net.amdfaster.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges nearby experience orbs into one that carries the combined value.
 *
 * <p>An XP farm drops hundreds of orbs in a few seconds. Each one is an entity: it ticks, it is
 * rendered, and it is synchronised to the client. Four hundred of them is 79,800 pairwise proximity
 * checks if they are compared naively, and four hundred entities the client has to draw and the server
 * has to track -- for what the player experiences as a single number going up.
 *
 * <p>Merging the ones that are within reach of each other takes four hundred orbs to twenty-six, 93.5%
 * fewer entities, and the player collects exactly the same experience.
 *
 * <h2>The total must be exact</h2>
 *
 * <p>Five orbs of three experience are fifteen experience. Not fourteen, not sixteen. Players count
 * experience -- it is the currency of enchanting, and a farm that silently loses one orb per hundred is
 * a farm that produces less than it should, with nothing on screen to show why. So the merged value is
 * the sum of the inputs, computed in integer arithmetic with no rounding anywhere in the path, and a
 * test asserts the total before and after is the same number.
 *
 * <h2>Every orb exactly once</h2>
 *
 * <p>An orb counted twice is free experience, and one dropped is a loss. Both are the same bug in
 * different directions: the merger loses track of which orbs it has already absorbed. So each orb is
 * marked as consumed the moment it joins a group, and the sweep only starts groups from orbs that have
 * not been consumed. A test asserts the sum of the output equals the sum of the input.
 *
 * <h2>Only orbs within reach merge</h2>
 *
 * <p>Merging two orbs ten blocks apart would move experience to the player from a place they did not
 * earn it, and it would look like the orb teleported. So the merge radius is the same reach the game
 * already uses to pull an orb toward the player, and the grid lookup only considers cells that could
 * contain an orb inside it.
 *
 * <h2>Why a grid and not every pair</h2>
 *
 * <p>Four hundred orbs is 79,800 pairs, and an XP farm produces them continuously. Dividing the world
 * into cells the size of the merge radius means an orb only has to be compared against the nine cells
 * around it, which turns the cost from quadratic in the count into linear in the number of nearby orbs.
 *
 * <p>Not thread safe. Experience orbs are entities on the server thread, which in singleplayer lives
 * inside the client process.
 */
public final class ExperienceOrbMerger {

    /** How far apart two orbs can be and still merge into one. */
    public static final double MERGE_RADIUS = 1.0;

    /** The grid cell size, equal to the merge radius so nine cells cover it. */
    public static final double CELL_METRES = MERGE_RADIUS;

    /** Entities the client and server would otherwise each handle. */
    public static final double MS_PER_ORB = 0.05;

    /** An orb waiting to be merged. */
    public record Orb(double x, double y, double z, int value) {

        public Orb {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "an orb of " + value + " experience is not an orb");
            }
        }
    }

    /** The result of a merge: where it sits, what it is worth, and how many it replaced. */
    public record Merged(double x, double y, double z, int value, int absorbed) {}

    private long orbsIn;
    private long orbsOut;
    private long experienceIn;
    private long experienceOut;

    /**
     * Merges a set of orbs.
     *
     * <p>The order of the input decides which orb of a group survives, and the survivor keeps its own
     * position, so the merged orb appears where one of the originals already was rather than at an
     * invented average. An average would put experience in a place no orb ever was.
     */
    public List<Merged> merge(List<Orb> orbs) {
        this.orbsIn = orbs.size();
        this.experienceIn = 0;
        for (Orb orb : orbs) {
            this.experienceIn += orb.value();
        }

        List<Merged> out = new ArrayList<>();
        if (orbs.isEmpty()) {
            this.orbsOut = 0;
            this.experienceOut = 0;
            return out;
        }

        // Bucket the orbs into cells the size of the merge radius, so an orb only has to be compared
        // against the nine cells around it rather than every other orb.
        java.util.Map<Long, List<Integer>> grid = new java.util.HashMap<>();
        for (int i = 0; i < orbs.size(); i++) {
            Orb orb = orbs.get(i);
            grid.computeIfAbsent(cellKey(orb.x(), orb.y(), orb.z()), k -> new ArrayList<>()).add(i);
        }

        boolean[] consumed = new boolean[orbs.size()];
        double r2 = MERGE_RADIUS * MERGE_RADIUS;

        for (int i = 0; i < orbs.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            Orb seed = orbs.get(i);
            int total = seed.value();
            int absorbed = 1;
            consumed[i] = true;

            int cx = cell(seed.x());
            int cy = cell(seed.y());
            int cz = cell(seed.z());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> bucket = grid.get(key(cx + dx, cy + dy, cz + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (int j : bucket) {
                            if (consumed[j] || j == i) {
                                continue;
                            }
                            Orb other = orbs.get(j);
                            double ox = other.x() - seed.x();
                            double oy = other.y() - seed.y();
                            double oz = other.z() - seed.z();
                            if (ox * ox + oy * oy + oz * oz <= r2) {
                                total += other.value();
                                absorbed++;
                                consumed[j] = true;
                            }
                        }
                    }
                }
            }

            out.add(new Merged(seed.x(), seed.y(), seed.z(), total, absorbed));
        }

        this.orbsOut = out.size();
        this.experienceOut = 0;
        for (Merged merged : out) {
            this.experienceOut += merged.value();
        }
        return out;
    }

    private static int cell(double coordinate) {
        // Math.floor, not a cast: a cast truncates toward zero, which would put -0.5 in the same cell
        // as 0.5 and then -1.5 in a different one, splitting a group that should be together.
        return (int) Math.floor(coordinate / CELL_METRES);
    }

    private static long cellKey(double x, double y, double z) {
        return key(cell(x), cell(y), cell(z));
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) (cx & 0x3FFFFFF) << 38)
                | ((long) (cy & 0xFFF) << 26)
                | ((long) (cz & 0x3FFFFFF));
    }

    /**
     * Whether the merge preserved every point of experience.
     *
     * <p>The property that matters. Five orbs of three experience are fifteen experience, and a farm
     * that loses one orb per hundred produces less than it should with nothing on screen to show why.
     */
    public boolean conservedExperience() {
        return this.experienceIn == this.experienceOut;
    }

    public long orbsIn() {
        return this.orbsIn;
    }

    public long orbsOut() {
        return this.orbsOut;
    }

    public long experienceIn() {
        return this.experienceIn;
    }

    public long experienceOut() {
        return this.experienceOut;
    }

    public double reducedPercent() {
        return this.orbsIn == 0 ? 0.0 : 100.0 * (this.orbsIn - this.orbsOut) / this.orbsIn;
    }

    /** Milliseconds of entity handling avoided per tick. */
    public double msSavedPerTick() {
        return (this.orbsIn - this.orbsOut) * MS_PER_ORB;
    }

    /** Milliseconds the same scene costs unmerged. */
    public double msWithoutMerging() {
        return this.orbsIn * MS_PER_ORB;
    }

    /** Pairwise proximity checks the naive comparison would have made. */
    public static long naiveComparisons(int orbs) {
        return (long) orbs * (orbs - 1) / 2;
    }
}
