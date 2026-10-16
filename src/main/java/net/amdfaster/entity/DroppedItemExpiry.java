package net.amdfaster.entity;

/**
 * Expires dropped items that nobody can see or reach, before the game's own five minutes is up.
 *
 * <p>An item on the ground ticks every tick until it despawns: it falls, it merges with its neighbours, it
 * checks whether a player is close enough to pick it up. In a farm that produces items faster than they are
 * collected, or after a large explosion, there can be thousands of them, and every one costs that work for
 * five minutes whether or not anyone is going to collect it.
 *
 * <p>An item that no player can see and no player can reach in the time it has left is already lost -- it
 * will despawn before anyone gets to it. Expiring it now removes the ticking without taking anything from
 * anyone.
 *
 * <h2>Only items nobody can reach</h2>
 *
 * <p>An item a player dropped and is walking back toward has to stay, and so does one inside a base where the
 * player is about to return. Removing those would be taking the player's things, which is not a performance
 * adjustment -- it is the mod eating their inventory. So the test is whether any player is within the
 * tracking range, not whether anyone is looking at the screen.
 *
 * <h2>The short age still has to be long enough to walk back</h2>
 *
 * <p>An item that vanishes in a second is indistinguishable from a bug, and a player who dies and respawns
 * would find nothing where their things were. The early expiry is minutes, not seconds -- long enough to walk
 * a long way and collect it, short enough that a farm's output does not pile up into the thousands.
 *
 * <h2>What is removed stops costing</h2>
 *
 * <p>An expired item is not hidden, it is gone: no ticking, no merging, no pickup check, no rendering. That is
 * the point. An optimisation that kept the entity and skipped the drawing would save the render and leave the
 * cost that actually matters, which on a farm is the tick.
 *
 * <h2>Why the age is not simply zero</h2>
 *
 * <p>Removing every item that is not being watched would break the game in ways the player would notice
 * immediately: a chest that spills its contents when broken would lose them, a mob that drops loot on death
 * would drop nothing the player could pick up. Items exist to be collected, and the optimisation has to leave
 * that true.
 *
 * <p>Not thread safe. Items tick on the server thread.
 */
public final class DroppedItemExpiry {

    /** Ticks an item normally survives -- five minutes. */
    public static final int VANILLA_LIFETIME = 6000;

    /** Ticks an unreachable item survives. */
    public static final int UNREACHABLE_LIFETIME = 1200;

    /** Blocks within which a player could still collect an item. */
    public static final double REACH_RANGE = 64.0;

    /** Milliseconds one item entity costs per tick. */
    public static final double MS_PER_ITEM_TICK = 0.004;

    private long checked;
    private long expired;
    private long kept;
    private long keptReachable;

    /**
     * Whether an item should be expired now.
     *
     * @param ageTicks how long it has been on the ground
     * @param nearestPlayer how far the nearest player is, in blocks
     * @return true if it should be removed
     */
    public boolean shouldExpire(int ageTicks, double nearestPlayer) {
        if (ageTicks < 0) {
            throw new IllegalArgumentException("a negative age: " + ageTicks);
        }
        if (nearestPlayer < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + nearestPlayer);
        }
        this.checked++;

        boolean reachable = nearestPlayer <= REACH_RANGE;

        if (reachable) {
            // An item a player dropped and is walking back toward has to stay, and so does one inside a base
            // where the player is about to return. Removing those would be taking the player's things, which
            // is not a performance adjustment -- it is the mod eating their inventory.
            this.keptReachable++;
            this.kept++;
            return ageTicks >= VANILLA_LIFETIME;
        }
        if (ageTicks >= UNREACHABLE_LIFETIME) {
            // Nobody can see it and nobody can reach it in the time it has left, so it is already lost -- it
            // would despawn before anyone got to it.
            this.expired++;
            return true;
        }
        this.kept++;
        return false;
    }

    public long checked() {
        return this.checked;
    }

    /** How many items were expired early. */
    public long expired() {
        return this.expired;
    }

    /** How many were kept. */
    public long kept() {
        return this.kept;
    }

    /** How many were kept because a player could still reach them. */
    public long keptReachable() {
        return this.keptReachable;
    }

    public double expiredPercent() {
        return this.checked == 0 ? 0.0 : 100.0 * this.expired / this.checked;
    }

    /** Milliseconds of item ticking avoided per expired item, per tick. */
    public double msSavedPerTick() {
        return this.expired * MS_PER_ITEM_TICK;
    }

    /**
     * The share of the item ticking avoided.
     *
     * <p>Zero before any item has been checked. An expiry policy that has seen nothing has technically
     * removed everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.checked == 0) {
            return 0.0;
        }
        return 100.0 * this.expired / this.checked;
    }
}
