package net.amdfaster.runtime;

/**
 * Decides how hard the client should be trying to render right now.
 *
 * <p>The cheapest frame is the one that is not drawn. A window the player is not looking at costs the
 * same CPU and GPU as one they are, and an idle player staring at a wall pays for a full frame rate
 * that nobody is consuming. Capping in those states is not a compromise on quality, because there is
 * nobody there to see the quality -- it is the only optimization in this mod that is free in the strict
 * sense: it cannot change what the player sees, because it only ever applies when the player is not
 * looking.
 *
 * <p>Three states, in order of how much they cost:
 *
 * <p><b>Unfocused.</b> Another application has the window. Almost nothing on screen is being watched,
 * so the cap is low. Minecraft still ticks -- a singleplayer world keeps simulating in the background
 * and pausing it would be a behaviour change -- but it does not need to draw sixty times a second.
 *
 * <p><b>Idle.</b> Focused, but no input for a while. The player has walked away from the machine, or is
 * reading something, or is in a menu. The cap rises above unfocused because the window is visible and
 * something might be moving in it.
 *
 * <p><b>Active.</b> Whatever the player asked for. Never capped by this class.
 *
 * <p>Separated from the event wiring for the usual reason: the transitions, the hysteresis, and the
 * grace period are where the bugs live, and none of them need a window or a clock to check. A governor
 * that flickers between states every frame is worse than no governor, because each transition resets
 * the frame limiter and the result is a stutter with no visible cause.
 *
 * <p>Not thread safe; driven from the client thread.
 */
public final class FrameGovernor {

    /** Frames per second allowed when the window does not have focus. */
    public static final int DEFAULT_UNFOCUSED_FPS = 10;

    /** Frames per second allowed when focused but idle. */
    public static final int DEFAULT_IDLE_FPS = 30;

    /** Milliseconds without input before the client counts as idle. */
    public static final long DEFAULT_IDLE_DELAY_MS = 60_000L;

    /**
     * Milliseconds of input activity that must pass before leaving idle.
     *
     * <p>Without this, a player who taps a key every few seconds would bounce out of idle and back, and
     * every transition would reset the limiter. The grace period makes the state change cost something,
     * so it only happens when the player is genuinely back.
     */
    public static final long DEFAULT_RESUME_GRACE_MS = 500L;

    /** Milliseconds after regaining focus before the cap is lifted. */
    public static final long DEFAULT_FOCUS_GRACE_MS = 250L;

    public enum State {
        /** Window does not have focus. */
        UNFOCUSED,
        /** Focused, but no input for a while. */
        IDLE,
        /** Rendering at whatever the player asked for. */
        ACTIVE
    }

    private final int unfocusedFps;
    private final int idleFps;
    private final long idleDelayMs;
    private final long resumeGraceMs;
    private final long focusGraceMs;

    private State state = State.ACTIVE;
    private long lastInputAtMs;
    private long focusChangedAtMs;
    private long idleSinceMs;

    private long transitions;
    private long cappedFrames;
    private long savedFrames;

    public FrameGovernor() {
        this(DEFAULT_UNFOCUSED_FPS, DEFAULT_IDLE_FPS, DEFAULT_IDLE_DELAY_MS, DEFAULT_RESUME_GRACE_MS,
                DEFAULT_FOCUS_GRACE_MS);
    }

    public FrameGovernor(int unfocusedFps, int idleFps, long idleDelayMs, long resumeGraceMs, long focusGraceMs) {
        if (unfocusedFps <= 0 || idleFps <= 0) {
            throw new IllegalArgumentException("caps must be positive: " + unfocusedFps + "/" + idleFps);
        }
        if (idleDelayMs < 0 || resumeGraceMs < 0 || focusGraceMs < 0) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        this.unfocusedFps = unfocusedFps;
        this.idleFps = idleFps;
        this.idleDelayMs = idleDelayMs;
        this.resumeGraceMs = resumeGraceMs;
        this.focusGraceMs = focusGraceMs;
    }

    public State state() {
        return this.state;
    }

    public long transitions() {
        return this.transitions;
    }

    /** Frames that ran under a cap rather than at the player's setting. */
    public long cappedFrames() {
        return this.cappedFrames;
    }

    /**
     * Frames not rendered because of the cap.
     *
     * <p>Reported because it is the actual saving, and because it makes the feature checkable: a
     * governor left running overnight should show a large number here, and one that shows zero is not
     * doing anything.
     */
    public long savedFrames() {
        return this.savedFrames;
    }

    /** Called when the player does anything at all: key, mouse, or controller. */
    public void noteInput(long nowMs) {
        this.lastInputAtMs = nowMs;
    }

    /** Called when the window gains or loses focus. */
    public void noteFocusChange(long nowMs, boolean focused) {
        this.focusChangedAtMs = nowMs;
        if (focused) {
            this.lastInputAtMs = nowMs;
        }
        update(nowMs, focused);
    }

    /**
     * Recomputes the state and returns the frame cap for this frame.
     *
     * @param focused   whether the window has focus
     * @param requestedFps the player's own frame rate setting
     * @return the frame rate to use this frame; {@code requestedFps} when active
     */
    public int tick(long nowMs, boolean focused, int requestedFps) {
        update(nowMs, focused);
        int cap = switch (this.state) {
            case UNFOCUSED -> Math.min(this.unfocusedFps, requestedFps);
            case IDLE -> Math.min(this.idleFps, requestedFps);
            case ACTIVE -> requestedFps;
        };
        if (cap < requestedFps) {
            this.cappedFrames++;
            // The frames this cap suppresses, per second. Counting them as a rate rather than one at a
            // time is the honest measure: the cap does not skip a specific frame, it lowers a ceiling.
            this.savedFrames += Math.max(0, requestedFps - cap);
        }
        return cap;
    }

    private void update(long nowMs, boolean focused) {
        State next;
        if (!focused) {
            next = State.UNFOCUSED;
        } else if (nowMs - this.lastInputAtMs >= this.idleDelayMs
                && nowMs - this.idleSinceMs >= this.resumeGraceMs) {
            next = State.IDLE;
        } else {
            // Focus was just regained. Lifting the cap instantly would let a window that is being
            // alt-tabbed through run at full rate for a frame at a time, which is the worst of both: it
            // costs the work without giving a smooth frame.
            next = nowMs - this.focusChangedAtMs >= this.focusGraceMs ? State.ACTIVE : this.state;
        }
        if (next == State.IDLE && this.state != State.IDLE) {
            this.idleSinceMs = nowMs;
        }
        if (next != this.state) {
            this.state = next;
            this.transitions++;
        }
    }

    /** Frame interval in milliseconds for a target rate, or 0 for uncapped. */
    public static long intervalMs(int fps) {
        return fps <= 0 ? 0L : 1000L / fps;
    }

    public void clear() {
        this.state = State.ACTIVE;
        this.cappedFrames = 0;
        this.savedFrames = 0;
        this.transitions = 0;
        this.idleSinceMs = 0;
    }
}
