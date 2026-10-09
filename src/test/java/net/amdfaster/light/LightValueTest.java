package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightValueTest {

    @Test
    void packAndUnpackRoundTripForEveryLevel() {
        for (int block = 0; block <= LightValue.MAX; block++) {
            for (int sky = 0; sky <= LightValue.MAX; sky++) {
                int packed = LightValue.pack(block, sky);
                assertEquals(block, LightValue.block(packed), "block " + block + " sky " + sky);
                assertEquals(sky, LightValue.sky(packed), "block " + block + " sky " + sky);
            }
        }
    }

    @Test
    void packRejectsLevelsOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> LightValue.pack(16, 0));
        assertThrows(IllegalArgumentException.class, () -> LightValue.pack(0, 16));
        assertThrows(IllegalArgumentException.class, () -> LightValue.pack(-1, 0));
    }

    @Test
    void theChannelsDoNotOverlap() {
        // The twelve zero bits between the fields are load bearing: average4 relies on them.
        assertEquals(0xF0, LightValue.pack(LightValue.MAX, 0), "L32");
        assertEquals(0xF00000, LightValue.pack(0, LightValue.MAX), "L33");
        assertEquals(0xF000F0, LightValue.fullBright(), "L34");
        assertEquals(0, LightValue.dark(), "L35");
    }

    @Test
    void theFastAverageMatchesTheObviousOneEverywhere() {
        // Exhaustive over the pairs that matter; average4 is one integer add and a shift, so a
        // carry into the neighbouring channel would show up here and nowhere else.
        for (int b0 = 0; b0 <= 15; b0 += 3) {
            for (int s0 = 0; s0 <= 15; s0 += 3) {
                for (int b1 = 0; b1 <= 15; b1 += 5) {
                    for (int s1 = 0; s1 <= 15; s1 += 5) {
                        int a = LightValue.pack(b0, s0);
                        int b = LightValue.pack(b1, s1);
                        int c = LightValue.pack(15 - b0, 15 - s0);
                        int d = LightValue.pack(15 - b1, 15 - s1);
                        assertEquals(LightValue.average4Slow(a, b, c, d), LightValue.average4(a, b, c, d),
                                "b=" + b0 + "," + b1 + " s=" + s0 + "," + s1);
                    }
                }
            }
        }
    }

    @Test
    void averagingFourIdenticalValuesIsThatValue() {
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                int packed = LightValue.pack(block, sky);
                assertEquals(packed, LightValue.average4(packed, packed, packed, packed), "L63");
            }
        }
    }

    @Test
    void theAverageTruncatesRatherThanRounds() {
        // Three dark, one bright: 15/4 = 3, not 4. Vanilla truncates too, and matching it matters
        // because a renderer that rounds reads half a level brighter than the world around it.
        int dark = LightValue.pack(0, 0);
        int bright = LightValue.pack(15, 15);
        int avg = LightValue.average4(dark, dark, dark, bright);
        assertEquals(3, LightValue.block(avg), "L75");
        assertEquals(3, LightValue.sky(avg), "L76");
    }

    @Test
    void emissionRaisesTheBlockChannelAndNeverLowersIt() {
        int lit = LightValue.pack(12, 4);
        assertEquals(12, LightValue.block(LightValue.withEmission(lit, 5)), "L82");
        assertEquals(15, LightValue.block(LightValue.withEmission(lit, 15)), "L83");
        assertEquals(4, LightValue.sky(LightValue.withEmission(lit, 15)), "sky must be untouched");
        assertEquals(lit, LightValue.withEmission(lit, 0), "L85");
        assertEquals(lit, LightValue.withEmission(lit, -3), "L86");
    }

    @Test
    void theLightmapCoordinateSitsInTheMiddleOfItsTexel() {
        assertEquals(0.5f / 16.0f, LightValue.lightmapCoord(0), 1e-6f, "L91");
        assertEquals(15.5f / 16.0f, LightValue.lightmapCoord(15), 1e-6f, "L92");
        assertEquals(LightValue.lightmapCoord(0), LightValue.lightmapCoord(-4), "clamped low");
        assertEquals(LightValue.lightmapCoord(15), LightValue.lightmapCoord(99), "clamped high");
    }

    @Test
    void consecutiveLevelsLandInDistinctTexels() {
        for (int level = 0; level < 15; level++) {
            assertTrue(LightValue.lightmapCoord(level + 1) > LightValue.lightmapCoord(level), "L100");
        }
    }
}
