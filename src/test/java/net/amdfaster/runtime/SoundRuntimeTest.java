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
        SoundRuntime runtime = new SoundRuntime();

        for (int i = 0; i < 6; i++) {
            assertEquals(Verdict.PLAYED, play(runtime, "block.piston.click", Category.BLOCKS).verdict());
        }
        Decision seventh = play(runtime, "block.piston.click", Category.BLOCKS);

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

        for (int tick = 0; tick < 200; tick++) {
            for (int i = 0; i < 4; i++) {
                Decision decision = runtime.decide("ambient." + i, "ambientmod", Category.AMBIENT,
                        100.0 + i * 16, 64.0, 100.0 + i * 16, 0.0, FOV, false);
                if (decision.plays()) {
                    playedOverSession++;
                }
            }
            runtime.endTick();
        }

        assertTrue(playedOverSession > 400, "sounds still play after two hundred ticks, got "
                + playedOverSession);
        assertTrue(runtime.refusedPercent() < 50.0,
                "and most of them are not refused: " + runtime.refusedPercent());
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
}
