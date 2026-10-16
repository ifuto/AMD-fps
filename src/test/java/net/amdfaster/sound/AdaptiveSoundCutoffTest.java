package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSoundCutoffTest {

    @Test
    void aQuietScenePlaysDistantSounds() {
        // There is nothing competing with them and the player hears the world.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertEquals(AdaptiveSoundCutoff.MAX_DISTANCE, culler.cutoff(0), 1e-12);
        assertTrue(culler.shouldPlay(30.0, 0, false));
    }

    @Test
    void aBusySceneTightensTheCutoff() {
        // The channels are being taken by things the player is not looking at while the ones they are
        // looking at go silent.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertEquals(8.0, culler.cutoff(AdaptiveSoundCutoff.STRICTEST_AT), 1e-12);
        assertEquals(20.0, culler.cutoff(AdaptiveSoundCutoff.STRICTEST_AT / 2), 1e-12);

        assertFalse(culler.shouldPlay(30.0, AdaptiveSoundCutoff.STRICTEST_AT, false),
                "the same sound that played in a quiet scene");
        assertEquals(1L, culler.beyondCutoff());
    }

    @Test
    void theTighteningIsGradualNotASwitch() {
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        double quiet = culler.cutoff(0);
        double half = culler.cutoff(AdaptiveSoundCutoff.STRICTEST_AT / 2);
        double strict = culler.cutoff(AdaptiveSoundCutoff.STRICTEST_AT);

        assertTrue(quiet > half && half > strict);
        assertTrue(strict > culler.cutoff(AdaptiveSoundCutoff.STRICTEST_AT * 2) - 1e-12,
                "and it does not go below the always-audible band");
    }

    @Test
    void nothingNearThePlayerIsEverDropped() {
        // A sound within eight blocks is something the player can see and is probably acting on. Dropping it
        // to relieve the pool would cost the feedback for the thing in front of them in order to keep the
        // noise of something far away, which is backwards.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        for (int playing = 0; playing <= 500; playing += 50) {
            assertTrue(culler.shouldPlay(AdaptiveSoundCutoff.ALWAYS_AUDIBLE, playing, false),
                    "at " + playing + " sounds playing");
        }
        assertEquals(11L, culler.played());
        assertEquals(0L, culler.beyondCutoff());
    }

    @Test
    void nothingPastTheEnginesOwnRangeIsAdded() {
        // The engine attenuates a sound to silence at its range limit, so this would produce nothing and
        // cost a channel to do it. Refusing it costs the player nothing at all.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertFalse(culler.shouldPlay(AdaptiveSoundCutoff.MAX_DISTANCE + 0.1, 0, false));
        assertEquals(1L, culler.tooFar());
        assertEquals(0L, culler.beyondCutoff(), "not a cut-off decision, an audibility one");
    }

    @Test
    void soundsAttachedToThePlayerAreExempt() {
        // Music, interface clicks and the player's own voice have no position in the world. Applying a
        // distance cut-off to them would make the menu silent or the music stop when the scene got busy,
        // which is not a performance trade but a broken interface.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertTrue(culler.shouldPlay(0.0, AdaptiveSoundCutoff.STRICTEST_AT * 4, true));
        assertEquals(1L, culler.exempt());
        assertEquals(1L, culler.played());
    }

    @Test
    void aCrowdedSceneDropsMostOfItsDistantSounds() {
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        for (int i = 0; i < 100; i++) {
            culler.shouldPlay(24.0, AdaptiveSoundCutoff.STRICTEST_AT, false);
        }

        assertEquals(100L, culler.offered());
        assertEquals(0L, culler.played(), "24 blocks is past a cut-off of 8");
        assertEquals(100.0, culler.savedPercent(), 1e-9);
        assertEquals(100 * AdaptiveSoundCutoff.MS_PER_CHANNEL, culler.msSaved(), 1e-9);
    }

    @Test
    void aMixedSceneKeepsWhatMatters() {
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        for (int i = 0; i < 50; i++) {
            culler.shouldPlay(4.0, AdaptiveSoundCutoff.STRICTEST_AT, false);
        }
        for (int i = 0; i < 50; i++) {
            culler.shouldPlay(28.0, AdaptiveSoundCutoff.STRICTEST_AT, false);
        }

        assertEquals(50L, culler.played(), "the near ones");
        assertEquals(50L, culler.beyondCutoff(), "the distant ones");
        assertEquals(50.0, culler.droppedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertThrows(IllegalArgumentException.class, () -> culler.cutoff(-1));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldPlay(-1.0, 0, false));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has been offered nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        AdaptiveSoundCutoff culler = new AdaptiveSoundCutoff();

        assertEquals(0.0, culler.droppedPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved(), 1e-12);
        assertEquals(0L, culler.offered());
        assertEquals(0L, culler.played());
        assertEquals(8.0, AdaptiveSoundCutoff.ALWAYS_AUDIBLE, 1e-12);
        assertEquals(32.0, AdaptiveSoundCutoff.MAX_DISTANCE, 1e-12);
        assertEquals(64, AdaptiveSoundCutoff.STRICTEST_AT);
    }
}
