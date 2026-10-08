package net.amdfaster.entity;

import java.util.Arrays;

/**
 * Batches entities by {@code (cell, model)}, so the number of draw calls depends on how the
 * entities are spread out and not on the order they arrived in.
 *
 * <p>{@link EntityBatcher} groups greedily: each instance looks back at the last few same-model
 * batches and joins one if it fits inside the span limit. That works well for a dense pile, which
 * is the case it was written for, and badly for the opposite one. Minecraft's entity list is not in
 * spatial order, so a hundred items scattered across a plains biome arrive interleaved, most of
 * them fail the span test against the recent batches, and each opens a batch of its own. The
 * grouping then depends on the list order, which is not stable across frames -- so the batch count
 * is not merely large, it is unpredictable, and a frame that happened to be cheap stops being.
 *
 * <p>Here a batch is a cell and a model. Two entities in the same cell are within
 * {@value EntityCell#SIZE} blocks on every axis, so the merged bounds are small by construction and
 * no span check is needed. Arriving in any order produces the same batches. The cost is that
 * entities near a cell boundary split across two batches where an optimal packing would use one;
 * that is bounded at eight times optimal, and it buys an order-independent, therefore predictable,
 * draw count.
 *
 * <p>One pass over the entities, one hash and one probe each, nothing allocated after the arrays
 * have grown. Not thread-safe.
 */
public final class SpatialEntityBatcher {

    /**
     * Instances per draw. A cell can hold more than this -- a hopper farm can -- in which case the
     * cell's batch is split and the grid is repointed at the new one.
     */
    public static final int DEFAULT_MAX_INSTANCES = 1024;

    private final int maxInstances;
    private final EntityGrid grid = new EntityGrid();

    private int[] instanceIndices = new int[256];
    private int instanceCount;

    private int[] batchModel = new int[64];
    private int[] batchFirst = new int[64];
    private int[] batchCount = new int[64];
    private float[] batchMinX = new float[64];
    private float[] batchMinY = new float[64];
    private float[] batchMinZ = new float[64];
    private float[] batchMaxX = new float[64];
    private float[] batchMaxY = new float[64];
    private float[] batchMaxZ = new float[64];
    private int batchTotal;

    public SpatialEntityBatcher() {
        this(DEFAULT_MAX_INSTANCES);
    }

    public SpatialEntityBatcher(int maxInstances) {
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be positive: " + maxInstances);
        }
        this.maxInstances = maxInstances;
    }

    /** Batches every entity in the buffer. */
    public SpatialBatchSet build(EntityBuffer entities) {
        reset();
        for (int i = 0; i < entities.size(); i++) {
            add(entities, i);
        }
        return finish();
    }

    /**
     * Batches only the entities a {@link EntityCuller} kept.
     *
     * <p>This is the pairing that makes the cost independent of the world's entity count: the
     * culler bounds how many reach here by what is visible, and this bounds how many draws they
     * become by where they are.
     */
    public SpatialBatchSet build(EntityBuffer entities, EntityCuller culler) {
        reset();
        for (int i = 0; i < culler.survivorCount(); i++) {
            add(entities, culler.survivor(i));
        }
        return finish();
    }

    private void reset() {
        this.grid.clear();
        this.instanceCount = 0;
        this.batchTotal = 0;
    }

    private void add(EntityBuffer entities, int index) {
        long cell = EntityCell.keyOf(entities.x(index), entities.y(index), entities.z(index));
        int model = entities.modelKey(index);

        int batch = this.grid.get(cell, model);
        if (batch < 0 || this.batchCount[batch] >= this.maxInstances) {
            batch = openBatch(model, entities, index);
            this.grid.set(cell, model, batch);
            return;
        }
        appendTo(batch, entities, index);
    }

    private int openBatch(int model, EntityBuffer entities, int index) {
        if (this.batchTotal == this.batchModel.length) {
            growBatches();
        }
        int batch = this.batchTotal++;
        this.batchModel[batch] = model;
        // The batch starts at the current instance count and its bounds start at this entity. A
        // split batch deliberately does not inherit the previous one's bounds: doing so would carry
        // the old extent forward and the merged box would grow with every split.
        this.batchFirst[batch] = this.instanceCount;
        this.batchCount[batch] = 0;
        this.batchMinX[batch] = Float.POSITIVE_INFINITY;
        this.batchMinY[batch] = Float.POSITIVE_INFINITY;
        this.batchMinZ[batch] = Float.POSITIVE_INFINITY;
        this.batchMaxX[batch] = Float.NEGATIVE_INFINITY;
        this.batchMaxY[batch] = Float.NEGATIVE_INFINITY;
        this.batchMaxZ[batch] = Float.NEGATIVE_INFINITY;
        appendTo(batch, entities, index);
        return batch;
    }

    private void appendTo(int batch, EntityBuffer entities, int index) {
        if (this.instanceCount == this.instanceIndices.length) {
            this.instanceIndices = Arrays.copyOf(this.instanceIndices,
                    this.instanceIndices.length << 1);
        }
        this.instanceIndices[this.instanceCount++] = index;
        this.batchCount[batch]++;
        this.batchMinX[batch] = Math.min(this.batchMinX[batch], entities.minX(index));
        this.batchMinY[batch] = Math.min(this.batchMinY[batch], entities.minY(index));
        this.batchMinZ[batch] = Math.min(this.batchMinZ[batch], entities.minZ(index));
        this.batchMaxX[batch] = Math.max(this.batchMaxX[batch], entities.maxX(index));
        this.batchMaxY[batch] = Math.max(this.batchMaxY[batch], entities.maxY(index));
        this.batchMaxZ[batch] = Math.max(this.batchMaxZ[batch], entities.maxZ(index));
    }

    private void growBatches() {
        int next = this.batchModel.length << 1;
        this.batchModel = Arrays.copyOf(this.batchModel, next);
        this.batchFirst = Arrays.copyOf(this.batchFirst, next);
        this.batchCount = Arrays.copyOf(this.batchCount, next);
        this.batchMinX = Arrays.copyOf(this.batchMinX, next);
        this.batchMinY = Arrays.copyOf(this.batchMinY, next);
        this.batchMinZ = Arrays.copyOf(this.batchMinZ, next);
        this.batchMaxX = Arrays.copyOf(this.batchMaxX, next);
        this.batchMaxY = Arrays.copyOf(this.batchMaxY, next);
        this.batchMaxZ = Arrays.copyOf(this.batchMaxZ, next);
    }

    private SpatialBatchSet finish() {
        EntityBatch[] batches = new EntityBatch[this.batchTotal];
        for (int i = 0; i < this.batchTotal; i++) {
            batches[i] = new EntityBatch(this.batchModel[i], this.batchFirst[i], this.batchCount[i],
                    this.batchMinX[i], this.batchMinY[i], this.batchMinZ[i],
                    this.batchMaxX[i], this.batchMaxY[i], this.batchMaxZ[i]);
        }
        return new SpatialBatchSet(this.instanceIndices, this.instanceCount, batches,
                this.batchTotal);
    }

    /** Batches opened so far in the current pass. */
    public int batchCount() {
        return this.batchTotal;
    }

    /** Instances assigned so far in the current pass. */
    public int instanceCount() {
        return this.instanceCount;
    }
}
