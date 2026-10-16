package net.amdfaster.sound;

/**
 * Prefers the sounds in front of the player when the pool cannot hold all of them.
 *
 * <p>When channels run out, something has to be dropped, and the obvious rule is distance -- keep the near
 * ones. But two sounds at the same distance are not equally worth a channel. The one the player is looking at
 * belongs to something they can see and are probably about to interact with; the one behind them belongs to
 * something they cannot see and have not decided anything about. Dropping the one behind costs less.
 *
 * <p>Preferring the front means the player never misses the sound of the thing they are looking at, which is
 * the case where a missing sound is most noticeable.
 *
 * <h2>Behind is not the same as unimportant</h2>
 *
 * <p>A creeper behind the player is exactly the sound the player most needs to hear, and dropping everything
 * rearward would remove it. So direction is a tie-breaker applied when the pool is contested, not a reason to
 * refuse a sound outright, and the critical sounds are handled by
 * {@link CriticalSoundWhitelist} before this is ever consulted.
 *
 * <h2>The field of view is the player's setting</h2>
 *
 * <p>A player who has set a wide field of view sees more of the world, and a sound at the edge of that view is
 * attached to something they can see. Using a fixed angle would put sounds outside their vision but inside
 * their view in the wrong bucket, and the player would hear a sound dropped for something they were looking
 * at.
 *
 * <h2>Distance still comes first</h2>
 *
 * <p>A near sound behind the player beats a distant one in front of them. What the player can hear clearly is
 * more informative than what they can see faintly, and prioritising direction over distance would keep a
 * sound at the far edge of the render distance while dropping one three blocks away.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) prioritises sounds directly in front of the player within
 * their field of view. This is an independent implementation of the same behaviour.
 */
public final class SoundDirectionPrioritizer {

    /** Degrees of margin around the field of view, so a sound at its edge still counts as in front. */
    public static final double FOV_MARGIN_DEGREES = 10.0;

    /** How much closer a rear sound has to be to outrank one in front. */
    public static final double REAR_PENALTY = 1.0;

    private long compared;
    private long frontChosen;
    private long rearChosen;

    /**
     * The angle between where the player is looking and where the sound is.
     *
     * @param yaw the direction the player faces, in degrees
     * @param toSoundX the horizontal offset to the sound
     * @param toSoundZ the horizontal offset to the sound
     * @return an angle between 0 and 180
     */
    public static double angleFromFacing(double yaw, double toSoundX, double toSoundZ) {
        if (toSoundX == 0.0 && toSoundZ == 0.0) {
            // The sound is at the player, so there is no direction to compare and it counts as in front.
            return 0.0;
        }
        double toSound = Math.toDegrees(Math.atan2(toSoundX, -toSoundZ));
        double difference = Math.abs(yaw - toSound) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }

    /**
     * Whether a sound counts as being in front of the player.
     *
     * @param angle the angle from where the player is facing
     * @param fieldOfView the player's own field of view setting, in degrees
     */
    public static boolean inFront(double angle, double fieldOfView) {
        if (fieldOfView <= 0.0) {
            throw new IllegalArgumentException("a positive field of view is required: " + fieldOfView);
        }
        // A player who has set a wide field of view sees more of the world, and a sound at the edge of that
        // view is attached to something they can see. A fixed angle would put sounds outside their vision
        // but inside their view in the wrong bucket.
        return angle <= fieldOfView / 2.0 + FOV_MARGIN_DEGREES;
    }

    /**
     * Chooses between two sounds when only one channel is left.
     *
     * @param frontDistance how far the sound in front is
     * @param rearDistance how far the sound behind is
     * @return true if the one in front should get the channel
     */
    public boolean chooseFront(double frontDistance, double rearDistance) {
        if (frontDistance < 0.0 || rearDistance < 0.0) {
            throw new IllegalArgumentException(
                    "a negative distance: " + frontDistance + ", " + rearDistance);
        }
        this.compared++;

        // A near sound behind the player beats a distant one in front of them. What the player can hear
        // clearly is more informative than what they can see faintly, and prioritising direction over
        // distance would keep a sound at the far edge of the render distance while dropping one three
        // blocks away.
        boolean frontWins = frontDistance <= rearDistance * REAR_PENALTY;
        if (frontWins) {
            this.frontChosen++;
        } else {
            this.rearChosen++;
        }
        return frontWins;
    }

    public long compared() {
        return this.compared;
    }

    /** How many times the sound in front won. */
    public long frontChosen() {
        return this.frontChosen;
    }

    /** How many times the nearer sound behind won. */
    public long rearChosen() {
        return this.rearChosen;
    }

    /**
     * The share of the contested channels the front sound won.
     *
     * <p>Zero before any choice has been made. A prioritiser that has chosen nothing has technically chosen
     * nothing, and reporting that as a rate would describe an unused one as though it had a result.
     */
    public double frontPercent() {
        return this.compared == 0 ? 0.0 : 100.0 * this.frontChosen / this.compared;
    }
}
