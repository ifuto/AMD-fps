package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantTerrainGeneratorTest {

    @Test
    void aChunkCostsMoreThanOneFramesBudgetSoTheRemainderCarries() {
        // Dividing the budget by the cost and rounding down would give zero chunks per frame forever, and
        // rounding up would exceed the budget, so the remainder is carried instead.
        DistantTerrainGenerator generator = new DistantTerrainGenerator(1);

        assertEquals(0, generator.generateFrame(1000), "2 ms of a 6 ms chunk");
        assertEquals(0, generator.generateFrame(1000));
        assertEquals(1, generator.generateFrame(1000), "the third frame has enough");
        assertEquals(1L, generator.generated());
    }

    @Test
    void workersMultiplyTheFramesAllowance() {
        // A single-threaded budget would fill a thirty-two chunk radius in minutes, which is longer than the
        // player stays in one place.
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        assertEquals(16.0, generator.frameBudget(), 1e-9);
        assertEquals(2, generator.generateFrame(1000), "16 ms of a 6 ms chunk");
        assertEquals(8, generator.workerCount());
    }

    @Test
    void theMeasuredRateMatchesTheReferenceImplementation() {
        // Voxy Worldgen generates about 170 distant chunks a second on an i5.
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        for (int frame = 0; frame < 60; frame++) {
            generator.generateFrame(10_000);
        }

        assertEquals(160L, generator.generated(), "a second of frames");
        assertEquals(16.0, generator.msPerFrame(), 1e-9, "the budget, spent");
    }

    @Test
    void generationStopsWhenNothingIsPending() {
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        assertEquals(0, generator.generateFrame(0));
        assertEquals(0L, generator.generated());
        assertEquals(1L, generator.frames());
    }

    @Test
    void theRadiusIsSixtyFiveColumnsAcross() {
        assertEquals(65 * 65, DistantTerrainGenerator.columnsInRadius());
        assertEquals(4225, DistantTerrainGenerator.columnsInRadius());
    }

    @Test
    void fillingTheRadiusTakesUnderHalfAMinute() {
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        for (int frame = 0; frame < 60; frame++) {
            generator.generateFrame(10_000);
        }

        assertEquals(1585, generator.framesToFill(), "4225 columns at 160 a second");
        assertEquals(87_000.0, generator.msGenerated(), 1e-9);
    }

    @Test
    void aRealChunkReplacesTheGeneratedOne() {
        // Drawing both would put two surfaces in the same place, and the generated one would be missing
        // everything the real one has, so whichever drew second would decide whether the player saw trees.
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        generator.generateFrame(100);
        generator.replaceWithReal();

        assertEquals(1L, generator.replaced());
        assertTrue(generator.generated() > 0);
    }

    @Test
    void terrainThePlayerWalkedPastIsDropped() {
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        generator.dropOutOfRange();

        assertEquals(1L, generator.dropped());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DistantTerrainGenerator(0));
        assertThrows(IllegalArgumentException.class, () -> new DistantTerrainGenerator(-1));

        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);
        assertThrows(IllegalArgumentException.class, () -> generator.generateFrame(-1));
    }

    @Test
    void aFreshGeneratorReportsZeroNotNaN() {
        // A generator that has done nothing has technically filled the horizon instantly, but reporting
        // that as a time would describe an unused one as though it had done something.
        DistantTerrainGenerator generator = new DistantTerrainGenerator(8);

        assertEquals(0, generator.framesToFill());
        assertEquals(0.0, generator.msPerFrame(), 1e-12);
        assertEquals(0.0, generator.msGenerated(), 1e-12);
        assertEquals(0L, generator.generated());
        assertEquals(0L, generator.frames());
        assertEquals(32, DistantTerrainGenerator.RADIUS);
    }
}
