package net.amdfaster.runtime;

import net.amdfaster.craft.RecipeSearchCache;
import net.amdfaster.explosion.BlastPhasePlanner;
import net.amdfaster.explosion.ChainReactionDeduplicator;
import net.amdfaster.explosion.EntityExposureCache;
import net.amdfaster.explosion.BlastAllocationModel;
import net.amdfaster.explosion.BlastDamageBuffer;
import net.amdfaster.explosion.ExplosionBlockCache;
import net.amdfaster.fluid.FlowDirectionCache;
import net.amdfaster.fluid.FluidSurfaceMerger;
import net.amdfaster.fluid.FluidTickFilter;
import net.amdfaster.hopper.HopperTransferCache;
import net.amdfaster.hopper.OccludedPositionSkipper;
import net.amdfaster.redstone.PowerCache;
import net.amdfaster.redstone.WireNetworkResolver;
import net.amdfaster.tick.DynamicLoadController;
import net.amdfaster.tick.RandomTickFilter;
import net.amdfaster.tick.WorkBudget;
import net.amdfaster.village.PoiIndex;
import net.amdfaster.village.StuckVillagerThrottler;
import net.amdfaster.block.BoxShape;
import net.amdfaster.block.HardnessCache;
import net.amdfaster.block.PistonShapes;
import net.amdfaster.block.ScheduledTickIndex;
import net.amdfaster.block.ShapeCache;
import net.amdfaster.mem.BlockStatePacker;
import net.amdfaster.mem.Canonicalizer;
import net.amdfaster.mem.DerivedStateCache;
import net.amdfaster.mem.EnumValuesCache;
import net.amdfaster.mem.ModelDeduplicator;
import net.amdfaster.mem.ScratchObjectPool;
import net.amdfaster.mc.BlockStateCache;
import net.amdfaster.mc.HeightmapIndex;
import net.amdfaster.mc.LevelBlockSampler;
import net.amdfaster.mc.ModelCache;
import net.amdfaster.mc.SectionCoords;
import net.amdfaster.mc.SectionSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the Minecraft-facing caches, and invalidates them by position when a chunk changes.
 *
 * <p>These are the components that sit between the mod and the game: they answer "what block is here", "what
 * is the height here", "what does this block look like". Each is correct on its own, but they all hold data
 * about specific positions, and a block that changes has to invalidate every one of them that cached that
 * position -- missing one means the player sees a block that is not there, or a hole where a block is.
 *
 * <p>This owns them and invalidates them together.
 *
 * <h2>Invalidation is by position, not wholesale</h2>
 *
 * <p>Placing one block changes one column and the light around it, not the whole world. Clearing everything on
 * every block change would throw away the cache the game is actively using, which is worse than not having one
 * -- the cache would never be warm, and the cost of rebuilding it would exceed the cost of the lookups it
 * saves.
 *
 * <h2>One placed block invalidates its neighbours</h2>
 *
 * <p>Light and height both cross section boundaries, so a block placed at the edge of a section changes the
 * heightmap of the section beside it. Invalidating only the section it is in would leave the neighbour with a
 * heightmap that says the sky is visible where it is not, and the lighting would be wrong in a way that looks
 * like a rendering bug.
 *
 * <h2>A uniform section is not read block by block</h2>
 *
 * <p>A section of solid stone is one value repeated, and reading four thousand and ninety-six blocks to find
 * that out costs more than the lookups it serves. The snapshot detects uniformity and answers from the single
 * value, which is the common case underground.
 *
 * <h2>Models are shared, and shared models are reference counted</h2>
 *
 * <p>Two hundred blocks with the same model need one copy of it, and evicting it while a block still uses it
 * would leave that block with no geometry. So the model cache counts references and refuses to evict a model
 * that is still in use, which is what makes sharing safe rather than merely cheap.
 *
 * <p>Not thread safe. Block lookups happen on the client thread and on mesh worker threads, so each worker needs
 * its own instance where that applies.
 */
public final class MinecraftRuntime {

    /** Models held before an unreferenced one is evicted. */
    public static final int DEFAULT_MODEL_CAPACITY = 4096;

    private final BlockStateCache blockStates = new BlockStateCache(BlockStateCache.DEFAULT_ENTRIES);
    private final ModelCache<Object> models = new ModelCache<>(DEFAULT_MODEL_CAPACITY);
    private final Map<Long, SectionSnapshot> sections = new HashMap<>();
    private final Map<Long, HeightmapIndex> heightmaps = new HashMap<>();

    /** Packs block-state properties into one int instead of an object per state. */
    private final BlockStatePacker statePacker = new BlockStatePacker();

    /** Interns derived state arrays so identical ones share storage. */
    private final DerivedStateCache derivedStates = new DerivedStateCache();

    /** Returns the shared enum-values array instead of cloning one per call. */
    private final EnumValuesCache enumValues = new EnumValuesCache();

    /** Interns model quads, which is where most of the model heap goes. */
    private final ModelDeduplicator modelQuads = new ModelDeduplicator();

    /**
     * Canonicalises model geometry.
     *
     * <p>Content is supplied here rather than built inside the canonicalizer, because only the caller knows
     * what makes two pieces of geometry interchangeable and what one is worth in bytes.
     */
    private final Canonicalizer<long[]> geometry = new Canonicalizer<>(new Canonicalizer.Content<>() {
        @Override
        public int hash(long[] value) {
            return java.util.Arrays.hashCode(value);
        }

        @Override
        public boolean equal(long[] a, long[] b) {
            return java.util.Arrays.equals(a, b);
        }

        @Override
        public int bytes(long[] value) {
            return value.length * Long.BYTES;
        }
    });

    /**
     * Pools scratch arrays used while baking.
     *
     * <p>The reset empties the array rather than dropping it, so a caller that reuses one cannot read the
     * previous bake out of it.
     */
    private final ScratchObjectPool<long[]> scratch =
            new ScratchObjectPool<>(() -> new long[64], array -> java.util.Arrays.fill(array, 0L));

    /** Caches block hardness, keyed on state and tool. */
    private final HardnessCache hardness = new HardnessCache();

    /** Interns box shapes so identical ones share storage. */
    private final ShapeCache shapes = new ShapeCache();

    /** Indexes scheduled block ticks by due tick. */
    private final ScheduledTickIndex scheduledTicks = new ScheduledTickIndex();

    /**
     * The level block sampler, or {@code null} when the game has not supplied one.
     *
     * <p>It needs a level and a block face source, which only the game can provide, so this class cannot build
     * it -- it is handed one when the wiring layer has a level, and until then the sampler is absent rather
     * than wrong. A sampler built against the wrong level would answer for a world that is not loaded.
     */
    private LevelBlockSampler sampler;

    private long blockChanges;
    private long invalidations;

    /** Caches recipe lookups for the crafting grid. */
    private final RecipeSearchCache recipes = new RecipeSearchCache();
    /** Caches block resistance during an explosion. */
    private final ExplosionBlockCache explosionBlocks = new ExplosionBlockCache();
    /** Stops one blast re-resolving blocks another blast already destroyed. */
    private final ChainReactionDeduplicator chainReaction = new ChainReactionDeduplicator();
    /**
     * Caches the block lookups behind explosion <em>entity</em> damage.
     *
     * <p>The other two cover which blocks an explosion destroys. This covers how hard it hits the things
     * standing near it, which is a separate raycast per entity and the half that gets dearer as a chain
     * goes on, because the items the earlier blasts dropped are entities the later ones have to measure.
     */
    private final EntityExposureCache explosionExposure = new EntityExposureCache();
    /**
     * Holds the current blast's destroy list in a reused array of packed positions.
     *
     * <p>Owned here rather than per blast because the point is reuse: a chain's blasts are all about the
     * same size, so the first sizes the buffer and the rest never allocate. Allocating one per explosion
     * is what the boxed path does and is the cost this removes.
     */
    private final BlastDamageBuffer blastDamage = new BlastDamageBuffer();
    /** Caches fluid flow direction, which recomputing every tick is what makes water expensive. */
    private final FlowDirectionCache flow = new FlowDirectionCache();
    /** Merges fluid surfaces into one quad instead of one per block. */
    private final FluidSurfaceMerger fluidSurface = new FluidSurfaceMerger();
    /** Skips fluid ticks where nothing can move. */
    private final FluidTickFilter fluidTicks = new FluidTickFilter();
    /** Caches what a hopper points at. */
    private final HopperTransferCache hoppers = new HopperTransferCache();
    /** Skips hopper checks on positions that are fully enclosed. */
    private final OccludedPositionSkipper occludedPositions = new OccludedPositionSkipper();
    /** Caches redstone signal strength. */
    private final PowerCache power = new PowerCache();
    /** Resolves a wire network once instead of per wire. */
    private final WireNetworkResolver wires = new WireNetworkResolver();
    /** Moves mob cap and simulation distance with the measured tick cost. */
    private final DynamicLoadController load = new DynamicLoadController();
    /** Skips random-tick sampling in sections with nothing tickable. */
    private final RandomTickFilter randomTickFilter = new RandomTickFilter();
    /** Spreads deferred tick work across frames. */
    private final WorkBudget budget = new WorkBudget();
    /** Indexes points of interest by position. */
    private final PoiIndex pois = new PoiIndex();
    /** Throttles villagers that cannot move. */
    private final StuckVillagerThrottler stuckVillagers = new StuckVillagerThrottler();

    /** The block state cache. */
    public BlockStateCache blockStates() {
        return this.blockStates;
    }

    /**
     * Supplies the level block sampler, once the game has a level to sample.
     *
     * @return this, so the caller can chain
     */
    public MinecraftRuntime withSampler(LevelBlockSampler sampler) {
        this.sampler = sampler;
        return this;
    }

    /**
     * The level block sampler, which answers "is this opaque".
     *
     * @return the sampler, or {@code null} if the game has not supplied one
     */
    public LevelBlockSampler sampler() {
        return this.sampler;
    }

    /** The shared model cache. */
    public ModelCache<Object> models() {
        return this.models;
    }

    /** The snapshot for one section, creating it on first sight. */
    public SectionSnapshot section(int sectionX, int sectionY, int sectionZ) {
        long key = SectionCoords.toWorld(sectionX, 0) * 31L
                + SectionCoords.toWorld(sectionY, 0) * 7L
                + SectionCoords.toWorld(sectionZ, 0);
        return this.sections.computeIfAbsent(key,
                k -> SectionSnapshot.forSection(sectionX, sectionY, sectionZ));
    }

    /** The heightmap for one chunk, creating it on first sight. */
    public HeightmapIndex heightmap(int chunkX, int chunkZ) {
        long key = (long) chunkX * 0x9E3779B97F4A7C15L + chunkZ;
        return this.heightmaps.computeIfAbsent(key, k -> HeightmapIndex.forChunk(chunkX, chunkZ));
    }

    /** How many sections are held. */
    public int sectionsHeld() {
        return this.sections.size();
    }

    /** How many heightmaps are held. */
    public int heightmapsHeld() {
        return this.heightmaps.size();
    }

    /**
     * Invalidates everything for one block position, and the neighbours that depend on it.
     *
     * <p>Light and height both cross section boundaries, so a block placed at the edge of a section changes
     * the heightmap of the section beside it. Invalidating only the section it is in would leave the neighbour
     * with a heightmap that says the sky is visible where it is not.
     *
     * @param x the block x
     * @param y the block y
     * @param z the block z
     */
    public void onBlockChange(int x, int y, int z) {
        this.blockChanges++;
        this.invalidations++;

        this.blockStates.clear();
        if (this.sampler != null) {
            this.sampler.invalidate();
        }

        int sectionX = SectionCoords.sectionOf(x);
        int sectionY = SectionCoords.sectionOf(y);
        int sectionZ = SectionCoords.sectionOf(z);

        invalidateSection(sectionX, sectionY, sectionZ);
        // The six face neighbours, because light and height cross the boundary.
        invalidateSection(sectionX + 1, sectionY, sectionZ);
        invalidateSection(sectionX - 1, sectionY, sectionZ);
        invalidateSection(sectionX, sectionY + 1, sectionZ);
        invalidateSection(sectionX, sectionY - 1, sectionZ);
        invalidateSection(sectionX, sectionY, sectionZ + 1);
        invalidateSection(sectionX, sectionY, sectionZ - 1);

        this.heightmap(SectionCoords.sectionOf(x), SectionCoords.sectionOf(z)).clear();
    }

    private void invalidateSection(int sectionX, int sectionY, int sectionZ) {
        long key = SectionCoords.toWorld(sectionX, 0) * 31L
                + SectionCoords.toWorld(sectionY, 0) * 7L
                + SectionCoords.toWorld(sectionZ, 0);
        SectionSnapshot snapshot = this.sections.get(key);
        if (snapshot != null) {
            snapshot.clear();
            this.invalidations++;
        }
    }

    /**
     * Invalidates everything, on a world change.
     *
     * <p>The positions are meaningless across worlds, so a cache keyed on position would otherwise answer for
     * a world that no longer exists.
     */
    public void onWorldChange() {
        this.blockStates.clear();
        if (this.sampler != null) {
            this.sampler.invalidate();
        }
        this.models.clear();
        this.sections.clear();
        this.heightmaps.clear();
        this.blockChanges = 0;
        this.invalidations = 0;
    }

    /** How many block changes have been processed this session. */
    public long blockChanges() {
        return this.blockChanges;
    }

    /** How many caches were invalidated. */
    public long invalidations() {
        return this.invalidations;
    }

    /** How many models are resident. */
    public int modelsResident() {
        return this.models.residentCount();
    }

    /**
     * Evicts the models nothing is using any more.
     *
     * <p>A model still in use is not evicted, because that block would be left with no geometry.
     *
     * @return how many were evicted
     */
    public int evictUnusedModels() {
        return this.models.evictUnreferenced();
    }

    /**
     * The share of the block lookups that were cached.
     *
     * <p>Zero before anything has been looked up. A runtime that has looked nothing up has technically cached
     * everything, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double blockCacheHitPercent() {
        int total = this.blockStates.hits() + this.blockStates.misses();
        return total == 0 ? 0.0 : 100.0 * this.blockStates.hits() / total;
    }

    /** The share of the model loads that were reused. */
    public double modelReusePercent() {
        long total = this.models.loads() + this.models.reused();
        return total == 0 ? 0.0 : 100.0 * this.models.reused() / total;
    }

    /** The block-state packer. */
    public BlockStatePacker statePacker() {
        return this.statePacker;
    }

    /** The derived-state cache. */
    public DerivedStateCache derivedStates() {
        return this.derivedStates;
    }

    /** The shared enum-values cache. */
    public EnumValuesCache enumValues() {
        return this.enumValues;
    }

    /** The model quad deduplicator. */
    public ModelDeduplicator modelQuads() {
        return this.modelQuads;
    }

    /** The geometry canonicalizer. */
    public Canonicalizer<long[]> geometry() {
        return this.geometry;
    }

    /** Takes a scratch array, to be given back when the bake is done. */
    public long[] takeScratch() {
        return this.scratch.take();
    }

    /** Returns a scratch array to the pool. */
    public void giveScratch(long[] array) {
        this.scratch.give(array);
    }

    /** The hardness cache. */
    public HardnessCache hardness() {
        return this.hardness;
    }

    /** The box-shape cache. */
    public ShapeCache shapes() {
        return this.shapes;
    }

    /** The scheduled block-tick index. */
    public ScheduledTickIndex scheduledTicks() {
        return this.scheduledTicks;
    }

    /**
     * The piston head or base shape for a facing.
     *
     * <p>Goes through PistonShapes rather than building a BoxShape here, because the extended and retracted
     * shapes differ per axis and a shape built by hand would be wrong for the cases that are not the one
     * being looked at.
     *
     * @param axis the axis the piston faces along
     * @param positive whether it faces towards positive on that axis
     * @param extended whether the piston is extended
     * @return the shape
     */
    public BoxShape pistonShape(int axis, boolean positive, boolean extended) {
        return PistonShapes.instance().shape(axis, positive, extended);
    }

    /** The recipe search cache. */
    public RecipeSearchCache recipes() {
        return this.recipes;
    }

    /** The explosion block cache. */
    public ExplosionBlockCache explosionBlocks() {
        return this.explosionBlocks;
    }

    /** The chain reaction deduplicator. */
    public ChainReactionDeduplicator chainReaction() {
        return this.chainReaction;
    }

    /**
     * The explosion entity exposure cache.
     *
     * <p>Both this and {@link #chainReaction()} hold answers that go stale at the end of the tick, so both
     * are cleared from the tick end rather than left to hold them.
     */
    public EntityExposureCache explosionExposure() {
        return this.explosionExposure;
    }

    /**
     * Works out how this tick's explosions have to be ordered against the shared caches.
     *
     * <p>{@link #chainReaction()} and {@link #explosionExposure()} both hold answers that are only valid
     * while the world has not changed, and an explosion changes it: {@code explode()} casts every ray
     * against a world nothing has been written to yet, then {@code finalizeExplosion()} removes 823 blocks.
     * A second explosion in the same tick has to see those holes, so its rays travel further through air
     * where the first blast put them.
     *
     * <p>What this decides is which explosions may share one ray phase. Two whose reach does not overlap
     * can, because neither destroyed a block the other reads; two that do overlap cannot, and serving the
     * second one the first one's cached resistances would stop its rays at a wall that is no longer
     * standing. That is terrain surviving an explosion it should not have, and it presents as a weak
     * blast rather than an incorrect one, so the criterion is computed per pair rather than assumed.
     *
     * @param blasts this tick's explosions, in the order they detonate
     * @return how many ray phases are needed and how many cache entries have to be dropped between them
     */
    public BlastPhasePlanner.Schedule scheduleBlasts(BlastPhasePlanner.Blast... blasts) {
        return BlastPhasePlanner.schedule(blasts);
    }

    /**
     * The current blast's destroy list.
     *
     * <p>Call {@link BlastDamageBuffer#clear()} at the start of each blast. Not clearing would carry the
     * previous blast's positions into the next one and destroy blocks this blast never touched.
     */
    public BlastDamageBuffer blastDamage() {
        return this.blastDamage;
    }

    /**
     * What packing the destroy list saves, measured against the game's allocation rate.
     *
     * <p>Worth asking rather than assuming, because the per-blast byte counts are easy to state and the
     * significance is not. A blast's 13,168 bytes of block positions against a reused array is a real 2 to
     * 1, but a hundred blasts a second is 0.16 per cent of the 800 MB a second Minecraft allocates when
     * busy. So the reason this matters is not the share, it is where the allocation lands: a TNT chain
     * puts dozens of blasts inside one 50 millisecond tick, and an amount that is negligible spread over a
     * second is not negligible inside the tick it arrives in.
     *
     * <p>The other half of the answer is that the JIT cannot remove these on its own. The destroy list is
     * a collection handed to the level, which makes every position in it globally escaping, and escape
     * analysis only scalar-replaces what stays inside the method that made it.
     */
    public BlastAllocationModel.Blast blastAllocation() {
        return BlastAllocationModel.perBlast();
    }

    /**
     * What a blast rate adds to the game's allocation, as a percentage of it.
     *
     * @param blastsPerSecond explosions per second
     * @return the share of the 800 MB/s baseline, which is the number that keeps the claim honest
     */
    public double blastAllocationShare(int blastsPerSecond) {
        return BlastAllocationModel.percentOfGameRate(blastsPerSecond,
                BlastDamageBuffer.BLOCKS_PER_BLAST * BlastAllocationModel.BLOCKPOS_BYTES);
    }

    /** The flow direction cache. */
    public FlowDirectionCache flow() {
        return this.flow;
    }

    /** The fluid surface merger. */
    public FluidSurfaceMerger fluidSurface() {
        return this.fluidSurface;
    }

    /** The fluid tick filter. */
    public FluidTickFilter fluidTicks() {
        return this.fluidTicks;
    }

    /** The hopper transfer cache. */
    public HopperTransferCache hoppers() {
        return this.hoppers;
    }

    /** The occluded position skipper. */
    public OccludedPositionSkipper occludedPositions() {
        return this.occludedPositions;
    }

    /** The redstone power cache. */
    public PowerCache power() {
        return this.power;
    }

    /** The wire network resolver. */
    public WireNetworkResolver wires() {
        return this.wires;
    }

    /** The dynamic load controller. */
    public DynamicLoadController load() {
        return this.load;
    }

    /** The random tick filter. */
    public RandomTickFilter randomTickFilter() {
        return this.randomTickFilter;
    }

    /** The work budget. */
    public WorkBudget budget() {
        return this.budget;
    }

    /** The point-of-interest index. */
    public PoiIndex pois() {
        return this.pois;
    }

    /** The stuck villager throttler. */
    public StuckVillagerThrottler stuckVillagers() {
        return this.stuckVillagers;
    }
}
