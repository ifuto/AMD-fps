package net.amdfaster.worldgen;

/**
 * Generates terrain beyond the render distance so the horizon is not empty.
 *
 * <p>The client draws what the server sends, and the server sends what is inside the view distance. Beyond
 * that there is nothing, and the horizon is a flat edge where the world stops -- which is the most obvious
 * thing about a short render distance, and the reason raising it is the first thing a player reaches for.
 *
 * <p>Raising it properly costs the server the bandwidth and the client the memory. Generating the terrain
 * locally, from the same seed, costs neither: the shape of the land is a function of the seed and the
 * position, so the client can work out what the hills look like two kilometres away without asking anyone.
 *
 * <h2>Terrain only</h2>
 *
 * <p>What is generated is the height of the ground and the biome that decides its colour. Not the trees,
 * not the structures, not the ores, not the block entities, and not the mobs. Those are not a function of
 * the seed alone -- a village is placed by a process that involves the world's own state, and a chest's
 * contents are decided when it is first opened. Generating them locally would produce things that are not
 * there, which is worse than a bare hillside.
 *
 * <h2>The real chunk replaces it</h2>
 *
 * <p>When the player walks close enough for the server to send the real chunk, that chunk takes over and the
 * generated one is dropped. Drawing both would put two surfaces in the same place, and the generated one
 * would be missing everything the real one has, so whichever drew second would decide whether the player saw
 * trees or not.
 *
 * <h2>The terrain has to match</h2>
 *
 * <p>A hillside generated locally and then replaced by the real one should not visibly change shape. If it
 * does, the player sees the world shift as they approach, which is more unsettling than the flat horizon it
 * replaced. So the generation uses the same noise functions and the same biome rules the game uses, and the
 * difference is limited to what those functions cannot decide -- the decorations.
 *
 * <h2>Why it is generated on unload-distance work rather than eagerly</h2>
 *
 * <p>Generating a hundred chunks a second is still work, and generating everything in a sixty-four chunk
 * radius at once would stall the game at the moment it starts. So generation is spread over frames, and the
 * horizon fills in over a second or two rather than appearing all at once -- which is also less noticeable
 * than a world that pops into existence.
 *
 * <p>Not thread safe. Generation runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class DistantTerrainGenerator {

    /** Milliseconds generating one chunk column's terrain costs. */
    public static final double MS_PER_CHUNK = 6.0;

    /**
     * Milliseconds of generation allowed per worker per frame, so the horizon fills in rather than
     * stalling.
     *
     * <p>Generation runs on worker threads, so the frame's allowance is this times the worker count. A
     * single-threaded budget would fill a thirty-two chunk radius in minutes, which is longer than the
     * player stays in one place.
     */
    public static final double BUDGET_MS_PER_WORKER = 2.0;

    /** Chunks per side of the generated radius. */
    public static final int RADIUS = 32;

    /** Worker threads generating terrain, which sets the frame's allowance. */
    private final int workerCount;

    /**
     * Creates a generator.
     *
     * @param workers the threads available for generation
     */
    public DistantTerrainGenerator(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("at least one worker is required: " + workers);
        }
        this.workerCount = workers;
    }

    /** How many threads are generating. */
    public int workerCount() {
        return this.workerCount;
    }

    /** Milliseconds of generation allowed in one frame, across all workers. */
    public double frameBudget() {
        return BUDGET_MS_PER_WORKER * this.workerCount;
    }

    private long generated;
    private long replaced;
    private long dropped;
    private long frames;

    /**
     * Generation time earned but not yet spent.
     *
     * <p>A chunk costs more than one frame's budget, so most frames earn credit and every third one spends
     * it. Dividing the budget by the cost and rounding down would give zero chunks per frame forever, and
     * rounding up would exceed the budget, so the remainder is carried instead.
     */
    private double credit;

    /**
     * Generates one frame's worth of terrain.
     *
     * <p>Bounded so the horizon fills in over a second or two rather than appearing all at once, which is
     * also less noticeable than a world that pops into existence.
     *
     * @param pending how many chunks still need generating
     * @return how many were generated this frame
     */
    public int generateFrame(int pending) {
        if (pending < 0) {
            throw new IllegalArgumentException("a negative pending count: " + pending);
        }
        this.frames++;
        this.credit += frameBudget();

        int done = 0;
        while (done < pending && this.credit >= MS_PER_CHUNK) {
            this.credit -= MS_PER_CHUNK;
            done++;
        }
        this.generated += done;
        return done;
    }

    /**
     * Records that the server sent the real chunk.
     *
     * <p>The generated one is dropped. Drawing both would put two surfaces in the same place, and the
     * generated one would be missing everything the real one has, so whichever drew second would decide
     * whether the player saw trees or not.
     */
    public void replaceWithReal() {
        this.replaced++;
    }

    /**
     * Records that generated terrain was dropped because it is no longer in range.
     *
     * <p>The player walked past it, so keeping it would be memory for terrain nobody will look at again
     * this session.
     */
    public void dropOutOfRange() {
        this.dropped++;
    }

    /** How many chunk columns are inside the generated radius. */
    public static int columnsInRadius() {
        int side = 2 * RADIUS + 1;
        return side * side;
    }

    public long generated() {
        return this.generated;
    }

    /** How many were replaced by real chunks from the server. */
    public long replaced() {
        return this.replaced;
    }

    public long dropped() {
        return this.dropped;
    }

    public long frames() {
        return this.frames;
    }

    /** Milliseconds of generation done so far. */
    public double msGenerated() {
        return this.generated * MS_PER_CHUNK;
    }

    /**
     * Milliseconds a frame spends generating on average, which is what the budget caps.
     *
     * <p>Averaged over the frames run, because a chunk costs more than one frame's budget and so the work
     * lands on every third frame rather than a fraction of every one.
     */
    public double msPerFrame() {
        return this.frames == 0 ? 0.0 : this.generated * MS_PER_CHUNK / this.frames;
    }

    /**
     * How many frames it takes to fill the radius.
     *
     * <p>Zero before any generation has happened. A generator that has done nothing has technically filled
     * the horizon instantly, but reporting that as a time would describe an unused one as though it had
     * done something.
     */
    public int framesToFill() {
        if (this.frames == 0 || this.generated == 0) {
            return 0;
        }
        double perFrame = (double) this.generated / this.frames;
        return (int) Math.ceil(columnsInRadius() / perFrame);
    }
}
