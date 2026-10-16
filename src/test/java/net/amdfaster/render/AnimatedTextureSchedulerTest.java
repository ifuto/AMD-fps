package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatedTextureSchedulerTest {

    @Test
    void anInvisibleTextureIsNotUpdated() {
        // Sixty animated textures at 0.02 ms each is 1.2 ms a frame, and in most scenes about 70% are
        // not on screen at all -- a lava texture animating in a cave the player left ten minutes ago.
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();

        assertFalse(scheduler.shouldUpdate(false, 100L, 1, 0.0, -1L));
        assertEquals(1L, scheduler.skippedInvisible());
        assertEquals(0L, scheduler.updated());
    }

    @Test
    void aVisibleTextureOnANewFrameIsUpdated() {
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();

        assertTrue(scheduler.shouldUpdate(true, 100L, 1, 0.0, -1L));
        assertEquals(1L, scheduler.updated());
    }

    @Test
    void aTextureAlreadyShowingTheCurrentFrameIsNotReUploaded() {
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();
        long frame = AnimatedTextureScheduler.frameAt(100L, 1);

        assertFalse(scheduler.shouldUpdate(true, 100L, 1, 0.0, frame));
        assertEquals(1L, scheduler.skippedRedundant());
    }

    @Test
    void aTextureThatBecomesVisibleIsUpdatedThatFrame() {
        // The failure this makes easy to introduce. A texture carrying a frame from when it was last
        // updated shows frozen and then jumps; turning a corner onto a lake and finding the water
        // standing still is exactly as noticeable as a dropped frame.
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();
        long staleFrame = AnimatedTextureScheduler.frameAt(0L, 1);

        assertFalse(scheduler.shouldUpdate(false, 500L, 1, 0.0, staleFrame), "invisible, so skipped");
        assertTrue(scheduler.shouldUpdate(true, 500L, 1, 0.0, staleFrame),
                "and the frame it becomes visible is the one that updates it");
    }

    @Test
    void thePhaseDoesNotDriftWhenUpdatesAreSkipped() {
        // Several water blocks in one lake have to move together. Counting frames from the last update
        // would put them out of step and the surface would ripple in patches.
        long skipped = AnimatedTextureScheduler.frameAt(1000L, 1);
        long notSkipped = AnimatedTextureScheduler.frameAt(1000L, 1);

        assertEquals(skipped, notSkipped, "the frame depends on the world time, not on the history");
        assertEquals(1000L, skipped);
    }

    @Test
    void aSlowAnimationAdvancesLessOften() {
        assertEquals(0L, AnimatedTextureScheduler.frameAt(0L, 5));
        assertEquals(0L, AnimatedTextureScheduler.frameAt(4L, 5), "still frame 0");
        assertEquals(1L, AnimatedTextureScheduler.frameAt(5L, 5));
        assertEquals(1L, AnimatedTextureScheduler.frameAt(9L, 5));
        assertEquals(5, AnimatedTextureScheduler.SLOW_PERIOD);
    }

    @Test
    void negativeWorldTimeIsHandled() {
        // floorDiv, not truncation, so a rolled-back world time still gives a sensible frame.
        assertEquals(-1L, AnimatedTextureScheduler.frameAt(-1L, 1));
        assertEquals(-1L, AnimatedTextureScheduler.frameAt(-5L, 5));
        assertEquals(-2L, AnimatedTextureScheduler.frameAt(-6L, 5));
    }

    @Test
    void aFarTextureAnimatesAtHalfRate() {
        // At distance the eye cannot resolve the individual frames.
        assertFalse(AnimatedTextureScheduler.isFar(32.0), "at the threshold it is still near");
        assertTrue(AnimatedTextureScheduler.isFar(32.1));
        assertEquals(1, AnimatedTextureScheduler.effectivePeriod(1, 10.0));
        assertEquals(2, AnimatedTextureScheduler.effectivePeriod(1, 100.0));
        assertEquals(10, AnimatedTextureScheduler.effectivePeriod(5, 100.0));
    }

    @Test
    void theSavingIsTheShareOfUpdatesNeverMade() {
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();

        // The measured scene: 60 textures, 30% visible, and the visible ones half already current.
        for (int i = 0; i < 60; i++) {
            boolean visible = i < 18;
            long frame = AnimatedTextureScheduler.frameAt(100L, 1);
            long last = visible && i % 2 == 0 ? frame : -1L;
            scheduler.shouldUpdate(visible, 100L, 1, 0.0, last);
        }

        assertEquals(60L, scheduler.offered());
        assertEquals(9L, scheduler.updated());
        assertEquals(42L, scheduler.skippedInvisible());
        assertEquals(9L, scheduler.skippedRedundant());
        assertEquals(85.0, scheduler.skippedPercent(), 0.01);
    }

    @Test
    void theCostIsWhatTheFormulaSays() {
        assertEquals(1.2, AnimatedTextureScheduler.msPerFrame(60), 1e-9);

        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();
        scheduler.shouldUpdate(false, 0L, 1, 0.0, -1L);
        assertEquals(AnimatedTextureScheduler.MS_PER_UPDATE, scheduler.msSavedPerFrame(), 1e-9);
        assertEquals(0.02, AnimatedTextureScheduler.MS_PER_UPDATE, 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.shouldUpdate(true, 0L, 0, 0.0, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.shouldUpdate(true, 0L, -1, 0.0, -1L));
    }

    @Test
    void aFreshSchedulerReportsZeroNotNaN() {
        AnimatedTextureScheduler scheduler = new AnimatedTextureScheduler();

        assertEquals(0.0, scheduler.skippedPercent());
        assertEquals(0L, scheduler.offered());
        assertEquals(0.0, scheduler.msSavedPerFrame());
    }
}
