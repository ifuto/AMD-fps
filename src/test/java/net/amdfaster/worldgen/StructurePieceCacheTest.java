package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructurePieceCacheTest {

    /** A stair facing north, a door hinge, a wall torch, a plain block, and a floor. */
    private static final int[] HOUSE = {
        StructurePieceCache.NORTH,   // stair
        StructurePieceCache.EAST,    // door hinge side
        StructurePieceCache.WEST,    // torch on a wall
        1,                           // plain cobblestone, no direction
        0                            // air
    };

    @Test
    void aCachedDesignIsReusedInsteadOfRegenerated() {
        // A village is eight houses from three or four designs. Vanilla lays each one out block by
        // block, so the same 400-block house is built eight times when only its rotation differs.
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);

        for (int i = 0; i < 8; i++) {
            assertTrue(cache.place(1, 0, i % 4) != null, "house " + i);
        }

        assertEquals(1, cache.generated());
        assertEquals(8L, cache.reused());
    }

    @Test
    void rotatingCyclesThroughTheFourDirections() {
        // A mapping that is not a permutation would drift, and a house would end up facing a direction
        // that does not exist.
        int facing = StructurePieceCache.NORTH;
        for (int turn = 0; turn < 4; turn++) {
            facing = StructurePieceCache.rotate(facing, 1);
        }
        assertEquals(StructurePieceCache.NORTH, facing, "four quarter turns come back");
    }

    @Test
    void eachQuarterTurnIsADifferentDirection() {
        int[] seen = new int[4];
        int facing = StructurePieceCache.NORTH;
        for (int turn = 0; turn < 4; turn++) {
            seen[turn] = facing;
            facing = StructurePieceCache.rotate(facing, 1);
        }
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                assertTrue(seen[i] != seen[j], "turns " + i + " and " + j + " agree");
            }
        }
    }

    @Test
    void rotatingATemplateTurnsEveryDirectionalBlock() {
        // Copying raw block ids would give a house with stairs leading into walls and torches floating
        // in the air, which is visibly broken rather than subtly wrong.
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);

        int[] turned = cache.place(1, 0, 1);

        assertEquals(StructurePieceCache.rotate(StructurePieceCache.NORTH, 1), turned[0]);
        assertEquals(StructurePieceCache.rotate(StructurePieceCache.EAST, 1), turned[1]);
        assertEquals(StructurePieceCache.rotate(StructurePieceCache.WEST, 1), turned[2]);
        assertEquals(1, turned[3], "plain cobblestone has no direction to turn");
        assertEquals(0, turned[4], "and air stays air");
    }

    @Test
    void turningAllTheWayRoundGivesTheTemplateBack() {
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);

        assertArrayEquals(HOUSE, cache.place(1, 0, 0));
        assertArrayEquals(HOUSE, cache.place(1, 0, 4 & 3), "four turns is no turn");
    }

    @Test
    void verticalAndDirectionlessBlocksAreNotTurned() {
        // Turning a house does not make a floor become a wall.
        assertEquals(0, StructurePieceCache.rotate(0, 1), "down");
        assertEquals(1, StructurePieceCache.rotate(1, 1), "up");
        assertEquals(7, StructurePieceCache.rotate(7, 3), "not a facing at all");
    }

    @Test
    void placingReturnsACopyNotTheTemplate() {
        // Handing out the template itself would let a caller's write change every later placement of the
        // same design -- and the second house would inherit the first one's edits.
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);

        int[] first = cache.place(1, 0, 0);
        first[0] = 999;
        int[] second = cache.place(1, 0, 0);

        assertNotSame(first, second);
        assertEquals(HOUSE[0], second[0], "the template was not modified");
    }

    @Test
    void storingCopiesTheCallerArray() {
        // Otherwise a caller reusing its buffer would change the cached design.
        StructurePieceCache cache = new StructurePieceCache();
        int[] blocks = HOUSE.clone();
        cache.store(1, 0, blocks);
        blocks[0] = 999;

        assertEquals(HOUSE[0], cache.place(1, 0, 0)[0]);
    }

    @Test
    void anUncachedDesignReportsIt() {
        // The caller has to generate it and store it; silently returning nothing would place an empty
        // house, which is the same as a house that vanished.
        StructurePieceCache cache = new StructurePieceCache();

        assertNull(cache.place(9, 0, 0));
        assertFalse(cache.contains(9, 0));
    }

    @Test
    void variantsAreSeparateDesigns() {
        // A mirrored house is a different template, and sharing one would put every house on the same
        // side of the road.
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);
        cache.store(1, 1, new int[] {StructurePieceCache.SOUTH});

        assertEquals(2, cache.templateCount());
        assertEquals(HOUSE.length, cache.place(1, 0, 0).length);
        assertEquals(1, cache.place(1, 1, 0).length);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        StructurePieceCache cache = new StructurePieceCache();
        cache.store(1, 0, HOUSE);

        assertThrows(IllegalArgumentException.class, () -> cache.store(2, 0, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> cache.place(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> cache.place(1, 0, 4));
    }

    @Test
    void aVillageIsCheaperWithFourDesignsThanEight() {
        StructurePieceCache cache = new StructurePieceCache();
        for (int design = 0; design < 4; design++) {
            cache.store(design, 0, HOUSE);
        }

        double with = cache.msWithCache(8, StructurePieceCache.HOUSE_BLOCKS);
        double without = cache.msWithoutCache(8, StructurePieceCache.HOUSE_BLOCKS);

        assertEquals(8 * 400 * StructurePieceCache.MS_PER_BLOCK, without, 1e-9);
        assertEquals(6.4, without, 0.001, "eight houses at 400 blocks");
        assertTrue(with < without, with + " vs " + without);
        assertTrue(cache.savedPercent(8, StructurePieceCache.HOUSE_BLOCKS) > 40.0);
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // Nothing is cached, so nothing can be served from it. Reporting the copy cost anyway would
        // claim a saving for work that cannot happen.
        StructurePieceCache cache = new StructurePieceCache();

        assertEquals(0.0, cache.savedPercent(8, 400), "an empty cache saves nothing");
        assertEquals(0, cache.templateCount());
        assertEquals(0L, cache.reused());
    }
}
