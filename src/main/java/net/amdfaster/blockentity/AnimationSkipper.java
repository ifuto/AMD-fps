package net.amdfaster.blockentity;

/**
 * Stops drawing a block entity's animation while it is not moving.
 *
 * <p>A chest lid animates when it opens and closes, and for the rest of its life it is still. But the
 * renderer does not know that: it evaluates the animation every frame, builds the lid's geometry at
 * whatever angle the animation says, and draws it. In a storage room where nobody has opened a chest in
 * minutes, every chest is still being animated sixty times a second, and the animation always evaluates to
 * the same closed position.
 *
 * <p>Detecting that the lid has not moved and drawing the closed geometry directly removes the animation
 * evaluation and lets the lid be drawn as part of the section, which is what makes the saving more than the
 * cost of one trigonometric function.
 *
 * <h2>Still means still, not slow</h2>
 *
 * <p>A lid halfway through closing is moving, however slowly, and freezing it would leave a chest visibly
 * stuck open -- which is a rendering bug the player notices immediately, and one that looks like the game
 * has stopped responding to the click. So the check is whether the position has changed at all, not whether
 * it has changed by much.
 *
 * <h2>The animation resumes the moment it moves</h2>
 *
 * <p>A player clicks, the lid starts to open, and the very next frame has to show it moving. If resumption
 * waited for a tick or a timer, the lid would appear to lag the click, which is exactly the feedback the
 * animation exists to give. So a change of position resumes the animation immediately, in the same frame
 * it is detected.
 *
 * <h2>Why this is not the same as baking</h2>
 *
 * <p>Baking puts the static geometry into the section mesh, which is right for the parts that never move.
 * A lid does move, sometimes, so it cannot be baked -- but while it is still, drawing it as though it were
 * static is indistinguishable from drawing the animated version at angle zero. This is the case baking
 * cannot cover and skipping can.
 *
 * <p>Not thread safe. Rendering runs on the render thread.
 */
public final class AnimationSkipper {

    /** Milliseconds evaluating one block entity's animation costs per frame. */
    public static final double MS_PER_ANIMATION = 0.003;

    /** Ticks an animation takes to finish, so a lid is moving for about fifteen frames. */
    public static final int ANIMATION_TICKS = 15;

    private long frames;
    private long skipped;
    private long animated;
    private long resumed;

    /**
     * Whether a block entity's animation should be evaluated this frame.
     *
     * @param position the animation's current value, such as a lid angle
     * @param previous the value it had last frame
     * @return true if the animation must be evaluated and drawn
     */
    public boolean shouldAnimate(double position, double previous) {
        this.frames++;

        if (position != previous) {
            // Moving, however slowly. Freezing a lid halfway through closing would leave a chest visibly
            // stuck open, which looks like the game has stopped responding to the click.
            this.animated++;
            if (this.skipped > 0) {
                // Resumed in the same frame the change was detected. Waiting for a tick or a timer would
                // make the lid lag the click, which is exactly the feedback the animation exists to give.
                this.resumed++;
            }
            return true;
        }

        this.skipped++;
        return false;
    }

    public long frames() {
        return this.frames;
    }

    /** How many frames drew the static geometry instead of evaluating the animation. */
    public long skipped() {
        return this.skipped;
    }

    /** How many frames evaluated the animation. */
    public long animated() {
        return this.animated;
    }

    /** How many times a still animation started moving again. */
    public long resumed() {
        return this.resumed;
    }

    public double skippedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.skipped / this.frames;
    }

    /** Milliseconds of animation evaluation avoided in total. */
    public double msSaved() {
        return this.skipped * MS_PER_ANIMATION;
    }

    /** Milliseconds evaluating the animation on every frame would have cost. */
    public double msWithoutSkipping() {
        return this.frames * MS_PER_ANIMATION;
    }

    /**
     * The share of the animation evaluation avoided.
     *
     * <p>Zero before any frame has been asked about. A skipper that has been asked nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = msWithoutSkipping();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msSaved() / without;
    }
}
