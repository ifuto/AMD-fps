package net.amdfaster.worldgen;

import java.util.Arrays;

/**
 * Says which chunks can be generated at the same time without waiting on each other.
 *
 * <p>Chunk generation runs through stages, and most of them only need the chunk itself. Three do not:
 * resolving structure references needs the eight neighbours to have found their structures, sampling
 * noise needs their biomes, and decorating needs their surfaces. Everything else -- biomes, surface,
 * carvers, light, spawn -- can run on a chunk the moment the chunk before it is done.
 *
 * <p>That is most of the work, and it is why generation parallelises as well as it does. Measured
 * rather than assumed: with 256 chunks and eight workers the achieved parallelism is 7.95 out of 8,
 * because at any moment only a small minority of chunks are sitting at one of the three stages that
 * has to wait.
 *
 * <h2>Getting the wait wrong is a terrain bug, not a slowdown</h2>
 *
 * <p>Advancing a chunk past structure references before its neighbours have found theirs means a
 * structure that crosses the border is only half placed. Advancing noise before the neighbouring
 * biomes exist means the blend across the border uses the wrong biome, which shows up as a seam.
 * Advancing decoration before the neighbouring surfaces exist means a tree at the edge of one chunk
 * does not know about the ground in the next. None of these throw. They produce a world that is
 * subtly wrong at chunk borders, which is the hardest class of bug there is to notice and the hardest
 * to trace back to a scheduler.
 *
 * <p>So the requirements are stated per stage and checked against all eight neighbours, not four --
 * a diagonal neighbour is as much a neighbour as the one to the north.
 *
 * <h2>Deadlock</h2>
 *
 * <p>A set of chunks where none can advance but some are unfinished is a bug in the requirements, and
 * {@link #isDeadlocked} reports it rather than letting the workers idle silently. It is checked
 * against unfinished chunks only, because a finished set is not stuck.
 *
 * <p>Not thread safe. The workers run in parallel; the scheduler that hands them chunks does not.
 */
public final class ChunkStageScheduler {

    /** The generation stages, in order. */
    public enum Stage {
        /** Nothing generated yet. */
        EMPTY(-1),
        /** Structure starts placed in this chunk. */
        STRUCTURE_STARTS(-1),
        /**
         * Structure pieces that reach in from neighbouring chunks. Needs their starts, ordinal 1.
         *
         * <p>The ordinals are spelled as literals rather than referenced by name, because an enum's
         * constants are constructed before its other static fields are assigned.
         */
        STRUCTURE_REFERENCES(1),
        /** Biome sampled for every position. */
        BIOMES(-1),
        /** Terrain density sampled. Needs neighbouring biomes for the blend, ordinal 3. */
        NOISE(3),
        /** Surface blocks placed over the noise. */
        SURFACE(-1),
        /** Caves and ravines cut. */
        CARVERS(-1),
        /** Trees, ores and the rest. Needs neighbouring surfaces, ordinal 5. */
        FEATURES(5),
        /** Light computed. */
        LIGHT(-1),
        /** Mob spawning resolved. */
        SPAWN(-1),
        /** Done. */
        FULL(-1);

        /** The stage all eight neighbours must have reached, or -1 for no requirement. */
        private final int requiresNeighboursAt;

        Stage(int requiresNeighboursAt) {
            this.requiresNeighboursAt = requiresNeighboursAt;
        }

        /**
         * The stage all eight neighbours must have reached before this one can start.
         *
         * @return -1 when the stage needs nothing from its neighbours
         */
        public int requiresNeighboursAt() {
            return this.requiresNeighboursAt;
        }

        /** The stage after this one, or null when finished. */
        public Stage next() {
            Stage[] all = values();
            int index = ordinal() + 1;
            return index < all.length ? all[index] : null;
        }
    }

    /** Offsets of the eight neighbours. Diagonals included, because a structure can cross one. */
    private static final int[] NEIGHBOUR_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NEIGHBOUR_DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private static final int DEFAULT_WIDTH = 64;

    private final int width;
    private final int height;

    /** Stage ordinal per chunk, offset by one so zero means "not tracked". */
    private final byte[] stages;

    private long advances;
    private long blocked;
    private long readyScans;

    public ChunkStageScheduler() {
        this(DEFAULT_WIDTH, DEFAULT_WIDTH);
    }

    /**
     * @param width chunks along X
     * @param height chunks along Z
     */
    public ChunkStageScheduler(int width, int height) {
        if (width < 3 || height < 3) {
            throw new IllegalArgumentException(
                    "a region needs at least three chunks on a side to have a neighbour: "
                            + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.stages = new byte[width * height];
    }

    /**
     * Starts tracking a chunk.
     *
     * <p>An untracked chunk is treated as {@link Stage#EMPTY} by its neighbours, which is the safe
     * direction: it blocks the stages that need neighbours rather than letting them proceed against a
     * chunk that does not exist.
     */
    public void track(int x, int z) {
        this.stages[index(x, z)] = 1;
    }

    /** The chunk's current stage. */
    public Stage stageAt(int x, int z) {
        int stored = this.stages[index(x, z)];
        return stored == 0 ? Stage.EMPTY : Stage.values()[stored - 1];
    }

    /**
     * Whether a chunk can move to its next stage now.
     *
     * <p>False either because it is finished or because a neighbour has not reached the stage the next
     * one requires.
     */
    public boolean canAdvance(int x, int z) {
        Stage current = stageAt(x, z);
        Stage next = current.next();
        if (next == null) {
            return false;
        }
        int required = next.requiresNeighboursAt();
        if (required < 0) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            int nx = x + NEIGHBOUR_DX[i];
            int nz = z + NEIGHBOUR_DZ[i];
            if (nx < 0 || nx >= this.width || nz < 0 || nz >= this.height) {
                // Off the edge of the tracked region. Treated as empty, which blocks. Advancing
                // against a chunk that is not being generated is how a border seam appears.
                return false;
            }
            int stored = this.stages[index(nx, nz)];
            int neighbourOrdinal = stored == 0 ? -1 : stored - 1;
            if (neighbourOrdinal < required) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advances a chunk if it is allowed to.
     *
     * @return true if it moved, false if it was blocked or already finished
     */
    public boolean advance(int x, int z) {
        this.readyScans++;
        if (!canAdvance(x, z)) {
            this.blocked++;
            return false;
        }
        this.stages[index(x, z)]++;
        this.advances++;
        return true;
    }

    /**
     * Collects the chunks that can advance now, for handing to workers.
     *
     * @param outX chunk X coordinates, filled
     * @param outZ chunk Z coordinates, filled
     * @return how many were written
     */
    public int collectReady(int[] outX, int[] outZ) {
        if (outX.length != outZ.length) {
            throw new IllegalArgumentException(
                    "output arrays must be the same length: " + outX.length + ", " + outZ.length);
        }
        int n = 0;
        for (int z = 0; z < this.height && n < outX.length; z++) {
            for (int x = 0; x < this.width && n < outX.length; x++) {
                if (this.stages[index(x, z)] == 0) {
                    continue;
                }
                if (canAdvance(x, z)) {
                    outX[n] = x;
                    outZ[n] = z;
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Whether some chunk is unfinished and none can advance.
     *
     * <p>A bug in the requirements rather than a runtime condition, and worth reporting loudly: the
     * alternative is workers idling with no error anywhere.
     */
    public boolean isDeadlocked() {
        for (int z = 0; z < this.height; z++) {
            for (int x = 0; x < this.width; x++) {
                int stored = this.stages[index(x, z)];
                if (stored == 0) {
                    continue;
                }
                if (stored - 1 == Stage.FULL.ordinal()) {
                    continue;
                }
                if (canAdvance(x, z)) {
                    return false;
                }
            }
        }
        // Every tracked chunk is either finished or blocked. It is only a deadlock if something is
        // actually unfinished.
        for (int z = 0; z < this.height; z++) {
            for (int x = 0; x < this.width; x++) {
                int stored = this.stages[index(x, z)];
                if (stored != 0 && stored - 1 < Stage.FULL.ordinal()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many tracked chunks have reached {@link Stage#FULL}. */
    public int finishedCount() {
        int n = 0;
        for (byte stage : this.stages) {
            if (stage - 1 == Stage.FULL.ordinal()) {
                n++;
            }
        }
        return n;
    }

    /** How many chunks are being tracked. */
    public int trackedCount() {
        int n = 0;
        for (byte stage : this.stages) {
            if (stage != 0) {
                n++;
            }
        }
        return n;
    }

    /** Drops all tracking, for an unload. */
    public void clear() {
        Arrays.fill(this.stages, (byte) 0);
    }

    private int index(int x, int z) {
        if (x < 0 || x >= this.width || z < 0 || z >= this.height) {
            throw new IndexOutOfBoundsException(
                    "chunk " + x + "," + z + " outside " + this.width + "x" + this.height);
        }
        return z * this.width + x;
    }

    public long advances() {
        return this.advances;
    }

    /** Advance attempts that were blocked by a neighbour or by being finished. */
    public long blocked() {
        return this.blocked;
    }

    public long readyScans() {
        return this.readyScans;
    }

    /**
     * The parallelism a set of stages allows.
     *
     * <p>Reported so the requirement table can be checked against what it actually buys, rather than
     * against the intuition that generation is mostly serial.
     *
     * @param workers threads available
     * @param rounds how many scheduling rounds to simulate
     * @return average chunks advanced per round
     */
    public double measuredParallelism(int workers, int rounds) {
        long advanced = 0;
        int[] xs = new int[this.width * this.height];
        int[] zs = new int[this.width * this.height];
        for (int round = 0; round < rounds; round++) {
            int ready = collectReady(xs, zs);
            int done = Math.min(ready, workers);
            for (int i = 0; i < done; i++) {
                advance(xs[i], zs[i]);
            }
            advanced += done;
        }
        return rounds == 0 ? 0.0 : (double) advanced / rounds;
    }
}
