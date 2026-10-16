package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLengthCodecTest {

    private static final int CELLS = RunLengthCodec.SECTION_CELLS;

    private static byte[] solid(byte value) {
        byte[] out = new byte[CELLS];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static byte[] layered() {
        byte[] out = new byte[CELLS];
        for (int i = 0; i < CELLS; i++) {
            out[i] = (byte) (i < 1024 ? 0 : i < 2048 ? 1 : i < 3072 ? 3 : 7);
        }
        return out;
    }

    @Test
    void aSolidSectionBecomesOneEntry() {
        // A section of solid stone becomes one entry, and the difference is what gets read from disk and sent
        // over the network.
        RunLengthCodec codec = new RunLengthCodec();

        int[] encoded = codec.encode(solid((byte) 1));

        assertEquals(2, encoded.length, "one value-length pair");
        assertEquals(1, encoded[0]);
        assertEquals(CELLS, encoded[1]);
    }

    @Test
    void theRoundTripIsExact() {
        // A codec that lost a value or shifted a run by one would move a block somewhere else in the section,
        // and since this is what gets written to disk that is corruption of the player's world, silent until
        // the player walks to the place where the block moved.
        RunLengthCodec codec = new RunLengthCodec();

        assertArrayEquals(solid((byte) 5), codec.decode(codec.encode(solid((byte) 5))));
        assertArrayEquals(layered(), codec.decode(codec.encode(layered())));
        assertArrayEquals(solid((byte) 0), codec.decode(codec.encode(solid((byte) 0))));
    }

    @Test
    void aLayeredSectionBecomesAFewEntries() {
        RunLengthCodec codec = new RunLengthCodec();

        int[] encoded = codec.encode(layered());

        assertEquals(8, encoded.length, "four runs of a thousand and twenty-four");
        assertEquals(4096L / 4, codec.compressionRatio(), 1e-9);
    }

    @Test
    void dataWithNoRunsIsStoredRaw() {
        // Alternating values encode to two entries per value, which is larger than the original. Writing that
        // anyway would make the file bigger and slower to read, so the header says which of the two the data is.
        RunLengthCodec codec = new RunLengthCodec();
        byte[] alternating = new byte[CELLS];
        for (int i = 0; i < CELLS; i++) {
            alternating[i] = (byte) (i % 2);
        }

        int[] encoded = codec.encode(alternating);

        assertEquals(-1, encoded[0], "the raw marker");
        assertEquals(CELLS + 1, encoded.length);
        assertEquals(1L, codec.rawSections());
        assertArrayEquals(alternating, codec.decode(encoded));
        assertEquals(0.0, codec.savedPercent(), 1e-9, "nothing saved, and not reported as a loss");
    }

    @Test
    void theOccupancyMaskComesFromTheRuns() {
        // A run of zero is a run of unoccupied cells, which is what the mesher needs before it sweeps.
        RunLengthCodec codec = new RunLengthCodec();

        boolean[] occupied = codec.occupancy(codec.encode(layered()));

        assertFalse(occupied[0], "the first quarter is air");
        assertTrue(occupied[1024], "and the rest is not");
        assertEquals(CELLS, occupied.length);
    }

    @Test
    void aSolidSectionCompressesAlmostEntirely() {
        RunLengthCodec codec = new RunLengthCodec();
        codec.encode(solid((byte) 1));

        assertEquals(3L, codec.bytesEncoded());
        assertEquals(4096L, codec.bytesUncompressed());
        assertEquals(99.93, codec.savedPercent(), 0.01);
        assertEquals(4096.0, codec.compressionRatio(), 1e-9);
    }

    @Test
    void corruptDataIsRejected() {
        RunLengthCodec codec = new RunLengthCodec();

        assertThrows(IllegalArgumentException.class, () -> codec.decode(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new int[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new int[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new int[] {1, CELLS + 1}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new int[] {1, CELLS - 1}));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[10]));
    }

    @Test
    void aFreshCodecReportsZeroNotNaN() {
        // A codec that has encoded nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        RunLengthCodec codec = new RunLengthCodec();

        assertEquals(0.0, codec.savedPercent());
        assertEquals(0.0, codec.compressionRatio());
        assertEquals(0L, codec.sections());
        assertEquals(0L, codec.entries());
        assertEquals(4096, RunLengthCodec.MAX_RUN);
        assertEquals(3, RunLengthCodec.BYTES_PER_ENTRY);
    }
}
