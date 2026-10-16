package net.amdfaster.mem;

import java.util.ArrayList;
import java.util.List;

/**
 * Notifies every cache that the world has changed, so none of them keeps the old one alive.
 *
 * <p>A cache that survives a world change holds geometry, block states and entity data for terrain that
 * no longer exists. With a dozen caches holding forty megabytes each, leaving one world and entering
 * another leaves nearly half a gigabyte behind, and doing it five times leaves more than most JVMs are
 * given. The heap fills, the collector runs more often, and the frame rate drops in the new world for a
 * reason that has nothing to do with it.
 *
 * <h2>Every cache has to hear about it</h2>
 *
 * <p>A leak is not caused by a cache that clears itself badly; it is caused by one that was never told.
 * So registration is the mechanism: a cache that wants clearing registers once at startup, and the
 * registry holds it for the lifetime of the client. Adding a cache means adding it here, and forgetting to
 * is the one failure this design cannot prevent -- which is why the registry reports how many it notified,
 * so a number that stops growing when a new cache is added is visible in the debug output rather than only
 * in a heap dump.
 *
 * <h2>Clearing is idempotent</h2>
 *
 * <p>A world change can be signalled more than once: disconnecting, then loading a save, then being kicked
 * back to the menu. Each of those may raise the event, and a cache that throws or corrupts itself on the
 * second clear would turn a routine logout into a crash. Clearing twice has to leave the same state as
 * clearing once.
 *
 * <h2>The caches are reused, not rebuilt</h2>
 *
 * <p>Clearing empties a cache; it does not discard it. The next world uses the same objects, so the
 * allocations made to build them are not repeated and the JVM does not have to re-warm anything. A cache
 * that had to be recreated would need its own lifecycle, and a second way to forget to register it.
 *
 * <h2>A failure in one cache does not stop the others</h2>
 *
 * <p>If clearing one cache throws, the remaining caches would still be holding the old world, and the
 * leak this exists to prevent would happen anyway -- along with an exception that hides the real problem.
 * Each cache is cleared independently and failures are collected, so one broken cache leaks on its own
 * rather than taking the others with it.
 *
 * <p>Thread safety: world changes are raised on the client thread and every cache is registered before
 * the world loads, so the list is not modified concurrently with a clear.
 */
public final class WorldChangeRegistry {

    /** A cache that can drop everything it holds for the current world. */
    public interface Clearable {
        /** The name shown in diagnostics, so a failing cache can be identified. */
        String cacheName();

        /** Drops everything held for the current world. Must be safe to call twice. */
        void clearForWorldChange();
    }

    private final List<Clearable> caches = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private long clears;

    /**
     * Registers a cache to be cleared on every world change.
     *
     * <p>Called once at startup. A cache that is not registered is never told, and quietly keeps the
     * previous world in memory.
     */
    public void register(Clearable cache) {
        if (cache == null) {
            throw new IllegalArgumentException("a cache is required");
        }
        this.caches.add(cache);
    }

    /** How many caches are registered. */
    public int registered() {
        return this.caches.size();
    }

    /**
     * Clears every registered cache.
     *
     * <p>Safe to call more than once: a world change can be signalled by disconnecting, by loading a save
     * and by being returned to the menu, and each may raise the event.
     *
     * @return how many caches were cleared successfully
     */
    public int clearAll() {
        this.clears++;
        int cleared = 0;

        for (Clearable cache : this.caches) {
            try {
                cache.clearForWorldChange();
                cleared++;
            } catch (RuntimeException | Error failure) {
                // Collected rather than propagated. Throwing here would leave every remaining cache
                // holding the old world, so the leak this exists to prevent would happen anyway along
                // with an exception that hides the real problem.
                this.failures.add(cache.cacheName() + ": " + failure);
            }
        }
        return cleared;
    }

    /** How many times a world change has been signalled. */
    public long clears() {
        return this.clears;
    }

    /**
     * The caches that failed to clear, as {@code name: error}.
     *
     * <p>Empty in the normal case. A cache that fails to clear is leaking, and this is what identifies it.
     */
    public List<String> failures() {
        return List.copyOf(this.failures);
    }

    /** Whether every registered cache cleared on the most recent world change. */
    public boolean allCleared() {
        return this.failures.isEmpty();
    }
}
