package net.amdfaster.resource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the data fixers for a world when it is opened, rather than all of them at startup.
 *
 * <p>Every version of the game that changed a data format needs a fixer that converts the old one to the
 * new one, and starting the game builds the whole chain -- every version transition since the beginning.
 * That is thousands of fixers, and it takes seconds, for work that is only ever used when a world saved by
 * an older version is opened. A player who only plays the current version never uses any of it.
 *
 * <p>Building the chain when a world is actually opened, and only the part of it that world needs, moves
 * those seconds out of startup entirely for most launches.
 *
 * <h2>Current-version data is not converted</h2>
 *
 * <p>A world saved by this version needs no fixer at all, and running it through a chain that does nothing
 * would cost time and risk changing data that was already correct. So the version is compared first and a
 * matching one skips the fixer entirely.
 *
 * <h2>A fixer that cannot be built is an error, not a fallback</h2>
 *
 * <p>The fixers exist to convert data that the current code cannot read. If one cannot be built, the data
 * cannot be converted, and continuing would mean reading old data with new code -- which does not fail
 * loudly, it produces a world that is subtly wrong: items in the wrong slots, blocks with the wrong state,
 * inventories that have lost things. Throwing stops the load with a message the player can act on, which is
 * better than a world that loads and is quietly broken.
 *
 * <h2>Why the built chain is kept</h2>
 *
 * <p>Opening the same world twice should not rebuild its fixers. The chain is cached by the version it
 * converts from, and every world saved by that version reuses it.
 *
 * <h2>Why this is worth the risk</h2>
 *
 * <p>A fixer that is built lazily is built at the moment it is needed, which is during a world load, where
 * the player is already waiting. Building it at startup would be the same work in a place the player also
 * waits -- except that most startups never need it at all, so for those the work is simply removed.
 *
 * <p>Thread safety: world loading runs on the server thread and fixers are built there.
 */
public final class LazyDataFixer {

    /** Milliseconds building one version's fixer costs. */
    public static final double MS_PER_FIXER = 3.0;

    /** Version transitions in the chain since the game began. */
    public static final int CHAIN_LENGTH = 90;

    private final Map<Integer, Object> built = new HashMap<>();

    private long requests;
    private long builds;
    private long skipped;
    private long reused;

    /**
     * Returns the fixer that converts from a saved version to the current one.
     *
     * @param savedVersion the version the world was saved by
     * @param currentVersion the version running now
     * @param builder builds the chain, called at most once per saved version
     * @return the fixer, or null if none is needed
     */
    public Object fixerFor(int savedVersion, int currentVersion, Supplier<Object> builder) {
        if (builder == null) {
            throw new IllegalArgumentException("a builder is required");
        }
        this.requests++;

        if (savedVersion == currentVersion) {
            // A world saved by this version needs no fixer at all. Running it through a chain that does
            // nothing would cost time and risk changing data that was already correct.
            this.skipped++;
            return null;
        }
        if (savedVersion > currentVersion) {
            throw new IllegalStateException(
                    "the world was saved by a newer version (" + savedVersion
                            + ") than this one (" + currentVersion + ") and cannot be downgraded");
        }

        Object existing = this.built.get(savedVersion);
        if (existing != null) {
            this.reused++;
            return existing;
        }

        Object fixer = builder.get();
        if (fixer == null) {
            // The fixers exist to convert data the current code cannot read. If one cannot be built,
            // continuing would mean reading old data with new code, which does not fail loudly -- it
            // produces a world that is subtly wrong: items in the wrong slots, blocks with the wrong
            // state, inventories that have lost things.
            throw new IllegalStateException(
                    "no fixer could be built for version " + savedVersion);
        }
        this.built.put(savedVersion, fixer);
        this.builds++;
        return fixer;
    }

    /** Whether a chain has been built for a version. */
    public boolean isBuilt(int savedVersion) {
        return this.built.containsKey(savedVersion);
    }

    /** How many version chains are cached. */
    public int builtCount() {
        return this.built.size();
    }

    /** Drops every built chain, on a client shutdown. */
    public void clear() {
        this.built.clear();
    }

    public long requests() {
        return this.requests;
    }

    public long builds() {
        return this.builds;
    }

    /** How many worlds needed no conversion at all. */
    public long skipped() {
        return this.skipped;
    }

    /** How many worlds reused an already built chain. */
    public long reused() {
        return this.reused;
    }

    /** Milliseconds spent building fixers. */
    public double msBuilt() {
        return this.builds * CHAIN_LENGTH * MS_PER_FIXER;
    }

    /** Milliseconds building the whole chain at startup would have cost. */
    public double msIfBuiltAtStartup() {
        return CHAIN_LENGTH * MS_PER_FIXER;
    }

    /**
     * The share of the startup fixer building avoided.
     *
     * <p>Zero before any world has been opened. A fixer registry that has been asked for nothing has
     * technically deferred everything, but reporting that as a saving would describe an unused one as
     * though it had done something.
     */
    public double savedPercent() {
        double startup = msIfBuiltAtStartup();
        if (this.requests == 0 || startup == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, startup - msBuilt()) / startup;
    }
}
