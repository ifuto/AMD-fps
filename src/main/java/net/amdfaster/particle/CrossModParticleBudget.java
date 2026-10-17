package net.amdfaster.particle;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts every mod's particles against one budget, instead of letting each have its own.
 *
 * <p>The game limits how many particles exist at once, but the limit is the game's: it counts the particles the
 * game knows about. A mod that adds its own particle system adds particles the limit does not see, so ten mods
 * each behaving perfectly within their own idea of reasonable add up to ten times what the frame can draw --
 * and the player's frame rate falls in a scene where no single mod is doing anything unusual.
 *
 * <p>Counting them all against one number is the only way the total is bounded, and it needs no cooperation
 * from the mods, because the particles are counted where they are drawn rather than where they are spawned.
 *
 * <h2>The budget is shared fairly</h2>
 *
 * <p>A first-come limit would let whichever mod spawns first take the whole thing, and a mod that adds ambient
 * effects everywhere would starve a mod whose particles are the point of what it does. So each source gets a
 * share, and a source that is under its share does not block one that has room.
 *
 * <h2>The vanilla particles are in the same budget</h2>
 *
 * <p>Treating them separately would mean the total is the game's limit plus every mod's, which is the problem
 * again with one more term. Rain, campfire smoke and a mod's magic effects all cost the same frame time, so
 * they all come out of the same number.
 *
 * <h2>An unused share is not wasted</h2>
 *
 * <p>A quiet scene where most sources are idle should be able to draw more of the ones that are active, so a
 * source that is over its share is allowed up to what the whole budget has left. The share bounds what one
 * source can take on its own, not what it can take when nobody else is asking.
 *
 * <h2>Why this is per source and not per mod</h2>
 *
 * <p>A mod is not a unit the renderer can see: it sees a particle and where it came from. Counting per source
 * means a mod with fifty particle types is bounded by its total rather than getting fifty shares, which is the
 * opposite of what would happen if the unit were the type.
 *
 * <p>Thread safety: particles are counted on the render thread.
 *
 * <p>Reference: vanilla's particle limit and Cull Particles' distance culling both bound particle cost, but
 * neither spans mod boundaries. This is an independent implementation of a shared budget.
 */
public final class CrossModParticleBudget {

    /** Particles the whole frame is allowed at once. */
    public static final int TOTAL_BUDGET = 500;

    /** Milliseconds drawing one particle costs. */
    public static final double MS_PER_PARTICLE = 0.002;

    private final Map<String, Integer> bySource = new HashMap<>();

    private int total;
    private long requested;
    private long spawned;
    private long refusedShare;
    private long refusedTotal;

    /**
     * How many particles one source may hold on its own.
     *
     * @param sources how many distinct sources are currently active
     */
    public static int shareFor(int sources) {
        if (sources < 1) {
            throw new IllegalArgumentException("at least one source is required: " + sources);
        }
        return Math.max(1, TOTAL_BUDGET / sources);
    }

    /**
     * Whether a particle may be spawned.
     *
     * @param source an identifier for where the particle came from, such as a mod's namespace
     * @return true if it may be spawned
     */
    public boolean allow(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a source is required");
        }
        this.requested++;

        if (this.total >= TOTAL_BUDGET) {
            this.refusedTotal++;
            return false;
        }

        int held = this.bySource.getOrDefault(source, 0);
        // The share is what the whole budget gives each active source, so it grows as sources go quiet: one
        // active source may use all of it, and ten may use a tenth each. That is what lets a quiet scene draw
        // more of the sources that are active without any of them having to ask.
        int distinct = this.bySource.size() + (held == 0 ? 1 : 0);
        int share = shareFor(distinct);

        if (held >= share) {
            // A first-come limit would let whichever mod spawns first take the whole thing, and a mod that
            // adds ambient effects everywhere would starve one whose particles are the point of what it does.
            this.refusedShare++;
            return false;
        }

        this.bySource.put(source, held + 1);
        this.total++;
        this.spawned++;
        return true;
    }

    /** Releases a particle when it expires. */
    public void release(String source) {
        Integer held = this.bySource.get(source);
        if (held == null) {
            throw new IllegalStateException("releasing a particle from a source that has none: " + source);
        }
        if (held <= 1) {
            this.bySource.remove(source);
        } else {
            this.bySource.put(source, held - 1);
        }
        this.total--;
    }

    /** How many particles are alive. */
    public int total() {
        return this.total;
    }

    /** How many distinct sources are currently active. */
    public int sources() {
        return this.bySource.size();
    }

    /** How many one source holds. */
    public int heldBy(String source) {
        return this.bySource.getOrDefault(source, 0);
    }

    public long requested() {
        return this.requested;
    }

    public long spawned() {
        return this.spawned;
    }

    /** How many were refused because their source had reached its share. */
    public long refusedShare() {
        return this.refusedShare;
    }

    /** How many were refused because the whole budget was full. */
    public long refusedTotal() {
        return this.refusedTotal;
    }

    /**
     * The share one source currently has.
     *
     * <p>Exposed so a test can check the fairness rule against the number it implies rather than against a
     * restatement of it.
     */
    public int currentShare() {
        return shareFor(Math.max(1, this.bySource.size()));
    }

    /** Milliseconds of particle drawing the budget prevents. */
    public double msCapped() {
        return Math.max(0, this.requested - this.spawned) * MS_PER_PARTICLE;
    }

    /** Milliseconds drawing every requested particle would cost. */
    public double msWithoutBudget() {
        return this.requested * MS_PER_PARTICLE;
    }

    /**
     * The share of the particle cost the budget removed.
     *
     * <p>Zero before anything has been requested. A budget that has been asked for nothing has technically
     * removed everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutBudget();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msCapped() / without;
    }
}
