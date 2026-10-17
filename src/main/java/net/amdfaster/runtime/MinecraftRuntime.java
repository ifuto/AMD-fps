package net.amdfaster.runtime;

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
}
