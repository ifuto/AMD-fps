package net.amdfaster.mesh;

/**
 * The finished mesh of one 16x16x16 section, split into six orientation buckets.
 *
 * <p>Immutable and safe to hand to the render thread. The buckets are the draw-time win: a section
 * the camera is not inside draws at most three of them, so roughly half the triangles never reach
 * the vertex stage, with no second pass and no per-triangle test.
 */
public final class SectionMesh {

    /** Edge length of a section in blocks. Minecraft sections are 16^3. */
    public static final int SIZE = 16;

    private final int originX;
    private final int originY;
    private final int originZ;
    private final Meshlet[][] meshlets;
    private final int[] quadCounts;
    private final int totalQuads;
    private final int totalVertices;

    SectionMesh(int originX, int originY, int originZ, Meshlet[][] meshlets, int[] quadCounts,
            int totalQuads, int totalVertices) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.meshlets = meshlets;
        this.quadCounts = quadCounts;
        this.totalQuads = totalQuads;
        this.totalVertices = totalVertices;
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

    /** Never null; empty for an orientation the section has no geometry in. */
    public Meshlet[] meshlets(Orientation orientation) {
        return this.meshlets[orientation.ordinal()];
    }

    public int quadCount(Orientation orientation) {
        return this.quadCounts[orientation.ordinal()];
    }

    public int totalQuads() {
        return this.totalQuads;
    }

    public int totalVertices() {
        return this.totalVertices;
    }

    public int totalTriangles() {
        return this.totalQuads * Quad.TRIANGLES;
    }

    public int totalMeshlets() {
        int n = 0;
        for (Meshlet[] bucket : this.meshlets) {
            n += bucket.length;
        }
        return n;
    }

    public boolean isEmpty() {
        return this.totalQuads == 0;
    }

    /**
     * Which buckets can be seen from a camera at block coordinates. See
     * {@link Orientation#visibleMask}.
     */
    public int visibleBuckets(double cameraX, double cameraY, double cameraZ) {
        return Orientation.visibleMask(cameraX, cameraY, cameraZ,
                this.originX, this.originY, this.originZ, SIZE);
    }

    /**
     * How many triangles survive bucket masking from a camera position. This is the number the
     * six-bucket split is judged on.
     */
    public int visibleTriangles(double cameraX, double cameraY, double cameraZ) {
        int mask = this.visibleBuckets(cameraX, cameraY, cameraZ);
        int triangles = 0;
        for (Orientation o : Orientation.values()) {
            if ((mask & o.bit()) != 0) {
                triangles += this.quadCounts[o.ordinal()] * Quad.TRIANGLES;
            }
        }
        return triangles;
    }

    public int totalPositionBytes() {
        return this.totalVertices * Meshlet.POSITION_STRIDE;
    }

    public int totalAttributeBytes() {
        return this.totalVertices * Meshlet.ATTRIBUTE_STRIDE;
    }

    public int totalIndexBytes() {
        return this.totalQuads * Meshlet.INDEX_BYTES_PER_QUAD;
    }

    @Override
    public String toString() {
        return "SectionMesh[" + this.originX + "," + this.originY + "," + this.originZ
                + " quads=" + this.totalQuads + " meshlets=" + this.totalMeshlets() + "]";
    }
}
