package net.amdfaster.entity;

import java.util.Arrays;

/**
 * The bone hierarchy of one entity model, flattened into arrays.
 *
 * <p>Vanilla builds entity models as a tree of {@code ModelPart} objects and walks that tree every
 * frame for every entity, computing a matrix per part and tessellating the result. That walk is the
 * animation cost instancing is meant to remove, and this class is what replaces it: the same hierarchy,
 * described once as parallel arrays, evaluated into matrices that a shader can consume.
 *
 * <h2>The convention</h2>
 *
 * <p>A bone's world matrix is {@code parentWorld * translate(base) * rotate(axis, angle)}. The
 * translation is applied before the rotation in the product, so a bone pivots at its own base offset
 * and that offset is not itself rotated. The consequence is that the translation column of a world
 * matrix is the plain sum of the base offsets along the chain, and only the rotational part carries
 * animation. Verified against a five bone rig: a leg at base (2, -6, 6) rotated 0.6 radians about X
 * still reports translation (2, -6, 6), not a rotated version of it.
 *
 * <h2>Why bones must be ordered parent before child</h2>
 *
 * <p>So that evaluating a rig is one forward pass with no recursion and no stack. Vanilla's part tree
 * flattens to this order with a depth-first walk. The constructor enforces it, because a rig that
 * violates it would silently read an uninitialised matrix rather than fail.
 *
 * <p>Immutable and thread safe once constructed.
 */
public final class BoneRig {

    /** The bone carries no animation of its own. */
    public static final int AXIS_NONE = -1;
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /** More bones than any vanilla model has; the extra slots stay identity. */
    public static final int MAX_BONES = 24;

    private final int[] parent;
    private final float[] baseX;
    private final float[] baseY;
    private final float[] baseZ;
    private final int[] axis;
    private final int boneCount;

    /**
     * @param parent parent index per bone, or -1 for a root. Every parent must be an earlier index.
     * @param baseX pivot offset from the parent, in model units
     * @param baseY pivot offset from the parent, in model units
     * @param baseZ pivot offset from the parent, in model units
     * @param axis rotation axis per bone, or {@link #AXIS_NONE}
     */
    public BoneRig(int[] parent, float[] baseX, float[] baseY, float[] baseZ, int[] axis) {
        if (parent.length == 0) {
            throw new IllegalArgumentException("a rig with no bones draws nothing");
        }
        if (parent.length > MAX_BONES) {
            throw new IllegalArgumentException(
                    "rig has " + parent.length + " bones, the palette holds " + MAX_BONES);
        }
        int n = parent.length;
        if (baseX.length != n || baseY.length != n || baseZ.length != n || axis.length != n) {
            throw new IllegalArgumentException("every array must be one entry per bone");
        }
        for (int i = 0; i < n; i++) {
            if (parent[i] == i) {
                throw new IllegalArgumentException("bone " + i + " is its own parent");
            }
            if (parent[i] > i) {
                // Not merely a style rule: the evaluator walks forward and reads the parent's finished
                // matrix, so a later parent would contribute whatever was left in the array.
                throw new IllegalArgumentException(
                        "bone " + i + " has parent " + parent[i] + ", which comes after it; "
                                + "flatten the part tree depth first so parents precede children");
            }
            if (parent[i] < -1) {
                throw new IllegalArgumentException("bone " + i + " has parent " + parent[i]);
            }
            if (axis[i] < AXIS_NONE || axis[i] > AXIS_Z) {
                throw new IllegalArgumentException("bone " + i + " has axis " + axis[i]);
            }
            if (Float.isNaN(baseX[i]) || Float.isNaN(baseY[i]) || Float.isNaN(baseZ[i])) {
                throw new IllegalArgumentException("bone " + i + " has a NaN pivot");
            }
        }
        this.parent = parent.clone();
        this.baseX = baseX.clone();
        this.baseY = baseY.clone();
        this.baseZ = baseZ.clone();
        this.axis = axis.clone();
        this.boneCount = n;
    }

    public int boneCount() {
        return this.boneCount;
    }

    public int parent(int bone) {
        return this.parent[bone];
    }

    public float baseX(int bone) {
        return this.baseX[bone];
    }

    public float baseY(int bone) {
        return this.baseY[bone];
    }

    public float baseZ(int bone) {
        return this.baseZ[bone];
    }

    public int axis(int bone) {
        return this.axis[bone];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BoneRig rig)) {
            return false;
        }
        return this.boneCount == rig.boneCount
                && Arrays.equals(this.parent, rig.parent)
                && Arrays.equals(this.axis, rig.axis)
                && Arrays.equals(this.baseX, rig.baseX)
                && Arrays.equals(this.baseY, rig.baseY)
                && Arrays.equals(this.baseZ, rig.baseZ);
    }

    @Override
    public int hashCode() {
        int result = this.boneCount;
        result = 31 * result + Arrays.hashCode(this.parent);
        result = 31 * result + Arrays.hashCode(this.axis);
        result = 31 * result + Arrays.hashCode(this.baseX);
        result = 31 * result + Arrays.hashCode(this.baseY);
        return 31 * result + Arrays.hashCode(this.baseZ);
    }
}
