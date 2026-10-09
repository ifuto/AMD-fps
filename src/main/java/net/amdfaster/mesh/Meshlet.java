package net.amdfaster.mesh;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An immutable cluster of quads inside one section, all sharing an {@link Orientation}.
 *
 * <p>This is the unit the GPU culls. Splitting a section into clusters rather than drawing it whole
 * is what makes per-cluster frustum and back-face rejection possible, and the research is specific
 * about the size: <em>64 vertices / 126 triangles is the optimum</em>, with up to a 35.4% render
 * time reduction from frustum plus back-face culling (Unterguggenberger et al., Pacific Graphics
 * 2021). Minecraft quads are 2 triangles each, so the bound that binds here is
 * {@value #MAX_QUADS} quads = {@value #MAX_TRIANGLES} triangles, and 62 x 4 = 248 vertices fits
 * inside the 256 that an 8-bit local index can address.
 *
 * <p>Vertex positions are section-local and the index buffer is 8-bit per corner, which is what
 * keeps a meshlet's upload small. See {@code docs/notes/09} §4.
 */
public final class Meshlet {

    /** Quads per meshlet. 62 quads = 124 triangles, just under the 126-triangle optimum. */
    public static final int MAX_QUADS = 62;

    public static final int MAX_TRIANGLES = MAX_QUADS * Quad.TRIANGLES;

    /** 8-bit local indices address at most 256 vertices; 62 quads x 4 corners = 248 fits. */
    public static final int MAX_VERTICES = 256;

    /**
     * Bytes per entry in the cull data buffer: the packed bounds plus the section origin.
     *
     * <p>Kept here rather than read from {@code CullBindings} so this class does not depend on the
     * gpu package, and pinned against it by a test.
     */
    public static final int CULL_RECORD_BYTES = 16;

    /**
     * Bytes per vertex in the position stream: {@code short x, short y, short z, short flags}.
     *
     * <p>8 bytes keeps eight vertices in one 128-byte cache line, which is the line size RDNA
     * fetches. Position gets its own stream because AMD's guide says a position-only stream
     * improves depth-only passes, and AMD-Faster uses a Z pre-pass for cutout geometry.
     */
    public static final int POSITION_STRIDE = 8;

    /** Bytes per vertex in the attribute stream: {@code float u, float v}. */
    public static final int ATTRIBUTE_STRIDE = 8;

    /**
     * Bytes per vertex in the light stream: one word holding both lightmap channels and the ambient
     * occlusion level.
     *
     * <p>Its own stream rather than a field appended to the attributes, for the same reason position
     * has one. The Z pre-pass that cutout geometry uses reads positions and nothing else, so a
     * narrow light stream costs it nothing. And light changes without geometry changing -- a torch
     * placed or the sun going down rewrites this stream and leaves positions and uv untouched, which
     * an interleaved layout would make impossible.
     *
     * <p>Four bytes also divides a 64-byte cache line exactly, so sixteen vertices sit in a line and
     * none straddles one.
     */
    public static final int LIGHT_STRIDE = 4;

    /**
     * Bytes of index data per quad: six 8-bit indices, {@code 0 1 2 0 2 3}, drawn as a triangle
     * list.
     *
     * <p>A strip would be 4 bytes, but closing strips needs a primitive restart index and the guide
     * says to avoid those ("Restart index can reduce the primitive rate on older generations").
     * 8-bit indices need {@code VK_EXT_index_type_uint8}; without it the same data is widened to
     * 16-bit at upload time.
     */
    public static final int INDEX_BYTES_PER_QUAD = 6;

    /** Triangle-list expansion of one quad's four corners. */
    private static final int[] QUAD_INDEX_EXPANSION = {0, 1, 2, 0, 2, 3};

    private final Orientation orientation;
    private final short[] positions;
    private final float[] uvs;
    private final byte[] quadIndices;
    private final int[] quadColors;
    private final int[] vertexLights;
    private final int quadCount;
    private final int vertexCount;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final boolean singleSided;

    /**
     * Bit of {@link #packedBounds()} that says every face in this meshlet is single-sided.
     *
     * <p>Back-face culling a whole meshlet is only sound when nothing in it needs both sides drawn.
     * The flag is opt-in: a meshlet whose builder was never told otherwise keeps it clear and is
     * never back-face culled, so the worst case of forgetting to set it is a missed optimisation
     * rather than geometry that disappears.
     */
    public static final int SINGLE_SIDED_BIT = 30;

    Meshlet(Orientation orientation, short[] positions, float[] uvs, byte[] quadIndices,
            int[] quadColors, int[] vertexLights, int quadCount, int vertexCount,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean singleSided) {
        if (quadCount > MAX_QUADS) {
            throw new IllegalArgumentException("meshlet has " + quadCount + " quads, max " + MAX_QUADS);
        }
        if (vertexCount > MAX_VERTICES) {
            throw new IllegalArgumentException("meshlet has " + vertexCount + " vertices, max " + MAX_VERTICES);
        }
        this.orientation = orientation;
        this.positions = positions;
        this.uvs = uvs;
        this.quadIndices = quadIndices;
        this.quadColors = quadColors;
        this.vertexLights = vertexLights;
        this.quadCount = quadCount;
        this.vertexCount = vertexCount;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.singleSided = singleSided;
    }

    public Orientation orientation() {
        return this.orientation;
    }

    public int quadCount() {
        return this.quadCount;
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public int triangleCount() {
        return this.quadCount * Quad.TRIANGLES;
    }

    /** Local index of vertex {@code i}, 0..255. */
    public int vertexIndex(int quad, int corner) {
        return this.quadIndices[quad * Quad.VERTICES + corner] & 0xFF;
    }

    public int positionX(int vertex) {
        return this.positions[vertex * 3];
    }

    public int positionY(int vertex) {
        return this.positions[vertex * 3 + 1];
    }

    public int positionZ(int vertex) {
        return this.positions[vertex * 3 + 2];
    }

    public float u(int vertex) {
        return this.uvs[vertex * 2];
    }

    public float v(int vertex) {
        return this.uvs[vertex * 2 + 1];
    }

    public int color(int quad) {
        return this.quadColors[quad];
    }

    /**
     * Packed light and ambient occlusion at vertex {@code vertex}.
     *
     * <p>Per vertex, not per quad. Two corners of the same merged quad can be at opposite ends of
     * the light range, and the rasteriser interpolates between them for free.
     */
    public int light(int vertex) {
        return this.vertexLights[vertex];
    }

    public int minX() {
        return this.minX;
    }

    public int minY() {
        return this.minY;
    }

    public int minZ() {
        return this.minZ;
    }

    public int maxX() {
        return this.maxX;
    }

    public int maxY() {
        return this.maxY;
    }

    public int maxZ() {
        return this.maxZ;
    }

    /**
     * Whether every face in this meshlet can be assumed to be seen from one side only.
     *
     * <p>Required before back-face culling the meshlet as a whole. See {@link #SINGLE_SIDED_BIT}.
     */
    public boolean singleSided() {
        return this.singleSided;
    }

    /**
     * The culling data the GPU gets, packed into 4 bytes.
     *
     * <p>Six axis-aligned bounds at 5 bits each (0..16 section-local) = 30 bits. The compressed
     * meshlet paper manages with 3 bytes per meshlet using a bounding sphere plus a back-face cone
     * (Mlakar, CGF 2024); an AABB costs one extra byte but is <em>exact</em> for axis-aligned voxel
     * geometry rather than conservative, and it needs no cone/apex fields at all because the
     * orientation bucket already gives the normal for free.
     */
    public int packedBounds() {
        return (this.minX & 0x1F)
                | ((this.minY & 0x1F) << 5)
                | ((this.minZ & 0x1F) << 10)
                | ((this.maxX & 0x1F) << 15)
                | ((this.maxY & 0x1F) << 20)
                | ((this.maxZ & 0x1F) << 25)
                | (this.singleSided ? 1 << SINGLE_SIDED_BIT : 0);
    }

    public static int unpackMinX(int packedBounds) {
        return packedBounds & 0x1F;
    }

    public static int unpackMinY(int packedBounds) {
        return (packedBounds >> 5) & 0x1F;
    }

    public static int unpackMinZ(int packedBounds) {
        return (packedBounds >> 10) & 0x1F;
    }

    public static int unpackMaxX(int packedBounds) {
        return (packedBounds >> 15) & 0x1F;
    }

    public static int unpackMaxY(int packedBounds) {
        return (packedBounds >> 20) & 0x1F;
    }

    public static int unpackMaxZ(int packedBounds) {
        return (packedBounds >> 25) & 0x1F;
    }

    /** Bytes this meshlet needs in the position stream. */
    public int positionBytes() {
        return this.vertexCount * POSITION_STRIDE;
    }

    /** Bytes this meshlet needs in the attribute stream. */
    public int attributeBytes() {
        return this.vertexCount * ATTRIBUTE_STRIDE;
    }

    /** Bytes this meshlet needs in the light stream. */
    public int lightBytes() {
        return this.vertexCount * LIGHT_STRIDE;
    }

    /** Bytes this meshlet needs in the index stream. */
    public int indexBytes() {
        return this.quadCount * INDEX_BYTES_PER_QUAD;
    }

    /**
     * Writes the position stream. The buffer must be in little-endian order and positioned at a
     * {@value #POSITION_STRIDE}-byte multiple.
     *
     * <p>The write is strictly sequential, which is not incidental: the destination for chunk
     * geometry is a {@code HOST_VISIBLE} mapping, and AMD's guide is explicit that such memory is
     * uncached and write-combined, so it must only be written with memcpy or sequentially and must
     * never be read back. See {@code docs/notes/10} §2.1.
     */
    public void writePositions(ByteBuffer out) {
        requireOrder(out);
        for (int i = 0; i < this.vertexCount; i++) {
            out.putShort(this.positions[i * 3]);
            out.putShort(this.positions[i * 3 + 1]);
            out.putShort(this.positions[i * 3 + 2]);
            out.putShort((short) 0);
        }
    }

    /**
     * Writes the {@value #CULL_RECORD_BYTES}-byte cull record the compute shader reads at slot
     * {@code i}: the packed bounds, then the section origin as three float bit patterns.
     *
     * <p>The origin is float bits and not the integer it came from, because a section origin is
     * negative for any section west or north of the world origin and the shader recovers it with
     * {@code uintBitsToFloat}. Writing the raw integer would read a negative origin as roughly four
     * billion, which puts the box so far away that the frustum test rejects it -- so the failure
     * would be half the world silently missing rather than a crash. A section origin is a multiple
     * of {@code 16}, comfortably inside the 24-bit exact range of a float, so nothing is lost.
     *
     * <p>Sequential, for the same reason as the other writers: this lands in host-visible memory
     * and that memory is write-combined.
     */
    public void writeRecord(ByteBuffer out, int originX, int originY, int originZ) {
        requireOrder(out);
        out.putInt(this.packedBounds());
        out.putInt(Float.floatToRawIntBits((float) originX));
        out.putInt(Float.floatToRawIntBits((float) originY));
        out.putInt(Float.floatToRawIntBits((float) originZ));
    }

    /**
     * Writes this meshlet's orientation bucket: its {@link Orientation#ordinal()}, 0 to 5.
     *
     * <p>A separate buffer rather than three more bits in the packed bounds word. The word has two
     * spare bits and a bucket needs three, so it would not fit without widening the record -- and
     * the cull shader reads this buffer only for meshlets that survived the frustum test, so
     * keeping it out of the record also keeps the record it does read unconditionally smaller.
     */
    public void writeOrientation(ByteBuffer out) {
        requireOrder(out);
        out.putInt(this.orientation.ordinal());
    }

    /** Writes the attribute stream. */
    public void writeAttributes(ByteBuffer out) {
        requireOrder(out);
        for (int i = 0; i < this.vertexCount; i++) {
            out.putFloat(this.uvs[i * 2]);
            out.putFloat(this.uvs[i * 2 + 1]);
        }
    }

    /**
     * Writes the light stream.
     *
     * <p>Sequential like the others, for the same reason: this lands in host-visible memory and that
     * memory is write-combined.
     */
    public void writeLights(ByteBuffer out) {
        requireOrder(out);
        for (int i = 0; i < this.vertexCount; i++) {
            out.putInt(this.vertexLights[i]);
        }
    }

    /** Writes the index stream, expanding each quad into six 8-bit triangle-list indices. */
    public void writeIndices(ByteBuffer out) {
        for (int q = 0; q < this.quadCount; q++) {
            int base = q * Quad.VERTICES;
            for (int e : QUAD_INDEX_EXPANSION) {
                out.put(this.quadIndices[base + e]);
            }
        }
    }

    private static void requireOrder(ByteBuffer buffer) {
        if (buffer.order() != java.nio.ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("GPU buffers are little-endian");
        }
    }

    @Override
    public String toString() {
        return "Meshlet[" + this.orientation + " quads=" + this.quadCount
                + " verts=" + this.vertexCount
                + " bounds=" + Arrays.toString(new int[]{this.minX, this.minY, this.minZ,
                        this.maxX, this.maxY, this.maxZ}) + "]";
    }
}
