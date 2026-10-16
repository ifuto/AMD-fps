package net.amdfaster.entity;

/**
 * Ticks distant entities less often, because nothing about them is changing.
 *
 * <p>Every loaded entity ticks every tick: it checks for collisions, updates its motion, ages, looks for a
 * target. For a mob two hundred blocks away in a chunk the player is not looking at, all of that work
 * produces a result nobody can observe and, in most cases, a state indistinguishable from the one it was
 * in. With a farm a thousand blocks out and a storage system full of item frames, the tick budget goes to
 * entities the player will not see for ten minutes.
 *
 * <p>Ticking those every fourth tick instead gives most of the budget back, and a mob that thinks four times
 * a second instead of twenty is not observably different at that distance.
 *
 * <h2>Some entities always tick</h2>
 *
 * <p>An item entity on the ground, an arrow in flight and a lit TNT are all mid-something: the item is
 * merging or despawning, the arrow is falling, the TNT has a fuse counting down. Slowing those would be
 * visible and would be wrong -- an arrow that falls in slow motion, a fuse that takes four times as long,
 * items that linger four times as long before despawning. So the categories whose behaviour is physics or a
 * timer are exempt, and the exemption is by kind rather than by distance, because a distant arrow is just as
 * much an arrow as a near one.
 *
 * <h2>The range is a distance, not a chunk count</h2>
 *
 * <p>A chunk is sixteen blocks wide, so a range measured in chunks changes in sixteen-block jumps: an entity
 * at 15.9 blocks and one at 16.1 would be treated differently for no reason a player could perceive, and
 * walking in a straight line would make entities visibly stutter between rates at each boundary. A distance
 * in blocks changes smoothly, and the transition is not something that happens at a particular place.
 *
 * <h2>Coming into range restores the normal rate immediately</h2>
 *
 * <p>A player walking up to a mob expects it to react. If the rate only changed at the next scheduled tick,
 * the mob would sit still for up to a quarter of a second after the player arrived, which reads as the game
 * not having noticed. So the rate is decided each tick from the current distance, not remembered from when
 * the entity went out of range.
 *
 * <h2>Why this is a tick rate and not a pause</h2>
 *
 * <p>Pausing an entity would freeze it: a cow would stand still until the player arrived and then resume,
 * which is visible the moment the player looks. Ticking it less often means it keeps doing what it was doing,
 * just with coarser steps, and at a distance where the player cannot see the individual steps. The difference
 * is between an entity that is slow and an entity that is stopped, and only one of those is acceptable.
 *
 * <p>Not thread safe. Entities tick on the server thread.
 */
public final class ActivationRangeScheduler {

    /** Blocks within which an entity ticks every tick. */
    public static final double ACTIVE_RANGE = 32.0;

    /** Ticks between updates for an entity outside that range. */
    public static final int IDLE_INTERVAL = 4;

    /** Milliseconds one entity tick costs. */
    public static final double MS_PER_TICK = 0.02;

    private long ticks;
    private long active;
    private long idle;
    private long exempt;

    /**
     * Whether an entity should tick this tick.
     *
     * @param distance how far it is from the nearest player, in blocks
     * @param physicsOrTimer whether it is an item, an arrow or a lit TNT -- categories whose behaviour is
     *     physics or a countdown, which cannot be slowed without being wrong
     * @param tick the current tick, used to spread the idle entities across the interval
     * @param entityId the entity's id, used to give it a phase within the interval
     */
    public boolean shouldTick(double distance, boolean physicsOrTimer, long tick, int entityId) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + distance);
        }
        this.ticks++;

        if (physicsOrTimer) {
            // An item is merging or despawning, an arrow is falling, a TNT has a fuse counting down. Slowing
            // those would be visible and would be wrong, so the exemption is by kind rather than by distance
            // -- a distant arrow is just as much an arrow as a near one.
            this.exempt++;
            return true;
        }
        if (distance <= ACTIVE_RANGE) {
            this.active++;
            return true;
        }
        // Staggered by id, so a quarter of the distant entities is due on each tick rather than all of them
        // on one, which would be a spike at a fixed cadence rather than a saving.
        boolean due = Math.floorMod(entityId, IDLE_INTERVAL)
                == Math.floorMod(tick, IDLE_INTERVAL);
        if (due) {
            this.idle++;
        }
        return due;
    }

    /**
     * Whether an entity is inside the active range.
     *
     * <p>A distance in blocks rather than a chunk count, because a chunk is sixteen blocks wide and a range
     * measured in chunks changes in sixteen-block jumps -- an entity at 15.9 blocks and one at 16.1 would be
     * treated differently for no reason a player could perceive.
     */
    public static boolean isActive(double distance) {
        return distance <= ACTIVE_RANGE;
    }

    public long ticks() {
        return this.ticks;
    }

    /** How many were inside the range and ticked normally. */
    public long active() {
        return this.active;
    }

    /** How many idle ticks happened. */
    public long idle() {
        return this.idle;
    }

    /** How many were exempt because their behaviour is physics or a timer. */
    public long exempt() {
        return this.exempt;
    }

    /** Milliseconds of entity ticking done. */
    public double msTicking() {
        return (this.active + this.idle + this.exempt) * MS_PER_TICK;
    }

    /** Milliseconds ticking every entity every tick would cost. */
    public double msWithoutScheduling() {
        return this.ticks * MS_PER_TICK;
    }

    /**
     * The share of the entity ticking avoided.
     *
     * <p>Zero before any entity has been offered. A scheduler that has been offered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutScheduling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msTicking()) / without;
    }
}
