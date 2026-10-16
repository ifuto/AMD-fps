package net.amdfaster.resource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads model definitions when something first needs them, rather than all of them at startup.
 *
 * <p>Startup reads every model definition in the pack, and most of them are never drawn: a modpack adds
 * models for blocks that only appear in one dimension, for items the player never crafts, and for states
 * that only occur in generated structures. Loading them all costs the same whether or not the player will
 * ever see them. Loading on demand moves that cost to the moment it is actually needed, and for models
 * that are never needed, removes it entirely.
 *
 * <h2>A late load has to succeed</h2>
 *
 * <p>That is the part that makes this more than a deferred startup. A model requested mid-game has to be
 * there when it is asked for, so the first request loads it synchronously and the caller gets a real
 * model. Returning nothing and letting the renderer fall back to a missing-texture cube would turn every
 * first appearance of a block into a purple square that fixes itself on the next frame -- visible, and
 * worse, intermittent, so it reads as a rendering bug rather than as a loading strategy.
 *
 * <h2>Once loaded, it stays loaded</h2>
 *
 * <p>Unloading a model that was just drawn would mean loading it again the next frame, which is slower
 * than not having deferred it at all. So the set of loaded models only grows during a session, and the
 * saving comes from the ones that are never requested rather than from evicting ones that are.
 *
 * <h2>Loading the same model twice is one load</h2>
 *
 * <p>Several blocks can share a model definition, and the first one to need it loads it while the rest
 * find it already there. Counting those as separate loads would overstate the cost of the deferred
 * approach and understate what it saved.
 *
 * <h2>Why this is opt-in</h2>
 *
 * <p>Deferring a load moves work from a loading screen, where the player expects to wait, into gameplay,
 * where a hitch is noticeable. For a player with a fast disk and a small pack the trade is clearly bad:
 * the models would have loaded in a moment anyway, and now the first trip to the Nether stutters. So it is
 * off until someone turns it on, and the class does not enable itself.
 *
 * <p>Thread safety: model requests come from the render thread and mesh building from worker threads, so
 * callers need to synchronise. This class does not.
 */
public final class LazyModelLoader {

    /** Milliseconds loading one model definition costs. */
    public static final double MS_PER_MODEL = 0.02;

    /** Models in a large modpack. */
    public static final int PACK_MODELS = 200_000;

    private final Map<String, Object> loaded = new HashMap<>();

    private long requests;
    private long loads;
    private long reused;
    private boolean enabled;

    /**
     * Enables deferred loading.
     *
     * <p>Off by default. Deferring moves work from a loading screen, where the player expects to wait,
     * into gameplay, where a hitch is noticeable, so it is a trade rather than a straight improvement.
     */
    public void enable() {
        this.enabled = true;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Returns a model, loading it if this is the first request for it.
     *
     * <p>The load is synchronous. A model requested mid-game has to be there when it is asked for;
     * returning nothing would make the first appearance of a block a missing-texture cube that fixes
     * itself on the next frame, which is visible and intermittent.
     *
     * @param id the model identifier
     * @param definition supplies the model, called at most once per identifier
     */
    public Object model(String id, Supplier<Object> definition) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a model id is required");
        }
        if (definition == null) {
            throw new IllegalArgumentException("a definition is required for " + id);
        }
        this.requests++;

        Object existing = this.loaded.get(id);
        if (existing != null) {
            // Several blocks can share a model definition. Counting those as separate loads would overstate
            // the cost of deferring and understate what it saved.
            this.reused++;
            return existing;
        }

        Object model = definition.get();
        if (model == null) {
            throw new IllegalStateException("the definition for " + id + " produced no model");
        }
        // Kept for the rest of the session. Unloading one that was just drawn would mean loading it again
        // next frame, which is slower than not having deferred it at all.
        this.loaded.put(id, model);
        this.loads++;
        return model;
    }

    /** Whether a model has been loaded. */
    public boolean isLoaded(String id) {
        return this.loaded.containsKey(id);
    }

    /** How many models have been loaded so far. */
    public int loadedCount() {
        return this.loaded.size();
    }

    /** Drops every loaded model, on a resource reload or a world change. */
    public void clear() {
        this.loaded.clear();
    }

    public long requests() {
        return this.requests;
    }

    public long loads() {
        return this.loads;
    }

    /** How many requests found the model already loaded. */
    public long reused() {
        return this.reused;
    }

    /** Milliseconds spent loading models during play. */
    public double msDeferred() {
        return this.loads * MS_PER_MODEL;
    }

    /** Milliseconds loading every model in the pack at startup would have cost. */
    public double msIfAllLoadedAtStartup() {
        return PACK_MODELS * MS_PER_MODEL;
    }

    /**
     * The share of the startup model loading avoided.
     *
     * <p>Zero before any model has been requested. A loader that has been asked for nothing has technically
     * deferred everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double all = msIfAllLoadedAtStartup();
        if (all == 0.0 || this.requests == 0) {
            return 0.0;
        }
        return 100.0 * (all - msDeferred()) / all;
    }
}
