package net.amdfaster.entity;

/**
 * Evaluates every bone of every visible entity once per frame into one contiguous buffer.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>An instanced draw collapses N entities into one submission, but each entity still has its own
 * pose, so the pose has to reach the shader per instance. The obvious way is to compute bone matrices
 * in the vertex shader from the animation scalars. That recomputes the same dozen or two matrices once
 * per vertex: a 120 vertex model with 500 entities on screen evaluates 720,000 matrices a frame where
 * 6,000 would do, and the evaluation is dominated by {@code sin} and {@code cos}, which run at a
 * quarter of full rate on RDNA and an eighth on Xe. On the hardware this mod targets that is the
 * expensive way to do it.
 *
 * <p>So the matrices are evaluated here, once per entity per bone, and the vertex shader only blends
 * between them, which is full-rate multiply-add with no transcendentals at all.
 *
 * <h2>What it costs instead</h2>
 *
 * <p>Upload bandwidth. Twenty-four bones is 1.5 KB per entity, so 500 entities is 0.73 MB a frame.
 * That is real and it is the trade: a fixed bandwidth cost against a per-vertex arithmetic cost that
 * grows with model complexity and entity count together. At the entity counts where instancing matters
 * at all, the arithmetic side is much larger.
 *
 * <p>The better long-term answer is a compute pass doing this same evaluation on the GPU, which keeps
 * the 120-fold reduction in matrix evaluations without the upload. This class is written so that the
 * evaluation is a pure function of rig and angles, which is what a compute shader needs in order to
 * replace it without the rest of the pipeline noticing.
 *
 * <h2>Layout</h2>
 *
 * <p>Column-major {@code mat4}, 16 floats, matching GLSL. Instance {@code i}, bone {@code b} starts at
 * float {@code (i * MAX_BONES + b) * 16}. Bones past a rig's own count are left as identity so the
 * shader can index uniformly without a bounds check per vertex.
 *
 * <p>Not thread safe.
 */
public final class BonePalette {

    /** Bones per instance, whether the rig uses them all or not. */
    public static final int MAX_BONES = BoneRig.MAX_BONES;

    public static final int FLOATS_PER_MATRIX = 16;

    /** Floats of palette per instance, including the unused trailing bones. */
    public static final int FLOATS_PER_INSTANCE = MAX_BONES * FLOATS_PER_MATRIX;

    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * 4;

    private static final int DEFAULT_CAPACITY = 256;

    private float[] matrices;
    private int capacity;

    private long matrixEvaluations;

    public BonePalette() {
        this(DEFAULT_CAPACITY);
    }

    public BonePalette(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least one: " + capacity);
        }
        this.capacity = capacity;
        this.matrices = new float[capacity * FLOATS_PER_INSTANCE];
        fillIdentityRange(0, this.matrices.length);
    }

    /**
     * Writes one instance's bones.
     *
     * @param instanceIndex which instance this pose belongs to
     * @param rig the bone hierarchy
     * @param angle the rotation angle per bone, in radians. Only entries for bones whose axis is not
     *     {@link BoneRig#AXIS_NONE} are read.
     * @return the float offset this instance's palette starts at
     */
    public int writeInstance(int instanceIndex, BoneRig rig, float[] angle) {
        if (instanceIndex < 0) {
            throw new IllegalArgumentException("instance index must not be negative: " + instanceIndex);
        }
        if (instanceIndex >= this.capacity) {
            grow(instanceIndex + 1);
        }
        if (angle.length < rig.boneCount()) {
            throw new IllegalArgumentException(
                    "need one angle per bone: " + angle.length + " given, " + rig.boneCount() + " needed");
        }
        int base = instanceIndex * FLOATS_PER_INSTANCE;
        int bones = rig.boneCount();

        for (int bone = 0; bone < bones; bone++) {
            int at = base + bone * FLOATS_PER_MATRIX;
            float bx = rig.baseX(bone);
            float by = rig.baseY(bone);
            float bz = rig.baseZ(bone);
            float a = angle[bone];
            int axis = rig.axis(bone);

            // Build the local matrix: translate to the pivot, then rotate about it. Sixteen named
            // locals rather than a float[16], because at 500 entities and 24 bones this runs 12,000
            // times a frame and a fresh array each time is 12,000 allocations the collector has to
            // chase for no reason.
            float l0, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11;
            if (axis == BoneRig.AXIS_NONE) {
                l0 = 1; l1 = 0; l2 = 0; l3 = 0;
                l4 = 0; l5 = 1; l6 = 0; l7 = 0;
                l8 = 0; l9 = 0; l10 = 1; l11 = 0;
            } else {
                float c = (float) Math.cos(a);
                float sn = (float) Math.sin(a);
                if (axis == BoneRig.AXIS_X) {
                    l0 = 1; l1 = 0; l2 = 0; l3 = 0;
                    l4 = 0; l5 = c; l6 = sn; l7 = 0;
                    l8 = 0; l9 = -sn; l10 = c; l11 = 0;
                } else if (axis == BoneRig.AXIS_Y) {
                    l0 = c; l1 = 0; l2 = -sn; l3 = 0;
                    l4 = 0; l5 = 1; l6 = 0; l7 = 0;
                    l8 = sn; l9 = 0; l10 = c; l11 = 0;
                } else {
                    l0 = c; l1 = sn; l2 = 0; l3 = 0;
                    l4 = -sn; l5 = c; l6 = 0; l7 = 0;
                    l8 = 0; l9 = 0; l10 = 1; l11 = 0;
                }
            }
            // The rotation is about the pivot, so it does not move the pivot: the translation column
            // is the base offset unchanged, and only the rotational block carries the animation.
            float l12 = bx;
            float l13 = by;
            float l14 = bz;
            float l15 = 1;

            int parent = rig.parent(bone);
            if (parent < 0) {
                this.matrices[at] = l0;
                this.matrices[at + 1] = l1;
                this.matrices[at + 2] = l2;
                this.matrices[at + 3] = l3;
                this.matrices[at + 4] = l4;
                this.matrices[at + 5] = l5;
                this.matrices[at + 6] = l6;
                this.matrices[at + 7] = l7;
                this.matrices[at + 8] = l8;
                this.matrices[at + 9] = l9;
                this.matrices[at + 10] = l10;
                this.matrices[at + 11] = l11;
                this.matrices[at + 12] = l12;
                this.matrices[at + 13] = l13;
                this.matrices[at + 14] = l14;
                this.matrices[at + 15] = l15;
                continue;
            }

            int pat = base + parent * FLOATS_PER_MATRIX;
            float[] p = this.matrices;
            float p0 = p[pat], p1 = p[pat + 1], p2 = p[pat + 2], p3 = p[pat + 3];
            float p4 = p[pat + 4], p5 = p[pat + 5], p6 = p[pat + 6], p7 = p[pat + 7];
            float p8 = p[pat + 8], p9 = p[pat + 9], p10 = p[pat + 10], p11 = p[pat + 11];
            float p12 = p[pat + 12], p13 = p[pat + 13], p14 = p[pat + 14], p15 = p[pat + 15];

            // Column-major parent * local. Result column c is the parent applied to local column c,
            // so each of the four blocks below reads only its own column of the local matrix and
            // never a value this product has already written.
            this.matrices[at] = p0 * l0 + p4 * l1 + p8 * l2 + p12 * l3;
            this.matrices[at + 1] = p1 * l0 + p5 * l1 + p9 * l2 + p13 * l3;
            this.matrices[at + 2] = p2 * l0 + p6 * l1 + p10 * l2 + p14 * l3;
            this.matrices[at + 3] = p3 * l0 + p7 * l1 + p11 * l2 + p15 * l3;

            this.matrices[at + 4] = p0 * l4 + p4 * l5 + p8 * l6 + p12 * l7;
            this.matrices[at + 5] = p1 * l4 + p5 * l5 + p9 * l6 + p13 * l7;
            this.matrices[at + 6] = p2 * l4 + p6 * l5 + p10 * l6 + p14 * l7;
            this.matrices[at + 7] = p3 * l4 + p7 * l5 + p11 * l6 + p15 * l7;

            this.matrices[at + 8] = p0 * l8 + p4 * l9 + p8 * l10 + p12 * l11;
            this.matrices[at + 9] = p1 * l8 + p5 * l9 + p9 * l10 + p13 * l11;
            this.matrices[at + 10] = p2 * l8 + p6 * l9 + p10 * l10 + p14 * l11;
            this.matrices[at + 11] = p3 * l8 + p7 * l9 + p11 * l10 + p15 * l11;

            this.matrices[at + 12] = p0 * l12 + p4 * l13 + p8 * l14 + p12 * l15;
            this.matrices[at + 13] = p1 * l12 + p5 * l13 + p9 * l14 + p13 * l15;
            this.matrices[at + 14] = p2 * l12 + p6 * l13 + p10 * l14 + p14 * l15;
            this.matrices[at + 15] = p3 * l12 + p7 * l13 + p11 * l14 + p15 * l15;
            this.matrixEvaluations++;
        }
        return base;
    }

    private void fillIdentityRange(int from, int to) {
        for (int i = from; i < to; i += FLOATS_PER_MATRIX) {
            if (i + FLOATS_PER_MATRIX > to) {
                break;
            }
            for (int k = 0; k < FLOATS_PER_MATRIX; k++) {
                this.matrices[i + k] = 0f;
            }
            this.matrices[i] = 1f;
            this.matrices[i + 5] = 1f;
            this.matrices[i + 10] = 1f;
            this.matrices[i + 15] = 1f;
        }
    }

    private void grow(int needed) {
        int newCapacity = this.capacity;
        while (newCapacity < needed) {
            newCapacity *= 2;
        }
        float[] grown = new float[newCapacity * FLOATS_PER_INSTANCE];
        System.arraycopy(this.matrices, 0, grown, 0, this.matrices.length);
        this.matrices = grown;
        fillIdentityRange(this.capacity * FLOATS_PER_INSTANCE, grown.length);
        this.capacity = newCapacity;
    }

    public float[] backingArray() {
        return this.matrices;
    }

    public int capacity() {
        return this.capacity;
    }

    public float matrix(int instanceIndex, int bone, int column, int row) {
        return this.matrices[instanceIndex * FLOATS_PER_INSTANCE + bone * FLOATS_PER_MATRIX
                + column * 4 + row];
    }

    /** Matrix multiplications performed. One per non-root bone per instance written. */
    public long matrixEvaluations() {
        return this.matrixEvaluations;
    }
}
