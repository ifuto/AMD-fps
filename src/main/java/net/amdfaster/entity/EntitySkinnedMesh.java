package net.amdfaster.entity;

import java.util.Arrays;

/**
 * The vertex format the instanced entity shader reads, and the code that fills it.
 *
 * <p>Vanilla tessellates every entity model on the CPU every frame: it walks the {@code ModelPart}
 * tree, transforms each vertex by the accumulated part matrices, and pushes the result into a buffer
 * that is uploaded and thrown away. An instanced draw cannot do that, because one submission now
 * covers many entities with different poses, so the vertices have to stay in model space and carry
 * the bone indices and weights that let the GPU pose them instead.
 *
 * <p>That is what this class holds. The mesh is built once per model and uploaded once; the per-frame
 * cost moves to {@link BonePalette}, which is where it is far cheaper.
 *
 * <h2>The layout, and why it is 64 bytes</h2>
 *
 * <pre>
 * offset  size  field         shader input
 *      0    12  position      layout(location = 0) in vec3
 *     12    12  normal        layout(location = 1) in vec3
 *     24     8  uv            layout(location = 2) in vec2
 *     32    16  boneWeights   layout(location = 3) in vec4
 *     48    16  boneIndices   layout(location = 4) in ivec4
 * </pre>
 *
 * <p>Four bone influences is what vanilla's box-based parts actually need, since a vertex belongs to
 * exactly one part. The other three slots exist so the format does not have to change for models with
 * genuine skinning, and because a fixed-width record lets the upload be one contiguous copy.
 *
 * <p>64 bytes is wide. Packing the indices as four bytes and the weights as four normalised bytes
 * would bring it to 40, and the normals could be oct-encoded for less again. That is worth doing
 * eventually but it is not the bottleneck: this buffer is uploaded once per model, while the instance
 * and bone buffers are rewritten every frame. Optimising the wrong one of the three would be a waste.
 *
 * <p>Not thread safe to build. Immutable once {@link #complete()} has been called.
 */
public final class EntitySkinnedMesh {

    public static final int VERTEX_STRIDE = 64;

    public static final int OFFSET_POSITION = 0;
    public static final int OFFSET_NORMAL = 12;
    public static final int OFFSET_UV = 24;
    public static final int OFFSET_BONE_WEIGHTS = 32;
    public static final int OFFSET_BONE_INDICES = 48;

    /** Influences per vertex. Vanilla parts need one; three more are spare. */
    public static final int INFLUENCES_PER_VERTEX = 4;

    public static final int FLOATS_PER_VERTEX = VERTEX_STRIDE / 4;

    private static final int DEFAULT_CAPACITY = 256;

    /** Weights must sum to this, within {@link #WEIGHT_TOLERANCE}. */
    public static final float WEIGHT_SUM = 1.0f;

    /**
     * How far the weights may be from summing to one.
     *
     * <p>Loose enough to absorb float rounding from four divisions, tight enough that a vertex with a
     * dropped influence shows up rather than quietly shrinking.
     */
    public static final float WEIGHT_TOLERANCE = 1e-4f;

    private float[] vertices;
    private int capacity;
    private int vertexCount;
    private boolean complete;

    public EntitySkinnedMesh() {
        this(DEFAULT_CAPACITY);
    }

    public EntitySkinnedMesh(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least one: " + capacity);
        }
        this.capacity = capacity;
        this.vertices = new float[capacity * FLOATS_PER_VERTEX];
    }

    /**
     * Adds one vertex.
     *
     * @param bone the influencing bone indices. Unused slots must be zero.
     * @param weight the influencing weights. They are normalised here rather than trusted, because a
     *     set that sums to 0.99 makes the vertex drift toward the origin as the animation advances,
     *     and that shows up as a model that slowly collapses rather than as an error.
     * @return the vertex index
     */
    public int addVertex(float x, float y, float z,
                         float nx, float ny, float nz,
                         float u, float v,
                         int[] bone, float[] weight) {
        if (this.complete) {
            throw new IllegalStateException("the mesh is complete and cannot take more vertices");
        }
        if (bone.length != INFLUENCES_PER_VERTEX || weight.length != INFLUENCES_PER_VERTEX) {
            throw new IllegalArgumentException(
                    "need exactly " + INFLUENCES_PER_VERTEX + " influences, got "
                            + bone.length + " indices and " + weight.length + " weights");
        }
        if (this.vertexCount == this.capacity) {
            grow();
        }
        int base = this.vertexCount * FLOATS_PER_VERTEX;
        float[] dst = this.vertices;
        dst[base] = x;
        dst[base + 1] = y;
        dst[base + 2] = z;
        dst[base + 3] = nx;
        dst[base + 4] = ny;
        dst[base + 5] = nz;
        dst[base + 6] = u;
        dst[base + 7] = v;

        // Normalise, then put the rounding error on the largest weight so the sum is exactly one.
        // Putting it on the largest keeps the relative error smallest, and a weight of exactly 1.0
        // for a single-influence vertex -- the common case for vanilla boxes -- stays exactly 1.0.
        float total = 0f;
        for (int i = 0; i < INFLUENCES_PER_VERTEX; i++) {
            float w = weight[i];
            if (w < 0f || Float.isNaN(w)) {
                throw new IllegalArgumentException("weight " + i + " is " + w);
            }
            total += w;
        }
        if (total <= 0f) {
            throw new IllegalArgumentException(
                    "weights sum to " + total + ", so the vertex belongs to no bone and would be "
                            + "drawn at the model origin for every entity");
        }
        int largest = 0;
        float running = 0f;
        for (int i = 0; i < INFLUENCES_PER_VERTEX; i++) {
            float normalised = weight[i] / total;
            dst[base + 8 + i] = normalised;
            running += normalised;
            if (weight[i] > weight[largest]) {
                largest = i;
            }
        }
        dst[base + 8 + largest] = WEIGHT_SUM - (running - dst[base + 8 + largest]);

        for (int i = 0; i < INFLUENCES_PER_VERTEX; i++) {
            int index = bone[i];
            if (index < 0 || index >= BoneRig.MAX_BONES) {
                throw new IllegalArgumentException(
                        "bone index " + index + " is outside the palette's " + BoneRig.MAX_BONES);
            }
            // Stored as the float whose bits are the integer, which is how the shader reads it back
            // through an ivec4 attribute. A numeric cast would work here but the round trip through
            // floatBitsToInt on the GPU is what actually happens, so the two have to agree.
            dst[base + 12 + i] = Float.intBitsToFloat(index);
        }
        this.vertexCount++;
        return this.vertexCount - 1;
    }

    /** Convenience for the common case of a vertex belonging to exactly one bone. */
    public int addVertex(float x, float y, float z,
                         float nx, float ny, float nz,
                         float u, float v,
                         int bone) {
        return addVertex(x, y, z, nx, ny, nz, u, v,
                new int[] {bone, 0, 0, 0}, new float[] {1f, 0f, 0f, 0f});
    }

    /**
     * Marks the mesh finished.
     *
     * <p>Separate from building because the upload has to know the used length, and because a mesh
     * that keeps accepting vertices cannot be treated as immutable by the caching layer.
     */
    public void complete() {
        this.complete = true;
    }

    public boolean isComplete() {
        return this.complete;
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public int capacity() {
        return this.capacity;
    }

    /** Bytes actually holding vertex data, which is what gets uploaded. */
    public int usedBytes() {
        return this.vertexCount * VERTEX_STRIDE;
    }

    public float[] backingArray() {
        return this.vertices;
    }

    public float field(int vertexIndex, int byteOffset, int component) {
        if (vertexIndex < 0 || vertexIndex >= this.vertexCount) {
            throw new IndexOutOfBoundsException(
                    "vertex " + vertexIndex + " of " + this.vertexCount);
        }
        if (byteOffset < 0 || byteOffset >= VERTEX_STRIDE || byteOffset % 4 != 0) {
            throw new IllegalArgumentException("offset must be a multiple of four: " + byteOffset);
        }
        if (component < 0 || component > 3) {
            throw new IllegalArgumentException("component out of range: " + component);
        }
        return this.vertices[vertexIndex * FLOATS_PER_VERTEX + byteOffset / 4 + component];
    }

    /** The weight sum of one vertex, which has to be one. */
    public float weightSum(int vertexIndex) {
        float sum = 0f;
        for (int i = 0; i < INFLUENCES_PER_VERTEX; i++) {
            sum += field(vertexIndex, OFFSET_BONE_WEIGHTS, i);
        }
        return sum;
    }

    /** The bone index of one influence, read back the way the shader reads it. */
    public int boneIndex(int vertexIndex, int influence) {
        return Float.floatToRawIntBits(field(vertexIndex, OFFSET_BONE_INDICES, influence));
    }

    private void grow() {
        this.capacity *= 2;
        this.vertices = Arrays.copyOf(this.vertices, this.capacity * FLOATS_PER_VERTEX);
    }
}
