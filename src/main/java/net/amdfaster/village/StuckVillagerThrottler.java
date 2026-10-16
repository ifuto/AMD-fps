package net.amdfaster.village;

/**
 * Ticks a villager that cannot move less often, because its AI has nothing to decide.
 *
 * <p>A villager in a trading hall sits in a one-block space for the whole session. Every tick it still runs
 * its AI: it looks for a path, checks whether it wants to move, evaluates its goals, and reaches the same
 * conclusion every time -- that there is nowhere to go. A trading hall with two hundred villagers is two
 * hundred of those evaluations a tick, twenty times a second, all of them deciding not to move.
 *
 * <p>Ticking those villagers every twentieth tick instead removes most of that, and a villager that cannot
 * move is not observably different whether it thinks once a second or twenty times.
 *
 * <h2>The test is whether it can move, not whether it is a villager</h2>
 *
 * <p>A villager wandering a village should tick normally, and a villager in a pen with room to walk should
 * too. Throttling all of them would mean villagers in the open visibly hesitating, which is a change to how
 * the game looks. What is being throttled is the case where the answer is always the same, and the way to
 * know that is to check whether there is anywhere to go.
 *
 * <h2>A player interacting restores it immediately</h2>
 *
 * <p>Opening a trading screen, feeding a villager, or hitting one all need it to respond on the tick it
 * happens. If the villager were throttled and only noticed at its next scheduled tick, the interaction would
 * take up to a second to register -- which the player would experience as the game ignoring them, and would
 * click again. So any interaction with a player forces a normal tick, whatever the throttle says.
 *
 * <h2>Nothing about the villager changes</h2>
 *
 * <p>Its profession, its trades, its prices and its level are all untouched. It is the same villager doing
 * the same things, with the decisions it cannot act on made less often. A change that altered what a villager
 * sells or what it costs would be a gameplay change, and this is not one.
 *
 * <h2>Why twenty ticks and not more</h2>
 *
 * <p>A villager that is throttled still has to notice the world around it -- a player arriving, a zombie at
 * the door, a bed becoming free. Once a second is often enough for all of those to be caught in time for
 * them to matter, and longer would start to be visible: a villager that takes three seconds to panic at a
 * zombie is a villager that gets eaten.
 *
 * <p>Not thread safe. Villagers tick on the server thread.
 */
public final class StuckVillagerThrottler {

    /** Ticks between updates for a villager that cannot move. */
    public static final int THROTTLE_INTERVAL = 20;

    /** Milliseconds one villager tick costs. */
    public static final double MS_PER_TICK = 0.05;

    private long ticks;
    private long normal;
    private long throttled;
    private long restored;

    /**
     * Whether a villager should tick this tick.
     *
     * @param canMove whether there is anywhere for it to go
     * @param playerInteracted whether a player opened its trades, fed it or hit it
     * @param tick the current tick, used to spread the throttled villagers across the interval
     * @param villagerId the villager's id, used to give it a phase within the interval
     */
    public boolean shouldTick(boolean canMove, boolean playerInteracted, long tick, int villagerId) {
        this.ticks++;

        if (playerInteracted) {
            // Opening a trading screen, feeding a villager or hitting one all need it to respond on the tick
            // it happens. If it only noticed at its next scheduled tick the interaction would take up to a
            // second to register, which the player would experience as the game ignoring them.
            this.restored++;
            this.normal++;
            return true;
        }
        if (canMove) {
            // A villager wandering a village should tick normally, and one in a pen with room to walk should
            // too. What is throttled is the case where the answer is always the same.
            this.normal++;
            return true;
        }
        boolean due = Math.floorMod(villagerId, THROTTLE_INTERVAL)
                == Math.floorMod(tick, THROTTLE_INTERVAL);
        if (due) {
            this.throttled++;
        }
        return due;
    }

    public long ticks() {
        return this.ticks;
    }

    /** How many were ticked normally. */
    public long normal() {
        return this.normal;
    }

    /** How many throttled ticks happened. */
    public long throttled() {
        return this.throttled;
    }

    /** How many were brought back because a player interacted. */
    public long restored() {
        return this.restored;
    }

    /** Milliseconds of villager ticking done. */
    public double msTicking() {
        return (this.normal + this.throttled) * MS_PER_TICK;
    }

    /** Milliseconds ticking every villager every tick would cost. */
    public double msWithoutThrottling() {
        return this.ticks * MS_PER_TICK;
    }

    /**
     * The share of the villager ticking avoided.
     *
     * <p>Zero before any villager has been offered. A throttler that has been offered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutThrottling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msTicking()) / without;
    }
}
