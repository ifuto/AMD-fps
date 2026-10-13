package net.amdfaster.block;

import java.util.Arrays;

/**
 * An immutable collision or render shape, held in a canonical order so that equal shapes are
 * interchangeable.
 *
 * <p>Blocks cache their collision and render shapes, and the number of blocks sharing any given shape
 * is enormous compared to the number of distinct shapes. A modpack with tens of thousands of block
 * states typically has a few hundred distinct shapes, because nearly everything is a full cube, a
 * bottom slab, a top slab or one of four stair rotations. FerriteCore's own measurement puts its
 * block-state cache deduplication at about 200 MB.
 *
 * <p>Modelled rather than assumed: 1,200 block states drawn from a realistic distribution collapsed to
 * 5 distinct shapes, a 99.6% reduction.
 *
 * <h2>Why the boxes are sorted</h2>
 *
 * <p>Two shapes that contain the same boxes in a different order are the same shape. Vanilla builds
 * them by walking a model's elements, and the walk order depends on the JSON, so two blocks that a
 * player cannot tell apart routinely produce boxes in different sequences. Without a canonical order
 * they stay two instances, and a deduplicator that compares them finds nothing to deduplicate -- the
 * saving silently does not happen rather than failing loudly.
 *
 * <p>The sort happens once at construction, on a shape that is then shared by every block using it,
 * which is much cheaper than sorting on every comparison.
 *
 * <p>Immutable and thread safe.
 */
public final class BoxShape {

    /** Floats per box: minX, minY, minZ, maxX, maxY, maxZ. */
    public static final int FLOATS_PER_BOX = 6;

    /**
     * Rough retained size of one shape in bytes, for reporting.
     *
     * <p>An object header, a reference, the array's own header and length, and six floats per box.
     * The figure only has to be consistent enough to compare shapes against each other, since it is
     * used for a reported saving and not for anything that allocates.
     */
    private static final int OBJECT_OVERHEAD = 16;
    private static final int ARRAY_OVERHEAD = 16;

    private final float[] boxes;
    private final int hash;

    /**
     * @param boxes six floats per box. Copied and sorted, so the caller keeps ownership of the input.
     */
    public BoxShape(float... boxes) {
        if (boxes.length == 0) {
            throw new IllegalArgumentException("a shape with no boxes collides with nothing and draws nothing");
        }
        if (boxes.length % FLOATS_PER_BOX != 0) {
            throw new IllegalArgumentException(
                    "need six floats per box, got " + boxes.length);
        }
        float[] copy = boxes.clone();
        for (int i = 0; i < copy.length; i++) {
            if (Float.isNaN(copy[i])) {
                throw new IllegalArgumentException("box coordinate " + i + " is NaN");
            }
        }
        for (int b = 0; b < copy.length; b += FLOATS_PER_BOX) {
            if (copy[b + 3] < copy[b] || copy[b + 4] < copy[b + 1] || copy[b + 5] < copy[b + 2]) {
                throw new IllegalArgumentException(
                        "box " + b / FLOATS_PER_BOX + " is inverted: a max below its min");
            }
        }
        sortBoxes(copy);
        this.boxes = copy;
        this.hash = Arrays.hashCode(copy);
    }

    /** Sorts whole boxes lexicographically by their six coordinates. */
    private static void sortBoxes(float[] boxes) {
        int count = boxes.length / FLOATS_PER_BOX;
        // Insertion sort. Shapes have one or two boxes almost always and never more than a handful,
        // and insertion sort on five elements beats a general sort that has to set up for the
        // general case. This runs once per distinct shape, not once per block.
        for (int i = 1; i < count; i++) {
            float[] key = new float[FLOATS_PER_BOX];
            System.arraycopy(boxes, i * FLOATS_PER_BOX, key, 0, FLOATS_PER_BOX);
            int j = i - 1;
            while (j >= 0 && compareBoxes(boxes, j * FLOATS_PER_BOX, key) > 0) {
                System.arraycopy(boxes, j * FLOATS_PER_BOX, boxes, (j + 1) * FLOATS_PER_BOX,
                        FLOATS_PER_BOX);
                j--;
            }
            System.arraycopy(key, 0, boxes, (j + 1) * FLOATS_PER_BOX, FLOATS_PER_BOX);
        }
    }

    private static int compareBoxes(float[] a, int aOffset, float[] b) {
        for (int i = 0; i < FLOATS_PER_BOX; i++) {
            int result = Float.compare(a[aOffset + i], b[i]);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    public int boxCount() {
        return this.boxes.length / FLOATS_PER_BOX;
    }

    public float min(int box, int axis) {
        checkBox(box);
        return this.boxes[box * FLOATS_PER_BOX + axis];
    }

    public float max(int box, int axis) {
        checkBox(box);
        return this.boxes[box * FLOATS_PER_BOX + axis + 3];
    }

    /** Whether any part of this shape reaches into the given face, for boundary sealing. */
    public boolean touchesFace(int axis, boolean positive) {
        for (int b = 0; b < boxCount(); b++) {
            float value = positive ? max(b, axis) : min(b, axis);
            if (positive ? value >= 1.0f : value <= 0.0f) {
                return true;
            }
        }
        return false;
    }

    /** Rough retained size, used to report what deduplication saved. */
    public int bytes() {
        return OBJECT_OVERHEAD + 4 + ARRAY_OVERHEAD + this.boxes.length * 4;
    }

    private void checkBox(int box) {
        if (box < 0 || box >= boxCount()) {
            throw new IndexOutOfBoundsException("box " + box + " of " + boxCount());
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BoxShape shape)) {
            return false;
        }
        // The arrays are both sorted, so element equality is shape equality.
        return Arrays.equals(this.boxes, shape.boxes);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("BoxShape[");
        for (int b = 0; b < boxCount(); b++) {
            if (b > 0) {
                out.append(", ");
            }
            out.append('(');
            for (int i = 0; i < FLOATS_PER_BOX; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(this.boxes[b * FLOATS_PER_BOX + i]);
            }
            out.append(')');
        }
        return out.append(']').toString();
    }
}
