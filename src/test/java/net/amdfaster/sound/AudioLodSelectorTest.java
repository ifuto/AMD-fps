package net.amdfaster.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.sound.AudioLodSelector.Placement;
import net.amdfaster.sound.AudioLodSelector.Tier;
import org.junit.jupiter.api.Test;

class AudioLodSelectorTest {

    @Test
    void aNearbySoundIsFullRateAndAudioRateParameters() {
        Placement placement = AudioLodSelector.place(8.0, 32.0, 1.0, false);

        assertEquals(Tier.FULL, placement.tier());
        assertEquals(0.75, placement.attenuated(), 1e-12);
        assertEquals(0.0, placement.decibels() - AudioLodSelector.decibels(0.75), 1e-12);
        assertFalse(placement.controlRate(), "a nearby sound's pan and doppler are perceptible per frame");
        assertEquals(48000, placement.tier().sampleRate());
        assertTrue(placement.tier().physical());
    }

    @Test
    void theTierBoundariesAreDistanceFractions() {
        assertEquals(Tier.FULL, AudioLodSelector.place(15.9, 32.0, 1.0, false).tier());
        assertEquals(Tier.REDUCED, AudioLodSelector.place(16.0, 32.0, 1.0, false).tier(), "exactly half");
        assertEquals(Tier.REDUCED, AudioLodSelector.place(20.0, 32.0, 1.0, false).tier());
        assertEquals(Tier.MINIMAL, AudioLodSelector.place(25.6, 32.0, 1.0, false).tier(), "exactly 0.8");
        assertEquals(Tier.MINIMAL, AudioLodSelector.place(30.0, 32.0, 1.0, false).tier());
        assertEquals(Tier.VIRTUAL, AudioLodSelector.place(31.68, 32.0, 1.0, false).tier(), "exactly 0.99");
        assertEquals(Tier.OFF, AudioLodSelector.place(32.0, 32.0, 1.0, false).tier());
        assertEquals(Tier.OFF, AudioLodSelector.place(40.0, 32.0, 1.0, false).tier(), "past the limit");
    }

    @Test
    void theSampleRatesStopWhereTheCodecsStopBeingGood() {
        assertEquals(48000, Tier.FULL.sampleRate());
        assertEquals(22050, Tier.REDUCED.sampleRate());
        assertEquals(16000, Tier.MINIMAL.sampleRate(), "the floor; below it the codecs degrade");
        assertEquals(0, Tier.VIRTUAL.sampleRate());
        assertFalse(Tier.VIRTUAL.physical());
        assertFalse(Tier.OFF.physical());
        assertTrue(Tier.MINIMAL.physical());
    }

    @Test
    void aCriticalSoundIsNeverVirtualised() {
        // A footstep 31 blocks away can be dropped. The hiss of a creeper next to the player cannot, even
        // at the same distance, and losing it is a gameplay loss rather than an audio one.
        Placement ordinary = AudioLodSelector.place(31.68, 32.0, 1.0, false);
        Placement critical = AudioLodSelector.place(31.68, 32.0, 1.0, true);

        assertEquals(Tier.VIRTUAL, ordinary.tier());
        assertEquals(Tier.MINIMAL, critical.tier(), "still mixed, just at the lowest rate");
        assertTrue(critical.tier().physical());
    }

    @Test
    void aMiddlewareStyleDecibelThresholdWouldVirtualiseAlmostNothing() {
        // This is the reason the tiers are chosen on distance and the dB is only reported. -40 dB is the
        // figure middleware defaults to, on the assumption of a logarithmic curve where it is a useful
        // chunk of the range. Minecraft attenuates linearly, so -40 dB is the last one per cent of it, and
        // -96 dB is essentially the range limit itself.
        assertEquals(31.68, AudioLodSelector.distanceForDecibels(-40.0, 32.0), 1e-9);
        assertEquals(31.9995, AudioLodSelector.distanceForDecibels(-96.0, 32.0), 1e-4);

        // The same helper shows what the tiers actually correspond to, which is a much wider band.
        assertEquals(16.0, AudioLodSelector.distanceForDecibels(-6.0206, 32.0), 1e-3);
        assertEquals(25.6, AudioLodSelector.distanceForDecibels(-13.9794, 32.0), 1e-3);
    }

    @Test
    void decibelsOfSilenceIsNotAFiniteNumber() {
        assertEquals(Double.NEGATIVE_INFINITY, AudioLodSelector.decibels(0.0));
        assertEquals(0.0, AudioLodSelector.decibels(1.0), 1e-12);
        assertEquals(-6.020599913279624, AudioLodSelector.decibels(0.5), 1e-12);
        assertEquals(-40.0, AudioLodSelector.decibels(0.01), 1e-12);

        Placement silent = AudioLodSelector.place(32.0, 32.0, 1.0, false);
        assertEquals(Double.NEGATIVE_INFINITY, silent.decibels());
        assertEquals(Tier.OFF, silent.tier());
    }

    @Test
    void aNonAttenuatingSoundIsAlwaysFullRate() {
        // A UI click has no range to be a fraction of, and is heard at full volume anywhere, so it is
        // never a candidate for a lower tier.
        Placement placement = AudioLodSelector.place(5.0, 0.0, 1.0, false);

        assertEquals(Tier.FULL, placement.tier());
        assertEquals(1.0, placement.attenuated(), 1e-12);
        assertEquals(0.0, placement.decibels(), 1e-12);
        assertFalse(placement.controlRate());
    }

    @Test
    void controlRateIsTheLargestSavingAndCostsNoQuality() {
        // The listener does not move 48,000 times a second; it moves once a tick. Unlike the sample rate
        // tiers this changes nothing the player could hear, which is why it applies to every tier below
        // FULL rather than only the quiet ones.
        assertEquals(60, AudioLodSelector.CONTROL_RATE_HZ);
        assertEquals(800, AudioLodSelector.controlRateDivisor());

        assertFalse(AudioLodSelector.place(8.0, 32.0, 1.0, false).controlRate());
        assertTrue(AudioLodSelector.place(16.0, 32.0, 1.0, false).controlRate());
        assertTrue(AudioLodSelector.place(25.6, 32.0, 1.0, false).controlRate());
        assertTrue(AudioLodSelector.place(31.68, 32.0, 1.0, false).controlRate());
    }

    @Test
    void virtualisingHalfTheVoicesIsRoughly45PerCentOffTheMixingCost() {
        assertEquals(3.2, AudioLodSelector.tickMillis(64, 0), 1e-12);
        assertEquals(1.76, AudioLodSelector.tickMillis(32, 32), 1e-12);
        assertEquals(0.32, AudioLodSelector.tickMillis(0, 64), 1e-12);
        assertEquals(0.0, AudioLodSelector.tickMillis(0, 0), 1e-12);

        double allPhysical = AudioLodSelector.tickMillis(64, 0);
        double half = AudioLodSelector.tickMillis(32, 32);
        assertEquals(0.55, half / allPhysical, 1e-9);
    }

    @Test
    void virtualVoicesAreNotFree() {
        // A virtual voice still computes its volume and its elapsed time, and is still an emitter for
        // spatial processing. 200 of them is not cheap, it is just cheaper than 200 physical ones -- and
        // the per-voice cost is a tenth rather than zero so that the count keeps mattering.
        assertEquals(0.05, AudioLodSelector.MS_PER_PHYSICAL_VOICE, 1e-12);
        assertEquals(0.005, AudioLodSelector.MS_PER_VIRTUAL_VOICE, 1e-12);
        assertEquals(10.0,
                AudioLodSelector.MS_PER_PHYSICAL_VOICE / AudioLodSelector.MS_PER_VIRTUAL_VOICE, 1e-12);
        assertEquals(1.0, AudioLodSelector.tickMillis(0, 200), 1e-12);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioLodSelector.place(-1.0, 32.0, 1.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> AudioLodSelector.place(1.0, 32.0, -1.0, false));
        assertThrows(IllegalArgumentException.class, () -> AudioLodSelector.decibels(-0.5));
        assertThrows(IllegalArgumentException.class,
                () -> AudioLodSelector.distanceForDecibels(6.0, 32.0), "an attenuation is not positive");
        assertThrows(IllegalArgumentException.class,
                () -> AudioLodSelector.distanceForDecibels(-40.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> AudioLodSelector.tickMillis(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> AudioLodSelector.tickMillis(0, -1));
    }
}
