package net.amdfaster.entity;

/**
 * A group of same-model instances that draw as one call.
 *
 * <p>Two things make this worth having. The draw itself becomes
 * {@code vkCmdDrawIndexed(..., instanceCount, 1, ...)} instead of {@code instanceCount} separate
 * draws, which is the whole point: on AMD the per-draw cost is command-buffer space and a wave of
 * setup work, and a hundred dropped cobblestone in a farm hopper is a hundred of those for geometry
 * that is one model. The merged bounds then let the frustum test run once for the group instead of
 * once per entity, which is where the CPU side of entity rendering actually goes.
 *
 * @param modelKey       the model and texture the whole batch shares
 * @param firstInstance  index of this batch's first instance in the batched instance array
 * @param instanceCount  how many instances draw in the call
 * @param minX,minY,minZ minimum corner of the box enclosing every instance
 * @param maxX,maxY,maxZ maximum corner of that box
 */
public record EntityBatch(
        int modelKey,
        int firstInstance,
        int instanceCount,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
) {

    public EntityBatch {
        if (instanceCount <= 0) {
            throw new IllegalArgumentException("an empty batch is never drawn: " + instanceCount);
        }
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("inverted bounds");
        }
    }

    public float spanX() {
        return this.maxX - this.minX;
    }

    public float spanY() {
        return this.maxY - this.minY;
    }

    public float spanZ() {
        return this.maxZ - this.minZ;
    }

    /** Centre of the merged bounds, used for distance sort. */
    public float centerX() {
        return (this.minX + this.maxX) * 0.5f;
    }

    public float centerY() {
        return (this.minY + this.maxY) * 0.5f;
    }

    public float centerZ() {
        return (this.minZ + this.maxZ) * 0.5f;
    }

    /** Offset of this batch's instance data in a buffer of {@link EntityGpuLayout} records. */
    public long instanceDataOffset() {
        return (long) this.firstInstance * EntityGpuLayout.INSTANCE_BYTES;
    }

    /**
     * Whether a box intersects this batch's merged bounds.
     *
     * <p>Used by the frustum test. A conservative box is correct here: rejecting a batch whose box
     * is off screen is safe because every instance is inside the box, and keeping one that is
     * partially off screen only costs the instances that the rasteriser discards anyway.
     */
    public boolean intersects(float otherMinX, float otherMinY, float otherMinZ,
                              float otherMaxX, float otherMaxY, float otherMaxZ) {
        return this.maxX >= otherMinX && this.minX <= otherMaxX
                && this.maxY >= otherMinY && this.minY <= otherMaxY
                && this.maxZ >= otherMinZ && this.minZ <= otherMaxZ;
    }
}
