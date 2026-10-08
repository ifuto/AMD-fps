package net.amdfaster.mesh;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates quads of one {@link Orientation} into meshlets.
 *
 * <p>Corners are deduplicated on (position, uv) as they arrive. The hit rate is low for
 * one-block quads, because every block face carries its own 0..1 uv and so the corner shared by
 * two neighbouring blocks still gets two different uv values. That is correct -- merging them
 * would smear the texture -- and it is why greedy meshing matters more than deduplication here:
 * fewer, larger quads are the actual saving.
 *
 * <p>Follow-up worth measuring: store a uv origin and scale per quad instead of a uv per corner.
 * Positions alone would then be the dedup key, adjacent quads really would share corners, and the
 * attribute stream would shrink -- at the cost of an SSBO lookup per vertex.
 *
 * <p>Not thread-safe and not intended to be. Meshing runs on the chunk-build threads; the result is
 * handed to the render thread as an immutable {@link Meshlet}.
 */
public final class MeshletBuilder {

    /**
     * What makes two corners the same vertex.
     *
     * <p>Light is part of the key, and leaving it out is a subtle corruption rather than a visible
     * one. Two corners can share a position and a uv and still be lit differently -- the corner
     * where a lit face meets a shaded one is exactly that -- and merging them would give both
     * whichever light arrived first. The seam would then depend on the order the mesher happened to
     * visit the two faces.
     */
    private record VertexKey(short x, short y, short z, float u, float v, int light) {
    }

    private final Orientation orientation;
    private final Map<VertexKey, Integer> vertexIndex = new HashMap<>();

    private final short[] positions = new short[Meshlet.MAX_VERTICES * 3];
    private final float[] uvs = new float[Meshlet.MAX_VERTICES * 2];
    private final byte[] quadIndices = new byte[Meshlet.MAX_QUADS * Quad.VERTICES];
    private final int[] quadColors = new int[Meshlet.MAX_QUADS];
    private final int[] vertexLights = new int[Meshlet.MAX_VERTICES];

    private int vertexCount;
    private int quadCount;
    private int minX = 16;
    private int minY = 16;
    private int minZ = 16;
    private int maxX;
    private int maxY;
    private int maxZ;

    public MeshletBuilder(Orientation orientation) {
        this.orientation = orientation;
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

    public boolean isEmpty() {
        return this.quadCount == 0;
    }

    /**
     * Whether another quad fits.
     *
     * <p>Only the quad count has to be checked: 62 quads x 4 corners = 248, which is inside the 256
     * that an 8-bit index can address, so the vertex limit cannot be reached while the quad limit
     * holds.
     */
    public boolean isFull() {
        return this.quadCount >= Meshlet.MAX_QUADS;
    }

    public void add(Quad quad) {
        if (quad.orientation() != this.orientation) {
            throw new IllegalArgumentException("quad faces " + quad.orientation()
                    + " but this builder collects " + this.orientation);
        }
        if (quad.isDegenerate()) {
            throw new IllegalArgumentException("degenerate quad: " + quad);
        }
        if (this.isFull()) {
            throw new IllegalStateException("meshlet is full after " + Meshlet.MAX_QUADS + " quads");
        }

        int base = this.quadCount * Quad.VERTICES;
        for (int corner = 0; corner < Quad.VERTICES; corner++) {
            short x = (short) quad.cornerX(corner);
            short y = (short) quad.cornerY(corner);
            short z = (short) quad.cornerZ(corner);
            float u = quad.cornerU(corner);
            float v = quad.cornerV(corner);
            int light = quad.cornerLight(corner);

            VertexKey key = new VertexKey(x, y, z, u, v, light);
            Integer existing = this.vertexIndex.get(key);
            int index;
            if (existing != null) {
                index = existing;
            } else {
                index = this.vertexCount++;
                this.positions[index * 3] = x;
                this.positions[index * 3 + 1] = y;
                this.positions[index * 3 + 2] = z;
                this.uvs[index * 2] = u;
                this.uvs[index * 2 + 1] = v;
                this.vertexLights[index] = light;
                this.vertexIndex.put(key, index);
            }
            this.quadIndices[base + corner] = (byte) index;

            if (x < this.minX) {
                this.minX = x;
            }
            if (y < this.minY) {
                this.minY = y;
            }
            if (z < this.minZ) {
                this.minZ = z;
            }
            if (x > this.maxX) {
                this.maxX = x;
            }
            if (y > this.maxY) {
                this.maxY = y;
            }
            if (z > this.maxZ) {
                this.maxZ = z;
            }
        }

        this.quadColors[this.quadCount] = quad.color();
        this.quadCount++;
    }

    /** Produces the meshlet and clears the builder so it can start the next one. */
    public Meshlet build() {
        if (this.quadCount == 0) {
            throw new IllegalStateException("nothing to build");
        }

        Meshlet meshlet = new Meshlet(this.orientation,
                Arrays.copyOf(this.positions, this.vertexCount * 3),
                Arrays.copyOf(this.uvs, this.vertexCount * 2),
                Arrays.copyOf(this.quadIndices, this.quadCount * Quad.VERTICES),
                Arrays.copyOf(this.quadColors, this.quadCount),
                Arrays.copyOf(this.vertexLights, this.vertexCount),
                this.quadCount, this.vertexCount,
                this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);

        this.reset();
        return meshlet;
    }

    public void reset() {
        this.vertexIndex.clear();
        this.vertexCount = 0;
        this.quadCount = 0;
        this.minX = 16;
        this.minY = 16;
        this.minZ = 16;
        this.maxX = 0;
        this.maxY = 0;
        this.maxZ = 0;
    }
}
