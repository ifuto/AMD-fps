package net.amdfaster.runtime;

import net.amdfaster.worldgen.BiomeLookupCache;
import net.amdfaster.worldgen.ChunkStageScheduler;
import net.amdfaster.worldgen.GpuTerrainGenerator;
import net.amdfaster.worldgen.RandomTickSkipper;
import net.amdfaster.worldgen.BiomeBlendCache;
import net.amdfaster.worldgen.ChunkRecycleCache;
import net.amdfaster.worldgen.DistantTerrainGenerator;
import net.amdfaster.worldgen.NoiseCache;
import net.amdfaster.worldgen.RunLengthCodec;
import net.amdfaster.worldgen.StructureLayoutCache;

/**
 * Runs the world generation decisions in one place, and re-seeds them when the world changes.
 *
 * <p>Generating a chunk means sampling noise, blending biome colours, laying out structures and encoding the
 * result. Each has a cache that makes the second time cheaper, and each is correct on its own -- but they all
 * hold data derived from a seed, and a cache that keeps the last world's noise while the block cache holds the
 * new one generates terrain that does not match the world the player is standing in.
 *
 * <p>This owns them, and re-seeds them together on a world change.
 *
 * <h2>Re-seeding is not clearing</h2>
 *
 * <p>The noise cache holds samples keyed on position, and the same coordinates in a different world are
 * different terrain. Clearing it would be right but wasteful, because the samples for the new world are simply
 * other samples -- so it is re-seeded, which invalidates everything derived from the old seed and keeps the
 * cache warm for the new one.
 *
 * <h2>The structure cache is cleared, not re-seeded</h2>
 *
 * <p>A structure layout is keyed on the seed and the position together, so it cannot be re-seeded -- a village
 * in the old world is not a village in the new one at any coordinates. It is cleared, and the first lookup in
 * the new world searches again.
 *
 * <h2>Distant terrain is a picture, not a world</h2>
 *
 * <p>The distant generator produces terrain the player can see before the real chunks arrive. It can be drawn,
 * but nothing may be done with it: no collision, no block breaking, no pathfinding. Someone could have dug that
 * tunnel out while it was being generated, and a player who walks into ground that is only a picture falls
 * through the world.
 *
 * <h2>The frame budget is what keeps generation from stalling</h2>
 *
 * <p>Generating every pending chunk at once would freeze the game, and generating one at a time would take
 * minutes to fill the horizon. So each frame does as much as its budget allows and the horizon fills in over a
 * second or two, which is what makes distant terrain usable rather than a stutter.
 *
 * <p>Not thread safe at this level. The caches are consulted from generation worker threads, which is why each
 * of them is documented as needing its own instance per worker where that applies.
 */
public final class WorldGenRuntime {

    private final NoiseCache noise = new NoiseCache();
    private final BiomeBlendCache biomeBlend = new BiomeBlendCache();
    private final StructureLayoutCache structures = new StructureLayoutCache();
    private final RunLengthCodec codec = new RunLengthCodec();
    private final ChunkRecycleCache recycled = new ChunkRecycleCache();

    /** Caches biome and surface lookups, which are the expensive part of generation. */
    private final BiomeLookupCache biomes = new BiomeLookupCache();

    /** Decides which generation stage a chunk can advance to next. */
    private final ChunkStageScheduler stages = new ChunkStageScheduler();

    /** Generates terrain on the GPU when a device is available. */
    private GpuTerrainGenerator gpuTerrain = new GpuTerrainGenerator(false);

    /** Skips random ticks in chunks with nothing that can grow. */
    private final RandomTickSkipper randomTicks = new RandomTickSkipper();
    private final DistantTerrainGenerator distant;

    /** Workers the distant terrain generator uses when the caller does not say. */
    public static final int DEFAULT_WORKERS = 4;

    private long worldChanges;

    private final net.amdfaster.worldgen.StructurePieceCache structurePieces =
            new net.amdfaster.worldgen.StructurePieceCache();

    public WorldGenRuntime() {
        this(DEFAULT_WORKERS);
    }

    public WorldGenRuntime(int workers) {
        this.distant = new DistantTerrainGenerator(workers);
    }

    public NoiseCache noise() {
        return this.noise;
    }

    public BiomeBlendCache biomeBlend() {
        return this.biomeBlend;
    }

    public StructureLayoutCache structures() {
        return this.structures;
    }

    public net.amdfaster.worldgen.StructurePieceCache structurePieces() {
        return this.structurePieces;
    }

    /**
     * Places a structure piece, generating its blocks the first time a kind and variant are asked for and
     * copying them every time after.
     *
     * <p>Structure pieces are the same handful of shapes over and over, rotated to face different ways.
     * Generating each one from the template costs the full piece walk; copying costs a rotation lookup per
     * block. The cache is keyed on kind and variant, so two villages sharing a house shape share the work.
     *
     * @return the block ids to place, in the piece's own order
     */
    public int[] placeStructurePiece(int kind, int variant, int turns, java.util.function.IntSupplier generate) {
        if (!this.structurePieces.contains(kind, variant)) {
            this.structurePieces.store(kind, variant, generate.get());
        }
        return this.structurePieces.place(kind, variant, turns);
    }

    public RunLengthCodec codec() {
        return this.codec;
    }

    public ChunkRecycleCache recycled() {
        return this.recycled;
    }

    public DistantTerrainGenerator distant() {
        return this.distant;
    }

    /**
     * Leaves a world without knowing which one comes next.
     *
     * <p>Clears rather than re-seeds, because at this point there is no seed to re-seed with -- inventing one
     * would key the noise cache to a world that does not exist, and the first lookup in the real world would
     * then be answered from it.
     */
    public void onDisconnect() {
        this.structurePieces.clear();
        this.worldChanges++;
        this.noise.clear();
        this.structures.clear();
        this.recycled.clear();
    }

    /**
     * Moves to a new world.
     *
     * <p>The noise cache is re-seeded rather than cleared, because the samples for the new world are simply
     * other samples and clearing would throw away a warm cache for no reason. The structure cache is cleared,
     * because a layout is keyed on the old seed and cannot be re-seeded into meaning anything.
     *
     * @param seed the new world's seed
     */
    public void onWorldChange(long seed) {
        this.worldChanges++;
        this.noise.reseed(seed);
        this.structures.clear();
        this.recycled.clear();
        // The biome blend cache holds no world-derived state -- it caches the arithmetic of averaging nine
        // colours, which is the same in every world -- so there is nothing here to invalidate.
    }

    /**
     * Generates as much distant terrain as this frame's budget allows.
     *
     * <p>Generating every pending chunk at once would freeze the game, and generating one at a time would take
     * minutes to fill the horizon.
     *
     * @param pending how many columns still need generating
     * @return how many were generated this frame
     */
    public int generateDistant(int pending) {
        return this.distant.generateFrame(pending);
    }

    /** Reports a real chunk arriving, which replaces the distant version of it. */
    public void chunkArrived() {
        this.distant.replaceWithReal();
    }

    /**
     * Whether the distant terrain may be used for anything but drawing.
     *
     * <p>Never. Someone could have dug that tunnel out while it was being generated, and a player who walks
     * into ground that is only a picture falls through the world.
     */
    public boolean allowGameplayUseOfDistantTerrain() {
        return false;
    }

    /** How many world changes have been made this session. */
    public long worldChanges() {
        return this.worldChanges;
    }

    /** Milliseconds the noise cache saved this session. */
    public double msSavedOnNoise() {
        return this.noise.msSaved();
    }

    /** Milliseconds the structure cache saved this session. */
    public double msSavedOnStructures() {
        return this.structures.msWithoutCache() - this.structures.msSpent();
    }

    /**
     * The share of the noise lookups that were cached.
     *
     * <p>Zero before anything has been sampled. A runtime that has sampled nothing has technically cached
     * everything, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double noiseHitPercent() {
        return this.noise.hitPercent();
    }

    /** The compression the run-length codec achieved. */
    public double codecCompressionRatio() {
        return this.codec.compressionRatio();
    }

    /** The biome lookup cache. */
    public BiomeLookupCache biomes() {
        return this.biomes;
    }

    /** The chunk stage scheduler. */
    public ChunkStageScheduler stages() {
        return this.stages;
    }

    /** The GPU terrain generator. */
    public GpuTerrainGenerator gpuTerrain() {
        return this.gpuTerrain;
    }

    /** The random-tick skipper. */
    public RandomTickSkipper randomTicks() {
        return this.randomTicks;
    }

    /**
     * Reports that a device is available, so terrain generation can move to it.
     *
     * <p>The generator is built without a device because whether one exists is not known until the platform
     * probe runs, and generating on a device that is not there would fall back per chunk rather than once.
     */
    public void gpuTerrainAvailable() {
        this.gpuTerrain = new GpuTerrainGenerator(true);
    }
}
