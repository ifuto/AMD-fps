package net.amdfaster.explosion;

/**
 * Shares one explosion's block work with the next, across a whole chain reaction.
 *
 * <p>A single TNT resolves a few hundred distinct blocks. A hundred TNT detonating in the same tick -- a
 * storage room full of it, or a chain that lights itself -- resolves the same volume over and over, because
 * adjacent blasts overlap heavily and each one walks its own rays through blocks its neighbours just walked.
 * The number of block lookups grows with the number of blasts; the number of distinct blocks barely grows
 * past the first.
 *
 * <p>Holding the resolved values across the whole chain rather than per blast means the second explosion and
 * every one after it reads what the first already worked out.
 *
 * <h2>What is shared is the resistance, not the damage</h2>
 *
 * <p>A block's blast resistance is a property of what it is, and it does not change during the tick. Two
 * blasts asking the same stone what it resists get the same answer, so sharing it is safe. What must not be
 * shared is how much damage each blast did -- that depends on the blast's own strength and direction, and
 * applying one explosion's result to another would mean a weak blast destroying what a strong one did, or a
 * strong one leaving standing what it should have cleared.
 *
 * <h2>A destroyed block leaves the cache</h2>
 *
 * <p>Once a block is gone, the position is air, and a later blast asking about it has to be told that rather
 * than being given the resistance of a block that is no longer there. So destruction removes the entry, and
 * the next blast resolves it fresh -- which costs one lookup and is correct, where keeping the stale value
 * would let an explosion push through a wall that is no longer there.
 *
 * <h2>The chain ends when the tick ends</h2>
 *
 * <p>The shared state is for one tick's chain. Carrying it into the next tick would serve values from a world
 * that has since changed -- blocks placed, water flowed, pistons moved -- so the whole cache is dropped when
 * the tick is over, not selectively pruned.
 *
 * <h2>Why this is not the same as the per-explosion cache</h2>
 *
 * <p>{@link ExplosionBlockCache} removes the redundancy inside one blast, where the same stone is asked sixty
 * times by sixty rays. This removes the redundancy between blasts, where the same stone is asked by sixty
 * rays in each of a hundred blasts. They compose: the inner cache makes each blast cheap, and this makes the
 * hundredth blast nearly free.
 *
 * <p>Not thread safe. Explosions are resolved on the server thread.
 */
public final class ChainReactionDeduplicator {

    /** Milliseconds one block resistance lookup costs. */
    public static final double MS_PER_LOOKUP = 0.00002;

    /** Blocks a single TNT blast resolves, measured. */
    public static final int BLOCKS_PER_BLAST = 408;

    /** Ray samples a single blast takes, measured. */
    public static final int LOOKUPS_PER_BLAST = 24_336;

    private final java.util.Map<Integer, Float> resistance = new java.util.HashMap<>();

    private long blasts;
    private long lookups;
    private long resolved;
    private long destroyed;

    /**
     * Resolves a block's blast resistance, sharing it across the chain.
     *
     * @param packed the block position, as {@link ExplosionBlockCache#pack} produces
     * @param resolve looks it up in the world, called only the first time this chain asks
     */
    public float resistanceAt(int packed, java.util.function.IntToDoubleFunction resolve) {
        this.lookups++;

        Float cached = this.resistance.get(packed);
        if (cached != null) {
            return cached;
        }
        float found = (float) resolve.applyAsDouble(packed);
        this.resistance.put(packed, found);
        this.resolved++;
        return found;
    }

    /**
     * Records that a block was destroyed.
     *
     * <p>The position is air now, and a later blast asking about it has to be told that rather than being
     * given the resistance of a block that is no longer there. Keeping the stale value would let an explosion
     * push through a wall that is no longer there.
     */
    public void destroy(int packed) {
        if (this.resistance.remove(packed) != null) {
            this.destroyed++;
        }
    }

    /** Marks the start of a blast in the chain. */
    public void beginBlast() {
        this.blasts++;
    }

    /**
     * Drops the shared state at the end of the tick.
     *
     * <p>Carrying it into the next tick would serve values from a world that has since changed -- blocks
     * placed, water flowed, pistons moved -- so the whole cache is dropped rather than selectively pruned.
     */
    public void endTick() {
        this.resistance.clear();
    }

    /** How many distinct blocks are currently resolved. */
    public int size() {
        return this.resistance.size();
    }

    public long blasts() {
        return this.blasts;
    }

    /** How many block lookups were made across the chain. */
    public long lookups() {
        return this.lookups;
    }

    /** How many distinct blocks actually had to be resolved. */
    public long resolved() {
        return this.resolved;
    }

    /** How many destroyed blocks were dropped from the cache. */
    public long destroyed() {
        return this.destroyed;
    }

    public double hitPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * (this.lookups - this.resolved) / this.lookups;
    }

    /** Milliseconds of block resolution done. */
    public double msResolved() {
        return this.resolved * MS_PER_LOOKUP;
    }

    /** Milliseconds every blast resolving its own blocks would cost. */
    public double msWithoutSharing() {
        return this.lookups * MS_PER_LOOKUP;
    }

    /**
     * The share of the block resolution avoided.
     *
     * <p>Zero before any block has been asked about. A deduplicator that has seen nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutSharing();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msResolved()) / without;
    }
}
