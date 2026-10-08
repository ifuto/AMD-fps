package net.amdfaster.entity;

/**
 * The output of a spatial batching pass: entity indices reordered so each batch is contiguous, and
 * the batch records that address those runs.
 *
 * <p>Separate from {@link EntityBatchSet} because it carries indices into an {@link EntityBuffer}
 * rather than {@link EntityInstance} records. Materialising a record per entity here would undo
 * what the buffer was built for, and the indices are all the upload stage needs: it reads eight
 * primitives out of the buffer and writes thirty-two bytes into the instance buffer.
 *
 * <p>The arrays are owned by the batcher and reused across frames, so the counts are explicit
 * rather than implied by the array lengths.
 */
public final class SpatialBatchSet {

    private final int[] instanceIndices;
    private final int instanceCount;
    private final EntityBatch[] batches;
    private final int batchCount;

    SpatialBatchSet(int[] instanceIndices, int instanceCount, EntityBatch[] batches, int batchCount) {
        this.instanceIndices = instanceIndices;
        this.instanceCount = instanceCount;
        this.batches = batches;
        this.batchCount = batchCount;
    }

    /** Draw calls needed, which is the number this whole exercise exists to reduce. */
    public int drawCallCount() {
        return this.batchCount;
    }

    public int instanceCount() {
        return this.instanceCount;
    }

    /** The index into the source {@link EntityBuffer} of the i-th instance, in batch order. */
    public int instanceIndex(int i) {
        return this.instanceIndices[i];
    }

    public EntityBatch batch(int i) {
        return this.batches[i];
    }

    /** Bytes of instance data to upload. */
    public int instanceDataBytes() {
        return this.instanceCount * EntityGpuLayout.INSTANCE_BYTES;
    }

    /** True when the batch runs cover every instance exactly once, with no gap or overlap. */
    public boolean isContiguous() {
        int expected = 0;
        for (int i = 0; i < this.batchCount; i++) {
            if (this.batches[i].firstInstance() != expected) {
                return false;
            }
            expected += this.batches[i].instanceCount();
        }
        return expected == this.instanceCount;
    }

    /**
     * Every batch's model, in draw order, for tests that compare two batching strategies without
     * depending on the order the batches happened to be opened.
     */
    public String shape() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < this.batchCount; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(this.batches[i].modelKey()).append('@').append(this.batches[i].instanceCount());
        }
        return out.toString();
    }
}
