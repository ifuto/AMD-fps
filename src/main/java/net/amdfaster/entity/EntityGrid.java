package net.amdfaster.entity;

import java.util.Arrays;

/**
 * An open-addressed map from {@code (cell, model)} to a batch index.
 *
 * <p>This is the index that lets batching run in one pass over the entities with a constant cost
 * each. The obvious implementation is a {@code HashMap<Integer, Map<Long, Integer>>} keyed by model
 * then cell, and it works -- but it boxes both keys, allocates a node per entry, and allocates an
 * inner map per model. At fifty thousand entities in one frame that is a hundred thousand objects
 * on the render thread, which is the exact cost {@link EntityBuffer} exists to avoid.
 *
 * <p>Here the whole index is three flat arrays. A hit is one hash and one probe. Nothing is
 * allocated until the table has to grow, and it does not shrink between frames, so a steady-state
 * frame allocates nothing.
 *
 * <p>Not thread-safe.
 */
public final class EntityGrid {

    /** Load factor as a percentage. Kept low because linear probing degrades quickly past it. */
    private static final int LOAD_PERCENT = 70;

    private static final int INITIAL_CAPACITY = 256;

    private long[] cellKeys;
    private int[] modelKeys;
    private int[] values;
    private int size;
    private int threshold;

    public EntityGrid() {
        this(INITIAL_CAPACITY);
    }

    public EntityGrid(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a positive power of two: " + capacity);
        }
        this.cellKeys = new long[capacity];
        this.modelKeys = new int[capacity];
        this.values = new int[capacity];
        Arrays.fill(this.cellKeys, EntityCell.EMPTY);
        this.threshold = thresholdFor(capacity);
    }

    private static int thresholdFor(int capacity) {
        return Math.max(1, capacity * LOAD_PERCENT / 100);
    }

    /**
     * Spreads a cell key and a model key.
     *
     * <p>Cell keys are sequential for neighbouring cells, so a hash that kept the low bits would
     * put a line of cells into consecutive slots and degenerate into a linear scan. The multiply
     * by an odd constant pushes those differences up into the high bits before they are folded
     * back down.
     */
    private static int hash(long cellKey, int modelKey) {
        long h = cellKey * 0x9E3779B97F4A7C15L;
        h ^= (modelKey & 0xFFFFFFFFL) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        return (int) h;
    }

    private int probe(long cellKey, int modelKey) {
        int mask = this.cellKeys.length - 1;
        int i = hash(cellKey, modelKey) & mask;
        while (true) {
            long existing = this.cellKeys[i];
            if (existing == EntityCell.EMPTY) {
                return -1;
            }
            if (existing == cellKey && this.modelKeys[i] == modelKey) {
                return i;
            }
            i = (i + 1) & mask;
        }
    }

    /** The batch index for a cell and model, or {@code -1} if that pair has no batch yet. */
    public int get(long cellKey, int modelKey) {
        int slot = probe(cellKey, modelKey);
        return slot < 0 ? -1 : this.values[slot];
    }

    /** Whether a cell and model already have a batch. */
    public boolean contains(long cellKey, int modelKey) {
        return probe(cellKey, modelKey) >= 0;
    }

    /**
     * Records a new cell and model.
     *
     * @throws IllegalStateException if the pair is already present. Batching calls this only after
     *         a {@link #get} missed, so a collision here means the caller's logic is wrong, and
     *         silently overwriting would lose a batch of instances from the draw list.
     */
    public void put(long cellKey, int modelKey, int value) {
        if (this.size >= this.threshold) {
            grow();
        }
        int mask = this.cellKeys.length - 1;
        int i = hash(cellKey, modelKey) & mask;
        while (this.cellKeys[i] != EntityCell.EMPTY) {
            if (this.cellKeys[i] == cellKey && this.modelKeys[i] == modelKey) {
                throw new IllegalStateException("cell " + cellKey + " model " + modelKey
                        + " is already in the grid");
            }
            i = (i + 1) & mask;
        }
        this.cellKeys[i] = cellKey;
        this.modelKeys[i] = modelKey;
        this.values[i] = value;
        this.size++;
    }

    /**
     * Records a cell and model, replacing any batch already recorded for that pair.
     *
     * <p>Batching needs this for one case: a cell holding more instances than one draw may carry.
     * When that happens the batcher opens a second batch for the same cell and model and repoints
     * the grid at it, so the next instance in that cell joins the new one.
     */
    public void set(long cellKey, int modelKey, int value) {
        int slot = probe(cellKey, modelKey);
        if (slot >= 0) {
            this.values[slot] = value;
            return;
        }
        put(cellKey, modelKey, value);
    }

    private void grow() {
        long[] oldCells = this.cellKeys;
        int[] oldModels = this.modelKeys;
        int[] oldValues = this.values;

        int next = oldCells.length << 1;
        this.cellKeys = new long[next];
        this.modelKeys = new int[next];
        this.values = new int[next];
        Arrays.fill(this.cellKeys, EntityCell.EMPTY);
        this.threshold = thresholdFor(next);
        this.size = 0;

        int mask = next - 1;
        for (int i = 0; i < oldCells.length; i++) {
            if (oldCells[i] == EntityCell.EMPTY) {
                continue;
            }
            int j = hash(oldCells[i], oldModels[i]) & mask;
            while (this.cellKeys[j] != EntityCell.EMPTY) {
                j = (j + 1) & mask;
            }
            this.cellKeys[j] = oldCells[i];
            this.modelKeys[j] = oldModels[i];
            this.values[j] = oldValues[i];
            this.size++;
        }
    }

    /** Empties the index but keeps the arrays, so the next frame does not reallocate. */
    public void clear() {
        Arrays.fill(this.cellKeys, EntityCell.EMPTY);
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    /** Current table length, so a test can assert that a steady-state frame stops growing it. */
    public int capacity() {
        return this.cellKeys.length;
    }
}
