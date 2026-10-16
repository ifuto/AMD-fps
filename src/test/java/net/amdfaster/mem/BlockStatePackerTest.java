package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStatePackerTest {

    /** A stair: facing 6, half 2, shape 5, waterlogged 2. */
    private static BlockStatePacker stairs() {
        BlockStatePacker packer = new BlockStatePacker();
        packer.addProperty("facing", 6);
        packer.addProperty("half", 2);
        packer.addProperty("shape", 5);
        packer.addProperty("waterlogged", 2);
        packer.fix();
        return packer;
    }

    @Test
    void everyStateOfAStairRoundTrips() {
        // All 120 of them, not a sample. A field one bit too narrow wraps the last values onto the
        // first, so a stair facing west renders facing north, and that shows up on two of six facings
        // rather than on all of them.
        BlockStatePacker packer = stairs();
        int checked = 0;

        for (int facing = 0; facing < 6; facing++) {
            for (int half = 0; half < 2; half++) {
                for (int shape = 0; shape < 5; shape++) {
                    for (int waterlogged = 0; waterlogged < 2; waterlogged++) {
                        int[] values = {facing, half, shape, waterlogged};
                        int packed = packer.encode(values);
                        assertArrayEquals(values, packer.decodeAll(packed),
                                "state " + facing + "," + half + "," + shape + "," + waterlogged);
                        checked++;
                    }
                }
            }
        }
        assertEquals(120, checked);
        assertEquals(120, packer.stateCount());
    }

    @Test
    void theBitWidthsAreExactlyWhatTheValueCountsNeed() {
        assertEquals(0, BlockStatePacker.bitsFor(1), "one value carries no information");
        assertEquals(1, BlockStatePacker.bitsFor(2));
        assertEquals(2, BlockStatePacker.bitsFor(3));
        assertEquals(2, BlockStatePacker.bitsFor(4));
        assertEquals(3, BlockStatePacker.bitsFor(5));
        assertEquals(3, BlockStatePacker.bitsFor(6));
        assertEquals(3, BlockStatePacker.bitsFor(8));
        assertEquals(4, BlockStatePacker.bitsFor(9));
        assertEquals(4, BlockStatePacker.bitsFor(16));
        assertEquals(5, BlockStatePacker.bitsFor(17));
        assertEquals(5, BlockStatePacker.bitsFor(32));
        assertEquals(6, BlockStatePacker.bitsFor(33));
        assertEquals(7, BlockStatePacker.bitsFor(128));
    }

    @Test
    void everyValueCountGetsEnoughBitsAndNoMore() {
        // Checked by the definition rather than against a table: the width has to hold every value, and
        // one bit less must not.
        for (int valueCount = 1; valueCount <= 1000; valueCount++) {
            int width = BlockStatePacker.bitsFor(valueCount);
            assertTrue(width == 0 || (1 << width) >= valueCount,
                    valueCount + " values need more than " + width + " bits");
            assertTrue(width == 0 || (1 << (width - 1)) < valueCount,
                    valueCount + " values fit in " + (width - 1) + " bits, so " + width
                            + " is wasteful");
        }
    }

    @Test
    void aStairFitsInAByte() {
        BlockStatePacker packer = stairs();

        assertEquals(8, packer.totalBits(), "3 + 1 + 3 + 1");
        assertEquals(4, packer.propertyCount());
        assertEquals(3, packer.widthOf(0), "facing");
        assertEquals(1, packer.widthOf(1), "half");
        assertEquals(3, packer.widthOf(2), "shape");
        assertEquals(1, packer.widthOf(3), "waterlogged");
        assertTrue(packer.fitsAllStates());
    }

    @Test
    void aPropertyWithOneValueTakesNoBits() {
        // Giving it a bit would waste one that a real property needs, and on a block with enough
        // properties that is the difference between fitting in an int and not.
        BlockStatePacker packer = new BlockStatePacker();
        packer.addProperty("lit", 2);
        packer.addProperty("dummy", 1);
        packer.addProperty("age", 8);
        packer.fix();

        assertEquals(4, packer.totalBits(), "1 + 0 + 3");
        assertEquals(0, packer.widthOf(1));

        int packed = packer.encode(new int[] {1, 0, 5});
        assertEquals(1, packer.decode(packed, 0));
        assertEquals(0, packer.decode(packed, 1), "the only value it can have");
        assertEquals(5, packer.decode(packed, 2));
    }

    @Test
    void anOutOfRangeValueIsRejectedRatherThanMasked() {
        // Masking would produce a valid-looking integer holding an impossible state, which is the same
        // bug as a field too narrow and just as invisible.
        BlockStatePacker packer = stairs();

        assertThrows(IllegalArgumentException.class,
                () -> packer.encode(new int[] {6, 0, 0, 0}), "facing has six values, 0 to 5");
        assertThrows(IllegalArgumentException.class,
                () -> packer.encode(new int[] {0, 0, 0, -1}));
    }

    @Test
    void propertiesThatDoNotFitAreRefused() {
        BlockStatePacker packer = new BlockStatePacker();
        // Five properties of six bits is 30, which fits. The sixth would need 36.
        for (int i = 0; i < 5; i++) {
            packer.addProperty("p" + i, 64);
        }
        assertEquals(30, packer.totalBits());

        assertThrows(IllegalArgumentException.class, () -> packer.addProperty("p5", 64),
                "30 + 6 is past the 32 available");
        assertEquals(30, packer.totalBits(), "and nothing was added");

        // A narrower property still fits in the room that is left.
        packer.addProperty("small", 4);
        assertEquals(32, packer.totalBits());
    }

    @Test
    void aBlockWithNoPropertiesHasOneState() {
        BlockStatePacker packer = new BlockStatePacker();
        packer.fix();

        assertEquals(1, packer.stateCount());
        assertEquals(0, packer.totalBits());
        assertEquals(0, packer.encode(new int[0]));
        assertTrue(packer.fitsAllStates());
    }

    @Test
    void encodingBeforeFixingIsRefused() {
        BlockStatePacker packer = new BlockStatePacker();
        packer.addProperty("facing", 6);

        assertThrows(IllegalStateException.class, () -> packer.encode(new int[] {0}));
        assertThrows(IllegalStateException.class, () -> packer.decode(0, 0));

        packer.fix();
        assertThrows(IllegalStateException.class, () -> packer.addProperty("half", 2),
                "and no more properties afterwards");
    }

    @Test
    void theSavingAgainstAMapEntryIsReported() {
        BlockStatePacker packer = stairs();

        // Four map entries at a conservative 32 bytes each, against a 4 byte int.
        assertEquals(4L * 32 - 4, packer.bytesSavedPerState(32));
        assertEquals(124L, packer.bytesSavedPerState(32));
    }

    @Test
    void namesAreKeptForDiagnostics() {
        BlockStatePacker packer = stairs();

        assertEquals("facing", packer.nameOf(0));
        assertEquals("waterlogged", packer.nameOf(3));
        assertThrows(IndexOutOfBoundsException.class, () -> packer.nameOf(4));
        assertThrows(IndexOutOfBoundsException.class, () -> packer.widthOf(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> packer.decode(0, 4));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BlockStatePacker packer = new BlockStatePacker();

        assertThrows(IllegalArgumentException.class, () -> packer.addProperty("", 2));
        assertThrows(IllegalArgumentException.class, () -> packer.addProperty(null, 2));
        assertThrows(IllegalArgumentException.class, () -> packer.addProperty("x", 0));
        assertThrows(IllegalArgumentException.class, () -> BlockStatePacker.bitsFor(0));

        packer.addProperty("facing", 6);
        packer.fix();
        assertThrows(IllegalArgumentException.class, () -> packer.encode(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> packer.encode(new int[] {0, 0}));
    }

    @Test
    void aPackerWithNoPropertiesReportsZeroNotNaN() {
        BlockStatePacker packer = new BlockStatePacker();
        assertEquals(0, packer.propertyCount());
        assertEquals(0, packer.totalBits());
        assertFalse(packer.propertyCount() > 0);
        assertEquals(32, BlockStatePacker.BITS);
    }
}
