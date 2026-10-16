package net.amdfaster.pacing;

/**
 * Lowers the frame rate while the game window does not have focus.
 *
 * <p>A game running in the background renders at its full frame rate for nobody. At two hundred frames a
 * second that is two hundred draws a second of a window the player is not looking at, competing for the GPU
 * with whatever they switched to -- a browser playing a video, a video call, a second game. The machine
 * spends power and heat on frames that are never seen, and the thing the player is actually using runs
 * worse for it.
 *
 * <p>Dropping to a few frames a second while unfocused hands that capacity back, and the game is unchanged
 * when the player returns.
 *
 * <h2>Focus returning restores the rate immediately</h2>
 *
 * <p>A player alt-tabbing back expects the game to respond when they look at it. If the frame rate stayed
 * low for a moment, the first thing they would see is a frozen frame that then unfreezes, which reads as the
 * game having hung while they were away. So the limit is lifted on the frame focus returns, not a tick or a
 * timer later.
 *
 * <h2>The game keeps ticking</h2>
 *
 * <p>Lowering the frame rate is not pausing. On a server the player is still connected and their character
 * is still in the world; pausing the tick would leave them standing still while things happen around them,
 * and on a multiplayer server that is a disadvantage rather than a saving. So only the rendering rate
 * changes, and the game logic runs as it did.
 *
 * <h2>The limit is a floor, not zero</h2>
 *
 * <p>Stopping rendering entirely would mean a window that shows a frozen image, and a player glancing at a
 * second monitor would see a game that looks crashed. A few frames a second keeps it visibly alive -- the
 * clouds move, the hotbar updates -- at a fraction of the cost.
 *
 * <h2>Why the rate is not derived from the frame rate setting</h2>
 *
 * <p>A player who has capped the game at thirty frames a second has already made a choice about how much
 * the game costs, and scaling that down further when unfocused would make the background rate depend on a
 * setting chosen for the foreground. The unfocused limit is its own number, so the background behaviour is
 * the same whatever the player has chosen for play.
 *
 * <p>Thread safety: focus changes are reported on the client thread and the limit is read on the render
 * thread, so the flag is volatile.
 */
public final class UnfocusedFrameLimiter {

    /** Frames per second while the window does not have focus. */
    public static final int UNFOCUSED_FPS = 5;

    /** Milliseconds one frame costs to render. */
    public static final double MS_PER_FRAME = 5.0;

    private volatile boolean focused = true;

    private long framesFocused;
    private long framesUnfocused;
    private long focusChanges;

    /**
     * Records a change of focus.
     *
     * <p>The limit is lifted on the frame focus returns, not a tick or a timer later. A player alt-tabbing
     * back expects the game to respond when they look at it, and a frame rate that stayed low for a moment
     * would show a frozen frame that then unfreezes, which reads as the game having hung while they were
     * away.
     */
    public void setFocused(boolean focused) {
        if (this.focused != focused) {
            this.focusChanges++;
        }
        this.focused = focused;
    }

    public boolean isFocused() {
        return this.focused;
    }

    /**
     * The frame rate limit for the current frame.
     *
     * @param setting the player's own frame rate cap, which applies while focused
     * @return the limit to apply
     */
    public int limit(int setting) {
        if (setting <= 0) {
            throw new IllegalArgumentException("a positive frame rate is required: " + setting);
        }
        if (this.focused) {
            this.framesFocused++;
            // The player's own choice. Scaling it down would make the background rate depend on a setting
            // chosen for the foreground.
            return setting;
        }
        this.framesUnfocused++;
        // A floor rather than zero: stopping rendering entirely would show a frozen image, and a player
        // glancing at a second monitor would see a game that looks crashed. A few frames a second keeps it
        // visibly alive at a fraction of the cost.
        return UNFOCUSED_FPS;
    }

    public long framesFocused() {
        return this.framesFocused;
    }

    public long framesUnfocused() {
        return this.framesUnfocused;
    }

    /** How many times focus changed. */
    public long focusChanges() {
        return this.focusChanges;
    }

    /**
     * Milliseconds of rendering avoided per second spent unfocused.
     *
     * <p>Compared against the player's own setting, because that is what would have been rendered.
     */
    public double msSavedPerSecond(int setting) {
        if (setting <= 0) {
            throw new IllegalArgumentException("a positive frame rate is required: " + setting);
        }
        int avoided = Math.max(0, setting - UNFOCUSED_FPS);
        return avoided * MS_PER_FRAME;
    }

    /**
     * The share of the rendering cost avoided while unfocused.
     *
     * <p>Zero before any frame has been asked for. A limiter that has rendered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent(int setting) {
        if (setting <= 0) {
            throw new IllegalArgumentException("a positive frame rate is required: " + setting);
        }
        long frames = this.framesFocused + this.framesUnfocused;
        if (frames == 0) {
            return 0.0;
        }
        // Only the unfocused frames save anything; the focused ones cost exactly what they would have.
        return 100.0 * this.framesUnfocused * (setting - UNFOCUSED_FPS) / (frames * setting);
    }
}
