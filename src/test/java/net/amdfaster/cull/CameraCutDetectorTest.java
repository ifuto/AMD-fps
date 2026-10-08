package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cut detector that never fires turns occlusion culling into a way of deleting the world after a
 * teleport. One that fires constantly turns it off entirely, which costs the frames it was added to
 * save. Both directions are tested, because both are easy to get wrong in the same line of code.
 */
class CameraCutDetectorTest {

    @Test
    void theFirstFrameIsAlwaysACut() {
        // There is no previous frame to reuse, so there is nothing to be conservative about.
        CameraCutDetector cuts = new CameraCutDetector();
        assertTrue(cuts.update(0f, 64f, 0f, 0f, 0f));
        assertTrue(cuts.isCut());
        assertEquals(0, cuts.framesSinceCut());
        assertTrue(cuts.needsDepthPrepass());
    }

    @Test
    void ordinaryMovementIsNotACut() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        // Sprinting is under six blocks a second; at sixty frames a second that is a tenth of a block.
        assertFalse(cuts.update(0.1f, 64f, 0.1f, 0f, 0f));
        assertFalse(cuts.update(0.2f, 64f, 0.2f, 0f, 0f));
        assertFalse(cuts.isCut());
    }

    @Test
    void aTeleportIsACut() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        assertFalse(cuts.update(7f, 64f, 0f, 0f, 0f), "seven blocks is inside the threshold");
        assertTrue(cuts.update(200f, 64f, 0f, 0f, 0f), "an ender pearl lands far away");
        assertTrue(cuts.isCut());
        assertEquals(0, cuts.framesSinceCut());
    }

    @Test
    void aLongFallCountsAsACut() {
        // Deliberate: dropping into a cave really does invalidate the old depth, and treating it as
        // ordinary movement would cull the cave floor against a pyramid built from the surface.
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 100f, 0f, 0f, 0f);
        assertTrue(cuts.update(0f, 80f, 0f, 0f, 0f));
    }

    @Test
    void aMouseFlickIsACutButTurningYourHeadIsNot() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        assertFalse(cuts.update(0f, 64f, 0f, 0.5f, 0f), "half a radian is a glance");
        assertTrue(cuts.update(0f, 64f, 0f, 2.0f, 0f), "one and a half radians more is a whip");
    }

    @Test
    void pitchIsCheckedIndependentlyOfYaw() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        assertTrue(cuts.update(0f, 64f, 0f, 0f, 1.2f), "looking sharply up or down invalidates too");
    }

    @Test
    void yawWrappingIsNotMistakenForATurn() {
        // Minecraft's yaw accumulates without bound as the player spins. Comparing raw values would
        // report a cut every time the angle crossed a multiple of two pi, and a player who happened
        // to be facing the right way would never get occlusion culling at all.
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 6.28f, 0f);
        assertFalse(cuts.update(0f, 64f, 0f, 0.01f, 0f), "6.28 to 0.01 is a movement of 0.01");
        CameraCutDetector wrapped = new CameraCutDetector();
        wrapped.update(0f, 64f, 0f, 100.0f, 0f);
        assertFalse(wrapped.update(0f, 64f, 0f, 100.0f + (float) (4 * Math.PI), 0f),
                "four pi of accumulated yaw is no rotation at all");
    }

    @Test
    void theWrappedDeltaIsAlwaysTheShortWayRound() {
        assertEquals(0f, CameraCutDetector.wrappedDelta(1f, 1f), 0f);
        assertEquals((float) Math.PI, CameraCutDetector.wrappedDelta(0f, (float) Math.PI), 1e-6f);
        assertEquals(0.1f, CameraCutDetector.wrappedDelta(0.1f, 0f), 1e-6f);
        assertEquals(0.1f, CameraCutDetector.wrappedDelta(0f, 0.1f), 1e-6f,
                "the delta is unsigned; which way round is not the detector's business");
        assertEquals(0.1f, CameraCutDetector.wrappedDelta(0f, (float) (2 * Math.PI) - 0.1f), 1e-5f,
                "just under a full turn is a tenth of a radian the other way");
        for (float a = -20f; a <= 20f; a += 0.37f) {
            for (float b = -20f; b <= 20f; b += 0.53f) {
                float delta = CameraCutDetector.wrappedDelta(a, b);
                assertTrue(delta >= 0f && delta <= (float) Math.PI + 1e-5f,
                        "delta " + delta + " for " + a + " and " + b);
            }
        }
    }

    @Test
    void theRecoveryWindowCoversTheCutAndTheFrameAfter() {
        // One frame is not enough: the frame after a cut is the first to be culled against a pyramid
        // built from a prepass, and a single frame of over-culling leaves a hole the next pyramid
        // inherits.
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        cuts.update(0f, 64f, 0f, 0f, 0f);
        cuts.update(0f, 64f, 0f, 0f, 0f);
        assertFalse(cuts.needsDepthPrepass(), "settled");

        cuts.update(500f, 64f, 0f, 0f, 0f);
        assertTrue(cuts.needsDepthPrepass(), "the cut frame");
        assertEquals(0, cuts.framesSinceCut());

        cuts.update(500f, 64f, 0f, 0f, 0f);
        assertTrue(cuts.needsDepthPrepass(), "one frame later, still recovering");
        assertEquals(1, cuts.framesSinceCut());

        cuts.update(500f, 64f, 0f, 0f, 0f);
        assertFalse(cuts.needsDepthPrepass(), "back to reusing the previous frame");
        assertEquals(CameraCutDetector.RECOVERY_FRAMES, cuts.framesSinceCut());
    }

    @Test
    void invalidateForcesTheNextUpdateToBeACut() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        cuts.update(0f, 64f, 0f, 0f, 0f);
        cuts.update(0f, 64f, 0f, 0f, 0f);
        assertFalse(cuts.needsDepthPrepass());

        cuts.invalidate();
        assertTrue(cuts.update(0f, 64f, 0f, 0f, 0f),
                "an unchanged camera is still a cut after an explicit invalidation");
    }

    @Test
    void framesSinceCutSaturatesInsteadOfWrapping() {
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 64f, 0f, 0f, 0f);
        for (int i = 0; i < 100000; i++) {
            cuts.update(0f, 64f, 0f, 0f, 0f);
        }
        assertEquals(100000, cuts.framesSinceCut());
        assertFalse(cuts.needsDepthPrepass());
    }

    @Test
    void theThresholdsAreWhatTheDocumentationSays() {
        assertEquals(8.0f, CameraCutDetector.POSITION_JUMP_BLOCKS);
        assertEquals((float) (Math.PI / 4.0), CameraCutDetector.ANGLE_JUMP_RADIANS, 1e-6f);
        assertEquals(2, CameraCutDetector.RECOVERY_FRAMES);
    }
}
