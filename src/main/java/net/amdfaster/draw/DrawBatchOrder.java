package net.amdfaster.draw;

import java.util.Arrays;

/**
 * Orders draw calls so that consecutive draws change as little GPU state as possible.
 *
 * <p>Every pipeline bind and every texture bind costs the driver and the GPU front end something, and a
 * frame that alternates between two textures pays it twice per pair of draws rather than twice per frame.
 * Sorting the draw list so that everything sharing a pipeline is adjacent, and within that everything
 * sharing a texture is adjacent, turns a per-draw cost into a per-group cost.
 *
 * <p>The order that matters is <b>pipeline, then texture, then depth</b>. Pipeline first because a
 * pipeline bind is the most expensive of the three and flushes the most. Texture second. Depth last,
 * inside a group, so that a group is still drawn front to back and early Z still does its job -- grouping
 * by state and sorting by depth are not in conflict as long as depth is the innermost key.
 *
 * <h2>Why depth cannot be packed as raw bits</h2>
 *
 * <p>A float's bit pattern is not ordered the way a float is. Negative floats order backwards by their
 * bits, so packing them directly would draw the far half of the frame before the near half and give early
 * Z nothing to reject. The conversion here maps floats onto ints monotonically, which is the only way to
 * put a float into a sortable integer key.
 *
 * <p>Stateless.
 */
public final class DrawBatchOrder {

    /** Bits for the pipeline id. 256 pipelines is far more than a renderer has. */
    public static final int PIPELINE_BITS = 8;
    /** Bits for the texture id. */
    public static final int TEXTURE_BITS = 20;

    public static final int MAX_PIPELINES = 1 << PIPELINE_BITS;
    public static final int MAX_TEXTURES = 1 << TEXTURE_BITS;

    private static final int TEXTURE_SHIFT = 32;
    private static final int PIPELINE_SHIFT = 32 + TEXTURE_BITS;

    /** The float sign bit, which the mapping moves to the top of the unsigned range. */
    private static final int SIGN_MASK = 0x8000_0000;

    private DrawBatchOrder() {
    }

    /**
     * Packs one draw into a key whose natural ordering is the batching order.
     *
     * @param pipeline pipeline id, 0 to 255
     * @param texture texture id, 0 to 1048575
     * @param depth view depth, nearer is smaller; drawn front to back within a group
     * @return the sort key
     */
    public static long key(int pipeline, int texture, float depth) {
        if (pipeline < 0 || pipeline >= MAX_PIPELINES) {
            throw new IllegalArgumentException("pipeline id out of range: " + pipeline);
        }
        if (texture < 0 || texture >= MAX_TEXTURES) {
            throw new IllegalArgumentException("texture id out of range: " + texture);
        }
        return ((long) pipeline << PIPELINE_SHIFT)
                | ((long) texture << TEXTURE_SHIFT)
                | (depthToSortable(depth) & 0xffff_ffffL);
    }

    public static int pipelineOf(long key) {
        return (int) (key >>> PIPELINE_SHIFT);
    }

    public static int textureOf(long key) {
        return (int) ((key >>> TEXTURE_SHIFT) & (MAX_TEXTURES - 1));
    }

    /**
     * Maps a float onto an int with the same ordering, for use as the low bits of a sort key.
     *
     * <p>Positive floats already order correctly by their bit pattern, so they only need the sign bit set
     * to lift them above the negatives. Negative floats order backwards, so every bit is inverted, which
     * both reverses them into place and clears the sign bit, putting them in the lower half of the
     * unsigned range.
     *
     * <p>Inverting every bit rather than all but the sign is what makes this correct. Inverting all but
     * the sign leaves the sign bit set, so negative depths stay above positive ones and the frame draws
     * back to front.
     */
    public static int depthToSortable(float value) {
        int bits = Float.floatToIntBits(value);
        return bits < 0 ? ~bits : bits | SIGN_MASK;
    }

    /** The inverse of {@link #depthToSortable}. */
    public static float sortableToDepth(int sortable) {
        // A sortable with the sign bit set came from a non-negative float, and one without it came from a
        // negative float, which is the reverse of how floats themselves are laid out.
        int bits = sortable < 0 ? sortable ^ SIGN_MASK : ~sortable;
        return Float.intBitsToFloat(bits);
    }

    /** Sorts draw keys into batching order. */
    public static void sort(long[] keys) {
        sort(keys, keys.length);
    }

    /** Sorts the first {@code count} draw keys into batching order. */
    public static void sort(long[] keys, int count) {
        if (count > keys.length) {
            throw new IllegalArgumentException("count " + count + " exceeds the array's " + keys.length);
        }
        // Sorting a subrange rather than a copy, because a frame's draw list is rebuilt every frame and
        // copying it to sort it would cost more than the reordering saves.
        Arrays.sort(keys, 0, count);
    }

    /**
     * How many state changes a draw list costs.
     *
     * <p>Counts pipeline binds and texture binds together, which is what the driver actually pays. Used
     * both to report the saving and to check that a reordering really did reduce it.
     *
     * @param keys the draw list, in the order it will be submitted
     * @param count how many are valid
     */
    public static int stateChanges(long[] keys, int count) {
        if (count <= 1) {
            return 0;
        }
        int changes = 0;
        int previousPipeline = pipelineOf(keys[0]);
        int previousTexture = textureOf(keys[0]);
        for (int i = 1; i < count; i++) {
            int pipeline = pipelineOf(keys[i]);
            int texture = textureOf(keys[i]);
            if (pipeline != previousPipeline) {
                changes++;
                previousPipeline = pipeline;
                // A pipeline bind implies the texture is rebound as part of it, so it is not counted
                // twice for the same transition.
                previousTexture = texture;
            } else if (texture != previousTexture) {
                changes++;
                previousTexture = texture;
            }
        }
        return changes;
    }

    /**
     * The worst case for a draw list: every draw changing state.
     *
     * <p>Reported alongside {@link #stateChanges} so the saving reads as a ratio rather than a bare
     * number. Four draws and two changes is a poor result; four thousand draws and two changes is an
     * excellent one.
     */
    public static int worstCaseChanges(int count) {
        return Math.max(0, count - 1);
    }
}
