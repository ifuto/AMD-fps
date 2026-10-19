package net.amdfaster.runtime;

import net.amdfaster.runtime.SoundRuntime.Decision;
import net.amdfaster.runtime.SoundRuntime.Verdict;
import net.amdfaster.sound.CategoryBudgetAllocator.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundRuntimeTest {

    private static final double FOV = 70.0;
    private static final String MOD = "testmod";

    /** A sound far enough from everything else that no other stage interferes. */
    private static Decision play(SoundRuntime runtime, String sound, Category category) {
        return runtime.decide(sound, MOD, category, 100.0, 64.0, 100.0, 0.0, FOV, false);
    }

    @Test
    void anOrdinarySoundPlays() {
        SoundRuntime runtime = new SoundRuntime();

        Decision decision = play(runtime, "block.note_block.harp", Category.BLOCKS);

        assertEquals(Verdict.PLAYED, decision.verdict());
        assertTrue(decision.plays());
        assertEquals(1L, runtime.played());
    }

    @Test
    void aProtectedSoundPlaysWhateverElseSays() {
        // Checked first, because checking it last would mean the player's hurt sound was already refused by
        // the time the list was consulted.
        SoundRuntime runtime = new SoundRuntime();

        assertTrue(runtime.isProtected("entity.player.hurt"));

        for (int i = 0; i < 20; i++) {
            Decision decision = play(runtime, "entity.player.hurt", Category.PLAYERS);
            assertEquals(Verdict.PROTECTED, decision.verdict(), "never rate limited");
        }
        assertEquals(20L, runtime.played());
        assertEquals(0L, runtime.rateLimited());
    }

    @Test
    void aModCanProtectItsOwnSound() {
        SoundRuntime runtime = new SoundRuntime();

        runtime.protect("testmod.alarm");

        assertTrue(runtime.isProtected("testmod.alarm"));
        assertEquals(Verdict.PROTECTED, play(runtime, "testmod.alarm", Category.BLOCKS).verdict());
    }

    @Test
    void aSoundThatPlaysTooOftenIsRateLimited() {
        // Each play is from a different block, so the duplicate limiter does not bind first -- this test is
        // about the rate window, and at one position the duplicate limit of five would trip before the rate
        // limit of six ever mattered.
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 6; i++) {
            Decision decision = runtime.decide("block.piston.click", MOD, Category.BLOCKS,
                    100.0 + i * 16, 64.0, 100.0 + i * 16, 0.0, FOV, false);
            assertEquals(Verdict.PLAYED, decision.verdict(), "play " + i);
        }
        Decision seventh = runtime.decide("block.piston.click", MOD, Category.BLOCKS,
                100.0 + 6 * 16, 64.0, 100.0 + 6 * 16, 0.0, FOV, false);

        assertEquals(Verdict.RATE_LIMITED, seventh.verdict());
        assertFalse(seventh.plays());
        assertEquals(1L, runtime.rateLimited());
    }

    @Test
    void theRateWindowResetsOnANewTick() {
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 6; i++) {
            play(runtime, "block.piston.click", Category.BLOCKS);
        }
        for (int tick = 0; tick < 20; tick++) {
            runtime.endTick();
        }

        assertEquals(Verdict.PLAYED, play(runtime, "block.piston.click", Category.BLOCKS).verdict(),
                "the window moved on");
    }

    @Test
    void theSameSoundFromTheSameBlockIsADuplicate() {
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 5; i++) {
            assertEquals(Verdict.PLAYED,
                    runtime.decide("block.piston.click", MOD + i, Category.BLOCKS,
                            100.0, 64.0, 100.0, 0.0, FOV, false).verdict());
        }
        Decision sixth = runtime.decide("block.piston.click", MOD + "5", Category.BLOCKS,
                100.0, 64.0, 100.0, 0.0, FOV, false);

        assertEquals(Verdict.DUPLICATE, sixth.verdict());
        assertEquals(1L, runtime.duplicatesDropped());
    }

    @Test
    void theDuplicateWindowResetsOnANewTick() {
        // Missing a component's endTick means its counts grow for the whole session and it eventually refuses
        // everything, which looks like the world going quiet for no reason.
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 5; i++) {
            runtime.decide("block.piston.click", MOD + i, Category.BLOCKS,
                    100.0, 64.0, 100.0, 0.0, FOV, false);
        }
        runtime.endTick();

        assertEquals(Verdict.PLAYED,
                runtime.decide("block.piston.click", MOD + "9", Category.BLOCKS,
                        100.0, 64.0, 100.0, 0.0, FOV, false).verdict());
    }

    @Test
    void aSourceCannotTakeTheWholePool() {
        SoundRuntime runtime = new SoundRuntime();

        int capped = 0;
        for (int i = 0; i < 40; i++) {
            Decision decision = runtime.decide("testmod.machine" + i, MOD, Category.BLOCKS,
                    100.0 + i * 16, 64.0, 100.0 + i * 16, 0.0, FOV, false);
            if (decision.verdict() == Verdict.SOURCE_CAPPED) {
                capped++;
            }
        }

        assertTrue(capped > 0, "the source hit its cap");
        assertTrue(runtime.sourceCapped() > 0);
    }

    @Test
    void aSoundBehindThePlayerIsDroppedWhenThePoolIsContested() {
        SoundRuntime runtime = new SoundRuntime();

        Decision behind = runtime.decide("block.note_block.harp", MOD, Category.BLOCKS,
                100.0, 64.0, 100.0, 180.0, FOV, true);

        assertEquals(Verdict.BEHIND_CAMERA, behind.verdict());
        assertEquals(1L, runtime.behindCamera());
    }

    @Test
    void aSoundBehindThePlayerPlaysWhenThePoolIsNotContested() {
        // Direction is a tie-breaker for a contested pool, not a reason to refuse a sound outright -- a
        // creeper behind the player is exactly the sound they most need to hear.
        SoundRuntime runtime = new SoundRuntime();

        Decision behind = runtime.decide("block.note_block.harp", MOD, Category.BLOCKS,
                100.0, 64.0, 100.0, 180.0, FOV, false);

        assertEquals(Verdict.PLAYED, behind.verdict());
        assertEquals(0L, runtime.behindCamera());
    }

    @Test
    void theCountersAddUpToTheOffers() {
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 100; i++) {
            runtime.decide("sound" + i, MOD + (i % 3), Category.BLOCKS,
                    100.0 + i * 16, 64.0, 100.0 + i * 16, 0.0, FOV, false);
        }

        assertEquals(100L, runtime.offered());
        assertEquals(runtime.offered(), runtime.played() + runtime.refused(),
                "every offer is either played or refused, with no double counting");
    }

    @Test
    void resetClearsEverything() {
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 50; i++) {
            runtime.decide("sound" + i, MOD, Category.BLOCKS,
                    100.0 + i * 16, 64.0, 100.0, 0.0, FOV, false);
        }
        runtime.reset();

        assertEquals(0L, runtime.offered());
        assertEquals(0L, runtime.played());
        assertEquals(0L, runtime.refused());
        assertEquals(0L, runtime.tick());
    }

    @Test
    void aLongSessionDoesNotGoQuiet() {
        // The failure a missing endTick produces: counts grow for the whole session and everything is
        // eventually refused.
        SoundRuntime runtime = new SoundRuntime();
        long playedOverSession = 0;
        long playedInLastWindow = 0;

        for (int tick = 0; tick < 200; tick++) {
            for (int i = 0; i < 4; i++) {
                // WEATHER is the ambience category; there is no separate AMBIENT one.
                Decision decision = runtime.decide("ambient." + i, "ambientmod", Category.WEATHER,
                        100.0 + i * 16, 64.0, 100.0 + i * 16, 0.0, FOV, false);
                if (decision.plays()) {
                    playedOverSession++;
                    if (tick >= 180) {
                        playedInLastWindow++;
                    }
                }
            }
            runtime.endTick();
        }

        // The rate limiter is a sliding window of six per twenty ticks, so each sound plays in a six-tick
        // burst and is quiet for the rest of its window: four sounds over ten windows is 240. The point of
        // this test is that the last window still produces plays, because a component whose endTick was
        // missing would produce zero there and a world that went quiet.
        assertEquals(240L, playedOverSession);
        assertEquals(24L, playedInLastWindow,
                "the last window still plays: four sounds times six");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SoundRuntime runtime = new SoundRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.decide(null, MOD, Category.BLOCKS, 0, 0, 0, 0, FOV, false));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.decide("s", null, Category.BLOCKS, 0, 0, 0, 0, FOV, false));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.decide("s", MOD, null, 0, 0, 0, 0, FOV, false));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has decided nothing has technically refused nothing, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        SoundRuntime runtime = new SoundRuntime();

        assertEquals(0.0, runtime.refusedPercent());
        assertEquals(0L, runtime.offered());
        assertEquals(0L, runtime.refused());
        assertEquals(0L, runtime.tick());
        assertEquals(1.0, runtime.loopingVolume(), 1e-12);
    }

    @Test
    void theAudioPoolIsNotContestedUntilTheEventRateSaysSo() {
        // poolContested enables the direction filter, which drops sounds from behind the camera -- the one
        // filter in the pipeline whose absence a player can hear. It has to start off, and it is measured
        // from the rate of sound events rather than asserted, so that it only comes on when channels are
        // genuinely being asked for faster than they free up.
        SoundRuntime runtime = new SoundRuntime();

        assertFalse(runtime.poolContested(), "a fresh runtime is not contending for channels");
        assertEquals(0.0, runtime.pressure(), 1e-12);
    }

    @Test
    void aSoundIsWorthAChannelWhenTheSceneIsQuietAndNotWhenItIsBusy() {
        SoundRuntime sound = new SoundRuntime();
        // Twenty blocks is inside the 32-block maximum, so a quiet scene still starts it.
        assertTrue(sound.allowByDistance(20.0, false));

        // The cutoff tightens towards 8 blocks as the scene fills, so the same sound stops being worth a
        // channel. Nothing about the sound changed; what changed is everything else competing for one.
        for (int i = 0; i < net.amdfaster.sound.AdaptiveSoundCutoff.STRICTEST_AT; i++) {
            sound.noteStarted();
        }
        assertFalse(sound.allowByDistance(20.0, false));
        assertEquals(1L, sound.distanceRefused());
        // And a sound well inside even the strictest cutoff still plays.
        assertTrue(sound.allowByDistance(2.0, false));
    }

    @Test
    void anAttachedSoundIsNeverCutForDistance() {
        SoundRuntime sound = new SoundRuntime();
        for (int i = 0; i < net.amdfaster.sound.AdaptiveSoundCutoff.STRICTEST_AT; i++) {
            sound.noteStarted();
        }
        // The menu, the music and the player's own voice have no position in the world. Applying a
        // distance cutoff to them would make the interface silent when the scene got busy, which is not a
        // performance trade but a broken control.
        assertTrue(sound.allowByDistance(500.0, true));
        assertEquals(0L, sound.distanceRefused());
    }

    @Test
    void theDistancePressureResetsEachTick() {
        SoundRuntime sound = new SoundRuntime();
        for (int i = 0; i < net.amdfaster.sound.AdaptiveSoundCutoff.STRICTEST_AT; i++) {
            sound.noteStarted();
        }
        assertFalse(sound.allowByDistance(20.0, false));
        sound.endTick();
        // A tick's worth of sounds says nothing about the next one. Carrying the count over would leave the
        // cutoff permanently strict after a single busy moment.
        assertTrue(sound.allowByDistance(20.0, false));
    }
}
