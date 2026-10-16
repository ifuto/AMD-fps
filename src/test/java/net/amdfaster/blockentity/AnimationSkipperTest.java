package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationSkipperTest {

    @Test
    void aStillLidIsNotAnimated() {
        // In a storage room where nobody has opened a chest in minutes, every chest is still being
        // animated sixty times a second and the animation always evaluates to the same closed position.
        AnimationSkipper skipper = new AnimationSkipper();

        assertFalse(skipper.shouldAnimate(0.0, 0.0));
        assertEquals(1L, skipper.skipped());
        assertEquals(0L, skipper.animated());
    }

    @Test
    void aMovingLidIsAnimatedHoweverSlowly() {
        // Freezing a lid halfway through closing would leave a chest visibly stuck open, which looks like
        // the game has stopped responding to the click. The check is whether the position changed at all,
        // not whether it changed by much.
        AnimationSkipper skipper = new AnimationSkipper();

        assertTrue(skipper.shouldAnimate(0.001, 0.0), "a thousandth of a degree is still movement");
        assertEquals(1L, skipper.animated());
        assertEquals(0L, skipper.skipped());
    }

    @Test
    void theAnimationResumesInTheSameFrameItMoves() {
        // Waiting for a tick or a timer would make the lid lag the click, which is exactly the feedback the
        // animation exists to give.
        AnimationSkipper skipper = new AnimationSkipper();

        skipper.shouldAnimate(0.0, 0.0);
        assertTrue(skipper.shouldAnimate(0.4, 0.0), "resumed immediately");
        assertEquals(1L, skipper.resumed());
    }

    @Test
    void aLidThatNeverMovedIsNotCountedAsResumed() {
        // Resumed means it was still and then moved. A lid animating from the first frame never was still.
        AnimationSkipper skipper = new AnimationSkipper();

        skipper.shouldAnimate(0.4, 0.0);
        skipper.shouldAnimate(0.8, 0.4);

        assertEquals(0L, skipper.resumed());
        assertEquals(2L, skipper.animated());
    }

    @Test
    void aChestOpenedOnceIsStillForMostOfItsLife() {
        // A lid animates for about fifteen frames and is still for the rest of the session.
        AnimationSkipper skipper = new AnimationSkipper();
        double angle = 0.0;

        for (int tick = 0; tick < AnimationSkipper.ANIMATION_TICKS; tick++) {
            angle += 6.0;
            skipper.shouldAnimate(angle, angle - 6.0);
        }
        for (int frame = 0; frame < 3600; frame++) {
            skipper.shouldAnimate(angle, angle);
        }

        assertEquals(3615L, skipper.frames());
        assertEquals(3600L, skipper.skipped());
        assertEquals(15L, skipper.animated());
        assertEquals(99.59, skipper.skippedPercent(), 0.01);
    }

    @Test
    void theMeasuredSavingIsAlmostAllOfTheEvaluation() {
        AnimationSkipper skipper = new AnimationSkipper();
        double angle = 0.0;

        for (int tick = 0; tick < AnimationSkipper.ANIMATION_TICKS; tick++) {
            angle += 6.0;
            skipper.shouldAnimate(angle, angle - 6.0);
        }
        for (int frame = 0; frame < 3600; frame++) {
            skipper.shouldAnimate(angle, angle);
        }

        assertEquals(3615 * AnimationSkipper.MS_PER_ANIMATION, skipper.msWithoutSkipping(), 1e-9);
        assertEquals(3600 * AnimationSkipper.MS_PER_ANIMATION, skipper.msSaved(), 1e-9,
                "the frames that skipped, which is the whole saving");
        assertEquals(99.59, skipper.savedPercent(), 0.01,
                "saved over total, not the remainder");
    }

    @Test
    void aFreshSkipperReportsZeroNotNaN() {
        // A skipper asked nothing has technically avoided everything, but reporting that as a saving would
        // describe an unused one as though it had done something.
        AnimationSkipper skipper = new AnimationSkipper();

        assertEquals(0.0, skipper.skippedPercent());
        assertEquals(0.0, skipper.savedPercent());
        assertEquals(0.0, skipper.msSaved());
        assertEquals(0L, skipper.frames());
        assertEquals(0L, skipper.resumed());
        assertEquals(15, AnimationSkipper.ANIMATION_TICKS);
    }
}
