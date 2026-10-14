package net.amdfaster.worldgen;

import java.util.Arrays;

/**
 * Writes a chunk section the way world generation can, rather than the way gameplay has to.
 *
 * <p>A block placed by a player goes through a pipeline that exists because the world is live: the
 * palette is searched, the light engine is queued, six neighbours are scheduled for updates, the
 * heightmap moves and the block is checked for a block entity. Ten pieces of work per block. World
 * generation writes 98,304 blocks into a chunk, so that is 983,040 pieces of work, and at a render
 * distance of ten the initial load is 441 chunks and 433 million of them.
 *
 * <p>None of it is needed yet. The chunk is not in the world, nothing can see it, and light and
 * neighbour state get resolved once the section is complete. What generation actually needs is the
 * palette, the packed index array and the heightmap, and this builds those three directly.
 *
 * <h2>The packing has to match the format, not just be compact</h2>
 *
 * <p>Vanilla packs block-state indices without letting an entry straddle two longs. At five bits an
 * entry does not fit twelve to a long with room left over in the usual sense -- twelve entries use
 * sixty bits and the remaining four are wasted, and the next entry starts in the next long. Packing
 * densely instead produces a smaller array that the reader decodes wrongly, and the failure is a
 * chunk that looks scrambled rather than an exception. {@link #entriesPerLong} and
 * {@link #longsFor} implement the wasteful version on purpose.
 *
 * <p>The bit width has to be one of the legal ones for the same reason. Two bits holds four states
 * and three bits holds eight, and there is no three-bit layer: the widths are 1, 2, 4, 5, 6 and 8.
 * A section with five distinct blocks uses four bits, not three.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It does not update light, notify neighbours or create block entities, and a caller that treats
 * it as a general block setter will produce a chunk with no light in it. That is the trade: this is
 * for sections being generated, and the class says so rather than pretending otherwise.
 *
 * <p>Not thread safe.
 */
public final class ChunkSectionWriter {

    /** Blocks along one edge of a section. */
    public static final int SIZE = 16;

    /** Blocks in a section. */
    public static final int BLOCKS = SIZE * SIZE * SIZE;

    /** Index of the value meaning "not written yet", so a partially built section is detectable. */
    private static final int UNSET = -1;

    private final int[] states;

    /** Distinct states seen so far, in first-seen order, so the palette is stable. */
    private final int[] palette;
    private int paletteSize;

    /** Per-column highest non-air Y, relative to the section. */
    private final int[] heightmap;

    private long writes;
    private long heightmapUpdates;
    private boolean finished;

    public ChunkSectionWriter() {
        this.states = new int[BLOCKS];
        Arrays.fill(this.states, UNSET);
        this.palette = new int[BLOCKS];
        this.heightmap = new int[SIZE * SIZE];
        Arrays.fill(this.heightmap, UNSET);
    }

    /**
     * Records a block.
     *
     * @param blockStateId a non-negative id
     * @throws IllegalArgumentException if the id is negative, since {@link #UNSET} is negative and a
     *     negative id would be indistinguishable from a position never written
     */
    public void set(int x, int y, int z, int blockStateId) {
        checkCoord(x, "x");
        checkCoord(y, "y");
        checkCoord(z, "z");
        if (blockStateId < 0) {
            throw new IllegalArgumentException(
                    "block state id " + blockStateId + " collides with the unset marker");
        }
        int index = index(x, y, z);
        int existing = paletteIndexOf(blockStateId);
        if (existing < 0) {
            if (this.paletteSize >= this.palette.length) {
                throw new IllegalStateException(
                        "section has more than " + this.palette.length + " distinct block states");
            }
            existing = this.paletteSize;
            this.palette[this.paletteSize++] = blockStateId;
        }
        this.states[index] = existing;
        this.writes++;

        // The heightmap only cares about the highest non-air block in a column. Comparing against the
        // stored value rather than recomputing the column is what makes this one operation instead of
        // sixteen.
        if (blockStateId != 0 && y > this.heightmap[column(x, z)]) {
            this.heightmap[column(x, z)] = y;
            this.heightmapUpdates++;
        }
    }

    private int paletteIndexOf(int blockStateId) {
        for (int i = 0; i < this.paletteSize; i++) {
            if (this.palette[i] == blockStateId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Packs the section and marks it complete.
     *
     * @param airId the id treated as air for the heightmap, normally 0
     * @return the packed index array, sized by {@link #longsFor}
     * @throws IllegalStateException if any position was never written, because a section with holes
     *     in it is a generation bug and packing it would hide the hole under whatever the array
     *     happened to hold
     */
    public long[] pack(int airId) {
        int bits = bitsFor(this.paletteSize);
        int entries = entriesPerLong(bits);
        long[] packed = new long[longsFor(bits)];
        for (int i = 0; i < BLOCKS; i++) {
            int state = this.states[i];
            if (state == UNSET) {
                throw new IllegalStateException(
                        "position " + (i & 15) + "," + (i >> 8) + "," + ((i >> 4) & 15)
                                + " was never written");
            }
            // No straddling: each long holds entriesPerLong entries and the spare high bits stay
            // zero. Packing tightly would save space and break the reader.
            int longIndex = i / entries;
            int slot = i % entries;
            packed[longIndex] |= (long) state << (slot * bits);
        }
        this.finished = true;
        return packed;
    }

    /**
     * Bit width for a palette of this size.
     *
     * <p>Restricted to the widths the format has layers for: 1, 2, 4, 5, 6 and 8. There is no
     * three-bit layer, so five distinct blocks use four bits.
     */
    public static int bitsFor(int paletteSize) {
        if (paletteSize <= 0) {
            return 0;
        }
        if (paletteSize <= 2) {
            return 1;
        }
        if (paletteSize <= 4) {
            return 2;
        }
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 32) {
            return 5;
        }
        if (paletteSize <= 64) {
            return 6;
        }
        return 8;
    }

    /** Entries in one long at this bit width, with straddling forbidden. */
    public static int entriesPerLong(int bits) {
        if (bits <= 0) {
            return BLOCKS;
        }
        return 64 / bits;
    }

    /** Longs needed to hold a section at this bit width. */
    public static int longsFor(int bits) {
        if (bits <= 0) {
            return 0;
        }
        int entries = entriesPerLong(bits);
        return (BLOCKS + entries - 1) / entries;
    }

    /**
     * Reads back an index that {@link #pack} wrote.
     *
     * <p>Exists so the round trip can be checked rather than trusted, and because the layout is easy
     * to get wrong in a way that produces scrambled chunks rather than errors.
     */
    public static int unpack(long[] packed, int bits, int index) {
        int entries = entriesPerLong(bits);
        long word = packed[index / entries];
        int slot = index % entries;
        long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
        return (int) ((word >>> (slot * bits)) & mask);
    }

    /** Layout used by the packed array and by the meshers that read it: YZX. */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int column(int x, int z) {
        return (z << 4) | x;
    }

    private static void checkCoord(int value, String name) {
        if (value < 0 || value >= SIZE) {
            throw new IllegalArgumentException(name + " must be within " + SIZE + ": " + value);
        }
    }

    /** Distinct block states written into this section. */
    public int paletteSize() {
        return this.paletteSize;
    }

    /** The palette in first-seen order. */
    public int[] palette() {
        return Arrays.copyOf(this.palette, this.paletteSize);
    }

    /**
     * Highest non-air Y in a column, relative to the section, or -1 if the column is all air.
     *
     * @param airId reserved for callers whose air is not id 0; the heightmap is built against id 0
     */
    public int heightAt(int x, int z, int airId) {
        if (airId != 0) {
            throw new UnsupportedOperationException(
                    "the heightmap is built against air id 0, and was given " + airId);
        }
        checkCoord(x, "x");
        checkCoord(z, "z");
        return this.heightmap[column(x, z)];
    }

    /** Whether every position in the section has been written. */
    public boolean isComplete() {
        for (int state : this.states) {
            if (state == UNSET) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@link #pack} has run. */
    public boolean isFinished() {
        return this.finished;
    }

    public long writes() {
        return this.writes;
    }

    public long heightmapUpdates() {
        return this.heightmapUpdates;
    }

    /**
     * Bytes the packed section occupies.
     *
     * <p>Worth having because the alternative -- a short per block -- is 8,192 bytes, and the whole
     * argument for packing is the difference between that and 1,024.
     */
    public int packedByteSize() {
        return longsFor(bitsFor(this.paletteSize)) * 8;
    }
}
