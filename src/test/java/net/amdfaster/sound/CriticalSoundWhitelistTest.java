package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticalSoundWhitelistTest {

    private static final String HURT = "entity.player.hurt";

    @Test
    void theListStartsEmpty() {
        // Inferring which sounds matter is wrong for some sound somewhere, and a mod adding its own alarm
        // would be culled by a rule that could not have known about it.
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        assertEquals(0, whitelist.size());
        assertFalse(whitelist.isCritical(HURT));
    }

    @Test
    void theVanillaDefaultsCanBeAdded() {
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        whitelist.protectDefaults();

        assertEquals(CriticalSoundWhitelist.DEFAULTS.length, whitelist.size());
        assertEquals(5, CriticalSoundWhitelist.DEFAULTS.length);
        assertTrue(whitelist.isCritical("entity.player.hurt"));
        assertTrue(whitelist.isCritical("entity.tnt.primed"));
    }

    @Test
    void aCriticalSoundSurvivesCulling() {
        // Dropping one of these to make room for a piston click means the player dies without ever hearing
        // why.
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();
        whitelist.protect(HURT);

        assertTrue(whitelist.shouldPlay(HURT, false, true), "the culling said drop it");
        assertEquals(1L, whitelist.protectedSounds());
        assertEquals(0L, whitelist.culledNormally());
    }

    @Test
    void anOrdinarySoundIsCulledNormally() {
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();
        whitelist.protectDefaults();

        assertFalse(whitelist.shouldPlay("block.piston.click", false, true));
        assertEquals(1L, whitelist.culledNormally());
        assertEquals(0L, whitelist.protectedSounds());
    }

    @Test
    void anOrdinarySoundThatWasNotCulledStillPlays() {
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        assertTrue(whitelist.shouldPlay("block.piston.click", false, false));
        assertEquals(0L, whitelist.culledNormally());
        assertEquals(0L, whitelist.protectedSounds());
    }

    @Test
    void aFullPoolStopsEvenACriticalSound() {
        // The whitelist overrides the culling decisions, not the physics of the audio engine. If every
        // channel is in use there is nowhere to put a sound, and pretending otherwise would mean dropping
        // one of the other protected ones instead -- the same loss moved somewhere less obvious.
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();
        whitelist.protect(HURT);

        assertFalse(whitelist.shouldPlay(HURT, true, false));
        assertEquals(1L, whitelist.refusedByFullPool());
        assertEquals(0L, whitelist.protectedSounds());
    }

    @Test
    void unprotectingReturnsAToNormalCulling() {
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        whitelist.protect(HURT);
        whitelist.unprotect(HURT);

        assertFalse(whitelist.isCritical(HURT));
        assertFalse(whitelist.shouldPlay(HURT, false, true));
    }

    @Test
    void beingCriticalIsNotTheSameAsBeingLoud() {
        // What the defaults share is not volume but consequence: the player changes what they are doing
        // because of them. A hurt sound is quiet and short.
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();
        whitelist.protectDefaults();

        assertTrue(whitelist.isCritical("entity.player.hurt"), "quiet and short, but acted on");
        assertTrue(whitelist.isCritical("entity.generic.explode"),
                "and an explosion, which is both loud and acted on");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        assertThrows(IllegalArgumentException.class, () -> whitelist.protect(null));
        assertThrows(IllegalArgumentException.class, () -> whitelist.protect(""));
    }

    @Test
    void aFreshWhitelistReportsZeroNotNaN() {
        // A whitelist that has been asked nothing has technically saved everything, but reporting that as a
        // rate would describe an unused one as though it had a result.
        CriticalSoundWhitelist whitelist = new CriticalSoundWhitelist();

        assertEquals(0.0, whitelist.protectedPercent());
        assertEquals(0L, whitelist.checked());
        assertEquals(0L, whitelist.protectedSounds());
        assertEquals(0L, whitelist.refusedByFullPool());
        assertEquals(0, whitelist.size());
    }
}
