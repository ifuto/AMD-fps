package net.amdfaster.entity;

import java.util.List;

/**
 * The output of a batching pass: instances reordered so each batch is contiguous, and the batch
 * records that address those runs.
 *
 * @param instances every instance, grouped; {@code batch.firstInstance()} indexes into this
 * @param batches   one record per draw call, in draw order
 */
public record EntityBatchSet(List<EntityInstance> instances, List<EntityBatch> batches) {

    public int instanceCount() {
        return this.instances.size();
    }

    /** Draw calls needed, which is the number this whole exercise exists to reduce. */
    public int drawCallCount() {
        return this.batches.size();
    }

    /** Bytes of instance data to upload. */
    public int instanceDataBytes() {
        return this.instances.size() * EntityGpuLayout.INSTANCE_BYTES;
    }

    /** True when the batch runs cover every instance exactly once, with no gap or overlap. */
    public boolean isContiguous() {
        int expected = 0;
        for (EntityBatch batch : this.batches) {
            if (batch.firstInstance() != expected) {
                return false;
            }
            expected += batch.instanceCount();
        }
        return expected == this.instances.size();
    }
}
