package net.amdfaster.dirty;

/**
 * The exact set of sections whose mesh a single block change can invalidate.
 *
 * <p>This is the foundation of the biggest client-side win in the project. A block change does not
 * cost one mesh rebuild, it costs a storm of them: the change marks its own section dirty, then
 * light propagates up to fifteen blocks in every direction and every block the light touches marks
 * <em>its</em> section dirty. Community measurements of vanilla put a single placed block at around
 * fifty {@code rebuildChunk} calls, and each one re-meshes a 16x16x16 section -- greedy meshing,
 * ambient occlusion, vertex packing, upload. A TNT explosion changes hundreds of blocks at once and
 * multiplies that.
 *
 * <p>Two separate things follow from getting this set right, and they are worth different amounts.
 *
 * <p><b>It is minimal.</b> A block in the middle of a section invalidates one section, not the
 * eight around it. Only a block on a boundary can change a face that belongs to a neighbour: the
 * block at local x=0 owns the face at its own x=0, and the neighbour owns the face at its x=16, and
 * those are the same plane. So the neighbour's mesh changes only for a boundary block, and only in
 * the direction of the boundary.
 *
 * <p><b>It is exact.</b> Every section returned genuinely can change, and every section that can
 * change is returned. Culling this set further would drop visible faces; growing it is the vanilla
 * mistake.
 */
public final class SectionSet {

    /**
     * Most sections one block change can invalidate: the block's own section, plus one neighbour
     * per axis it touches a boundary on. 2 x 2 x 2.
     */
    public static final int MAX = 8;

    public static final int SIZE = 16;

    private static final int MASK = SIZE - 1;

    private final int[] xs = new int[MAX];
    private final int[] ys = new int[MAX];
    private final int[] zs = new int[MAX];
    private int size;

    /**
     * Fills this set with the sections invalidated by the block at {@code (x, y, z)} changing.
     *
     * <p>Reuse the same instance across calls; nothing is allocated after construction.
     *
     * @return how many sections were written, between 1 and {@value #MAX}
     */
    public int fillForBlockChange(int x, int y, int z) {
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        int lx = x & MASK;
        int ly = y & MASK;
        int lz = z & MASK;

        // A block on the low boundary of a section shares a face plane with the neighbour below it,
        // and a block on the high boundary shares one with the neighbour above. Those are the only
        // two cases where a second section's mesh can change.
        int xLo = lx == 0 ? -1 : 0;
        int xHi = lx == MASK ? 1 : 0;
        int yLo = ly == 0 ? -1 : 0;
        int yHi = ly == MASK ? 1 : 0;
        int zLo = lz == 0 ? -1 : 0;
        int zHi = lz == MASK ? 1 : 0;

        this.size = 0;
        for (int dx = xLo; dx <= xHi; dx++) {
            for (int dy = yLo; dy <= yHi; dy++) {
                for (int dz = zLo; dz <= zHi; dz++) {
                    this.xs[this.size] = sx + dx;
                    this.ys[this.size] = sy + dy;
                    this.zs[this.size] = sz + dz;
                    this.size++;
                }
            }
        }
        return this.size;
    }

    public int size() {
        return this.size;
    }

    public int x(int i) {
        return this.xs[i];
    }

    public int y(int i) {
        return this.ys[i];
    }

    public int z(int i) {
        return this.zs[i];
    }
}
