package net.amdfaster.light;

/**
 * A section's opacity as one bit per cell, plus the exact attenuation for the minority of cells that
 * need it.
 *
 * <p>Why a bitset. The flood fill asks one question about almost every neighbour it considers: can
 * light get in here at all? For a typical section that question is answered by fully opaque stone and
 * dirt or by air, and nothing in between. So the common case is one bit -- one sixty-fourth of the
 * memory of a byte per cell, and a single test with no call into whatever is supplying block data.
 * Only blocks that partially attenuate (leaves, water, ice, slabs) need the real number, and those are
 * stored separately so the array holding them is the size of the exceptions rather than of the
 * volume.
 *
 * <p>The saving compounds because the fill asks this question six times per cell it visits, and a
 * light update visits thousands of cells. Turning six virtual calls per cell into six bit tests is the
 * difference between an update that fits in the frame budget and one that does not.
 *
 * <p>Not thread safe.
 */
public final class OpacityField {

    /** Attenuation at or above which nothing gets through. Minecraft's own threshold. */
    public static final int OPAQUE = 15;

    /** Cells per section edge. */
    public static final int SIZE = 16;

    public static final int CELL_COUNT = SIZE * SIZE * SIZE;

    private final int originX;
    private final int originY;
    private final int originZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int cellCount;

    /** One bit per cell: set means light cannot enter at all. */
    private final long[] opaque;

    /** Exact attenuation for cells not in the bitset, two per byte. */
    private final byte[] attenuation;

    private int opaqueCells;

    public OpacityField(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("size must be positive: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cellCount = sizeX * sizeY * sizeZ;
        this.opaque = new long[(this.cellCount + 63) >>> 6];
        this.attenuation = new byte[(this.cellCount + 1) >>> 1];
    }

    /** An opacity field for one 16x16x16 section. */
    public static OpacityField forSection(int sectionX, int sectionY, int sectionZ) {
        return new OpacityField(sectionX << 4, sectionY << 4, sectionZ << 4, SIZE, SIZE, SIZE);
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    public int cellCount() {
        return this.cellCount;
    }

    /** Bytes used by the bitset alone. A byte-per-cell array would take eight times this. */
    public int bitsetBytes() {
        return this.opaque.length << 3;
    }

    /**
     * Bytes used by the exact attenuation values.
     *
     * <p>Half a byte per cell, because two fit in one. Still allocated unconditionally, which costs
     * memory for a section that is entirely stone or entirely air; trading that for a sparse map would
     * cost a lookup per access in the hottest loop in the light engine, which is the worse trade.
     */
    public int attenuationBytes() {
        return this.attenuation.length;
    }

    public boolean contains(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        // All three checked with one comparison: a negative local coordinate has its sign bit set, so
        // OR-ing them and testing the sign catches any axis at once.
        return ((lx | ly | lz) >= 0) && lx < this.sizeX && ly < this.sizeY && lz < this.sizeZ;
    }

    private int index(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        if (((lx | ly | lz) < 0) || lx >= this.sizeX || ly >= this.sizeY || lz >= this.sizeZ) {
            return -1;
        }
        return (lz * this.sizeY + ly) * this.sizeX + lx;
    }

    /** Whether light is blocked entirely. Outside the volume counts as blocked. */
    public boolean isOpaque(int x, int y, int z) {
        int index = index(x, y, z);
        if (index < 0) {
            return true;
        }
        return (this.opaque[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /**
     * Attenuation of a block, floored at 1.
     *
     * <p>The floor is Minecraft's rule and it is what makes glass and leaves cost the same as air
     * rather than less than air. Outside the volume reads as fully opaque, which stops propagation at
     * the edge without the caller needing a bounds check.
     */
    public int costOf(int x, int y, int z) {
        int index = index(x, y, z);
        if (index < 0) {
            return OPAQUE;
        }
        if ((this.opaque[index >>> 6] & (1L << (index & 63))) != 0L) {
            return OPAQUE;
        }
        return Math.max(1, this.attenuation[index >>> 1] >>> ((index & 1) << 2) & 0xF);
    }

    /**
     * Records a block's attenuation.
     *
     * @return true if the cell's classification changed
     */
    public boolean set(int x, int y, int z, int attenuationValue) {
        if (attenuationValue < 0 || attenuationValue > OPAQUE) {
            throw new IllegalArgumentException("attenuation out of range: " + attenuationValue);
        }
        int index = index(x, y, z);
        if (index < 0) {
            return false;
        }
        boolean wasOpaque = (this.opaque[index >>> 6] & (1L << (index & 63))) != 0L;
        boolean isOpaque = attenuationValue >= OPAQUE;
        if (wasOpaque != isOpaque) {
            if (isOpaque) {
                this.opaque[index >>> 6] |= 1L << (index & 63);
                this.opaqueCells++;
            } else {
                this.opaque[index >>> 6] &= ~(1L << (index & 63));
                this.opaqueCells--;
            }
        }
        int shift = (index & 1) << 2;
        this.attenuation[index >>> 1] = (byte) ((this.attenuation[index >>> 1] & ~(0xF << shift))
                | ((attenuationValue & 0xF) << shift));
        return wasOpaque != isOpaque;
    }

    public int opaqueCells() {
        return this.opaqueCells;
    }

    /** Cells that are neither fully opaque nor fully clear. */
    public int filteringCells() {
        int filtering = 0;
        for (int index = 0; index < this.cellCount; index++) {
            if ((this.opaque[index >>> 6] & (1L << (index & 63))) != 0L) {
                continue;
            }
            if (((this.attenuation[index >>> 1] >>> ((index & 1) << 2)) & 0xF) > 1) {
                filtering++;
            }
        }
        return filtering;
    }

    public void clear() {
        java.util.Arrays.fill(this.opaque, 0L);
        java.util.Arrays.fill(this.attenuation, (byte) 0);
        this.opaqueCells = 0;
    }
}
