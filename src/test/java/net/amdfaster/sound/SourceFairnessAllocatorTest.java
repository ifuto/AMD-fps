package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFairnessAllocatorTest {

    private static final String PISTON = "block:0,64,0";

    @Test
    void oneSourceCannotTakeTheWholeBudget() {
        // A piston clock firing a hundred times a second gets there first and takes every channel, after
        // which the player's footsteps and a creeper behind them are all silent.
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(PISTON, false);
        }

        assertEquals(SourceFairnessAllocator.PER_SOURCE_CAP, allocator.inUse(),
                "capped at an eighth of the pool");
        assertEquals(100 - SourceFairnessAllocator.PER_SOURCE_CAP, allocator.capped());
        assertEquals(8, SourceFairnessAllocator.PER_SOURCE_CAP);
    }

    @Test
    void theRestOfTheWorldStillGetsChannels() {
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(PISTON, false);
        }
        int othersPlayed = 0;
        for (int i = 0; i < 20; i++) {
            if (allocator.allow("entity:" + i, false)) {
                othersPlayed++;
            }
        }

        assertEquals(20, othersPlayed, "all twenty still audible");
        assertEquals(SourceFairnessAllocator.PER_SOURCE_CAP + 20, allocator.inUse());
    }

    @Test
    void aCappedSourceIsStillClearlyPresent() {
        // A cap of one would make a machine sound like it fires once a second, which is its own kind of
        // wrong. An eighth of the pool leaves it audible.
        assertTrue(SourceFairnessAllocator.PER_SOURCE_CAP > 1);
        assertEquals(SourceFairnessAllocator.BUDGET / 8, SourceFairnessAllocator.PER_SOURCE_CAP);
    }

    @Test
    void thePlayersOwnSoundsBypassTheCap() {
        // Culling one because a machine was noisy means taking damage with no sound, and the player learns
        // nothing about why they are suddenly on half a heart.
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(PISTON, false);
        }
        for (int i = 0; i < SourceFairnessAllocator.PER_SOURCE_CAP; i++) {
            allocator.allow("player:attack", false);
        }

        assertTrue(allocator.allow("player:attack", true), "the cap is full but this still plays");
        assertEquals(1L, allocator.protectedSounds());
    }

    @Test
    void playerFeedbackIsExemptEvenWhenThePoolIsFull() {
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < SourceFairnessAllocator.BUDGET; i++) {
            allocator.allow("source:" + i, false);
        }
        assertEquals(SourceFairnessAllocator.BUDGET, allocator.inUse());

        assertTrue(allocator.allow("player:hurt", true));
    }

    @Test
    void theCapResetsEveryTick() {
        // A piston clock that stops should immediately stop reserving channels, and a creeper that starts
        // hissing should not find the budget still held by a machine that went quiet.
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(PISTON, false);
        }
        allocator.endTick();

        assertEquals(0, allocator.inUse());
        assertEquals(0, allocator.sources());
        assertTrue(allocator.allow(PISTON, false), "fresh cap next tick");
    }

    @Test
    void aFullPoolRefusesTheRest() {
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int i = 0; i < SourceFairnessAllocator.BUDGET; i++) {
            assertTrue(allocator.allow("source:" + i, false));
        }
        assertFalse(allocator.allow("one:more", false));

        assertEquals(1L, allocator.exhausted());
        assertEquals(SourceFairnessAllocator.BUDGET, allocator.inUse());
    }

    @Test
    void aNoisyMachineCostsAlmostNothing() {
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 100; i++) {
                allocator.allow(PISTON, false);
            }
            allocator.endTick();
        }

        assertEquals(2000L, allocator.requests());
        assertEquals(160L, allocator.played(), "eight per tick for twenty ticks");
        assertEquals(92.0, allocator.savedPercent(), 1e-9);
        assertEquals(1840 * SourceFairnessAllocator.MS_PER_CHANNEL, allocator.msSaved(), 1e-9);
    }

    @Test
    void aFreshAllocatorReportsZeroNotNaN() {
        // An allocator that has been asked nothing has technically refused nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        SourceFairnessAllocator allocator = new SourceFairnessAllocator();

        assertEquals(0.0, allocator.playedPercent());
        assertEquals(0.0, allocator.savedPercent());
        assertEquals(0.0, allocator.msSaved(), 1e-12);
        assertEquals(0L, allocator.requests());
        assertEquals(0, allocator.inUse());
        assertEquals(0, allocator.sources());
        assertEquals(64, SourceFairnessAllocator.BUDGET);
    }
}
