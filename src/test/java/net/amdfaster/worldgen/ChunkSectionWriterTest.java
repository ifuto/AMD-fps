package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionWriterTest {

    /** Fills a section with a stone base, dirt near the top and air above. */
    private static ChunkSectionWriter filledSection() {
        ChunkSectionWriter writer = new ChunkSectionWriter();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = y < 12 ? 1 : (y < 14 ? 2 : 0);
                    writer.set(x, y, z, id);
                }
            }
        }
        return writer;
    }

    @Test
    void everyLegalBitWidthRoundTrips() {
        // The packing is the part that fails silently. A densely packed array is smaller and the
        // reader decodes it into a scrambled chunk rather than throwing.
        int[] paletteSizes = {1, 2, 4, 16, 32, 64, 256};
        for (int paletteSize : paletteSizes) {
            int bits = ChunkSectionWriter.bitsFor(paletteSize);
            int[] expected = new int[ChunkSectionWriter.BLOCKS];
            long[] packed = new long[ChunkSectionWriter.longsFor(bits)];
            int entries = ChunkSectionWriter.entriesPerLong(bits);

            for (int i = 0; i < expected.length; i++) {
                expected[i] = i % paletteSize;
                packed[i / entries] |= (long) expected[i] << ((i % entries) * bits);
            }
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], ChunkSectionWriter.unpack(packed, bits, i),
                        "palette " + paletteSize + " at index " + i);
            }
        }
    }

    @Test
    void fiveBitsWastesFourBitsPerLongOnPurpose() {
        // Twelve entries at five bits use sixty bits. The remaining four are wasted, and the next
        // entry starts in the next long. That is what the reader expects.
        assertEquals(12, ChunkSectionWriter.entriesPerLong(5));
        assertEquals(342, ChunkSectionWriter.longsFor(5), "4096 / 12 rounded up");
        assertTrue(342 * 12 >= ChunkSectionWriter.BLOCKS);
        assertTrue(341 * 12 < ChunkSectionWriter.BLOCKS, "and 341 would not be enough");
    }

    @Test
    void bitWidthsJumpPastThreeAndSeven() {
        // There is no three-bit layer, so five distinct blocks use four bits. A section packed at
        // three bits would not decode.
        assertEquals(1, ChunkSectionWriter.bitsFor(1));
        assertEquals(1, ChunkSectionWriter.bitsFor(2));
        assertEquals(2, ChunkSectionWriter.bitsFor(3));
        assertEquals(2, ChunkSectionWriter.bitsFor(4));
        assertEquals(4, ChunkSectionWriter.bitsFor(5), "no three-bit layer");
        assertEquals(4, ChunkSectionWriter.bitsFor(16));
        assertEquals(5, ChunkSectionWriter.bitsFor(17));
        assertEquals(5, ChunkSectionWriter.bitsFor(32));
        assertEquals(6, ChunkSectionWriter.bitsFor(33));
        assertEquals(6, ChunkSectionWriter.bitsFor(64));
        assertEquals(8, ChunkSectionWriter.bitsFor(65), "no seven-bit layer");
        assertEquals(8, ChunkSectionWriter.bitsFor(256));
        assertEquals(0, ChunkSectionWriter.bitsFor(0), "an empty palette packs to nothing");
        assertEquals(0, ChunkSectionWriter.longsFor(0));
    }

    @Test
    void aSectionPacksSmallerThanAShortPerBlock() {
        // The whole argument for packing. A short per block is 8,192 bytes.
        ChunkSectionWriter writer = filledSection();

        assertEquals(3, writer.paletteSize(), "air, stone and dirt");
        assertEquals(2, ChunkSectionWriter.bitsFor(writer.paletteSize()));
        assertEquals(1024, writer.packedByteSize());
        assertEquals(8192, ChunkSectionWriter.BLOCKS * 2, "what a short array would cost");
    }

    @Test
    void packRejectsASectionWithHolesInIt() {
        // A hole would be packed as whatever the array happened to hold, and the chunk would look
        // wrong in game with no error anywhere. Refusing is cheaper than that.
        ChunkSectionWriter writer = new ChunkSectionWriter();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 15; x++) {
                    writer.set(x, y, z, 1);
                }
            }
        }
        assertFalse(writer.isComplete());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> writer.pack(0));
        assertTrue(failure.getMessage().contains("15,0,0"), "and it names the position: "
                + failure.getMessage());
    }

    @Test
    void aCompleteSectionPacksAndReadsBack() {
        ChunkSectionWriter writer = filledSection();
        assertTrue(writer.isComplete());

        long[] packed = writer.pack(0);
        int bits = ChunkSectionWriter.bitsFor(writer.paletteSize());

        assertEquals(ChunkSectionWriter.longsFor(bits), packed.length);
        // The palette is in first-seen order, and the fill starts at stone, so it is [1, 2, 0] -- the
        // stored indices are not the block ids. An earlier version of this test indexed the palette
        // with a block id and compared the result to another one.
        assertArrayEquals(new int[] {1, 2, 0}, writer.palette());
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wantId = y < 12 ? 1 : (y < 14 ? 2 : 0);
                    int index = ChunkSectionWriter.index(x, y, z);
                    int storedIndex = ChunkSectionWriter.unpack(packed, bits, index);
                    assertEquals(wantId, writer.palette()[storedIndex],
                            "block at " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(writer.isFinished());
    }

    @Test
    void thePaletteKeepsFirstSeenOrder() {
        // A stable palette means the same generated chunk packs identically every time, which matters
        // when a chunk is regenerated and compared.
        ChunkSectionWriter writer = new ChunkSectionWriter();
        writer.set(0, 0, 0, 42);
        writer.set(1, 0, 0, 7);
        writer.set(2, 0, 0, 42);

        assertArrayEquals(new int[] {42, 7}, writer.palette());
        assertEquals(2, writer.paletteSize());
    }

    @Test
    void theHeightmapTracksTheHighestNonAirBlockPerColumn() {
        ChunkSectionWriter writer = new ChunkSectionWriter();
        for (int y = 0; y < 10; y++) {
            writer.set(3, y, 5, 1);
        }
        writer.set(3, 12, 5, 1);
        writer.set(3, 11, 5, 1);

        assertEquals(12, writer.heightAt(3, 5, 0), "order does not matter");
        assertEquals(-1, writer.heightAt(4, 5, 0), "an untouched column is all air");
    }

    @Test
    void airPlacedAboveDoesNotLowerTheHeightmap() {
        ChunkSectionWriter writer = new ChunkSectionWriter();
        writer.set(0, 8, 0, 1);
        writer.set(0, 15, 0, 0);

        assertEquals(8, writer.heightAt(0, 0, 0));
    }

    @Test
    void theHeightmapIsBuiltAgainstIdZeroAndSaysSo() {
        ChunkSectionWriter writer = filledSection();
        assertEquals(13, writer.heightAt(0, 0, 0), "dirt up to y 13");

        assertThrows(UnsupportedOperationException.class, () -> writer.heightAt(0, 0, 1),
                "rather than quietly answering for the wrong air");
    }

    @Test
    void negativeIdsAndBadCoordinatesAreRejected() {
        ChunkSectionWriter writer = new ChunkSectionWriter();

        assertThrows(IllegalArgumentException.class, () -> writer.set(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> writer.set(-1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> writer.set(16, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> writer.set(0, 16, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> writer.set(0, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> writer.heightAt(16, 0, 0));
    }

    @Test
    void aSectionIsCountedAsTheWorkVanillaWouldHaveDone() {
        // Ten pieces of work per block through the live pipeline, against the three this does.
        ChunkSectionWriter writer = filledSection();

        assertEquals(ChunkSectionWriter.BLOCKS, writer.writes());
        assertEquals(983_040L, ChunkSectionWriter.BLOCKS * 24 * 10L,
                "what a chunk costs through setBlock");
        assertEquals(433_520_640L, ChunkSectionWriter.BLOCKS * 24 * 10L * 441L,
                "and an initial load at render distance ten");
        // The heightmap is one comparison per write, not a scan of the column.
        assertTrue(writer.heightmapUpdates() < writer.writes(),
                "columns only update when they get higher");
    }

    @Test
    void theIndexLayoutIsYzx() {
        assertEquals(0, ChunkSectionWriter.index(0, 0, 0));
        assertEquals(1, ChunkSectionWriter.index(1, 0, 0), "x is the low nibble");
        assertEquals(16, ChunkSectionWriter.index(0, 0, 1), "z is next");
        assertEquals(256, ChunkSectionWriter.index(0, 1, 0), "and y the top byte");
        assertEquals(4095, ChunkSectionWriter.index(15, 15, 15));
    }
}
