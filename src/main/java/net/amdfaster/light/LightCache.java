package net.amdfaster.light;

/**
 * An 18x18x18 snapshot of the light and opacity around one 16x16x16 section.
 *
 * <p>This exists purely as an optimisation, and it is a large one. Smooth lighting samples four
 * blocks per face corner, and a greedy-meshed section emits four corners per quad across up to six
 * orientations, so a single section can want tens of thousands of samples. Answering those by
 * walking the level's chunk map, with a bounds check and a section lookup each time, makes lighting
 * the dominant cost of meshing -- well ahead of the block lookups the mesher itself does.
 *
 * <p>Reading the neighbourhood once into a flat array turns every one of those samples into an
 * array index. 18^3 packed ints is 23 KB, which fits in L2 and mostly in L1, so the whole section's
 * lighting pass stays on-chip.
 *
 * <p>The one-block border is not padding for convenience: a face on the boundary of the section has
 * corners that lie outside it, and those corners need real light and real occluders. Without the
 * border, every section edge would be lit as if the world ended there.
 *
 * <p>Coordinates are section-local and run from {@code -1} to {@code 16} inclusive. The cache is
 * mutable and reused, so meshing a section allocates nothing.
 */
public final class LightCache {

    /** Side length of the cache, including the border on both sides. */
    public static final int SIDE = 18;

    /** How far the cache reaches below and to the negative side of the section. */
    public static final int BORDER = 1;

    /** Local coordinate of the first cached cell. */
    public static final int MIN = -BORDER;

    /** Local coordinate of the last cached cell, so the cached range is exactly SIDE wide. */
    public static final int MAX_INCLUSIVE = 16;

    /** Cells in the cache. */
    public static final int CELLS = SIDE * SIDE * SIDE;

    private static final int OPAQUE_BIT = 1 << 8;

    /**
     * One cell: block light in bits 0..3, sky light in bits 4..7, occlusion in bit 8.
     *
     * <p>Packed into a single int rather than three arrays so a corner lookup touches one cache
     * line. Three parallel arrays would be three lines for the same four neighbours.
     */
    private final int[] cells = new int[CELLS];

    private int originX;
    private int originY;
    private int originZ;

    private static int index(int x, int y, int z) {
        return ((y + BORDER) * SIDE + (z + BORDER)) * SIDE + (x + BORDER);
    }

    /** True when a local coordinate is inside the cache. */
    public static boolean inCache(int x, int y, int z) {
        return x >= MIN && x <= MAX_INCLUSIVE
                && y >= MIN && y <= MAX_INCLUSIVE
                && z >= MIN && z <= MAX_INCLUSIVE;
    }

    /**
     * Re-reads the whole neighbourhood for the section whose minimum corner is
     * {@code (originX, originY, originZ)} in world coordinates.
     *
     * <p>{@code 18^3 = 5832} samples per section. That is the price, and it is worth it: the
     * alternative is the same samples plus a chunk lookup each.
     */
    public void fill(LightSampler sampler, int originX, int originY, int originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        int i = 0;
        for (int y = MIN; y <= MAX_INCLUSIVE; y++) {
            int wy = originY + y;
            for (int z = MIN; z <= MAX_INCLUSIVE; z++) {
                int wz = originZ + z;
                for (int x = MIN; x <= MAX_INCLUSIVE; x++) {
                    int wx = originX + x;
                    int block = clamp(sampler.block(wx, wy, wz));
                    int sky = clamp(sampler.sky(wx, wy, wz));
                    int cell = block | (sky << 4);
                    if (sampler.occludes(wx, wy, wz)) {
                        cell |= OPAQUE_BIT;
                    }
                    this.cells[i++] = cell;
                }
            }
        }
    }

    private static int clamp(int level) {
        return level < 0 ? 0 : Math.min(level, LightValue.MAX);
    }

    /** World X of the section's minimum corner, as given to the last {@link #fill}. */
    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    private int cell(int x, int y, int z) {
        if (!inCache(x, y, z)) {
            throw new IllegalArgumentException("outside the light cache: " + x + "," + y + "," + z
                    + " (valid range " + MIN + ".." + MAX_INCLUSIVE + ")");
        }
        return this.cells[index(x, y, z)];
    }

    /**
     * The raw cell at a section-local coordinate.
     *
     * <p>Exposed because a corner lookup wants the light and the occlusion of the same cell, and
     * going through {@link #block}/{@link #sky}/{@link #occludes} would bounds-check and index the
     * array four times for one answer. Decode it with the static helpers below.
     */
    public int sample(int x, int y, int z) {
        return cell(x, y, z);
    }

    /** Block light of a raw cell, 0..15. */
    public static int blockOf(int cell) {
        return cell & 0xF;
    }

    /** Sky light of a raw cell, 0..15. */
    public static int skyOf(int cell) {
        return (cell >> 4) & 0xF;
    }

    /** Both channels of a raw cell, packed the way {@link LightValue} expects. */
    public static int lightOf(int cell) {
        return (cell & 0xF) | (((cell >> 4) & 0xF) << 20);
    }

    /** Whether a raw cell hides the corner behind it. */
    public static boolean occludesOf(int cell) {
        return (cell & OPAQUE_BIT) != 0;
    }

    /** Block light at a section-local coordinate, 0..15. */
    public int block(int x, int y, int z) {
        return blockOf(cell(x, y, z));
    }

    /** Sky light at a section-local coordinate, 0..15. */
    public int sky(int x, int y, int z) {
        return skyOf(cell(x, y, z));
    }

    /** Both channels, packed the way {@link LightValue} expects. */
    public int light(int x, int y, int z) {
        return lightOf(cell(x, y, z));
    }

    /** True when the block there hides the corner behind it. */
    public boolean occludes(int x, int y, int z) {
        return occludesOf(cell(x, y, z));
    }

    /**
     * True when every cached cell is fully lit and nothing occludes.
     *
     * <p>Used to skip the smooth-lighting path entirely: a section floating in open daylight with no
     * geometry around it produces the same value at every corner, so computing it four times per
     * quad is pure waste.
     */
    public boolean isUniform() {
        int first = this.cells[0];
        for (int i = 1; i < CELLS; i++) {
            if (this.cells[i] != first) {
                return false;
            }
        }
        return true;
    }
}
