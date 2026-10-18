package net.amdfaster.explosion;

import java.util.Arrays;

/**
 * Caches the block lookups behind an explosion's <em>entity</em> damage, which is the half of an
 * explosion that {@link ExplosionBlockCache} does not touch.
 *
 * <p>An explosion does two unrelated jobs. It walks 1,352 rays through the terrain to work out which
 * blocks to destroy, and it works out how exposed each nearby entity is, to scale the damage and the
 * knockback. The first is cached. The second was not, and it is the one that gets worse in exactly the
 * situation the first one is good at.
 *
 * <h2>Exposure is a grid of rays per entity, not one test</h2>
 *
 * <p>Exposure is the fraction of sample points on an entity's bounding box that the blast can reach. The
 * samples are not a fixed count: vanilla divides each axis into {@code 1 / (size * 2 + 1)} steps and walks
 * from 0 to 1, so the number of rays depends on how big the entity is. A player-sized box gives 3 x 5 x 3
 * = 45 rays, a cow 36, a primed TNT 27, and a dropped item 8. Each of those rays is a block traversal, so
 * one explosion costs 45 traversals per nearby player rather than one.
 *
 * <h2>Why this is the expensive half in a chain</h2>
 *
 * <p>Destroying blocks creates item entities, and those items are entities that every later blast in the
 * same chain has to compute exposure for. A hundred TNT detonating in one tick over a storage room is not
 * a hundred block walks and one hundred exposure passes -- it is a hundred block walks and a hundred
 * exposure passes over a growing pile of the items the earlier blasts just made. The block side gets
 * cheaper as the chain goes on because the blasts overlap; the entity side gets dearer.
 *
 * <h2>What is shared</h2>
 *
 * <p>Whether a block obstructs is a property of the block, not of the entity or the ray. A solid stone
 * stops a ray to a cow and a ray to a dropped item alike, so the answer is resolved once per position and
 * reused across every ray of every entity in the tick. That is the same reasoning that makes
 * {@link ExplosionBlockCache} safe, and it carries the same hazard.
 *
 * <h2>The hazard, and why it is the same one</h2>
 *
 * <p>Obstruction goes stale when a block is destroyed, and the block destruction pass of one blast removes
 * blocks that a later blast's exposure rays would otherwise have been stopped by. An entity behind a wall
 * that blast A removed is no longer behind a wall, and reporting it as still shielded would deny it damage
 * and knockback it should have taken -- a visible wrongness in the one situation a player is watching
 * closely. So every removal must be reported through {@link #invalidate}, exactly as for resistance.
 *
 * <p>Within one blast it is safe for the reason it is safe there: vanilla resolves the rays and collects
 * what to destroy, and only removes anything afterwards. Nothing changes under the raycast phase.
 *
 * <h2>Exposure is never approximated</h2>
 *
 * <p>The sample count is reproduced exactly rather than replaced with something cheaper, because exposure
 * is a fraction and the denominator is the count. Using a coarser grid would change the damage: an entity
 * at 8 of 45 unobstructed takes a different hit from one at 2 of 8, and "roughly a quarter" is not the same
 * number. What gets cheaper is answering each sample, not taking fewer of them.
 *
 * <p>Not thread safe. Reached from the server thread, which in singleplayer is inside the client process.
 */
public final class EntityExposureCache {

    /** Supplies whether a block stops a ray. */
    public interface BlockSource {
        /**
         * @return true if a ray cannot pass through this position. Air and blocks with an empty shape do
         *     not obstruct; anything solid does.
         */
        boolean obstructs(int x, int y, int z);
    }

    /** Cost of one block traversal, for costing the saving rather than claiming one. */
    public static final double MS_PER_TRAVERSAL = 0.00001;

    /** Positions cached before the table grows. */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * Masks for the packed key.
     *
     * <p>Twenty-six bits of x, twelve of y, twenty-six of z, which is exactly sixty-four and is the
     * layout Minecraft itself packs a block position into. The first version of this used twenty-six
     * bits for each axis, which is seventy-eight -- it compiled, it ran, and it silently kept twelve
     * bits of z, so positions sharing an x and a y but differing high in z would have collided in the
     * table and one entity's obstruction would have been served for another's. The ranges are what the
     * world actually has: x and z reach the world border at 29,999,984, and y the build limits.
     */
    private static final long XZ_MASK = (1L << 26) - 1L;
    private static final long Y_MASK = (1L << 12) - 1L;

    private long[] keys;
    private boolean[] obstructing;
    private boolean[] filled;
    private int size;

    private long rays;
    private long lookups;
    private long resolved;
    private long unobstructed;
    private long invalidations;
    private long entities;

    private int entityRays;
    private int entityClear;

    public EntityExposureCache() {
        this.keys = new long[INITIAL_CAPACITY];
        this.obstructing = new boolean[INITIAL_CAPACITY];
        this.filled = new boolean[INITIAL_CAPACITY];
    }

    /**
     * How many sample rays vanilla takes for a bounding box of this size.
     *
     * <p>Reproduced rather than approximated. The step on each axis is {@code 1 / (size * 2 + 1)} and the
     * walk is accumulated in floating point from 0 while the value is at most 1, so the count is the number
     * of iterations that accumulate and not {@code floor(1 / step) + 1}. Those differ, and the difference is
     * the denominator of every damage figure that follows.
     *
     * @return the total sample count, the product of the three axes
     */
    public static int sampleCount(double width, double height, double depth) {
        return axisSamples(width) * axisSamples(height) * axisSamples(depth);
    }

    private static int axisSamples(double size) {
        if (!(size > 0.0) || Double.isNaN(size) || Double.isInfinite(size)) {
            throw new IllegalArgumentException("a bounding box axis must be a positive size: " + size);
        }
        double step = 1.0 / (size * 2.0 + 1.0);
        int count = 0;
        double at = 0.0;
        while (at <= 1.0) {
            count++;
            at += step;
        }
        return count;
    }

    /**
     * Packs a block position into the key used by the table.
     *
     * <p>Sign extension on the way back out is the part that is easy to get wrong: each field is recovered
     * by shifting it into the top of the long and then shifting back down arithmetically, which extends the
     * field's own sign rather than the key's. Getting it wrong would not throw, it would report a negative
     * coordinate as a large positive one and ask the world about a block that is not there.
     */
    public static long pack(int x, int y, int z) {
        return (((long) x & XZ_MASK) << 38)
                | (((long) y & Y_MASK) << 26)
                | ((long) z & XZ_MASK);
    }

    /**
     * Whether a block obstructs, resolved at most once per position between invalidations.
     *
     * @param packed the position, from {@link #pack}
     * @param source asked only on a miss
     * @return true if a ray cannot pass through it
     */
    public boolean obstructs(long packed, BlockSource source) {
        if (source == null) {
            throw new IllegalArgumentException("a block source is required");
        }
        this.lookups++;
        int slot = slotFor(packed);
        if (this.filled[slot]) {
            return this.obstructing[slot];
        }
        boolean value = source.obstructs(unpackX(packed), unpackY(packed), unpackZ(packed));
        this.keys[slot] = packed;
        this.obstructing[slot] = value;
        this.filled[slot] = true;
        this.size++;
        this.resolved++;
        return value;
    }

    /**
     * Starts one entity's exposure tally.
     *
     * <p>Called before the entity's sample rays, so that {@link #exposure} reports that entity and not the
     * whole tick. Forgetting it would fold every entity in the blast into one fraction, which would give
     * the entity that was mostly behind cover the exposure of the one standing in the open.
     */
    public void beginEntity() {
        this.entities++;
        this.entityRays = 0;
        this.entityClear = 0;
    }

    /**
     * Records one sample ray's result.
     *
     * @param clear true if the ray reached the entity without hitting anything
     */
    public void countRay(boolean clear) {
        this.rays++;
        this.entityRays++;
        if (clear) {
            this.unobstructed++;
            this.entityClear++;
        }
    }

    /**
     * The current entity's exposure, as a fraction of its sample rays.
     *
     * <p>Zero rather than NaN before any ray is counted. An entity with no samples is not a thing vanilla
     * produces, but reporting NaN here would put NaN into a damage calculation and the entity would take no
     * damage at all, which is a worse answer than zero.
     */
    public double exposure() {
        return this.entityRays == 0 ? 0.0 : (double) this.entityClear / this.entityRays;
    }

    /**
     * Drops a position because the block there was destroyed.
     *
     * <p>Mandatory, not a refinement. A block that blast A removed no longer stops blast B's rays, and an
     * entity that was standing behind it is no longer shielded. Leaving the stale entry would deny that
     * entity damage and knockback it should have taken.
     */
    public void invalidate(long packed) {
        int slot = slotFor(packed);
        if (this.filled[slot]) {
            this.filled[slot] = false;
            this.size--;
            this.invalidations++;
        }
    }

    /**
     * Drops a range of destroyed positions, because destruction arrives as a list.
     *
     * @param packed the destroyed positions
     * @param count how many entries of the array are live
     */
    public void invalidateRange(long[] packed, int count) {
        if (packed == null) {
            throw new IllegalArgumentException("an array of positions is required");
        }
        if (count < 0 || count > packed.length) {
            throw new IllegalArgumentException("count outside the array: " + count + " of " + packed.length);
        }
        for (int i = 0; i < count; i++) {
            invalidate(packed[i]);
        }
    }

    /**
     * Drops everything, at the end of the tick.
     *
     * <p>The whole table rather than selected entries, for the same reason the block caches do it: the next
     * tick's world has had blocks placed, water flowed and pistons moved since this one, and an obstruction
     * answer carried across that boundary describes a world that is no longer there.
     */
    public void endTick() {
        Arrays.fill(this.filled, false);
        this.size = 0;
    }

    /** How many positions are currently cached. */
    public int size() {
        return this.size;
    }

    /** How many sample rays have been counted. */
    public long rays() {
        return this.rays;
    }

    /** How many obstruction answers were asked for, cached or not. */
    public long lookups() {
        return this.lookups;
    }

    /** How many obstruction answers actually reached the world. */
    public long resolved() {
        return this.resolved;
    }

    /** How many sample rays reached their entity unobstructed. */
    public long unobstructed() {
        return this.unobstructed;
    }

    /** How many positions were dropped because the block there was destroyed. */
    public long invalidations() {
        return this.invalidations;
    }

    /** How many entities have had their exposure tallied. */
    public long entities() {
        return this.entities;
    }

    /** The share of obstruction answers served from the table. */
    public double hitPercent() {
        return this.lookups == 0 ? 0.0 : 100.0 * (this.lookups - this.resolved) / this.lookups;
    }

    /** Milliseconds the cached answers did not cost. */
    public double msSaved() {
        return (this.lookups - this.resolved) * MS_PER_TRAVERSAL;
    }

    private int slotFor(long packed) {
        if (this.size * 2 >= this.keys.length) {
            grow();
        }
        int mask = this.keys.length - 1;
        int slot = mix(packed) & mask;
        while (this.filled[slot] && this.keys[slot] != packed) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void grow() {
        long[] oldKeys = this.keys;
        boolean[] oldObstructing = this.obstructing;
        boolean[] oldFilled = this.filled;

        int capacity = oldKeys.length * 2;
        this.keys = new long[capacity];
        this.obstructing = new boolean[capacity];
        this.filled = new boolean[capacity];
        this.size = 0;

        int mask = capacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (!oldFilled[i]) {
                continue;
            }
            int slot = mix(oldKeys[i]) & mask;
            while (this.filled[slot]) {
                slot = (slot + 1) & mask;
            }
            this.keys[slot] = oldKeys[i];
            this.obstructing[slot] = oldObstructing[i];
            this.filled[slot] = true;
            this.size++;
        }
    }

    /** Spreads the packed key, which is otherwise dominated by the x axis in its low bits. */
    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return (int) h;
    }

    private static int unpackX(long packed) {
        // x already sits at the top of the key, so an arithmetic shift down extends its sign.
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }
}
