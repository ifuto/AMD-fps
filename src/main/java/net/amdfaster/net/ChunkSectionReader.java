package net.amdfaster.net;

import net.amdfaster.worldgen.ChunkSectionWriter;

/**
 * Reads a chunk section off the wire without allocating anything per block.
 *
 * <p>An initial load at render distance ten is 441 chunks of 24 sections, and each section carries up
 * to 4,096 block ids packed into longs plus a palette. The obvious parse allocates an int array per
 * section, reads every block through a palette lookup, and throws the array away -- 10,584 arrays of
 * 16 KB for one load, which is 170 MB of garbage for data that is immediately copied somewhere else.
 *
 * <p>This reads into a buffer the caller owns and reuses, so a load allocates once. That is not a
 * throughput win on its own; it is a garbage collection win, and in a game that is already stuttering
 * from chunk loads the difference is felt as fewer hitches rather than as a higher frame rate.
 *
 * <h2>The bit width has to match the palette</h2>
 *
 * <p>A section's data is packed at a width chosen from how many palette entries it has: 4 bits for up
 * to 16, 5 for up to 32, and so on. Reading at the wrong width does not throw. It reads the wrong bits
 * of the wrong longs and produces block ids that are perfectly valid numbers for the wrong blocks --
 * a chunk that renders as stone where there was water, with nothing in the log. So the width in the
 * packet is checked against what the palette size implies, and a mismatch is refused rather than read.
 *
 * <p>It also means the legal widths are 1, 2, 4, 5, 6 and 8. There is no 3-bit or 7-bit layer, so a
 * packet claiming one is corrupt and is rejected for the same reason.
 *
 * <h2>What is not compressed here</h2>
 *
 * <p>Packet payloads arrive zlib compressed and decompressing them is the larger cost, but doing it
 * faster needs a native inflater and this project cannot pass the JVM flags a Java one would want.
 * What can be done without that is not re-reading and not re-allocating, which is this.
 *
 * <p>Not thread safe. The buffer is reused, so a reader is owned by one thread.
 */
public final class ChunkSectionReader {

    /** Blocks in a section. */
    public static final int BLOCKS = ChunkSectionWriter.BLOCKS;

    /** The widest packing, eight bits, which is what a full 256-entry palette needs. */
    public static final int MAX_BITS = 8;

    private final int[] buffer;

    private long sectionsRead;
    private long blocksRead;
    private long paletteEntries;
    private long rejected;

    public ChunkSectionReader() {
        this.buffer = new int[BLOCKS];
    }

    /** The block ids of the last section read. */
    public int[] buffer() {
        return this.buffer;
    }

    /**
     * Reads one section's block data.
     *
     * @param data the packed longs, exactly {@code ChunkSectionWriter.longsFor(bits)} long
     * @param bits the packing width the sender used
     * @param paletteSize how many entries the sender's palette had
     * @return how many block ids were written into {@link #buffer()}
     * @throws IllegalArgumentException if the width is not legal, does not match the palette, or the
     *     data array is the wrong length
     */
    public int readSection(long[] data, int bits, int paletteSize) {
        if (!isLegalBits(bits)) {
            this.rejected++;
            throw new IllegalArgumentException(
                    "there is no " + bits + "-bit packing; legal widths are 1, 2, 4, 5, 6 and 8");
        }
        int implied = ChunkSectionWriter.bitsFor(paletteSize);
        if (implied != bits) {
            this.rejected++;
            throw new IllegalArgumentException(
                    "a palette of " + paletteSize + " entries packs at " + implied
                            + " bits, not " + bits + "; reading it anyway would produce valid ids"
                            + " for the wrong blocks");
        }
        int expected = ChunkSectionWriter.longsFor(bits);
        if (data.length != expected) {
            this.rejected++;
            throw new IllegalArgumentException(
                    "a " + bits + "-bit section is " + expected + " longs: " + data.length);
        }

        for (int i = 0; i < BLOCKS; i++) {
            int paletteIndex = ChunkSectionWriter.unpack(data, bits, i);
            if (paletteIndex >= paletteSize) {
                this.rejected++;
                throw new IllegalArgumentException(
                        "block " + i + " refers to palette entry " + paletteIndex
                                + " of " + paletteSize);
            }
            this.buffer[i] = paletteIndex;
        }
        this.sectionsRead++;
        this.blocksRead += BLOCKS;
        return BLOCKS;
    }

    /**
     * Applies a palette, turning palette indices into block state ids in place.
     *
     * <p>Separate from {@link #readSection} because a caller that only wants to know whether a section
     * is uniform, or that keeps palette indices, does not need this pass at all.
     *
     * @param palette block state id per palette entry
     */
    public void applyPalette(int[] palette) {
        for (int i = 0; i < BLOCKS; i++) {
            this.buffer[i] = palette[this.buffer[i]];
        }
    }

    /**
     * Whether a section is entirely one block.
     *
     * <p>Worth knowing before doing anything per block: a section of air or of stone is most of the
     * world, and a caller that can skip it saves the whole pass.
     */
    public boolean isUniform() {
        int first = this.buffer[0];
        for (int i = 1; i < BLOCKS; i++) {
            if (this.buffer[i] != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a width is one the packing actually has.
     *
     * <p>There is no 3-bit or 7-bit layer, so five distinct blocks use four bits and not three.
     */
    public static boolean isLegalBits(int bits) {
        switch (bits) {
            case 1:
            case 2:
            case 4:
            case 5:
            case 6:
            case 8:
                return true;
            default:
                return false;
        }
    }

    /**
     * Bytes a section occupies on the wire at a given width.
     *
     * <p>For working out what a load transfers, which is what the network category is about.
     */
    public static int wireBytes(int bits) {
        if (!isLegalBits(bits)) {
            throw new IllegalArgumentException("not a legal width: " + bits);
        }
        return ChunkSectionWriter.longsFor(bits) * 8;
    }

    public long sectionsRead() {
        return this.sectionsRead;
    }

    public long blocksRead() {
        return this.blocksRead;
    }

    /** Palette entries seen, for working out the palette's share of the transfer. */
    public long paletteEntries() {
        return this.paletteEntries;
    }

    /** Records that a palette of this many entries was read. */
    public void notePalette(int entries) {
        this.paletteEntries += entries;
    }

    /** Sections refused because the packet was inconsistent. */
    public long rejected() {
        return this.rejected;
    }

    /**
     * Bytes an initial load transfers, at a typical mix of widths.
     *
     * @param chunks how many chunks
     * @param sectionsPerChunk how many sections a chunk has
     * @param averageBits the average packing width across sections
     * @return bytes, ignoring the palette and the compression around it
     */
    public static long loadTransferBytes(int chunks, int sectionsPerChunk, int averageBits) {
        return (long) chunks * sectionsPerChunk * wireBytes(averageBits);
    }
}
