package net.amdfaster.mc;

import net.amdfaster.light.LightValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expectations came from a separate re-implementation of the bit layout, diffed before committing.
 * The concrete hex values are asserted as well as the round trip, because a round trip alone passes
 * for a layout that has simply put the fields somewhere else -- and somewhere else is a bug the
 * moment the shader or the GPU record expects them where the Javadoc says.
 */
class BlockKeysTest {

    @Test
    void airIsZeroAndDecodesToNothing() {
        assertEquals(0, BlockKeys.NO_GEOMETRY);
        assertFalse(BlockKeys.isPresent(BlockKeys.NO_GEOMETRY));
        assertEquals(0, BlockKeys.sprite(BlockKeys.NO_GEOMETRY));
        assertEquals(0, BlockKeys.blockLight(BlockKeys.NO_GEOMETRY));
        assertEquals(0, BlockKeys.skyLight(BlockKeys.NO_GEOMETRY));
        assertEquals(BlockKeys.NO_TINT, BlockKeys.tint(BlockKeys.NO_GEOMETRY));
        assertEquals(0, BlockKeys.emission(BlockKeys.NO_GEOMETRY));
        assertFalse(BlockKeys.isCutout(BlockKeys.NO_GEOMETRY));
    }

    @Test
    void theDarkestUntintedSpriteZeroBlockIsStillNotAir() {
        // The collision that would be invisible in play until a specific block vanished: every
        // field is zero, so only the present bit distinguishes it from air.
        int key = BlockKeys.encode(0, 0, 0, BlockKeys.NO_TINT, 0, false);
        assertNotEquals(BlockKeys.NO_GEOMETRY, key);
        assertTrue(BlockKeys.isPresent(key));
        assertEquals(0x80000000, key, "present bit alone");
    }

    @Test
    void everyFieldRoundTrips() {
        int[][] cases = {
                {0, 0, 0, BlockKeys.NO_TINT, 0, 0},
                {1, 2, 3, BlockKeys.NO_TINT, 0, 0},
                {BlockKeys.MAX_SPRITE, 15, 15, BlockKeys.MAX_TINT, 15, 1},
                {7, 15, 0, 0, 0, 1},
                {0, 0, 15, BlockKeys.NO_TINT, 15, 0},
                {5, 3, 9, 4, 2, 1},
        };
        for (int[] c : cases) {
            int key = BlockKeys.encode(c[0], c[1], c[2], c[3], c[4], c[5] != 0);
            assertEquals(c[0], BlockKeys.sprite(key), "sprite");
            assertEquals(c[1], BlockKeys.blockLight(key), "block light");
            assertEquals(c[2], BlockKeys.skyLight(key), "sky light");
            assertEquals(c[3], BlockKeys.tint(key), "tint");
            assertEquals(c[4], BlockKeys.emission(key), "emission");
            assertEquals(c[5] != 0, BlockKeys.isCutout(key), "cutout");
        }
    }

    @Test
    void thePackedValuesMatchTheLayoutInTheJavadoc() {
        assertEquals(0x80000000, BlockKeys.encode(0, 0, 0, BlockKeys.NO_TINT, 0, false));
        assertEquals(0x80024600, BlockKeys.encode(1, 2, 3, BlockKeys.NO_TINT, 0, false));
        assertEquals(0xFFFFFFFF,
                BlockKeys.encode(BlockKeys.MAX_SPRITE, 15, 15, BlockKeys.MAX_TINT, 15, true));
        assertEquals(0x800FE021, BlockKeys.encode(7, 15, 0, 0, 0, true));
        assertEquals(0x80001E1E, BlockKeys.encode(0, 0, 15, BlockKeys.NO_TINT, 15, false));
        assertEquals(0x800A72A5, BlockKeys.encode(5, 3, 9, 4, 2, true));
    }

    @Test
    void lightAgreesWithLightValuePack() {
        // The invariant that caught a real bug in the light cache: two encodings of the same two
        // levels that quietly disagree. Cross-package, so a change to either side has to be made to
        // both.
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                int key = BlockKeys.encode(1, block, sky, BlockKeys.NO_TINT, 0, false);
                assertEquals(LightValue.pack(block, sky), BlockKeys.light(key),
                        "block=" + block + " sky=" + sky);
            }
        }
    }

    @Test
    void changingAnyFieldChangesTheKey() {
        // The requirement greedy meshing depends on: if two faces would look different merged, they
        // must not have the same key.
        int base = BlockKeys.encode(5, 7, 9, 2, 1, false);
        assertNotEquals(base, BlockKeys.encode(6, 7, 9, 2, 1, false), "sprite");
        assertNotEquals(base, BlockKeys.encode(5, 8, 9, 2, 1, false), "block light");
        assertNotEquals(base, BlockKeys.encode(5, 7, 10, 2, 1, false), "sky light");
        assertNotEquals(base, BlockKeys.encode(5, 7, 9, 3, 1, false), "tint");
        assertNotEquals(base, BlockKeys.encode(5, 7, 9, BlockKeys.NO_TINT, 1, false), "tint to none");
        assertNotEquals(base, BlockKeys.encode(5, 7, 9, 2, 2, false), "emission");
        assertNotEquals(base, BlockKeys.encode(5, 7, 9, 2, 1, true), "cutout");
    }

    @Test
    void outOfRangeInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(BlockKeys.MAX_SPRITE + 1, 0, 0, BlockKeys.NO_TINT, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(-1, 0, 0, BlockKeys.NO_TINT, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(0, 16, 0, BlockKeys.NO_TINT, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(0, 0, 16, BlockKeys.NO_TINT, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(0, 0, 0, BlockKeys.MAX_TINT + 1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(0, 0, 0, -2, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> BlockKeys.encode(0, 0, 0, BlockKeys.NO_TINT, 16, false));
    }

    @Test
    void theFieldWidthsAreWhatTheJavadocClaims() {
        assertEquals(16383, BlockKeys.MAX_SPRITE, "14 bits");
        assertEquals(15, BlockKeys.MAX_LIGHT);
        assertEquals(15, BlockKeys.MAX_EMISSION);
        assertEquals(14, BlockKeys.MAX_TINT, "4 bits with 0 reserved for untinted");
        assertEquals(-1, BlockKeys.NO_TINT);
    }

    @Test
    void canMergeIsEqualityTodayAndHasOneHome() {
        int a = BlockKeys.encode(3, 4, 5, BlockKeys.NO_TINT, 0, false);
        int b = BlockKeys.encode(3, 4, 5, BlockKeys.NO_TINT, 0, false);
        assertTrue(BlockKeys.canMerge(a, b));
        assertFalse(BlockKeys.canMerge(a, BlockKeys.encode(3, 4, 6, BlockKeys.NO_TINT, 0, false)));
        // Air compares equal to air, which is fine: the mesher never asks, because a face whose key
        // is NO_GEOMETRY is skipped before the merge test. Naming the rule matters for the day it
        // stops being plain equality, not for today.
        assertTrue(BlockKeys.canMerge(BlockKeys.NO_GEOMETRY, BlockKeys.NO_GEOMETRY));
    }
}
