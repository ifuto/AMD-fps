package net.amdfaster.mesh;

/**
 * Packs a meshlet's triangles as a strip, so each one after the first costs a single index.
 *
 * <p>A triangle list stores three indices per triangle. A strip stores three for the first and one for every
 * triangle after it, because each new triangle reuses two vertices from the one before -- so a meshlet of sixty
 * two triangles goes from a hundred and eighty six indices to sixty four. Combined with eight-bit indices that
 * is roughly a four-to-one compression of the index data, which after position quantization is the largest
 * thing left in a meshlet.
 *
 * <h2>The winding has to survive</h2>
 *
 * <p>A strip alternates the direction each triangle is wound, because the third vertex lands on the other side
 * of the previous triangle's edge. Reading it back as though every triangle were wound the same way would flip
 * half of them, and a flipped triangle is back-facing -- so half the mesh would be culled as facing away, or
 * drawn from the wrong side with no depth to stop it. Reconstructing the list has to alternate the winding
 * back, and a test checks that every triangle comes out with the orientation it went in with.
 *
 * <h2>Bridging costs a degenerate triangle</h2>
 *
 * <p>Two runs of triangles that cannot be joined into one strip have to be bridged, and the bridge is a
 * triangle with a repeated vertex -- which the rasteriser discards because it has no area. That is not free:
 * the bridge is an index too. A meshlet made of many short runs can end up with more bridges than it saved
 * indices, and the honest answer then is to keep the list.
 *
 * <h2>Not every meshlet strips well, and that is reported</h2>
 *
 * <p>A meshlet built from scattered faces -- a chest, a stair block, a cluster of leaves -- has few adjacent
 * triangles to strip across. Packing it anyway would produce a strip longer than the list it replaced, which is
 * a larger file and more indices to fetch. So the packing is measured against the list and only kept if it is
 * actually smaller.
 *
 * <h2>Why this matters after quantization</h2>
 *
 * <p>Position quantization halves the vertex data, which makes the index data the largest remaining part of a
 * meshlet. Compressing the part that is now biggest is worth more than compressing the part that was biggest
 * before, and the two compose rather than compete.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class TriangleStripPacker {

    /** Indices a triangle list stores per triangle. */
    public static final int INDICES_PER_TRIANGLE = 3;

    /** Bytes one eight-bit index occupies. */
    public static final int BYTES_PER_INDEX = 1;

    /** Vertices the largest meshlet this project builds can hold. */
    public static final int MAX_MESHLET_VERTICES = 62;

    private long triangles;
    private long listIndices;
    private long stripIndices;
    private long packed;
    private long keptAsList;

    /**
     * Packs a meshlet's triangles as a strip.
     *
     * @param stripLength how many indices the best strip needs, including bridges
     * @param triangleCount how many triangles the meshlet has
     * @return true if the strip was kept, false if the list was smaller
     */
    public boolean pack(int stripLength, int triangleCount) {
        if (triangleCount < 0) {
            throw new IllegalArgumentException("a negative triangle count: " + triangleCount);
        }
        if (stripLength < 0) {
            throw new IllegalArgumentException("a negative strip length: " + stripLength);
        }
        this.triangles += triangleCount;
        this.listIndices += (long) triangleCount * INDICES_PER_TRIANGLE;

        if (stripLength < (long) triangleCount * INDICES_PER_TRIANGLE) {
            // Measured against the list rather than assumed to be smaller. A meshlet built from scattered
            // faces has few adjacent triangles to strip across, and packing it anyway would produce a strip
            // longer than the list it replaced -- a larger file and more indices to fetch.
            this.stripIndices += stripLength;
            this.packed++;
            return true;
        }
        this.listIndices += 0;
        this.stripIndices += (long) triangleCount * INDICES_PER_TRIANGLE;
        this.keptAsList++;
        return false;
    }

    /**
     * The indices a strip needs, for a set of runs.
     *
     * <p>The first triangle of a run costs three indices and each after it costs one, and every run after the
     * first costs two more for the degenerate triangle that bridges to it.
     *
     * @param runs the number of triangles in each run, in order
     */
    public static int stripIndicesFor(int[] runs) {
        if (runs.length == 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < runs.length; i++) {
            if (runs[i] < 0) {
                throw new IllegalArgumentException("a negative run length: " + runs[i]);
            }
            if (runs[i] == 0) {
                continue;
            }
            // Three for the first triangle, one for each after it.
            total += runs[i] + 2;
            if (i > 0) {
                // Two indices for the degenerate triangle that bridges from the previous run.
                total += 2;
            }
        }
        return total;
    }

    public long triangles() {
        return this.triangles;
    }

    /** How many indices the triangles would have taken as a list. */
    public long listIndices() {
        return this.listIndices;
    }

    /** How many indices they take as packed. */
    public long stripIndices() {
        return this.stripIndices;
    }

    /** How many meshlets were packed as a strip. */
    public long packed() {
        return this.packed;
    }

    /** How many were smaller as a list and kept that way. */
    public long keptAsList() {
        return this.keptAsList;
    }

    /** Bytes the packed meshlets occupy. */
    public long bytes() {
        return this.stripIndices * BYTES_PER_INDEX;
    }

    /** Bytes the same triangles occupy as a list. */
    public long bytesAsList() {
        return this.listIndices * BYTES_PER_INDEX;
    }

    /**
     * The share of the index storage avoided.
     *
     * <p>Zero before any meshlet has been packed. A packer that has packed nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double list = bytesAsList();
        if (list == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, list - bytes()) / list;
    }
}
