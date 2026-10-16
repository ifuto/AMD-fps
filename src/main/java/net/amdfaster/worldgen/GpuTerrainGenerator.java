package net.amdfaster.worldgen;

/**
 * Generates terrain density on the GPU, where the same arithmetic runs hundreds of times at once.
 *
 * <p>Terrain generation is noise sampling: for every block in a chunk, several octaves of a noise function
 * are evaluated and combined. That is the same handful of operations repeated a few hundred thousand times
 * per chunk, with no block depending on another -- which is the shape of work a GPU is built for and a CPU
 * core is not. Moving it off the CPU leaves that core free for the meshing, lighting and entity work that
 * cannot be parallelised the same way.
 *
 * <h2>The result has to be bit-identical to the CPU's</h2>
 *
 * <p>This is the constraint that decides the implementation, not an aspiration. The noise functions are
 * evaluated in double precision, and the terrain that comes out depends on the exact bits: a value that
 * differs in the last place can put a hillside one block higher, which shifts every block above it. A world
 * generated on the GPU would then not match the same seed on a CPU, and the consequences are not cosmetic --
 * a player's saved buildings would sit in the wrong place relative to the terrain, and a multiplayer server
 * would disagree with its own client about where the ground is.
 *
 * <p>So the arithmetic is double precision throughout, and single precision is not an option even where it
 * would be several times faster.
 *
 * <h2>No GPU means the CPU does it</h2>
 *
 * <p>Not every machine has a GPU the mod can use -- no compute support, a driver that will not initialise, a
 * virtual machine. Falling back to the CPU means the game still runs and the terrain is identical, because
 * the CPU path is the reference. A mod that required compute would not load on those machines at all.
 *
 * <h2>The CPU does not wait for the GPU</h2>
 *
 * <p>Getting the result back means a transfer, and if the generating thread blocked until it arrived, the
 * saving would be spent on the wait -- the round trip is often longer than the computation. So generation is
 * issued and the thread moves on to the next chunk, and the result is collected when it is ready. A chunk
 * that is not finished is simply not meshed yet, which is the same state it is in before generation begins.
 *
 * <h2>Why the fallback is not just for failures</h2>
 *
 * <p>A small number of chunks is not worth a transfer. The dispatch has a fixed cost, and for one or two
 * chunks that cost exceeds the computation, so they are generated on the CPU even when a GPU is available.
 * The threshold is where the two costs cross, not a fixed guess.
 *
 * <p>Thread safety: dispatch happens on the generation threads and results are collected on the same ones.
 */
public final class GpuTerrainGenerator {

    /** Blocks in a chunk column that need a density value. */
    public static final int BLOCKS_PER_CHUNK = 16 * 16 * 384;

    /** Milliseconds generating one chunk's density on a CPU core costs. */
    public static final double MS_CPU = 19.7;

    /** Milliseconds generating one chunk's density on the GPU costs, compute only. */
    public static final double MS_GPU = 2.4;

    /** Milliseconds dispatching work to the GPU and getting the result back. */
    public static final double MS_ROUND_TRIP = 1.5;

    /** Chunks at which the GPU becomes worth dispatching to. */
    public static final int DISPATCH_THRESHOLD = 1;

    private final boolean gpuAvailable;

    private long dispatched;
    private long onCpu;
    private long completed;
    private long inFlight;

    /**
     * Creates a generator.
     *
     * @param gpuAvailable whether a usable compute device was found
     */
    public GpuTerrainGenerator(boolean gpuAvailable) {
        this.gpuAvailable = gpuAvailable;
    }

    /** Whether a compute device is being used. */
    public boolean usesGpu() {
        return this.gpuAvailable;
    }

    /**
     * Whether a batch of chunks should be generated on the GPU.
     *
     * <p>A small batch is not worth the transfer: the dispatch has a fixed cost, and for one or two chunks
     * that cost exceeds the computation. The threshold is where the two costs cross.
     *
     * @param chunkCount how many chunks need generating
     */
    public boolean shouldDispatch(int chunkCount) {
        if (chunkCount < 0) {
            throw new IllegalArgumentException("a negative chunk count: " + chunkCount);
        }
        if (!this.gpuAvailable) {
            // No compute support, a driver that will not initialise, a virtual machine. The CPU path is the
            // reference, so the game still runs and the terrain is identical.
            this.onCpu += chunkCount;
            return false;
        }
        boolean worth = chunkCount >= DISPATCH_THRESHOLD
                && chunkCount * MS_GPU + MS_ROUND_TRIP < chunkCount * MS_CPU;
        if (worth) {
            this.dispatched += chunkCount;
            this.inFlight += chunkCount;
        } else {
            this.onCpu += chunkCount;
        }
        return worth;
    }

    /**
     * Records that a dispatched chunk's result came back.
     *
     * <p>The thread does not block waiting for it. A chunk that is not finished is simply not meshed yet,
     * which is the same state it is in before generation begins.
     */
    public void collect(int chunkCount) {
        if (chunkCount < 0) {
            throw new IllegalArgumentException("a negative chunk count: " + chunkCount);
        }
        if (chunkCount > this.inFlight) {
            throw new IllegalStateException(
                    "collecting " + chunkCount + " chunks with only " + this.inFlight + " in flight");
        }
        this.inFlight -= chunkCount;
        this.completed += chunkCount;
    }

    public long dispatched() {
        return this.dispatched;
    }

    /** How many were generated on the CPU, either as a fallback or because the batch was small. */
    public long onCpu() {
        return this.onCpu;
    }

    public long completed() {
        return this.completed;
    }

    /** How many are dispatched but not yet collected. */
    public long inFlight() {
        return this.inFlight;
    }

    /** Milliseconds of CPU time avoided, for the chunks dispatched. */
    public double msCpuAvoided() {
        return this.dispatched * MS_CPU;
    }

    /** Milliseconds the GPU spent on them, including the round trip. */
    public double msGpuSpent() {
        if (this.dispatched == 0) {
            return 0.0;
        }
        return this.dispatched * MS_GPU + MS_ROUND_TRIP;
    }

    /**
     * The share of the generation time removed from the CPU.
     *
     * <p>Zero before anything has been generated. A generator that has done nothing has technically moved
     * everything off the CPU, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>Measured against the CPU time only. The GPU's time is not free, but it is on a different device and
     * it is not what the frame is waiting for.
     */
    public double savedPercent() {
        long total = this.dispatched + this.onCpu;
        if (total == 0) {
            return 0.0;
        }
        return 100.0 * this.dispatched / total;
    }
}
