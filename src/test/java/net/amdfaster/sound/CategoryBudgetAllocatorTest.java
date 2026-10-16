package net.amdfaster.sound;

import net.amdfaster.sound.CategoryBudgetAllocator.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryBudgetAllocatorTest {

    @Test
    void eachCategoryHasItsOwnShareOfThePool() {
        assertEquals(64, Category.MASTER.channels());
        assertEquals(64, Category.VOICE.channels());
        assertEquals(60, Category.PLAYERS.channels());
        assertEquals(57, Category.HOSTILE.channels());
        assertEquals(51, Category.BLOCKS.channels());
        assertEquals(44, Category.MUSIC.channels());
        assertEquals(38, Category.NEUTRAL.channels());
        assertEquals(32, Category.WEATHER.channels());
    }

    @Test
    void weatherGivesWayBeforeHostileMobs() {
        // Which sound is lost has nothing to do with which sound matters, unless something decides it. A
        // full pool should cost the player the rain rather than the creeper.
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(Category.WEATHER);
        }

        assertEquals(32, allocator.inUse(Category.WEATHER), "capped at half the pool");
        assertEquals(68L, allocator.refusedShare());

        for (int i = 0; i < 32; i++) {
            assertTrue(allocator.allow(Category.HOSTILE), "the rest of the pool is free");
        }
        assertEquals(64, allocator.total());
    }

    @Test
    void aShareIsACeilingNotAReservation() {
        // If nothing is raining those channels are free for anything else, and a busy scene with no weather
        // gets the whole budget. Reserving them would waste channels in the common case, which is a quiet
        // one. Hostile mobs cap at 57, so weather takes the last 7 -- which it could not do if that share
        // had been held back for rain.
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < Category.HOSTILE.channels(); i++) {
            assertTrue(allocator.allow(Category.HOSTILE));
        }
        assertEquals(57, allocator.total());

        for (int i = 0; i < 7; i++) {
            assertTrue(allocator.allow(Category.WEATHER), "the unused share is free");
        }

        assertEquals(CategoryBudgetAllocator.BUDGET, allocator.total(),
                "nothing held back for weather");
    }

    @Test
    void masterAndVoiceAreNeverRefusedByTheirShare() {
        // They are the sounds the player cannot act on without, and letting them be crowded out by weather
        // would silence the game's own important audio in exactly the busy scenes where it matters most.
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < CategoryBudgetAllocator.BUDGET; i++) {
            assertTrue(allocator.allow(Category.MASTER));
        }
        assertEquals(CategoryBudgetAllocator.BUDGET, allocator.total());

        assertFalse(allocator.allow(Category.MASTER), "but a genuinely full pool still stops it");
        assertEquals(1L, allocator.refusedFull());
    }

    @Test
    void voiceCountsAgainstItselfNotAgainstMaster() {
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < 10; i++) {
            allocator.allow(Category.VOICE);
        }

        assertEquals(10, allocator.inUse(Category.VOICE));
        assertEquals(0, allocator.inUse(Category.MASTER), "unchanged by voice sounds");
        assertEquals(10, allocator.total());
    }

    @Test
    void aFullPoolRefusesEverything() {
        // Blocks are capped at 51 channels, so filling the pool needs a category whose share is the whole
        // of it; a hundred block clicks leave room for the rest of the world.
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < CategoryBudgetAllocator.BUDGET; i++) {
            assertTrue(allocator.allow(Category.MASTER));
        }
        assertEquals(CategoryBudgetAllocator.BUDGET, allocator.total());

        assertFalse(allocator.allow(Category.WEATHER));
        assertFalse(allocator.allow(Category.HOSTILE));

        assertEquals(2L, allocator.refusedFull());
        assertEquals(0L, allocator.refusedShare());
    }

    @Test
    void blocksAloneDoNotFillThePool() {
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < CategoryBudgetAllocator.BUDGET; i++) {
            allocator.allow(Category.BLOCKS);
        }

        assertEquals(51, allocator.total(), "capped at eighty percent");
        assertEquals(13L, allocator.refusedShare());
        assertTrue(allocator.allow(Category.WEATHER), "and the rest is still free");
    }

    @Test
    void theCountsResetEveryTick() {
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int i = 0; i < 100; i++) {
            allocator.allow(Category.WEATHER);
        }
        allocator.endTick();

        assertEquals(0, allocator.total());
        assertEquals(0, allocator.inUse(Category.WEATHER));
        assertTrue(allocator.allow(Category.WEATHER));
    }

    @Test
    void aRainstormIsMostlyRefused() {
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < 100; i++) {
                allocator.allow(Category.WEATHER);
            }
            allocator.endTick();
        }

        assertEquals(2000L, allocator.requests());
        assertEquals(640L, allocator.played(), "32 per tick for twenty ticks");
        assertEquals(68.0, allocator.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        assertThrows(IllegalArgumentException.class, () -> allocator.allow(null));
        assertThrows(IllegalArgumentException.class, () -> allocator.inUse(null));
    }

    @Test
    void aFreshAllocatorReportsZeroNotNaN() {
        // An allocator that has been asked nothing has technically refused nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        CategoryBudgetAllocator allocator = new CategoryBudgetAllocator();

        assertEquals(0.0, allocator.playedPercent());
        assertEquals(0.0, allocator.savedPercent());
        assertEquals(0L, allocator.requests());
        assertEquals(0, allocator.total());
        assertEquals(64, CategoryBudgetAllocator.BUDGET);
    }
}
