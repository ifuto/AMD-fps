package net.amdfaster.render;

/**
 * Updates the HUD on its own schedule rather than once per frame.
 *
 * <p>The health bar, the hotbar, the crosshair and the debug overlay are rebuilt every frame, at whatever
 * rate the game is running. At two hundred frames a second that is two hundred rebuilds a second of geometry
 * that changes when the player takes damage, switches item or moves -- events that happen a few times a
 * second at most. The rest of the time the rebuild produces exactly what the last one produced.
 *
 * <p>Updating on a fixed schedule instead, and reusing the geometry between updates, removes most of that
 * work. The HUD is the one part of the screen that is cheap to keep static, because it is small and it is
 * already drawn on top of everything else.
 *
 * <h2>The player's own actions are not delayed</h2>
 *
 * <p>A health bar that lags the damage taken is worse than no health bar: the player acts on what it shows,
 * and a bar that still reads full while the health is gone leads to a death that looks like the game lied.
 * So taking damage, changing hunger, breaking an item and switching the held item all force an immediate
 * update rather than waiting for the next scheduled one. The schedule is for the frames where nothing
 * happened, not for the ones where something did.
 *
 * <h2>The debug overlay is not delayed either</h2>
 *
 * <p>It shows the frame time, and a HUD that updates every hundred milliseconds would smooth over exactly
 * the spikes it is there to reveal. So it updates every frame when it is open, which is a deliberate
 * exception rather than an oversight.
 *
 * <h2>Why the rate is a schedule and not a threshold</h2>
 *
 * <p>Updating only when something changed would need every possible change to be reported, and one that was
 * missed would leave the HUD showing a stale value indefinitely -- a stuck health bar that never recovers.
 * A schedule updates regardless, so the worst case is one interval of staleness, which is short enough not
 * to matter for anything that is not forced.
 *
 * <p>Not thread safe. The HUD is drawn on the render thread.
 */
public final class HudUpdateScheduler {

    /** Frames between scheduled updates. */
    public static final int UPDATE_INTERVAL = 10;

    /** Milliseconds rebuilding the HUD costs. */
    public static final double MS_PER_REBUILD = 0.15;

    private long frames;
    private long scheduled;
    private long forced;
    private long reused;

    /**
     * Whether the HUD should be rebuilt this frame.
     *
     * @param playerActed whether something the player can see happened -- damage, hunger, an item breaking
     *     or the held item changing
     * @param debugOpen whether the debug overlay is shown
     */
    public boolean shouldUpdate(boolean playerActed, boolean debugOpen) {
        this.frames++;

        if (playerActed) {
            // A health bar that lags the damage taken is worse than no health bar: the player acts on what
            // it shows, and one that still reads full while the health is gone leads to a death that looks
            // like the game lied.
            this.forced++;
            return true;
        }
        if (debugOpen) {
            // The overlay shows the frame time, and a HUD updating every hundred milliseconds would smooth
            // over exactly the spikes it is there to reveal.
            this.forced++;
            return true;
        }
        if (this.frames % UPDATE_INTERVAL == 0) {
            this.scheduled++;
            return true;
        }
        this.reused++;
        return false;
    }

    public long frames() {
        return this.frames;
    }

    /** How many updates happened on the schedule. */
    public long scheduled() {
        return this.scheduled;
    }

    /** How many were forced by something the player did or by the debug overlay. */
    public long forced() {
        return this.forced;
    }

    /** How many frames reused the existing HUD. */
    public long reused() {
        return this.reused;
    }

    public double reusedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reused / this.frames;
    }

    /** Milliseconds of HUD rebuilding avoided. */
    public double msSaved() {
        return this.reused * MS_PER_REBUILD;
    }

    /** Milliseconds rebuilding the HUD every frame would cost. */
    public double msWithoutScheduling() {
        return this.frames * MS_PER_REBUILD;
    }

    /**
     * The share of the HUD rebuilding avoided.
     *
     * <p>Zero before any frame has been drawn. A scheduler that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutScheduling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msSaved() / without;
    }
}
