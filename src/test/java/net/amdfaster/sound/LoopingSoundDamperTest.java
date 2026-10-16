package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopingSoundDamperTest {

    @Test
    void itStartsAtFullVolume() {
        LoopingSoundDamper damper = new LoopingSoundDamper();

        assertEquals(1.0, damper.volume(), 1e-12);
        assertFalse(damper.atFloor());
    }

    @Test
    void pressureLowersTheVolumeOneStepAtATime() {
        // Cutting a continuous sound mid-wave is audible as a click, because the waveform does not end at
        // zero. Five ticks is a quarter of a second -- quick enough to relieve the pool, slow enough that
        // the ear reads it as the sound getting distant rather than as an edit.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 1; i <= LoopingSoundDamper.STEPS; i++) {
            double volume = damper.tick(true);
            assertEquals(1.0 - i * LoopingSoundDamper.STEP, volume, 1e-9, "step " + i);
        }
        assertEquals(LoopingSoundDamper.STEPS, damper.dampened());
    }

    @Test
    void itReachesTheFloorAndStopsThere() {
        // Going lower would stop the sound, and starting it again costs a channel and a load, which is the
        // expensive thing the dampening was avoiding.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < 20; i++) {
            damper.tick(true);
        }

        assertEquals(LoopingSoundDamper.FLOOR, damper.volume(), 1e-9);
        assertTrue(damper.atFloor());
        assertEquals(15L, damper.heldAtFloor(), "twenty ticks, five of them lowering");
    }

    @Test
    void theFloorIsNotSilence() {
        assertTrue(LoopingSoundDamper.FLOOR > 0.0);
        assertEquals(0.15, LoopingSoundDamper.FLOOR, 1e-12);
    }

    @Test
    void reliefRaisesItBackAtTheSameRate() {
        // A sound that faded out over five ticks and then snapped back to full volume would be more
        // noticeable than the click this exists to avoid, because the ear is very good at hearing something
        // return to exactly where it was.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < LoopingSoundDamper.STEPS; i++) {
            damper.tick(true);
        }
        assertEquals(LoopingSoundDamper.FLOOR, damper.volume(), 1e-9);

        for (int i = 1; i <= LoopingSoundDamper.STEPS; i++) {
            damper.tick(false);
        }

        assertEquals(1.0, damper.volume(), 1e-9, "back to where it started");
        assertEquals(LoopingSoundDamper.STEPS, damper.recovered());
    }

    @Test
    void itNeverExceedsItsOwnVolume() {
        // Recovering past where the sound was would make a machine get louder under load, which is the
        // opposite of what the player expects and would sound like a bug.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < 50; i++) {
            assertTrue(damper.tick(false) <= 1.0);
        }
        assertEquals(1.0, damper.volume(), 1e-12);
        assertEquals(0L, damper.recovered(), "it was already at full volume");
    }

    @Test
    void keepingTheSoundAliveAvoidsARestart() {
        // The relief is nearly as good as stopping it and the recovery is free, because there is no channel
        // to allocate and no sample to load again.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < LoopingSoundDamper.STEPS; i++) {
            damper.tick(true);
        }

        assertEquals(1L, damper.restartsAvoided());
        assertEquals(LoopingSoundDamper.MS_PER_RESTART, damper.msSaved(), 1e-12);
    }

    @Test
    void anUnchangingSceneDoesNothing() {
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < 100; i++) {
            damper.tick(false);
        }

        assertEquals(1.0, damper.volume(), 1e-12);
        assertEquals(100L, damper.ticks());
        assertEquals(0L, damper.dampened());
        assertEquals(0.0, damper.dampenedPercent(), 1e-12);
    }

    @Test
    void aSustainedLoadStaysDampened() {
        LoopingSoundDamper damper = new LoopingSoundDamper();

        for (int i = 0; i < 20; i++) {
            damper.tick(true);
        }

        assertEquals(25.0, damper.dampenedPercent(), 1e-9, "five of twenty ticks lowered it");
    }

    @Test
    void aFreshDamperReportsZeroNotNaN() {
        // A damper that has not run has technically done nothing, and reporting that as a rate would
        // describe an unused one as though it had a result.
        LoopingSoundDamper damper = new LoopingSoundDamper();

        assertEquals(0.0, damper.dampenedPercent());
        assertEquals(0.0, damper.msSaved(), 1e-12);
        assertEquals(0L, damper.ticks());
        assertEquals(0L, damper.restartsAvoided());
        assertEquals(5, LoopingSoundDamper.STEPS);
    }
}
