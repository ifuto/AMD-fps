package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundDirectionPrioritizerTest {

    private static final double FOV = 70.0;

    @Test
    void aSoundInTheMiddleOfTheViewIsInFront() {
        assertEquals(0.0, SoundDirectionPrioritizer.angleFromFacing(0.0, 0.0, -10.0), 1e-9);
        assertTrue(SoundDirectionPrioritizer.inFront(0.0, FOV));
    }

    @Test
    void aSoundBehindIsNotInFront() {
        assertEquals(180.0, SoundDirectionPrioritizer.angleFromFacing(0.0, 0.0, 10.0), 1e-9);
        assertFalse(SoundDirectionPrioritizer.inFront(180.0, FOV));
    }

    @Test
    void theAngleIsAlwaysBetweenZeroAndOneEighty() {
        // A difference of 270 degrees is a difference of 90 the other way round.
        assertEquals(90.0, SoundDirectionPrioritizer.angleFromFacing(0.0, 10.0, 0.0), 1e-9);
        assertEquals(90.0, SoundDirectionPrioritizer.angleFromFacing(270.0, 0.0, -10.0), 1e-9);
        assertEquals(45.0, SoundDirectionPrioritizer.angleFromFacing(0.0, 10.0, -10.0), 1e-9);
    }

    @Test
    void aSoundAtThePlayerCountsAsInFront() {
        // There is no direction to compare, and refusing it would drop the player's own footsteps.
        assertEquals(0.0, SoundDirectionPrioritizer.angleFromFacing(0.0, 0.0, 0.0), 1e-12);
        assertTrue(SoundDirectionPrioritizer.inFront(0.0, FOV));
    }

    @Test
    void theFieldOfViewIsThePlayersOwnSetting() {
        // A player who has set a wide field of view sees more of the world, and a sound at the edge of that
        // view is attached to something they can see.
        double edge = 60.0;

        assertTrue(SoundDirectionPrioritizer.inFront(edge, 110.0), "inside a wide view");
        assertFalse(SoundDirectionPrioritizer.inFront(edge, 70.0), "outside a narrow one");
    }

    @Test
    void theMarginKeepsTheEdgeOfTheViewCounting() {
        double half = FOV / 2.0;

        assertTrue(SoundDirectionPrioritizer.inFront(half, FOV), "exactly at the edge");
        assertTrue(SoundDirectionPrioritizer.inFront(
                half + SoundDirectionPrioritizer.FOV_MARGIN_DEGREES - 1.0, FOV), "within the margin");
        assertFalse(SoundDirectionPrioritizer.inFront(
                half + SoundDirectionPrioritizer.FOV_MARGIN_DEGREES + 1.0, FOV));
    }

    @Test
    void atTheSameDistanceTheFrontSoundWins() {
        // The one the player is looking at belongs to something they can see and are probably about to
        // interact with; the one behind belongs to something they have not decided anything about.
        SoundDirectionPrioritizer prioritizer = new SoundDirectionPrioritizer();

        assertTrue(prioritizer.chooseFront(10.0, 10.0));
        assertEquals(1L, prioritizer.frontChosen());
    }

    @Test
    void aNearerSoundBehindStillWins() {
        // A creeper behind the player is exactly the sound they most need to hear, and prioritising
        // direction over distance would keep a sound at the far edge of the render distance while dropping
        // one three blocks away.
        SoundDirectionPrioritizer prioritizer = new SoundDirectionPrioritizer();

        assertFalse(prioritizer.chooseFront(30.0, 3.0));
        assertEquals(1L, prioritizer.rearChosen());
    }

    @Test
    void aContestedPoolMostlyFavoursTheFront() {
        SoundDirectionPrioritizer prioritizer = new SoundDirectionPrioritizer();

        for (int i = 0; i < 100; i++) {
            prioritizer.chooseFront(8.0 + i % 5, 20.0 + i % 5);
        }

        assertEquals(100L, prioritizer.compared());
        assertEquals(100L, prioritizer.frontChosen());
        assertEquals(100.0, prioritizer.frontPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SoundDirectionPrioritizer prioritizer = new SoundDirectionPrioritizer();

        assertThrows(IllegalArgumentException.class,
                () -> SoundDirectionPrioritizer.inFront(10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> prioritizer.chooseFront(-1.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> prioritizer.chooseFront(10.0, -1.0));
    }

    @Test
    void aFreshPrioritizerReportsZeroNotNaN() {
        // A prioritiser that has chosen nothing has technically chosen nothing, and reporting that as a rate
        // would describe an unused one as though it had a result.
        SoundDirectionPrioritizer prioritizer = new SoundDirectionPrioritizer();

        assertEquals(0.0, prioritizer.frontPercent());
        assertEquals(0L, prioritizer.compared());
        assertEquals(0L, prioritizer.frontChosen());
        assertEquals(0L, prioritizer.rearChosen());
        assertEquals(10.0, SoundDirectionPrioritizer.FOV_MARGIN_DEGREES, 1e-12);
    }
}
