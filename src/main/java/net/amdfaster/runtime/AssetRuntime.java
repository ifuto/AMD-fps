package net.amdfaster.runtime;

import net.amdfaster.resource.LazyDataFixer;
import net.amdfaster.resource.LazyLanguageLoader;
import net.amdfaster.resource.LazyModelLoader;
import net.amdfaster.resource.ModelBakeCache;
import net.amdfaster.resource.ResourceLookupCache;
import net.amdfaster.mem.AtlasUploadCleanup;
import net.amdfaster.mem.CrossModAssetDeduplicator;
import net.amdfaster.mem.MemoryPressureMonitor;
import net.amdfaster.mem.NbtStringCache;
import net.amdfaster.mem.WorldChangeRegistry;

/**
 * Owns the caches that reduce memory, and clears them when the world changes.
 *
 * <p>A modpack's memory problem is mostly duplication: two hundred mods each holding their own copy of models,
 * textures and NBT strings that are byte for byte the same, plus the garbage they allocate while working. The
 * components that fix those are separate, and each is correct on its own -- but they all have one thing in
 * common that none of them can do for itself: they hold data from a world, and they all have to let go of it at
 * the same moment.
 *
 * <p>Clearing some of them and not others is worse than clearing none. A deduplicator holding the last world's
 * geometry while the block cache holds the new one answers for a world that no longer exists, and the symptom is
 * geometry that does not match the terrain -- which looks like a rendering bug and is very hard to trace back to
 * a cache.
 *
 * <h2>They are registered, not cleared by name</h2>
 *
 * <p>The registry holds whatever registers itself, so adding a cache means adding it there rather than finding
 * the place that clears the others. A cache that forgets to register is still a leak, but it is a leak that shows
 * up in the registry's count rather than only in a heap dump, which is the difference between a bug that is
 * findable and one that is not.
 *
 * <h2>Not everything here is world-specific</h2>
 *
 * <p>The asset deduplicator holds models and textures, which come from resource packs rather than
 * from the world, so they survive a world change and are deliberately not registered. Clearing them
 * would drop memory that mods are still pointing at and force every model to be baked again for no
 * reason. What is world-specific is the NBT, which comes from the server, and that is what gets
 * cleared.
 *
 * <h2>One failing cache does not stop the others</h2>
 *
 * <p>If clearing one throws, the rest still have to be cleared, because leaving them holding the old world is
 * exactly the failure the clearing exists to prevent. So each is cleared independently and the failures are
 * collected by name, which is what makes a broken cache identifiable rather than a mystery.
 *
 * <h2>The heap is watched, not guessed at</h2>
 *
 * <p>The pressure monitor samples what is actually in use rather than assuming a budget, so a pack that is
 * heavier than expected says so before the collector starts thrashing. It observes and does not intervene --
 * calling the collector would trade a heap that fills gradually for a pause whose timing the mod chose.
 *
 * <p>Not thread safe. Registration happens at startup and clearing on the client thread.
 */
public final class AssetRuntime {

    private final WorldChangeRegistry registry = new WorldChangeRegistry();
    private final CrossModAssetDeduplicator assets = new CrossModAssetDeduplicator();
    private final NbtStringCache nbt = new NbtStringCache();
    private final AtlasUploadCleanup atlas = new AtlasUploadCleanup();
    private final MemoryPressureMonitor heap = new MemoryPressureMonitor();

    /** Builds the data fixer only when a world of an older version is opened. */
    private final LazyDataFixer dataFixer = new LazyDataFixer();

    /** Loads languages on demand rather than at startup. */
    private final LazyLanguageLoader languages = new LazyLanguageLoader();

    /** Loads model definitions on demand. */
    private final LazyModelLoader models = new LazyModelLoader();

    /** Reuses a baked model set when nothing that would change it has changed. */
    private final ModelBakeCache bakedModels = new ModelBakeCache();

    /** Caches resource lookups by namespace and path. */
    private final ResourceLookupCache lookups = new ResourceLookupCache();

    public AssetRuntime() {
        // Registered rather than cleared by name, so a cache added later is cleared by registering itself
        // rather than by someone remembering to add it to a list somewhere else.
        // The cross-mod asset deduplicator is deliberately NOT registered. It holds models and textures,
        // which come from resource packs rather than from the world, so they are still valid and still
        // referenced after a world change. Clearing them would drop memory that mods are still pointing at
        // and force every model to be baked again for no reason -- the opposite of what this class is for.
        // What is world-specific is the NBT, which comes from the server.
        this.registry.register(new WorldChangeRegistry.Clearable() {
            @Override
            public String cacheName() {
                return "nbtStrings";
            }

            @Override
            public void clearForWorldChange() {
                AssetRuntime.this.nbt.clear();
            }
        });
    }

    /** The shared asset deduplicator, for mods and for the mod's own models. */
    public CrossModAssetDeduplicator assets() {
        return this.assets;
    }

    /** The NBT string cache. */
    public NbtStringCache nbt() {
        return this.nbt;
    }

    /** The atlas upload cleanup. */
    public AtlasUploadCleanup atlas() {
        return this.atlas;
    }

    /** The heap pressure monitor. */
    public MemoryPressureMonitor heap() {
        return this.heap;
    }

    /** The registry, so other caches can register themselves. */
    public WorldChangeRegistry registry() {
        return this.registry;
    }

    /** How many caches are registered to be cleared. */
    public int registeredCaches() {
        return this.registry.registered();
    }

    /**
     * Clears every registered cache, on a world change.
     *
     * <p>Each is cleared independently, so one that throws does not leave the others holding the old world.
     *
     * @return how many were cleared
     */
    public int onWorldChange() {
        return this.registry.clearAll();
    }

    /** The caches that failed to clear, by name. Empty when all of them cleared. */
    public java.util.List<String> clearFailures() {
        return this.registry.failures();
    }

    /**
     * Reports the heap in use, so the monitor can say when it is getting tight.
     *
     * @param usedBytes bytes currently in use
     * @param maxBytes bytes the heap can grow to
     * @return true if a warning should be shown
     */
    public boolean sampleHeap(long usedBytes, long maxBytes) {
        return this.heap.sample(usedBytes, maxBytes);
    }

    /** Bytes not held, because duplicates share one copy. */
    public long bytesSaved() {
        return this.assets.bytesSaved() + this.nbt.bytesSaved();
    }

    /** Milliseconds of work avoided by sharing rather than rebuilding. */
    public double msSaved() {
        return this.assets.msSaved();
    }

    /**
     * The share of the assets interned that turned out to be duplicates.
     *
     * <p>Zero before anything has been interned. A runtime that has seen nothing has technically found no
     * duplicates, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double sharedPercent() {
        return this.assets.sharedPercent();
    }

    /** The lazy data fixer. */
    public LazyDataFixer dataFixer() {
        return this.dataFixer;
    }

    /** The lazy language loader. */
    public LazyLanguageLoader languages() {
        return this.languages;
    }

    /** The lazy model loader. */
    public LazyModelLoader models() {
        return this.models;
    }

    /** The model bake cache. */
    public ModelBakeCache bakedModels() {
        return this.bakedModels;
    }

    /** The resource lookup cache. */
    public ResourceLookupCache lookups() {
        return this.lookups;
    }

    /**
     * Whether the baked model set on disk can be reused.
     *
     * <p>The identity covers resource packs, mods and game version, because all three change baked output.
     * A cache keyed on fewer than those would hand back models baked for a pack that is no longer loaded,
     * which shows up as wrong geometry rather than as a crash.
     *
     * @param resourcePacks the loaded pack list
     * @param mods the loaded mod list
     * @param gameVersion the game version
     * @return whether the cached bake matches
     */
    public boolean canReuseBakedModels(String resourcePacks, String mods, String gameVersion) {
        return this.bakedModels.matches(
                ModelBakeCache.identity(resourcePacks, mods, gameVersion));
    }
}
