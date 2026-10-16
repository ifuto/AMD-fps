package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSoundPressureTest {

    @Test
    void aQuietSceneStaysAtZeroPressure() {
        // A quiet walk through a forest should play every bird and every footstep, costing nothing extra.
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        for (int tick = 0; tick < 20; tick++) {
            assertEquals(0.0, gauge.tick(0), 1e-12);
        }
        assertFalse(gauge.isSaturated());
    }

    @Test
    void aSustainedFloodSaturates() {
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        for (int tick = 0; tick < 40; tick++) {
            gauge.tick(10);
        }

        assertTrue(gauge.isSaturated(), "ten events a tick is two hundred a second");
        assertTrue(gauge.pressure() >= AdaptiveSoundPressure.SATURATION,
                "an asymptotic rise reaches the threshold rather than exactly one");
    }

    @Test
    void pressureRisesOverSeveralTicksNotOne() {
        // A pressure that jumped from zero to maximum on one loud tick would tighten the limits instantly
        // and then release them just as fast, and the player would hear the world's audio pumping.
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        assertEquals(0.25, gauge.tick(5), 1e-9, "a quarter of the way on the first tick");
        assertEquals(0.4375, gauge.tick(5), 1e-9);
        assertEquals(0.578125, gauge.tick(5), 1e-9);
        assertFalse(gauge.isSaturated(), "a single burst has not changed the soundscape");
    }

    @Test
    void itRelaxesWhenTheSceneQuietsDown() {
        // Walking out of the farm should restore the full limits within a second or two, not leave the world
        // muffled because of where the player has been.
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        for (int tick = 0; tick < 40; tick++) {
            gauge.tick(10);
        }
        assertTrue(gauge.isSaturated());

        for (int tick = 0; tick < 20; tick++) {
            gauge.tick(0);
        }

        assertEquals(0.0, gauge.pressure(), 1e-9, "back to a quiet scene in a second");
        assertFalse(gauge.isSaturated());
    }

    @Test
    void pressureIsARateNotACount() {
        // A count would grow forever and never relax, so a player who walked past one noisy machine five
        // minutes ago would still be paying for it.
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        for (int tick = 0; tick < 100; tick++) {
            gauge.tick(0);
        }
        gauge.tick(10);

        assertEquals(0.25, gauge.pressure(), 1e-9,
                "only the current rate matters, not the hundred quiet ticks before it");
    }

    @Test
    void theLimitTightensWithThePressure() {
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        assertEquals(64, gauge.limit(64, 16), "a quiet scene gets the full budget");

        for (int tick = 0; tick < 40; tick++) {
            gauge.tick(10);
        }

        assertEquals(16, gauge.limit(64, 16), "and a flooded one the strict end");
        assertEquals(24L, gauge.saturated(),
                "the ticks past the threshold, which the rise does not reach until tick 17");
    }

    @Test
    void aLimitBetweenTheEndsIsRounded() {
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        gauge.tick(5);

        assertEquals(0.25, gauge.pressure(), 1e-9);
        assertEquals(52, gauge.limit(64, 16), "three quarters of the way from 64 to 16");
    }

    @Test
    void theAverageRateIsReported() {
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        for (int tick = 0; tick < 20; tick++) {
            gauge.tick(5);
        }

        assertEquals(100L, gauge.events());
        assertEquals(100.0, gauge.averageRate(), 1e-9, "five a tick is a hundred a second");
        assertEquals(20L, gauge.ticks());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        assertThrows(IllegalArgumentException.class, () -> gauge.tick(-1));
        assertThrows(IllegalArgumentException.class, () -> gauge.limit(16, 64));
        assertThrows(IllegalArgumentException.class, () -> gauge.limit(0, 0));
    }

    @Test
    void aFreshGaugeReportsZeroNotNaN() {
        // A gauge that has measured nothing has technically spent no time under pressure, and reporting that
        // as a rate would describe an unused one as though it had measured something.
        AdaptiveSoundPressure gauge = new AdaptiveSoundPressure();

        assertEquals(0.0, gauge.pressure(), 1e-12);
        assertEquals(0.0, gauge.averageRate(), 1e-12);
        assertEquals(0.0, gauge.saturatedPercent(), 1e-12);
        assertEquals(0L, gauge.ticks());
        assertEquals(0L, gauge.saturated());
        assertEquals(100, AdaptiveSoundPressure.SATURATING_RATE);
    }
}
