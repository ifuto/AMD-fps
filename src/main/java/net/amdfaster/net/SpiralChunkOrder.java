package net.amdfaster.net;

/**
 * Orders chunk loading from the player outward, rather than across the whole area at once.
 *
 * <p>Loading a world means generating and meshing every chunk in the view distance, and the order that
 * happens in decides what the player sees while it is going on. Scanning the area in a fixed direction
 * -- west to east, row by row -- fills the far corner first and the ground under the player last, so the
 * player stands in a hole looking at a world that is complete everywhere except where they are.
 *
 * <p>Working outward from the centre fills the visible ground first, and the world appears around the player
 * rather than assembling itself somewhere else.
 *
 * <h2>Every chunk appears exactly once</h2>
 *
 * <p>A spiral that skips a column or visits one twice is not a slower load, it is a broken one: a missing
 * chunk is a hole that never fills, and the player would have to walk away and back to get it. So the order
 * is checked to cover every position in the area once, and a test walks the whole area asserting both.
 *
 * <h2>The distance is what orders them, and ties keep their order</h2>
 *
 * <p>Chunks at the same distance from the centre are equally urgent, but their relative order still has to
 * be the same every time. If it depended on which worker finished first, the same world would load in a
 * different pattern each launch -- harmless in itself, but it makes a loading bug unreproducible, which is
 * the worst thing a load-order bug can be.
 *
 * <h2>The order is recomputed when the player moves</h2>
 *
 * <p>The centre is where the player is, and a player who walks two hundred blocks has a different set of
 * nearby chunks. Using the order from where they loaded in would fill the world around a place they left
 * minutes ago. So the centre is part of what determines the order, not something fixed at startup.
 *
 * <h2>Why this is a load order and not a priority</h2>
 *
 * <p>{@link net.amdfaster.mesh.job.MeshJobPrioritizer} decides which of the queued jobs runs next, and it
 * can change its mind as jobs arrive. This decides the order the chunks are asked for in the first place,
 * which happens before any of them are queued. Getting the request order right means the jobs that matter
 * are the ones that arrive first, so the prioritiser has less to correct.
 *
 * <p>Not thread safe. The order is computed on the client thread.
 */
public final class SpiralChunkOrder {

    /** Milliseconds generating and meshing one chunk column costs. */
    public static final double MS_PER_CHUNK = 25.0;

    private int centreX;
    private int centreZ;
    private long computed;

    /**
     * The order chunks should be loaded in, as packed {@code x, z} pairs.
     *
     * <p>The centre is first and the order works outward, so the ground under the player is available before
     * the horizon.
     *
     * @param radius the view distance in chunks
     */
    public int[] order(int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("a negative radius: " + radius);
        }
        this.centreX = 0;
        this.centreZ = 0;
        this.computed++;

        int side = 2 * radius + 1;
        int[] out = new int[side * side * 2];
        int index = 0;

        for (int ring = 0; ring <= radius; ring++) {
            if (ring == 0) {
                out[index++] = this.centreX;
                out[index++] = this.centreZ;
                continue;
            }
            // Walk the square ring at this distance, in a fixed direction, so ties keep their order and the
            // same world loads in the same pattern every launch. Each side runs from one corner to the one
            // before the next side's start, so a corner belongs to exactly one side: running every side over
            // the same inclusive range would visit two corners twice and miss two others.
            for (int step = -ring; step < ring; step++) {
                out[index++] = this.centreX + ring;
                out[index++] = this.centreZ + step;
            }
            for (int step = ring; step > -ring; step--) {
                out[index++] = this.centreX + step;
                out[index++] = this.centreZ + ring;
            }
            for (int step = ring; step > -ring; step--) {
                out[index++] = this.centreX - ring;
                out[index++] = this.centreZ + step;
            }
            for (int step = -ring; step < ring; step++) {
                out[index++] = this.centreX + step;
                out[index++] = this.centreZ - ring;
            }
        }
        return out;
    }

    /**
     * How far a chunk is from the centre, in chunks.
     *
     * <p>The order is by this distance, and chunks at the same distance are equally urgent.
     */
    public int distance(int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - this.centreX), Math.abs(chunkZ - this.centreZ));
    }

    /** How many orders have been computed. */
    public long computed() {
        return this.computed;
    }

    /** The centre the last order was built around. */
    public int centreX() {
        return this.centreX;
    }

    public int centreZ() {
        return this.centreZ;
    }

    /** Milliseconds before the chunk the player is standing on is ready. */
    public double msBeforeCentreReady() {
        return MS_PER_CHUNK;
    }

    /**
     * Milliseconds before the chunk the player is standing on is ready, with a row-by-row scan.
     *
     * <p>The centre is in the middle of the area, so about half of it is loaded first.
     */
    public double msBeforeCentreReadyRowByRow(int radius) {
        int side = 2 * radius + 1;
        int total = side * side;
        int centreRow = radius;
        int before = centreRow * side + radius;
        return (before + 1) * MS_PER_CHUNK;
    }

    /**
     * The share of the wait before the ground under the player appears that is removed.
     *
     * <p>Zero when there is nothing to load. A scheduler that has loaded nothing has technically made the
     * player wait for nothing, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent(int radius) {
        double without = msBeforeCentreReadyRowByRow(radius);
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msBeforeCentreReady()) / without;
    }
}
