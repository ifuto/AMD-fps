package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateSoundLimiterTest {

    private static final String CLICK = "block.piston.click";

    @Test
    void theFirstFewCopiesPlay() {
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            assertTrue(limiter.allow(10.0, 64.0, 20.0, CLICK), "copy " + i);
        }
        assertEquals(DuplicateSoundLimiter.MAX_DUPLICATES, limiter.played());
    }

    @Test
    void theSixthCopyFromTheSameBlockIsNotStarted() {
        // Five copies of a click are not five clicks -- they are one click at five times the volume, which
        // is loud in a way no single source in the game ever is.
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(10.0, 64.0, 20.0, CLICK);
        }
        assertFalse(limiter.allow(10.0, 64.0, 20.0, CLICK));

        assertEquals(1L, limiter.dropped());
        assertEquals(5, DuplicateSoundLimiter.MAX_DUPLICATES);
    }

    @Test
    void positionsWithinABlockAreTheSamePosition() {
        // Two sounds a tenth of a block apart are the same event to the ear, and treating them as different
        // positions would let a collapsing stack of sand through one copy per grain.
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(10.1 * i % 1 + 10.0, 64.0, 20.0, CLICK);
        }
        assertFalse(limiter.allow(10.9, 64.0, 20.0, CLICK), "still the same block");
    }

    @Test
    void aDifferentBlockIsNotLimited() {
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(10.0, 64.0, 20.0, CLICK);
        }
        assertTrue(limiter.allow(11.0, 64.0, 20.0, CLICK), "the next block over");
    }

    @Test
    void aDifferentSoundAtTheSameBlockIsNotLimited() {
        // A piston firing and an item landing on it are two events, and the player should hear both. The
        // limit is per sound at a position, not per position.
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(10.0, 64.0, 20.0, CLICK);
        }
        assertTrue(limiter.allow(10.0, 64.0, 20.0, "entity.item.pickup"));
        assertEquals(2, limiter.distinct());
    }

    @Test
    void negativeCoordinatesAreHandled() {
        // floor rather than a truncating cast, so a position just below zero is the block below and not the
        // one at zero.
        assertNotEquals(DuplicateSoundLimiter.key(-0.5, 64.0, 20.0, CLICK),
                DuplicateSoundLimiter.key(0.5, 64.0, 20.0, CLICK));

        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();
        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(-10.2, 64.0, -20.7, CLICK);
        }
        assertFalse(limiter.allow(-10.9, 64.0, -20.1, CLICK), "the same block, in the negatives");
    }

    @Test
    void theCountsResetEveryTick() {
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int i = 0; i < DuplicateSoundLimiter.MAX_DUPLICATES; i++) {
            limiter.allow(10.0, 64.0, 20.0, CLICK);
        }
        limiter.endTick();

        assertEquals(0, limiter.distinct());
        assertTrue(limiter.allow(10.0, 64.0, 20.0, CLICK));
    }

    @Test
    void aCollapsingSandStackIsMostlyDropped() {
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        for (int grain = 0; grain < 100; grain++) {
            limiter.allow(10.0, 64.0, 20.0, "block.sand.fall");
        }

        assertEquals(100L, limiter.requests());
        assertEquals(5L, limiter.played());
        assertEquals(95.0, limiter.savedPercent(), 1e-9);
        assertEquals(95 * DuplicateSoundLimiter.MS_PER_CHANNEL, limiter.msSaved(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        assertThrows(IllegalArgumentException.class,
                () -> limiter.allow(10.0, 64.0, 20.0, null));
        assertThrows(IllegalArgumentException.class,
                () -> limiter.allow(10.0, 64.0, 20.0, ""));
    }

    @Test
    void aFreshLimiterReportsZeroNotNaN() {
        // A limiter that has been asked nothing has technically dropped nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        DuplicateSoundLimiter limiter = new DuplicateSoundLimiter();

        assertEquals(0.0, limiter.droppedPercent());
        assertEquals(0.0, limiter.savedPercent());
        assertEquals(0.0, limiter.msSaved(), 1e-12);
        assertEquals(0L, limiter.requests());
        assertEquals(0, limiter.distinct());
    }
}
