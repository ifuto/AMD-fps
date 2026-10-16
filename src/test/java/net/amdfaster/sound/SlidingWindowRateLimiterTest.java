package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {

    private static final String PISTON = "block.piston.click";
    private static final String HOPPER = "block.hopper.transfer";

    @Test
    void theFirstFewOccurrencesPlay() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        for (int tick = 0; tick < SlidingWindowRateLimiter.DEFAULT_LIMIT; tick++) {
            assertTrue(limiter.allow(PISTON, tick), "at tick " + tick);
        }
        assertEquals(SlidingWindowRateLimiter.DEFAULT_LIMIT, limiter.played());
    }

    @Test
    void theLimitIsEnforcedWithinTheWindow() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        for (int tick = 0; tick < 20; tick++) {
            limiter.allow(PISTON, tick);
        }

        assertEquals(6L, limiter.played());
        assertEquals(14L, limiter.refused());
    }

    @Test
    void theWindowSlidesWithTheClock() {
        // A fixed window would let a burst through twice at the boundary -- ten sounds in the last tick of
        // one window and ten in the first of the next are twenty in two ticks, which is exactly the spike the
        // limit exists to prevent.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        for (int tick = 0; tick < SlidingWindowRateLimiter.DEFAULT_LIMIT; tick++) {
            limiter.allow(PISTON, tick);
        }
        assertFalse(limiter.allow(PISTON, SlidingWindowRateLimiter.DEFAULT_LIMIT));

        assertTrue(limiter.allow(PISTON, SlidingWindowRateLimiter.DEFAULT_WINDOW),
                "the oldest has fallen out of the window");
    }

    @Test
    void aBurstAtTheBoundaryIsNotLetThroughTwice() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        int played = 0;

        for (int tick = 0; tick < 40; tick++) {
            if (limiter.allow(PISTON, tick)) {
                played++;
            }
        }

        assertEquals(12, played, "six per twenty ticks, twice over, and no more");
    }

    @Test
    void eachSoundIsCountedOnItsOwn() {
        // A piston and a hopper are different sounds and the player should hear both. Counting them together
        // would let a noisy machine silence the hopper next to it.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        for (int tick = 0; tick < 20; tick++) {
            limiter.allow(PISTON, tick);
        }
        for (int tick = 0; tick < SlidingWindowRateLimiter.DEFAULT_LIMIT; tick++) {
            assertTrue(limiter.allow(HOPPER, tick), "unaffected by the piston");
        }
        assertEquals(2, limiter.tracked());
    }

    @Test
    void aSoundCanHaveItsOwnWindow() {
        // A footstep repeating once a second is normal and one repeating thirty times a second is a machine,
        // but a piece of music repeating once a second would be broken.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        limiter.configure("entity.footstep", 200, 20);

        for (int tick = 0; tick < 200; tick++) {
            limiter.allow("entity.footstep", tick);
        }

        assertEquals(20L, limiter.played(), "twenty per ten seconds");
    }

    @Test
    void aPistonClockIsMostlyRefused() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        for (int tick = 0; tick < 200; tick++) {
            limiter.allow(PISTON, tick);
        }

        assertEquals(200L, limiter.offered());
        assertEquals(60L, limiter.played(), "six per second for ten seconds");
        assertEquals(70.0, limiter.refusedPercent(), 1e-9);
        assertEquals(140 * SlidingWindowRateLimiter.MS_PER_CHANNEL, limiter.msSaved(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        assertThrows(IllegalArgumentException.class, () -> limiter.allow(null, 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.configure(null, 20, 6));
        assertThrows(IllegalArgumentException.class, () -> limiter.configure(PISTON, 0, 6));
        assertThrows(IllegalArgumentException.class, () -> limiter.configure(PISTON, 20, 0));
    }

    @Test
    void aFreshLimiterReportsZeroNotNaN() {
        // A limiter that has been offered nothing has technically refused nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

        assertEquals(0.0, limiter.refusedPercent());
        assertEquals(0.0, limiter.savedPercent());
        assertEquals(0.0, limiter.msSaved(), 1e-12);
        assertEquals(0L, limiter.offered());
        assertEquals(0, limiter.tracked());
        assertEquals(20, SlidingWindowRateLimiter.DEFAULT_WINDOW);
        assertEquals(6, SlidingWindowRateLimiter.DEFAULT_LIMIT);
    }
}
