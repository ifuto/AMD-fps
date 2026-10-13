package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityAnimationTest {

    private static final float EPS = 1e-6f;

    private static final int[] QUADRUPED_CHANNELS = {
        EntityAnimation.NONE,          // root
        EntityAnimation.BODY_ROLL,     // body
        EntityAnimation.HEAD_PITCH,    // head
        EntityAnimation.LIMB_FRONT_LEFT,
        EntityAnimation.LIMB_FRONT_RIGHT,
    };

    @Test
    void oppositeLimbsSwingOutOfPhaseByNegation() {
        // The whole point. cos(x + PI) is -cos(x), so a pair of legs costs one cosine. Verified
        // numerically across a full cycle before being written this way.
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[QUADRUPED_CHANNELS.length];
        float[] limbSwings = {0f, 1.5708f, 3.1416f, 6.2832f, 12.5f};

        for (float limbSwing : limbSwings) {
            EntityAnimation.Pose pose = new EntityAnimation.Pose(
                    limbSwing, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            animation.evaluate(pose, QUADRUPED_CHANNELS, angle);

            float left = angle[3];
            float right = angle[4];
            assertEquals(-left, right, EPS, "limb swing " + limbSwing);
            assertEquals(0f, left + right, 1e-5f, "the pair cancels at " + limbSwing);
        }
    }

    @Test
    void limbAnglesMatchVanillasFormula() {
        // cos(limbSwing * 0.6662) * 1.4 * limbSwingAmount, which is what vanilla's leg parts use.
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[QUADRUPED_CHANNELS.length];
        float limbSwing = 3.1416f;
        EntityAnimation.Pose pose = new EntityAnimation.Pose(
                limbSwing, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        animation.evaluate(pose, QUADRUPED_CHANNELS, angle);

        assertEquals(-0.698222f, angle[3], EPS, "cos(3.1416 * 0.6662) * 1.4");
    }

    @Test
    void aStillEntityDoesNotSwingHoweverFarItHasWalked() {
        // limbSwingAmount goes to zero when an entity stops. limbSwing keeps its value, so a
        // formula that ignored the amount would leave the legs frozen mid-stride forever.
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[QUADRUPED_CHANNELS.length];
        EntityAnimation.Pose pose = new EntityAnimation.Pose(
                47.3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        animation.evaluate(pose, QUADRUPED_CHANNELS, angle);

        assertEquals(0f, angle[3], EPS);
        assertEquals(0f, angle[4], EPS);
    }

    @Test
    void theCosineIsEvaluatedOncePerEntityNotOncePerBone() {
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[QUADRUPED_CHANNELS.length];
        animation.evaluate(EntityAnimation.Pose.idle(), QUADRUPED_CHANNELS, angle);

        // One for the limbs. No breathing bone here, so no second one. Four limb bones on a real
        // quadruped would still be one, which is the saving.
        assertEquals(1L, animation.cosineEvaluations());
    }

    @Test
    void breathingIsOnlyEvaluatedWhenABoneUsesIt() {
        int[] withBreathing = {EntityAnimation.IDLE_BREATHE, EntityAnimation.NONE};
        int[] without = {EntityAnimation.NONE, EntityAnimation.NONE};
        EntityAnimation.Pose pose = new EntityAnimation.Pose(
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 40f);

        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[2];
        animation.evaluate(pose, without, angle);
        assertEquals(1L, animation.cosineEvaluations(), "no breathing bone, no sine");

        animation.resetCounters();
        animation.evaluate(pose, withBreathing, angle);
        assertEquals(2L, animation.cosineEvaluations());
        assertEquals((float) Math.sin(40f * EntityAnimation.IDLE_BREATHE_RATE)
                * EntityAnimation.IDLE_BREATHE_AMPLITUDE, angle[0], EPS);
    }

    @Test
    void everyBoneGetsAnAngleEvenWhenItHasNoChannel() {
        // A bone left at whatever the array held last frame would keep the previous entity's pose.
        // With a reused array across entities that is a limb stuck at another animal's angle.
        EntityAnimation animation = new EntityAnimation();
        float[] angle = {99f, 99f, 99f, 99f, 99f};
        animation.evaluate(EntityAnimation.Pose.idle(), QUADRUPED_CHANNELS, angle);

        for (int i = 0; i < angle.length; i++) {
            assertEquals(0f, angle[i], EPS, "bone " + i + " was overwritten");
        }
    }

    @Test
    void channelsThatReadAScalarPassItThroughUnscaled() {
        int[] channels = {EntityAnimation.HEAD_PITCH, EntityAnimation.HEAD_YAW,
                          EntityAnimation.JAW_OPEN, EntityAnimation.SIT_CROUCH};
        EntityAnimation.Pose pose = new EntityAnimation.Pose(
                0f, 0f, 0.3f, 0.5f, 0f, 0f, 0f, 0f, 0.7f, 0f,
                0.9f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[4];
        animation.evaluate(pose, channels, angle);

        assertEquals(0.3f, angle[0], EPS);
        assertEquals(0.5f, angle[1], EPS);
        assertEquals(0.7f, angle[2], EPS);
        assertEquals(0.9f, angle[3], EPS);
    }

    @Test
    void theChannelCountMatchesWhatRentitiesReports() {
        // Twenty-one animation categories. Pinned so adding a channel is a deliberate act that
        // updates the count rather than a silent change to a shader-facing contract.
        assertEquals(21, EntityAnimation.CHANNELS);
        assertEquals(0, EntityAnimation.LIMB_FRONT_LEFT);
        assertEquals(20, EntityAnimation.IDLE_BREATHE);
    }

    @Test
    void anUnknownChannelIsRejectedRatherThanIgnored() {
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[1];
        assertThrows(IllegalArgumentException.class,
                () -> animation.evaluate(EntityAnimation.Pose.idle(), new int[] {21}, angle));
        assertThrows(IllegalArgumentException.class,
                () -> animation.evaluate(EntityAnimation.Pose.idle(), new int[] {-2}, angle));
    }

    @Test
    void mismatchedChannelAndAngleArraysAreRejected() {
        EntityAnimation animation = new EntityAnimation();
        assertThrows(IllegalArgumentException.class, () -> animation.evaluate(
                EntityAnimation.Pose.idle(), new int[] {0, 0}, new float[] {0f}));
    }

    @Test
    void theIdlePoseLeavesEveryBoneAtRest() {
        int[] allChannels = new int[EntityAnimation.CHANNELS];
        for (int i = 0; i < allChannels.length; i++) {
            allChannels[i] = i;
        }
        EntityAnimation animation = new EntityAnimation();
        float[] angle = new float[allChannels.length];
        animation.evaluate(EntityAnimation.Pose.idle(), allChannels, angle);

        for (int i = 0; i < angle.length; i++) {
            assertEquals(0f, angle[i], EPS, "channel " + i + " is at rest when the pose is idle");
        }
        assertTrue(animation.cosineEvaluations() <= 2L);
    }
}
