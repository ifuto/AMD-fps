package net.amdfaster.net;

import net.amdfaster.worldgen.ChunkSectionWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionReaderTest {

    /** Packs a section the way the writer does, so the reader is tested against real packing. */
    private static long[] packedFrom(int[] blockIds, int[] palette) {
        ChunkSectionWriter writer = new ChunkSectionWriter();
        for (int i = 0; i < ChunkSectionWriter.BLOCKS; i++) {
            int x = i & 0xF;
            int z = (i >> 4) & 0xF;
            int y = i >> 8;
            writer.set(x, y, z, blockIds[i]);
        }
        int bits = ChunkSectionWriter.bitsFor(palette.length);
        long[] data = new long[ChunkSectionWriter.longsFor(bits)];
        for (int i = 0; i < ChunkSectionWriter.BLOCKS; i++) {
            int paletteIndex = -1;
            for (int p = 0; p < palette.length; p++) {
                if (palette[p] == blockIds[i]) {
                    paletteIndex = p;
                    break;
                }
            }
            assertTrue(paletteIndex >= 0, "block id is in the palette");
            ChunkSectionWriter.packInto(data, bits, i, paletteIndex);
        }
        return data;
    }

    @Test
    void aSectionRoundTripsThroughThePacking() {
        int[] palette = {7, 3, 11};
        int[] blockIds = new int[ChunkSectionReader.BLOCKS];
        for (int i = 0; i < blockIds.length; i++) {
            blockIds[i] = palette[i % 3];
        }
        long[] data = packedFrom(blockIds, palette);

        ChunkSectionReader reader = new ChunkSectionReader();
        int n = reader.readSection(data, ChunkSectionWriter.bitsFor(palette.length), palette.length);

        assertEquals(ChunkSectionReader.BLOCKS, n);
        for (int i = 0; i < blockIds.length; i++) {
            int paletteIndex = reader.buffer()[i];
            assertEquals(blockIds[i], palette[paletteIndex], "block " + i);
        }
        assertEquals(1L, reader.sectionsRead());
        assertEquals(4096L, reader.blocksRead());
        assertEquals(0L, reader.rejected());
    }

    @Test
    void aWidthThatDoesNotMatchThePaletteIsRefused() {
        // The silent corruption this guards. Reading at the wrong width does not throw on its own: it
        // reads the wrong bits of the wrong longs and produces ids that are valid numbers for the
        // wrong blocks, so a chunk renders as stone where there was water and nothing is logged.
        int[] palette = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
        long[] data = new long[ChunkSectionWriter.longsFor(5)];

        ChunkSectionReader reader = new ChunkSectionReader();

        assertThrows(IllegalArgumentException.class,
                () -> reader.readSection(data, 4, palette.length),
                "seventeen entries need five bits, not four");
        assertThrows(IllegalArgumentException.class,
                () -> reader.readSection(data, 5, 16),
                "and sixteen entries need four, not five");
        assertEquals(2L, reader.rejected());
    }

    @Test
    void thereIsNoThreeBitOrSevenBitPacking() {
        // Five distinct blocks use four bits, not three, because the packing has no 3-bit layer.
        assertTrue(ChunkSectionReader.isLegalBits(1));
        assertTrue(ChunkSectionReader.isLegalBits(2));
        assertTrue(ChunkSectionReader.isLegalBits(4));
        assertTrue(ChunkSectionReader.isLegalBits(5));
        assertTrue(ChunkSectionReader.isLegalBits(6));
        assertTrue(ChunkSectionReader.isLegalBits(8));
        assertFalse(ChunkSectionReader.isLegalBits(3));
        assertFalse(ChunkSectionReader.isLegalBits(7));
        assertFalse(ChunkSectionReader.isLegalBits(0));

        ChunkSectionReader reader = new ChunkSectionReader();
        assertThrows(IllegalArgumentException.class,
                () -> reader.readSection(new long[64], 3, 8));
        assertEquals(1L, reader.rejected());
    }

    @Test
    void aWrongLengthDataArrayIsRefused() {
        ChunkSectionReader reader = new ChunkSectionReader();

        assertThrows(IllegalArgumentException.class,
                () -> reader.readSection(new long[255], 4, 16),
                "a 4-bit section is 256 longs");
        assertEquals(1L, reader.rejected());
    }

    @Test
    void anIndexPastThePaletteIsRefused() {
        // A block referring to palette entry 5 of a 3-entry palette would read garbage out of the
        // caller's palette array.
        long[] data = new long[ChunkSectionWriter.longsFor(2)];
        // Palette indices 0..3 fit in two bits, but a 2-entry palette only defines 0 and 1.
        ChunkSectionWriter.packInto(data, 2, 0, 3);

        ChunkSectionReader reader = new ChunkSectionReader();

        assertThrows(IllegalArgumentException.class,
                () -> reader.readSection(data, 2, 2));
        assertEquals(1L, reader.rejected());
    }

    @Test
    void applyingAPaletteTurnsIndicesIntoBlockIds() {
        int[] palette = {100, 200, 300};
        int[] blockIds = new int[ChunkSectionReader.BLOCKS];
        for (int i = 0; i < blockIds.length; i++) {
            blockIds[i] = palette[i % 3];
        }
        long[] data = packedFrom(blockIds, palette);

        ChunkSectionReader reader = new ChunkSectionReader();
        reader.readSection(data, ChunkSectionWriter.bitsFor(palette.length), palette.length);
        reader.applyPalette(palette);

        for (int i = 0; i < blockIds.length; i++) {
            assertEquals(blockIds[i], reader.buffer()[i], "block " + i);
        }
    }

    @Test
    void aUniformSectionIsRecognised() {
        // Most of the world is air or stone, and a caller that can skip a uniform section saves the
        // whole per-block pass.
        int[] palette = {0};
        int[] blockIds = new int[ChunkSectionReader.BLOCKS];
        long[] data = packedFrom(blockIds, palette);

        ChunkSectionReader reader = new ChunkSectionReader();
        reader.readSection(data, ChunkSectionWriter.bitsFor(palette.length), palette.length);
        assertTrue(reader.isUniform());

        blockIds[100] = 0;
        int[] twoPalette = {0, 5};
        for (int i = 0; i < blockIds.length; i++) {
            blockIds[i] = i < 100 ? 0 : 5;
        }
        long[] mixed = packedFrom(blockIds, twoPalette);
        reader.readSection(mixed, ChunkSectionWriter.bitsFor(twoPalette.length), twoPalette.length);
        assertFalse(reader.isUniform());
    }

    @Test
    void theBufferIsReusedRatherThanAllocatedPerSection() {
        // The point of the class: a load is 10,584 sections, and allocating 16 KB for each is 170 MB of
        // garbage for data that is immediately copied elsewhere.
        ChunkSectionReader reader = new ChunkSectionReader();
        int[] palette = {1, 2};
        int[] blockIds = new int[ChunkSectionReader.BLOCKS];
        for (int i = 0; i < blockIds.length; i++) {
            blockIds[i] = palette[i % 2];
        }
        long[] data = packedFrom(blockIds, palette);

        int[] first = reader.buffer();
        for (int i = 0; i < 100; i++) {
            reader.readSection(data, 1, 2);
        }
        assertTrue(first == reader.buffer(), "the same array every time");
        assertEquals(100L, reader.sectionsRead());
    }

    @Test
    void theWireSizeIsWhatThePackingClaims() {
        assertEquals(512, ChunkSectionReader.wireBytes(1));
        assertEquals(1024, ChunkSectionReader.wireBytes(2));
        assertEquals(2048, ChunkSectionReader.wireBytes(4));
        assertEquals(2736, ChunkSectionReader.wireBytes(5), "twelve entries a long wastes four bits");
        assertEquals(3280, ChunkSectionReader.wireBytes(6));
        assertEquals(4096, ChunkSectionReader.wireBytes(8));
        assertThrows(IllegalArgumentException.class, () -> ChunkSectionReader.wireBytes(3));
    }

    @Test
    void anInitialLoadTransfersWhatTheWidthDecides() {
        // Render distance ten is 441 chunks of 24 sections. The packing width is the difference between
        // 20 MB and 41 MB, and a short array would be 82.
        assertEquals(21_676_032L, ChunkSectionReader.loadTransferBytes(441, 24, 4));
        assertEquals(43_352_064L, ChunkSectionReader.loadTransferBytes(441, 24, 8));
        assertEquals(86_704_128L, 441L * 24 * 8192, "what a short array per section would cost");
    }

    @Test
    void aFreshReaderReportsZeroNotNaN() {
        ChunkSectionReader reader = new ChunkSectionReader();
        assertEquals(0L, reader.sectionsRead());
        assertEquals(0L, reader.blocksRead());
        assertEquals(0L, reader.rejected());
        assertEquals(0L, reader.paletteEntries());
        assertEquals(4096, ChunkSectionReader.BLOCKS);
        assertEquals(8, ChunkSectionReader.MAX_BITS);
    }
}
