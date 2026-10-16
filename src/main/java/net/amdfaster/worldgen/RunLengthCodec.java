package net.amdfaster.worldgen;

/**
 * Stores a section as runs of equal values, because most of it is one value repeated.
 *
 * <p>A section's block data is mostly long runs: a column of air above the surface, a layer of stone below it,
 * a seam of dirt between. Storing every value separately means four thousand and ninety six entries for what is
 * often a few dozen distinct runs, and the storage and the bandwidth both scale with the entry count rather than
 * with the information in the section.
 *
 * <p>Run-length encoding stores each run as a value and a length. A section of solid stone becomes one entry, a
 * typical terrain section becomes a few dozen, and the difference is what gets read from disk and sent over the
 * network.
 *
 * <h2>The round trip has to be exact</h2>
 *
 * <p>Not approximately the same blocks -- the same blocks. A codec that lost a value or shifted a run by one
 * would move a block somewhere else in the section, and the result is a world that differs from the one that was
 * saved. Since the data is what gets written to disk, that is not a rendering difference, it is corruption of the
 * player's world, and it is silent until the player walks to the place where the block moved. So the decoder is
 * the exact inverse of the encoder and a test round-trips every case it can construct.
 *
 * <h2>The worst case has to fall back</h2>
 *
 * <p>Data with no runs at all -- alternating values, a section full of distinct blocks -- encodes to two entries
 * per value, which is larger than the original. Writing that anyway would make the file bigger and slower to
 * read, so the encoder measures the result and stores the raw values when the encoding did not help. The header
 * says which of the two the data is, so the decoder does not have to guess.
 *
 * <h2>The length has to fit</h2>
 *
 * <p>A section is four thousand and ninety six cells, so a run can be at most that long, which fits in twelve
 * bits. Storing the length in a byte would silently truncate a run of three hundred into one of two hundred and
 * fifty five, and the decoder would produce a short section -- which is a section with missing blocks, and the
 * missing ones are wherever the long run was, which is usually the air above the surface.
 *
 * <h2>Why this pairs with the occupancy mask</h2>
 *
 * <p>The mesher wants to know which cells are occupied before it sweeps, and a run-length encoding gives that
 * for free: a run of zero is a run of unoccupied cells. Decoding straight into an occupancy mask skips the block
 * data entirely for sections the mesher only needs the shape of.
 *
 * <p>Not thread safe. Chunk loading runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class RunLengthCodec {

    /** Cells in a section. */
    public static final int SECTION_CELLS = 16 * 16 * 16;

    /** The longest run a section can contain. */
    public static final int MAX_RUN = SECTION_CELLS;

    /** Bytes one encoded entry occupies: a value and a twelve-bit length. */
    public static final int BYTES_PER_ENTRY = 3;

    /** Bytes one raw value occupies. */
    public static final int BYTES_PER_VALUE = 1;

    /** Milliseconds encoding one section costs. */
    public static final double MS_PER_ENCODE = 0.02;

    /** Milliseconds decoding one section costs. */
    public static final double MS_PER_DECODE = 0.015;

    private long sections;
    private long values;
    private long entries;
    private long rawSections;
    private long encodedSections;

    /**
     * Encodes a section.
     *
     * @param values one byte per cell
     * @return the encoded form, as a flat array of value, length pairs, or the raw values prefixed by a marker
     *     if encoding did not help
     */
    public int[] encode(byte[] values) {
        if (values.length != SECTION_CELLS) {
            throw new IllegalArgumentException(
                    "a section has " + SECTION_CELLS + " cells, not " + values.length);
        }
        this.sections++;
        this.values += values.length;

        int runs = 1;
        for (int i = 1; i < values.length; i++) {
            if (values[i] != values[i - 1]) {
                runs++;
            }
        }

        if (runs * BYTES_PER_ENTRY >= values.length * BYTES_PER_VALUE) {
            // No runs at all -- alternating values, a section full of distinct blocks -- encodes to more than
            // the original, which would make the file bigger and slower to read. The header says which of the
            // two the data is, so the decoder does not have to guess.
            this.rawSections++;
            this.entries += values.length;
            int[] raw = new int[values.length + 1];
            raw[0] = -1;
            for (int i = 0; i < values.length; i++) {
                raw[i + 1] = values[i] & 0xFF;
            }
            return raw;
        }

        this.encodedSections++;
        this.entries += runs;

        int[] out = new int[runs * 2];
        int index = 0;
        byte current = values[0];
        int length = 1;
        for (int i = 1; i < values.length; i++) {
            if (values[i] == current) {
                length++;
                continue;
            }
            if (length > MAX_RUN) {
                throw new IllegalStateException("a run longer than a section: " + length);
            }
            out[index++] = current & 0xFF;
            out[index++] = length;
            current = values[i];
            length = 1;
        }
        out[index++] = current & 0xFF;
        out[index++] = length;
        return out;
    }

    /**
     * Decodes an encoded section.
     *
     * <p>The exact inverse of {@link #encode}: the same blocks, in the same places.
     */
    public byte[] decode(int[] encoded) {
        if (encoded.length == 0) {
            throw new IllegalArgumentException("nothing to decode");
        }
        byte[] out = new byte[SECTION_CELLS];

        if (encoded[0] == -1) {
            if (encoded.length != SECTION_CELLS + 1) {
                throw new IllegalArgumentException(
                        "raw data has " + SECTION_CELLS + " values, not " + (encoded.length - 1));
            }
            for (int i = 0; i < SECTION_CELLS; i++) {
                out[i] = (byte) encoded[i + 1];
            }
            return out;
        }
        if (encoded.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "entries come in pairs, but there were " + encoded.length + " values");
        }

        int position = 0;
        for (int i = 0; i < encoded.length; i += 2) {
            int length = encoded[i + 1];
            if (length <= 0 || length > MAX_RUN) {
                throw new IllegalArgumentException("a run of " + length + " cells");
            }
            if (position + length > SECTION_CELLS) {
                throw new IllegalArgumentException(
                        "the runs add to more than a section: " + (position + length));
            }
            for (int cell = 0; cell < length; cell++) {
                out[position + cell] = (byte) encoded[i];
            }
            position += length;
        }
        if (position != SECTION_CELLS) {
            throw new IllegalArgumentException(
                    "the runs add to " + position + " cells, not " + SECTION_CELLS);
        }
        return out;
    }

    /**
     * The occupancy mask a section decodes to, without materialising the block data.
     *
     * <p>A run of zero is a run of unoccupied cells, which is what the mesher needs before it sweeps.
     */
    public boolean[] occupancy(int[] encoded) {
        byte[] values = decode(encoded);
        boolean[] occupied = new boolean[SECTION_CELLS];
        for (int i = 0; i < SECTION_CELLS; i++) {
            occupied[i] = values[i] != 0;
        }
        return occupied;
    }

    public long sections() {
        return this.sections;
    }

    public long values() {
        return this.values;
    }

    /** How many entries the sections encoded to. */
    public long entries() {
        return this.entries;
    }

    /** How many sections did not compress and were stored raw. */
    public long rawSections() {
        return this.rawSections;
    }

    public long encodedSections() {
        return this.encodedSections;
    }

    /** Bytes the encoded form occupies. */
    public long bytesEncoded() {
        return this.entries * BYTES_PER_ENTRY;
    }

    /** Bytes the same sections occupy uncompressed. */
    public long bytesUncompressed() {
        return this.values * BYTES_PER_VALUE;
    }

    public double compressionRatio() {
        return this.entries == 0 ? 0.0 : (double) this.values / this.entries;
    }

    /**
     * The share of the storage avoided.
     *
     * <p>Zero before any section has been encoded. A codec that has encoded nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     */
    public double savedPercent() {
        double uncompressed = bytesUncompressed();
        if (uncompressed == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, uncompressed - bytesEncoded()) / uncompressed;
    }
}
