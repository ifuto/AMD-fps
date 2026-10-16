package net.amdfaster.entity;

/**
 * Gives an entity a level of attention based on how far away it is, rather than all or nothing.
 *
 * <p>A mob thirty blocks away does not need to think every tick, and one three hundred blocks away does not
 * need to think at all. A single cut-off -- active inside it, idle outside -- gets both cases wrong: it
 * spends full attention on mobs that only needed some, and it takes every tick away from mobs that are far
 * but not infinitely far, which is the band where the player is most likely to walk into them and notice
 * they have been standing still.
 *
 * <p>Four levels, by distance, spends attention where it does something and keeps a little everywhere else.
 *
 * <h2>Nothing is frozen forever</h2>
 *
 * <p>An entity that never ticks accumulates no time, so a mob five hundred blocks away would not age, would
 * not fall, and would not despawn -- and walking up to it would show something that has been suspended since
 * the player left, which is not a performance trade but a broken world. So even the most distant tier ticks
 * occasionally, and a bounded number of those wake-ups happen each tick so they do not bunch into a spike.
 *
 * <h2>Physics and timers are never skipped</h2>
 *
 * <p>A falling entity that stops being ticked stops falling and hangs in the air, and an entity on a despawn
 * timer that stops counting never despawns, so the world slowly fills with things that should have gone. So
 * work that changes the entity's state in ways the player can see runs at every tier, and what the tiers
 * reduce is the thinking -- pathfinding, target selection, wandering.
 *
 * <h2>The wake-ups are staggered</h2>
 *
 * <p>A hundred distant mobs all waking on the same tick is a hundred ticks of work at once, which is the
 * spike the tiers exist to avoid. Giving each entity a phase spreads them, and the per-tick cap bounds the
 * worst case however many are loaded.
 *
 * <h2>Players are never throttled</h2>
 *
 * <p>A player entity that ticked at a reduced rate would move in steps, and on a server the other players
 * would see them stutter. So players run at full rate at any distance, which is also what keeps the client's
 * own player correct.
 *
 * <p>Not thread safe. Entity ticking runs on the server thread.
 *
 * <p>Reference: Entity Tick Optimizer uses a four-tier activation system, and Paper's wake-up-inactive wakes
 * a bounded number of inactive entities per tick. This is an independent implementation of the same idea.
 */
public final class TieredActivationScheduler {

    /** How much attention each tier gives, as a divisor of the tick rate. */
    public enum Tier {
        /** Every tick. */
        FULL(1, 32.0),
        /** Every second tick. */
        REDUCED(2, 64.0),
        /** Every eighth tick. */
        INACTIVE(8, 128.0),
        /** Only when woken, and at most once a second. */
        MINIMAL(20, Double.MAX_VALUE);

        private final int divisor;
        private final double upTo;

        /** An explicit constructor: enum constants are constructed before other static fields. */
        Tier(int divisor, double upTo) {
            this.divisor = divisor;
            this.upTo = upTo;
        }

        /** How many ticks between full attention. */
        public int divisor() {
            return this.divisor;
        }

        /** The distance this tier covers, exclusive. */
        public double upTo() {
            return this.upTo;
        }
    }

    /** How many wake-ups may happen in one tick, so they do not bunch. */
    public static final int MAX_WAKEUPS_PER_TICK = 4;

    /** Milliseconds one full entity tick costs. */
    public static final double MS_PER_TICK = 0.02;

    private int wakeupsThisTick;

    private long offered;
    private long ticked;
    private long skipped;
    private long wakeups;
    private long wakeupsRefused;
    private long exempt;

    /**
     * The tier an entity belongs to.
     *
     * @param distance how far it is from the nearest player
     * @param player whether it is a player entity
     */
    public static Tier tierFor(double distance, boolean player) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + distance);
        }
        if (player) {
            // A player entity that ticked at a reduced rate would move in steps, and on a server the other
            // players would see them stutter.
            return Tier.FULL;
        }
        for (Tier tier : Tier.values()) {
            if (distance < tier.upTo) {
                return tier;
            }
        }
        return Tier.MINIMAL;
    }

    /**
     * Whether an entity should run its full tick this tick.
     *
     * @param distance how far it is from the nearest player
     * @param player whether it is a player entity
     * @param physics whether it needs physics or a despawn timer advanced, which is never skipped
     * @param entityId used to give it a phase within its divisor
     * @param tick the current tick
     */
    public boolean shouldTick(double distance, boolean player, boolean physics,
            int entityId, long tick) {
        this.offered++;

        Tier tier = tierFor(distance, player);
        if (player) {
            this.exempt++;
            this.ticked++;
            return true;
        }
        if (physics) {
            // A falling entity that stops being ticked stops falling and hangs in the air, and an entity on
            // a despawn timer that stops counting never despawns, so the world slowly fills with things
            // that should have gone.
            this.exempt++;
            this.ticked++;
            return true;
        }

        if (tier == Tier.MINIMAL) {
            // An entity that never ticks accumulates no time, so a mob five hundred blocks away would not
            // age, would not fall and would not despawn -- and walking up to it would show something that
            // has been suspended since the player left, which is not a performance trade but a broken
            // world.
            if (this.wakeupsThisTick >= MAX_WAKEUPS_PER_TICK) {
                this.wakeupsRefused++;
                this.skipped++;
                return false;
            }
            this.wakeupsThisTick++;
            this.wakeups++;
            this.ticked++;
            return true;
        }

        // Staggered by id, so a hundred distant mobs do not all wake on the same tick and produce the spike
        // the tiers exist to avoid.
        if (Math.floorMod(entityId, tier.divisor()) == Math.floorMod(tick, tier.divisor())) {
            this.ticked++;
            return true;
        }
        this.skipped++;
        return false;
    }

    /** Clears the wake-up budget for a new tick. */
    public void endTick() {
        this.wakeupsThisTick = 0;
    }

    /** How many wake-ups are left this tick. */
    public int wakeupsLeft() {
        return MAX_WAKEUPS_PER_TICK - this.wakeupsThisTick;
    }

    public long offered() {
        return this.offered;
    }

    public long ticked() {
        return this.ticked;
    }

    public long skipped() {
        return this.skipped;
    }

    /** How many minimal-tier entities were woken. */
    public long wakeups() {
        return this.wakeups;
    }

    /** How many were refused because the tick's wake-up budget was spent. */
    public long wakeupsRefused() {
        return this.wakeupsRefused;
    }

    /** How many were exempt as players or as needing physics. */
    public long exempt() {
        return this.exempt;
    }

    public double tickedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.ticked / this.offered;
    }

    /** Milliseconds of entity ticking done. */
    public double msTicking() {
        return this.ticked * MS_PER_TICK;
    }

    /** Milliseconds ticking every entity every tick would cost. */
    public double msWithoutScheduling() {
        return this.offered * MS_PER_TICK;
    }

    /**
     * The share of the entity ticking avoided.
     *
     * <p>Zero before any entity has been offered. A scheduler that has been offered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = msWithoutScheduling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msTicking()) / without;
    }
}
