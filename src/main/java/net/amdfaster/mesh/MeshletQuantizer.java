package net.amdfaster.mesh;

/**
 * Quantizes meshlet vertex positions against a grid shared by the whole mesh, so shared vertices land back
 * where they started.
 *
 * <p>Compressing a meshlet means storing its vertex positions as small integers instead of floats, which is
 * most of the size. The obvious way is to give each meshlet its own local grid: find its bounds, divide them
 * into steps, and store where each vertex falls. That works for a meshlet on its own, and breaks the mesh.
 *
 * <p>A vertex on the boundary between two meshlets is stored twice, once in each, and the two local grids are
 * different -- different origins, different step sizes -- so the same position dequantizes to two slightly
 * different floats. The triangles that shared it no longer share it, and the seam between the meshlets opens
 * into a visible crack. On a terrain mesh there is a seam every few blocks, so the cracks are everywhere.
 *
 * <h2>One grid for the whole mesh</h2>
 *
 * <p>The fix is to define the grid once for the mesh -- its bounds and its step size -- and give each meshlet
 * an offset on that grid rather than a grid of its own. A vertex is quantized to the global grid, the meshlet's
 * offset is subtracted, and the stored value is small because the meshlet is small relative to the mesh. Adding
 * the offset back gives exactly the global-grid value the other meshlet computed, because both are on the same
 * grid.
 *
 * <h2>The round trip has to be exact</h2>
 *
 * <p>The offset is itself quantized on the global grid before it is subtracted, so the subtraction is between
 * two integers and the result is an integer. Nothing in the round trip is a float operation on a value that
 * was already quantized, so there is no accumulated error to differ between meshlets. A scheme that computed
 * the offset in floating point would reintroduce exactly the discrepancy the shared grid removes.
 *
 * <h2>The cost is a coarser grid</h2>
 *
 * <p>A grid sized for the whole mesh has a larger step than one sized for a meshlet, so the same number of bits
 * represents positions less finely. That is the trade, and it is worth making: a crack is a visible defect and
 * a slightly coarser position is not visible at all, because the step is smaller than a pixel at the distances
 * the mesh is drawn from.
 *
 * <h2>Why this matters more for voxels than for scanned geometry</h2>
 *
 * <p>A scanned model has seams too, but they follow the model's own topology and a crack there may be hidden by
 * the shape. A voxel mesh's seams follow the meshlet boundaries, which are placed by the mesher for size
 * reasons and cut straight across flat surfaces -- exactly where a crack is most visible, and exactly where
 * there is no shape to hide it.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class MeshletQuantizer {

    /** Bits a quantized coordinate occupies. */
    public static final int QUANT_BITS = 16;

    /** The largest value a quantized coordinate can hold. */
    public static final int QUANT_MAX = (1 << QUANT_BITS) - 1;

    /** Bytes a quantized position occupies, against twelve for three floats. */
    public static final int BYTES_QUANTIZED = 6;

    /** Bytes a float position occupies. */
    public static final int BYTES_FLOAT = 12;

    private double globalMinX;
    private double globalMinY;
    private double globalMinZ;
    private double stepX;
    private double stepY;
    private double stepZ;

    private long quantized;
    private long meshlets;

    /**
     * Sets the grid from the whole mesh's bounds.
     *
     * <p>Called once per mesh, before any meshlet is quantized. Every meshlet then uses this grid, which is
     * what makes shared vertices agree.
     */
    public void setGlobalGrid(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double spanX = maxX - minX;
        double spanY = maxY - minY;
        double spanZ = maxZ - minZ;
        if (spanX < 0.0 || spanY < 0.0 || spanZ < 0.0) {
            throw new IllegalArgumentException(
                    "a maximum below the minimum: " + spanX + ", " + spanY + ", " + spanZ);
        }
        this.globalMinX = minX;
        this.globalMinY = minY;
        this.globalMinZ = minZ;
        this.stepX = spanX / QUANT_MAX;
        this.stepY = spanY / QUANT_MAX;
        this.stepZ = spanZ / QUANT_MAX;
    }

    /**
     * The quantized offset of a meshlet on the global grid.
     *
     * <p>Quantized rather than left as a float, so subtracting it from a quantized vertex is an integer
     * subtraction and nothing in the round trip accumulates error. A scheme that computed the offset in
     * floating point would reintroduce exactly the discrepancy the shared grid removes.
     */
    public int meshletOffset(double origin, double globalMin, double step) {
        if (step <= 0.0) {
            throw new IllegalArgumentException("a non-positive step: " + step);
        }
        return (int) Math.round((origin - globalMin) / step);
    }

    /**
     * Quantizes one coordinate against the global grid, then subtracts the meshlet's offset.
     *
     * @return a small integer, because the meshlet is small relative to the mesh
     */
    public int quantize(double value, double globalMin, double step, int meshletOffset) {
        if (step <= 0.0) {
            throw new IllegalArgumentException("a non-positive step: " + step);
        }
        this.quantized++;
        int global = (int) Math.round((value - globalMin) / step);
        return global - meshletOffset;
    }

    /**
     * Returns a quantized coordinate to world space.
     *
     * <p>Adding the offset back gives exactly the global-grid value another meshlet computed for the same
     * vertex, because both were quantized on the same grid -- which is what closes the seam.
     */
    public double dequantize(int local, int meshletOffset, double globalMin, double step) {
        return (local + meshletOffset) * step + globalMin;
    }

    /** Marks a meshlet as quantized. */
    public void endMeshlet() {
        this.meshlets++;
    }

    public long quantized() {
        return this.quantized;
    }

    /** How many meshlets have been quantized against this grid. */
    public long meshlets() {
        return this.meshlets;
    }

    /** The step size on one axis, which is the finest position the grid can represent. */
    public double step() {
        return this.stepX;
    }

    /**
     * Whether two meshlets quantizing the same vertex agree.
     *
     * <p>The property this class exists to guarantee. Two meshlets sharing a vertex on their boundary must
     * produce the same world position for it, or the seam between them opens into a crack.
     *
     * @param value the shared coordinate
     * @param offsetA the first meshlet's offset
     * @param offsetB the second meshlet's offset
     */
    public boolean agreesAcrossMeshlets(double value, int offsetA, int offsetB) {
        double a = dequantize(quantize(value, this.globalMinX, this.stepX, offsetA),
                offsetA, this.globalMinX, this.stepX);
        double b = dequantize(quantize(value, this.globalMinX, this.stepX, offsetB),
                offsetB, this.globalMinX, this.stepX);
        return a == b;
    }

    /** Bytes a meshlet's positions occupy after quantization. */
    public long bytesFor(int vertexCount) {
        return vertexCount * (long) BYTES_QUANTIZED;
    }

    /** Bytes the same positions occupy as floats. */
    public long bytesAsFloats(int vertexCount) {
        return vertexCount * (long) BYTES_FLOAT;
    }

    /**
     * The share of the position storage avoided.
     *
     * <p>Zero before any vertex has been quantized. A quantizer that has quantized nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent(int vertexCount) {
        double floats = bytesAsFloats(vertexCount);
        if (this.quantized == 0 || floats == 0.0) {
            return 0.0;
        }
        return 100.0 * (floats - bytesFor(vertexCount)) / floats;
    }
}
