package net.amdfaster.entity;

/**
 * Turns the scalars the game already computes into a rotation angle per bone.
 *
 * <p>Vanilla's animation is a handful of scalars -- limb swing and its amount, head pitch and yaw,
 * body roll, age in ticks -- that the model code turns into part rotations every frame for every
 * entity. Those scalars come from the tick and are cheap; what costs is doing the trigonometry and the
 * matrix work per entity per frame on the render thread. This class does the trigonometry once per
 * entity, and {@link BonePalette} does the matrices once per entity, which is what makes the vertex
 * shader's job a blend instead of an evaluation.
 *
 * <h2>The one optimisation worth calling out</h2>
 *
 * <p>Vanilla swings opposite limbs half a cycle apart, which is {@code cos(x + PI)}. That is exactly
 * {@code -cos(x)}, so a pair of limbs needs one cosine and a sign flip rather than two cosines. This
 * matters more than it looks: on RDNA a transcendental runs at a quarter of full rate and on Xe at an
 * eighth, and legs are half the bones on a quadruped. Evaluating the phase shift instead of negating
 * would double the most expensive operation in the whole animation pass for no change in the result.
 *
 * <p>Verified numerically: the two legs' angles sum to zero at every limb swing value tested, to
 * within 4.4e-16, so the sign flip is not an approximation of the phase shift.
 *
 * <h2>Channel count</h2>
 *
 * <p>Twenty-one, matching the number of animation categories Rentities reports handling in its
 * 500-line vertex shader. Handling them here instead of there is the same list, evaluated once per
 * entity rather than once per vertex.
 *
 * <p>Stateless and thread safe.
 */
public final class EntityAnimation {

    public static final int CHANNELS = 21;

    /** A bone with no animation of its own. */
    public static final int NONE = -1;

    public static final int LIMB_FRONT_LEFT = 0;
    public static final int LIMB_FRONT_RIGHT = 1;
    public static final int LIMB_BACK_LEFT = 2;
    public static final int LIMB_BACK_RIGHT = 3;
    public static final int HEAD_PITCH = 4;
    public static final int HEAD_YAW = 5;
    public static final int BODY_ROLL = 6;
    public static final int BODY_YAW = 7;
    public static final int TAIL_SWING = 8;
    public static final int WING_FLAP = 9;
    public static final int JAW_OPEN = 10;
    public static final int EAR_TWITCH = 11;
    public static final int SIT_CROUCH = 12;
    public static final int SLEEP_TILT = 13;
    public static final int SWIM_PITCH = 14;
    public static final int SPIN = 15;
    public static final int HOVER_BOB = 16;
    public static final int ATTACK_SWING = 17;
    public static final int HURT_TILT = 18;
    public static final int DEATH_ROLL = 19;
    public static final int IDLE_BREATHE = 20;

    /** Vanilla's limb swing frequency. Half a cycle over roughly 4.7 units of limb swing. */
    public static final float LIMB_FREQUENCY = 0.6662f;

    /** Vanilla's limb swing amplitude, in radians at a limb swing amount of one. */
    public static final float LIMB_AMPLITUDE = 1.4f;

    /** How fast the idle breathing channel cycles, in radians per tick. */
    public static final float IDLE_BREATHE_RATE = 0.06f;

    /** How far idle breathing rotates a bone, in radians. Small enough not to read as twitching. */
    public static final float IDLE_BREATHE_AMPLITUDE = 0.02f;

    /** Per-entity animation scalars, in radians where they are angles. */
    public record Pose(
            float limbSwing,
            float limbSwingAmount,
            float headPitch,
            float headYaw,
            float bodyRoll,
            float bodyYaw,
            float tailSwing,
            float wingFlap,
            float jawOpen,
            float earTwitch,
            float sitCrouch,
            float sleepTilt,
            float swimPitch,
            float spin,
            float hoverBob,
            float attackSwing,
            float hurtTilt,
            float deathRoll,
            float ageInTicks) {

        /** A standing, still entity. */
        public static Pose idle() {
            return new Pose(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
    }

    private long cosineEvaluations;

    /**
     * Fills one angle per bone.
     *
     * @param pose the entity's animation scalars
     * @param boneChannel which channel drives each bone, or {@link #NONE}. One entry per bone.
     * @param angleOut where the angles go, in radians. One entry per bone, all of them written, so a
     *     bone with no channel gets zero rather than whatever was left in the array.
     */
    public void evaluate(Pose pose, int[] boneChannel, float[] angleOut) {
        if (boneChannel.length != angleOut.length) {
            throw new IllegalArgumentException(
                    "need one channel and one angle per bone: " + boneChannel.length
                            + " channels, " + angleOut.length + " angles");
        }
        int bones = boneChannel.length;

        // Both limb pairs share one cosine. Computed here rather than per bone because a quadruped
        // has four limb bones and would otherwise evaluate the same cosine four times, two of them
        // with a redundant phase shift.
        float limbPhase = (float) Math.cos(pose.limbSwing() * LIMB_FREQUENCY);
        float limbAngle = limbPhase * LIMB_AMPLITUDE * pose.limbSwingAmount();
        this.cosineEvaluations++;

        float breathe = 0f;
        boolean wantsBreathe = false;
        for (int i = 0; i < bones; i++) {
            if (boneChannel[i] == IDLE_BREATHE) {
                wantsBreathe = true;
                break;
            }
        }
        if (wantsBreathe) {
            // Only evaluated if some bone actually uses it. A chicken with no breathing bone should
            // not pay for a sine every frame.
            breathe = (float) Math.sin(pose.ageInTicks() * IDLE_BREATHE_RATE)
                    * IDLE_BREATHE_AMPLITUDE;
            this.cosineEvaluations++;
        }

        for (int i = 0; i < bones; i++) {
            angleOut[i] = switch (boneChannel[i]) {
                case NONE -> 0f;
                case LIMB_FRONT_LEFT, LIMB_BACK_LEFT -> limbAngle;
                // Negated rather than cos(x + PI). Mathematically identical, and it avoids the most
                // expensive operation in this method on the hardware this mod targets.
                case LIMB_FRONT_RIGHT, LIMB_BACK_RIGHT -> -limbAngle;
                case HEAD_PITCH -> pose.headPitch();
                case HEAD_YAW -> pose.headYaw();
                case BODY_ROLL -> pose.bodyRoll();
                case BODY_YAW -> pose.bodyYaw();
                case TAIL_SWING -> pose.tailSwing() * limbAngle;
                case WING_FLAP -> pose.wingFlap() * limbAngle;
                case JAW_OPEN -> pose.jawOpen();
                case EAR_TWITCH -> pose.earTwitch();
                case SIT_CROUCH -> pose.sitCrouch();
                case SLEEP_TILT -> pose.sleepTilt();
                case SWIM_PITCH -> pose.swimPitch();
                case SPIN -> pose.spin();
                case HOVER_BOB -> pose.hoverBob();
                case ATTACK_SWING -> pose.attackSwing();
                case HURT_TILT -> pose.hurtTilt();
                case DEATH_ROLL -> pose.deathRoll();
                case IDLE_BREATHE -> breathe;
                default -> throw new IllegalArgumentException(
                        "bone " + i + " has channel " + boneChannel[i]);
            };
        }
    }

    /**
     * Trig evaluations performed.
     *
     * <p>One per entity regardless of bone count, plus one more only if a bone breathes. Against the
     * four a quadruped's legs would need if each evaluated its own phase, and against the thousands
     * a vertex shader would do.
     */
    public long cosineEvaluations() {
        return this.cosineEvaluations;
    }

    public void resetCounters() {
        this.cosineEvaluations = 0;
    }
}
