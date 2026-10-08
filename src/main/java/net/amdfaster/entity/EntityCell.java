package net.amdfaster.entity;

/**
 * Which {@value #SIZE}-block cube an entity sits in, and how to name that cube.
 *
 * <p>Batching by cell is what makes the batch count predictable. Two entities in the same cell are
 * within {@value #SIZE} blocks on every axis, so a cell's bounds are a valid batch bound by
 * construction -- no incremental min/max, no span check, and no dependence on the order the
 * entities happened to arrive in. A hopper farm and a hundred items scattered across a plains
 * biome both produce one batch per occupied cell, which is the property the greedy batcher in
 * {@link EntityBatcher} cannot offer.
 *
 * <p>{@value #SIZE} matches a section, so a cell boundary and a chunk boundary coincide. Entities
 * near a boundary do split across two batches, which is the cost of the scheme; it is bounded at
 * eight batches where an optimal packing would use one, and it buys order-independence.
 *
 * <p>All the arithmetic is shift and mask. {@code /} and {@code %} truncate toward zero, so
 * {@code -1 / 16} is {@code 0} and block -1 would land in cell 0 at local position -1 -- exactly
 * the boundary a chunk-straddling entity sits on.
 */
public final class EntityCell {

    public static final int SHIFT = 4;

    public static final int SIZE = 1 << SHIFT;

    private static final int MASK = SIZE - 1;

    /**
     * Bits per axis in the packed key. Twenty-one bits covers cells out to about 16.7 million
     * blocks, which is inside Minecraft's world border but not by a wide margin, so the packing
     * validates rather than silently wrapping.
     */
    private static final int FIELD_BITS = 21;

    private static final long FIELD_MASK = (1L << FIELD_BITS) - 1;

    /** Bias that moves the signed cell index into an unsigned field. */
    private static final long BIAS = 1L << (FIELD_BITS - 1);

    /** Smallest and largest cell index the key can hold. */
    public static final int MIN_CELL = -(int) BIAS;
    public static final int MAX_CELL = (int) BIAS - 1;

    /** The value that means "no cell" in a hash table slot. */
    public static final long EMPTY = Long.MIN_VALUE;

    private EntityCell() {
    }

    /** The cell containing world coordinate {@code v}. Rounds down, so -0.1 is in cell -1. */
    public static int cellOf(float v) {
        return (int) Math.floor(v) >> SHIFT;
    }

    /** The world coordinate of the cell's low corner. */
    public static int originOf(int cell) {
        return cell << SHIFT;
    }

    /** Position within the cell, 0..{@value #SIZE}-1. */
    public static int localOf(float v) {
        return ((int) Math.floor(v)) & MASK;
    }

    /**
     * Packs a cell coordinate into one long.
     *
     * @throws IllegalArgumentException if any axis is outside {@link #MIN_CELL}..{@link #MAX_CELL}.
     *         Wrapping instead would alias two distant cells onto one batch, which draws entities
     *         in the wrong place rather than merely slowly.
     */
    public static long key(int cx, int cy, int cz) {
        if (outOfRange(cx) || outOfRange(cy) || outOfRange(cz)) {
            throw new IllegalArgumentException(
                    "cell (" + cx + "," + cy + "," + cz + ") is outside " + MIN_CELL + ".." + MAX_CELL);
        }
        return (((long) cx + BIAS) << (2 * FIELD_BITS))
                | (((long) cy + BIAS) << FIELD_BITS)
                | ((long) cz + BIAS);
    }

    /** The key for the cell containing a world position. */
    public static long keyOf(float x, float y, float z) {
        return key(cellOf(x), cellOf(y), cellOf(z));
    }

    private static boolean outOfRange(int cell) {
        return cell < MIN_CELL || cell > MAX_CELL;
    }

    public static int cellX(long key) {
        return (int) ((key >>> (2 * FIELD_BITS)) & FIELD_MASK) - (int) BIAS;
    }

    public static int cellY(long key) {
        return (int) ((key >>> FIELD_BITS) & FIELD_MASK) - (int) BIAS;
    }

    public static int cellZ(long key) {
        return (int) (key & FIELD_MASK) - (int) BIAS;
    }
}
