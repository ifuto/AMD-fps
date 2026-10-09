package net.amdfaster.light;

/**
 * A bounded box of light levels, two per byte.
 *
 * <p>Two levels per byte is Minecraft's own storage format and it is worth matching rather than
 * simplifying away. Light is the one piece of per-block data that has to exist for every block in a
 * section whether or not anything is rendered there, so at render distance 16 it is one of the
 * largest resident arrays in the client. Halving it is a straight saving, and the packing costs one
 * shift and one mask per access on a path that is already doing a flood fill.
 *
 * <p>Coordinates are relative to the box origin, so a section's field is addressed 0..15 on each
 * axis regardless of where the section sits in the world. That keeps the index arithmetic small and
 * unsigned, and it means the same code works for a section and for a larger region.
 *
 * <p>Out-of-bounds reads return 0 and writes are ignored, rather than throwing. Propagation walks
 * outward from a source and reaches the edge of whatever volume it was given; treating the edge as
 * "no light" is the conservative answer and it keeps the caller from needing a bounds check at every
 * one of the six neighbour steps, which is six branches per block in the hottest loop there is.
 *
 * <p>Not thread safe.
 */
public final class LightField {

    /** Light levels are 0..15, which is four bits. */
    public static final int MAX_LEVEL = 15;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int areaXZ;

    /** Two levels per byte: even indices in the low nibble, odd in the high. */
    private final byte[] nibbles;

    private final int originX;
    private final int originY;
    private final int originZ;

    public LightField(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException(
                    "empty field: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.areaXZ = sizeX * sizeZ;

        int cells = sizeX * sizeY * sizeZ;
        this.nibbles = new byte[(cells + 1) >>> 1];
    }

    /** Blocks per section edge. Minecraft's chunk section is a 16x16x16 cube. */
    public static final int SIZE = 16;

    /** Cells in one section. */
    public static final int SECTION_CELLS = SIZE * SIZE * SIZE;

    /** Bytes one section's light costs: one nibble per cell. */
    public static final int SECTION_BYTES = SECTION_CELLS >>> 1;

    /** A field covering one 16x16x16 section at the given section origin in blocks. */
    public static LightField forSection(int sectionX, int sectionY, int sectionZ) {
        return new LightField(sectionX << 4, sectionY << 4, sectionZ << 4, SIZE, SIZE, SIZE);
    }

    /** Lowest world x this field covers. */
    public int originX() {
        return this.originX;
    }

    /** Lowest world y this field covers. */
    public int originY() {
        return this.originY;
    }

    /** Lowest world z this field covers. */
    public int originZ() {
        return this.originZ;
    }

    public int sizeX() {
        return this.sizeX;
    }

    public int sizeY() {
        return this.sizeY;
    }

    public int sizeZ() {
        return this.sizeZ;
    }

    public int cellCount() {
        return this.sizeX * this.sizeY * this.sizeZ;
    }

    /** Bytes of storage. Half the cell count, rounded up. */
    public int storageBytes() {
        return this.nibbles.length;
    }

    /** True when the coordinate is inside the box. */
    public boolean contains(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        return (lx | ly | lz) >= 0 && lx < this.sizeX && ly < this.sizeY && lz < this.sizeZ;
    }

    /** The light level at a world coordinate, or 0 outside the box. */
    public int get(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        // One comparison on the OR catches a negative on any axis, because a negative int has its
        // sign bit set and so does the OR of anything with it.
        if ((lx | ly | lz) < 0 || lx >= this.sizeX || ly >= this.sizeY || lz >= this.sizeZ) {
            return 0;
        }
        return read(lx + ly * this.sizeX + lz * this.areaXZ);
    }

    /**
     * Sets the light level at a world coordinate. Ignored outside the box.
     *
     * @return true when the value actually changed, which is what lets propagation stop early. A
     *         write that changes nothing cannot affect any neighbour, so the caller does not need to
     *         enqueue it -- and skipping those is most of what makes a light update cheap, since the
     *         overwhelming majority of a flood fill's writes are to cells that already hold the value.
     */
    public boolean set(int x, int y, int z, int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("light level outside 0..15: " + level);
        }
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        if ((lx | ly | lz) < 0 || lx >= this.sizeX || ly >= this.sizeY || lz >= this.sizeZ) {
            return false;
        }
        int index = lx + ly * this.sizeX + lz * this.areaXZ;
        if (read(index) == level) {
            return false;
        }
        write(index, level);
        return true;
    }

    /** Sets every cell. Used for the initial skylight fill and for tests. */
    public void fill(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("light level outside 0..15: " + level);
        }
        // 0x11 is the byte with both nibbles set to 1, so a level replicates by multiplying it by it.
        byte both = (byte) (level | (level << 4));
        java.util.Arrays.fill(this.nibbles, both);
        if ((cellCount() & 1) != 0) {
            // An odd cell count leaves the last byte's high nibble unused. Clearing it keeps a
            // subsequent read of that nibble from seeing a level that was never written.
            this.nibbles[this.nibbles.length - 1] = (byte) level;
        }
    }

    /** Counts cells holding a nonzero level. Exposed so tests can assert a fill actually reached. */
    public int litCellCount() {
        int count = 0;
        for (int i = 0; i < cellCount(); i++) {
            if (read(i) != 0) {
                count++;
            }
        }
        return count;
    }

    private int read(int index) {
        int b = this.nibbles[index >>> 1] & 0xFF;
        return (index & 1) == 0 ? b & 0xF : b >>> 4;
    }

    private void write(int index, int level) {
        int slot = index >>> 1;
        int current = this.nibbles[slot] & 0xFF;
        this.nibbles[slot] = (byte) ((index & 1) == 0
                ? (current & 0xF0) | level
                : (current & 0x0F) | (level << 4));
    }
}
