package net.amdfaster.cull;

/**
 * Decides when the previous frame's depth buffer has stopped describing the world the player is
 * looking at.
 *
 * <p>Two-pass occlusion culling is built on reusing last frame's depth: pass one redraws what was
 * visible before, the pyramid is built from that, and pass two rejects everything behind it. That
 * reuse is what makes pass one cheap, because its cost tracks the visible set instead of the whole
 * world. It is also the assumption that breaks.
 *
 * <p>An ender pearl, a {@code /tp}, a respawn, a dimension change or simply a fast mouse flick moves
 * the camera somewhere the old depth says nothing about. Culling against it hides geometry that is
 * actually in plain view, and because the pyramid is rebuilt from what survived, the mistake
 * propagates: culled geometry never gets redrawn, so it never gets back into the pyramid, so it stays
 * culled. That is the artefact that makes people turn occlusion culling off, and it is not a
 * rendering bug in any pass -- it is a stale input.
 *
 * <p>The fix is to notice the discontinuity and fall back to a depth prepass for a frame or two:
 * render the frustum-visible set to depth first, build the pyramid from that, then cull against
 * something current. It costs one extra pass on exactly the frames where the alternative is drawing
 * the wrong picture.
 *
 * <p>Not thread-safe.
 */
public final class CameraCutDetector {

    /**
     * Camera movement, in blocks, that counts as a teleport.
     *
     * <p>Walking is under five blocks a second and sprinting under six, so at sixty frames a second
     * no legitimate movement comes near this. Falling can, which is correct: a long fall into a cave
     * really does invalidate the old depth.
     */
    public static final float POSITION_JUMP_BLOCKS = 8.0f;

    /**
     * View rotation, in radians, that counts as a flick.
     *
     * <p>Forty-five degrees in one frame is a whip of the mouse, not a turn of the head. Below that
     * the old depth still overlaps the new view enough to be worth reusing, and reusing it is the
     * entire point of the two-pass scheme.
     */
    public static final float ANGLE_JUMP_RADIANS = (float) (Math.PI / 4.0);

    /**
     * How many frames to keep using a depth prepass after a cut.
     *
     * <p>More than one, because the frame after a cut is the first to be culled against a pyramid
     * built from a prepass, and one frame of over-culling is enough to leave a hole that the next
     * frame's pyramid inherits.
     */
    public static final int RECOVERY_FRAMES = 2;

    private static final float TWO_PI = (float) (2.0 * Math.PI);

    private boolean started;
    private float lastX;
    private float lastY;
    private float lastZ;
    private float lastYaw;
    private float lastPitch;
    private int framesSinceCut = Integer.MAX_VALUE;
    private boolean cut;

    /**
     * Records this frame's camera and reports whether it counts as a cut.
     *
     * <p>The first call is always a cut: there is no previous frame to reuse, so there is nothing to
     * be conservative about.
     *
     * @return true when the caller must not reuse the previous frame's depth
     */
    public boolean update(float x, float y, float z, float yaw, float pitch) {
        if (!this.started) {
            this.started = true;
            this.cut = true;
            this.framesSinceCut = 0;
            store(x, y, z, yaw, pitch);
            return true;
        }

        float dx = x - this.lastX;
        float dy = y - this.lastY;
        float dz = z - this.lastZ;
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        boolean moved = distanceSquared > POSITION_JUMP_BLOCKS * POSITION_JUMP_BLOCKS;
        boolean turned = wrappedDelta(yaw, this.lastYaw) > ANGLE_JUMP_RADIANS
                || wrappedDelta(pitch, this.lastPitch) > ANGLE_JUMP_RADIANS;

        this.cut = moved || turned;
        this.framesSinceCut = this.cut ? 0 : saturatingIncrement(this.framesSinceCut);
        store(x, y, z, yaw, pitch);
        return this.cut;
    }

    private void store(float x, float y, float z, float yaw, float pitch) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastYaw = yaw;
        this.lastPitch = pitch;
    }

    private static int saturatingIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    /**
     * The absolute difference between two angles, in {@code [0, pi]}.
     *
     * <p>Minecraft's yaw is unbounded -- it keeps accumulating as the player spins -- so a turn from
     * 6.28 to 0.01 is a movement of 0.01 radians and not a full circle. Comparing the raw values
     * would report a cut on every wrap and disable occlusion culling permanently for a player who
     * happens to be facing the right direction.
     */
    static float wrappedDelta(float a, float b) {
        float delta = (a - b) % TWO_PI;
        if (delta < 0f) {
            delta += TWO_PI;
        }
        return delta > (float) Math.PI ? TWO_PI - delta : delta;
    }

    /** True on the frame a cut was detected. */
    public boolean isCut() {
        return this.cut;
    }

    /** Frames since the last cut, saturating rather than wrapping. */
    public int framesSinceCut() {
        return this.framesSinceCut;
    }

    /**
     * Whether this frame needs a depth prepass before it can cull.
     *
     * <p>True on the cut itself and for {@link #RECOVERY_FRAMES} frames afterwards. While it is true
     * the caller builds the pyramid from a prepass of the frustum-visible set rather than from the
     * previous frame's survivors, and skips the occlusion reject so nothing is lost to a stale input.
     */
    public boolean needsDepthPrepass() {
        return this.framesSinceCut < RECOVERY_FRAMES;
    }

    /** Forgets the camera history, so the next {@link #update} is a cut. Use on respawn or resize. */
    public void invalidate() {
        this.started = false;
        this.framesSinceCut = Integer.MAX_VALUE;
        this.cut = false;
    }
}
