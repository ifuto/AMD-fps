package net.amdfaster.blockentity;

/**
 * Refreshes an open container screen on a schedule rather than every tick.
 *
 * <p>While a chest is open, its contents are compared and sent every tick. With several players looking at
 * the same storage system, or one player with a double chest and a crafting grid, that is a comparison and a
 * packet each tick for a display that changes when an item moves -- which, in a chest nobody is touching, is
 * never.
 *
 * <p>Refreshing every third tick instead removes two thirds of that work, and the difference on screen is
 * three ticks of a display that was not changing anyway.
 *
 * <h2>A change forces an immediate refresh</h2>
 *
 * <p>A player who takes an item expects the slot to empty when they click, not fifty milliseconds later. A
 * display that lags the inventory feels like the click did not register, and the player clicks again -- which
 * in a chest with a full stack takes two items. So any change to the contents refreshes on the tick it
 * happens, and the schedule is only for the ticks where nothing moved.
 *
 * <h2>The schedule is for the display, not the inventory</h2>
 *
 * <p>The container itself is unchanged: it still holds what it holds, a hopper still pushes into it every
 * tick, and a player's click still applies immediately. What is throttled is the redraw of the screen
 * showing it. Slowing the inventory down would be a gameplay change; slowing the picture of it is not, as
 * long as the picture catches up the moment there is something to catch up to.
 *
 * <h2>Closing the screen stops the work entirely</h2>
 *
 * <p>An open screen is the only reason the refresh happens, so a closed one has nothing to refresh. Continuing
 * to compare the contents of a chest nobody is looking at would cost exactly what this exists to remove.
 *
 * <h2>Why three ticks and not more</h2>
 *
 * <p>A longer interval saves more and feels worse. Three ticks is a sixth of a second, which is below what a
 * player notices in a display they are not actively watching change, and beyond that the lag between a
 * hopper filling a chest and the player seeing it becomes visible. The saving is a constant fraction either
 * way, so the interval is chosen for feel rather than for the number.
 *
 * <p>Not thread safe. The screen is refreshed on the client thread.
 */
public final class ContainerScreenUpdater {

    /** Ticks between scheduled refreshes. */
    public static final int UPDATE_INTERVAL = 3;

    /** Milliseconds comparing and sending one container's contents costs. */
    public static final double MS_PER_REFRESH = 0.08;

    private long ticks;
    private long refreshed;
    private long forced;
    private long skippedClosed;

    /**
     * Whether an open container screen should be refreshed this tick.
     *
     * @param open whether a player is looking at it
     * @param contentsChanged whether an item moved since the last refresh
     */
    public boolean shouldRefresh(boolean open, boolean contentsChanged) {
        this.ticks++;

        if (!open) {
            // An open screen is the only reason the refresh happens. Continuing to compare the contents of a
            // chest nobody is looking at would cost exactly what this exists to remove.
            this.skippedClosed++;
            return false;
        }
        if (contentsChanged) {
            // A player who takes an item expects the slot to empty when they click, not fifty milliseconds
            // later. A display that lags the inventory feels like the click did not register, and the player
            // clicks again -- which in a chest with a full stack takes two items.
            this.forced++;
            this.refreshed++;
            return true;
        }
        if (this.ticks % UPDATE_INTERVAL == 0) {
            this.refreshed++;
            return true;
        }
        return false;
    }

    public long ticks() {
        return this.ticks;
    }

    /** How many refreshes happened. */
    public long refreshed() {
        return this.refreshed;
    }

    /** How many were brought forward because something moved. */
    public long forced() {
        return this.forced;
    }

    /** How many ticks did nothing because the screen was closed. */
    public long skippedClosed() {
        return this.skippedClosed;
    }

    /** Milliseconds of refreshing done. */
    public double msRefreshed() {
        return this.refreshed * MS_PER_REFRESH;
    }

    /** Milliseconds refreshing every open tick would cost. */
    public double msWithoutScheduling() {
        return (this.ticks - this.skippedClosed) * MS_PER_REFRESH;
    }

    /**
     * The share of the refresh cost avoided.
     *
     * <p>Zero before any tick has passed. A scheduler that has run no ticks has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>Closed ticks are not in the baseline, because nothing would have been refreshed for them either way.
     */
    public double savedPercent() {
        double without = msWithoutScheduling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msRefreshed()) / without;
    }
}
