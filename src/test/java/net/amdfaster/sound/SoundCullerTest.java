package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundCullerTest {

    @Test
    void aSoundBeyondItsOwnAttenuationRangeNeedsNoChannel() {
        // The engine allocates the channel and does the mixing work, then finds the volume is zero.
        // Refusing it first is free in perceptual terms because it was never going to be heard.
        SoundCuller culler = new SoundCuller();

        assertFalse(culler.shouldPlay(17.0, 16.0, 1.0, 0, 64, 0), "past the range");
        assertFalse(culler.shouldPlay(16.0, 16.0, 1.0, 0, 64, 0), "exactly at it");
        assertEquals(2L, culler.inaudible());
        assertEquals(0L, culler.played());
    }

    @Test
    void anythingAudibleIsKept() {
        // The one thing this must not do. A missing footstep or explosion is immediately noticeable and
        // feels like the game is broken, so the test sits just inside the range rather than well inside.
        SoundCuller culler = new SoundCuller();

        assertTrue(culler.shouldPlay(15.999, 16.0, 1.0, 0, 64, 0), "a whisper of volume is still volume");
        assertTrue(culler.shouldPlay(0.0, 16.0, 1.0, 0, 64, 0), "at the source");
        assertEquals(2L, culler.played());
        assertEquals(0L, culler.inaudible());
    }

    @Test
    void aQuieterBaseVolumeGoesSilentSooner() {
        // Attenuation multiplies the base volume, so a quiet sound reaches zero at a shorter distance
        // only if the base is zero; otherwise it is audible right up to the range limit. Pinning this
        // stops a later "optimisation" from scaling the range by volume.
        assertEquals(0.0, SoundCuller.attenuated(8.0, 16.0, 0.0), "a silent sound is silent everywhere");
        assertTrue(SoundCuller.attenuated(15.0, 16.0, 0.01) > 0.0, "a quiet one is still audible in range");
    }

    @Test
    void attenuationIsLinearAndMatchesTheEngine() {
        assertEquals(1.0, SoundCuller.attenuated(0.0, 16.0, 1.0), 1e-9, "full volume at the source");
        assertEquals(0.5, SoundCuller.attenuated(8.0, 16.0, 1.0), 1e-9, "half way, half volume");
        assertEquals(0.75, SoundCuller.attenuated(4.0, 16.0, 1.0), 1e-9, "a quarter of the way, three quarters left");
        assertEquals(0.0, SoundCuller.attenuated(16.0, 16.0, 1.0), 1e-9);
    }

    @Test
    void aNonAttenuatingSoundIsHeardAnywhere() {
        // A UI click has no position and no range; treating maxDistance 0 as "inaudible" would mute the
        // whole interface.
        SoundCuller culler = new SoundCuller();

        assertTrue(culler.shouldPlay(1000.0, 0.0, 1.0, 0, 0, 0));
        assertEquals(1.0, SoundCuller.attenuated(1e9, 0.0, 1.0), 1e-9);
    }

    @Test
    void aRegionStopsAcceptingOnceItsBudgetIsSpent() {
        // Past the engine's channel limit it drops arbitrarily, which is how a player loses the sound
        // that mattered. Capping here keeps the decision ours instead of the engine's.
        SoundCuller culler = new SoundCuller();

        for (int i = 0; i < SoundCuller.CHANNEL_BUDGET; i++) {
            assertTrue(culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0), "sound " + i);
        }
        assertFalse(culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0), "the budget is spent");
        assertEquals(1L, culler.overBudget());
        assertEquals(SoundCuller.CHANNEL_BUDGET, culler.played());
    }

    @Test
    void theBudgetIsPerRegionNotGlobal() {
        // One wall of machinery should not silence the rest of the world.
        SoundCuller culler = new SoundCuller();

        for (int i = 0; i < SoundCuller.CHANNEL_BUDGET; i++) {
            culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0);
        }
        // A different cell, eight metres away on x, is a different region.
        assertTrue(culler.shouldPlay(1.0, 16.0, 1.0, 8, 64, 0));
    }

    @Test
    void theBudgetResetsEachTick() {
        SoundCuller culler = new SoundCuller();
        for (int i = 0; i < SoundCuller.CHANNEL_BUDGET; i++) {
            culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0);
        }
        assertFalse(culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0));

        culler.beginTick();

        assertTrue(culler.shouldPlay(1.0, 16.0, 1.0, 0, 64, 0), "and the region accepts again");
    }

    @Test
    void distantSourcesAreWrappedNotClamped() {
        // Clamping would pile every distant sound into one cell and cap them all together, so a single
        // far-off machine would silence sounds from the opposite direction.
        SoundCuller culler = new SoundCuller();

        for (int i = 0; i < SoundCuller.CHANNEL_BUDGET; i++) {
            culler.shouldPlay(1.0, 16.0, 1.0, 100_000, 64, 0);
        }
        // A source on the other side of the world is a different cell, so it is not capped by the first.
        assertTrue(culler.shouldPlay(1.0, 16.0, 1.0, -100_000, 64, 0));
    }

    @Test
    void negativeCoordinatesStillLandInACell() {
        SoundCuller culler = new SoundCuller();

        assertTrue(culler.shouldPlay(1.0, 16.0, 1.0, -1, -1, -1));
        assertEquals(1L, culler.played());
    }

    @Test
    void theSavingIsTheShareOfSoundsThatNeverNeededAChannel() {
        SoundCuller culler = new SoundCuller();

        for (int i = 0; i < 200; i++) {
            // Spread over a line so the region budget is not what limits them; only range is.
            double distance = i < 40 ? 4.0 : 40.0;
            culler.shouldPlay(distance, 16.0, 1.0, i * 8, 64, 0);
        }

        assertEquals(200L, culler.offered());
        assertEquals(40L, culler.played());
        assertEquals(160L, culler.inaudible());
        assertEquals(80.0, culler.culledPercent(), 0.01);
    }

    @Test
    void aNegativeDistanceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SoundCuller.attenuated(-1.0, 16.0, 1.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        SoundCuller culler = new SoundCuller();
        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.offered());
    }
}
