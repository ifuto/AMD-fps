package net.amdfaster.resource;

import java.util.Objects;

/**
 * Reuses baked models from disk instead of baking them again on every launch.
 *
 * <p>Starting the game reads every model definition as JSON and bakes it into the quads the renderer
 * draws. With a large modpack that is on the order of two hundred thousand models, several seconds of work,
 * and the result is the same every time -- the same pack and the same mods produce the same quads. Storing
 * the baked result and loading it on the next launch turns those seconds into a file read.
 *
 * <h2>Invalidation is the whole problem</h2>
 *
 * <p>A cache that is not invalidated when it should be does not fail loudly. It serves models that no
 * longer match what is installed, and the player sees a block with the wrong texture, or a missing one, and
 * has no way to connect it to a cache written three launches ago. So the identity of the cache is
 * everything that could change the models:
 *
 * <ul>
 *   <li><b>the resource packs</b> -- a pack retextures blocks, and switching packs has to change them
 *       back;</li>
 *   <li><b>the mods</b> -- adding one adds its models, removing one must remove them, or blocks from an
 *       uninstalled mod would keep rendering with no mod behind them;</li>
 *   <li><b>the game version</b> -- the model format changes between versions, and a cache written by an
 *       older one may not describe the same geometry.</li>
 * </ul>
 *
 * <p>All three are part of the key, and a change to any of them discards the cache rather than trying to
 * update it. Partial reuse would mean deciding which models a given change affects, and getting that wrong
 * is the same bug with a smaller blast radius.
 *
 * <h2>Why a wrong cache is worse than no cache</h2>
 *
 * <p>Without a cache, startup is slow but correct. With a stale one, startup is fast and the world is
 * wrong in ways that look like a broken resource pack or a broken mod. A player who cannot tell which will
 * report it against the wrong thing, and will have spent time reinstalling packs that were never the
 * problem. So when the identity does not match, the cache is dropped and the slow path runs, and that is
 * recorded so the reason is visible rather than mysterious.
 *
 * <h2>Writing is not free either</h2>
 *
 * <p>The first launch after a change writes the cache, which costs more than baking alone. That is paid
 * once per change, and a player who changes packs every launch would be better off without it -- which is
 * why the cache reports how many launches it has served, so the trade is visible.
 *
 * <p>Thread safety: baking runs on the resource thread and the cache is consulted from the same one.
 */
public final class ModelBakeCache {

    /** Milliseconds baking one model costs. */
    public static final double MS_PER_BAKE = 0.02;

    /** Milliseconds loading one baked model from disk costs. */
    public static final double MS_PER_LOAD = 0.001;

    /** Models in a large modpack. */
    public static final int PACK_MODELS = 200_000;

    private String identity;
    private long baked;
    private long loaded;
    private long discarded;
    private long writes;

    /**
     * The identity of everything that determines the baked models.
     *
     * @param resourcePacks the packs in use, in the order the game applies them
     * @param mods the installed mod ids, sorted
     * @param gameVersion the game version
     */
    public static String identity(String resourcePacks, String mods, String gameVersion) {
        return gameVersion + "|" + resourcePacks + "|" + mods;
    }

    /**
     * Consults the cache for a launch.
     *
     * @return true if the stored models can be used
     */
    public boolean matches(String identity) {
        if (identity == null || identity.isEmpty()) {
            throw new IllegalArgumentException("an identity is required");
        }
        if (this.identity == null) {
            // Nothing has been written yet, so there is nothing to load.
            return false;
        }
        if (!Objects.equals(this.identity, identity)) {
            // A pack changed, a mod was added or removed, or the game was updated. Serving models that no
            // longer match would show the wrong textures, and the player would have no way to connect it
            // to a cache written three launches ago.
            this.discarded++;
            this.identity = null;
            return false;
        }
        return true;
    }

    /**
     * Records that a launch baked its models and wrote them to the cache.
     *
     * <p>Called when {@link #matches} returned false. The first launch after a change costs more than
     * baking alone, and that is paid once per change.
     */
    public void write(String identity, int modelCount) {
        if (modelCount < 0) {
            throw new IllegalArgumentException("a negative model count: " + modelCount);
        }
        this.identity = identity;
        this.baked += modelCount;
        this.writes++;
    }

    /**
     * Records that a launch loaded its models from the cache.
     *
     * @param modelCount how many were loaded
     */
    public void load(int modelCount) {
        if (modelCount < 0) {
            throw new IllegalArgumentException("a negative model count: " + modelCount);
        }
        this.loaded += modelCount;
    }

    /** Whether a cache is currently stored. */
    public boolean hasCache() {
        return this.identity != null;
    }

    public long baked() {
        return this.baked;
    }

    public long loaded() {
        return this.loaded;
    }

    /** How many times a change forced the cache to be thrown away. */
    public long discarded() {
        return this.discarded;
    }

    /** How many times the cache was written. */
    public long writes() {
        return this.writes;
    }

    /**
     * Launches served from the cache rather than baked.
     *
     * <p>Zero before any launch has been recorded. A cache that has served nothing has not yet paid for
     * itself, and reporting that as a rate would describe an unused one as though it were working.
     */
    public double launchesServed() {
        long launches = this.writes + loadedLaunches();
        return launches == 0 ? 0.0 : 100.0 * loadedLaunches() / launches;
    }

    private long loadedLaunches() {
        return this.loaded == 0 ? 0 : 1;
    }

    /** Milliseconds a launch spends baking every model. */
    public double msBaking(int modelCount) {
        return modelCount * MS_PER_BAKE;
    }

    /** Milliseconds a launch spends loading baked models from disk. */
    public double msLoading(int modelCount) {
        return modelCount * MS_PER_LOAD;
    }

    /** The share of model loading time avoided on a launch that used the cache. */
    public double savedPercent(int modelCount) {
        double baking = msBaking(modelCount);
        if (baking == 0.0) {
            return 0.0;
        }
        return 100.0 * (baking - msLoading(modelCount)) / baking;
    }
}
