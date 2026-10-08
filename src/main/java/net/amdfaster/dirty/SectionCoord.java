package net.amdfaster.dirty;

/**
 * Packs a section coordinate into one long so the dirty sets can be flat arrays of primitives.
 *
 * <p>Twenty-one bits per axis covers sections out to about 16.7 million blocks. Minecraft's world
 * border is nearer than that, but not by enough to ignore, so packing validates rather than
 * wrapping: two distant sections aliasing onto one key would mean rebuilding the wrong one and
 * leaving visible holes in the world.
 *
 * <p>{@link #EMPTY} is the smallest possible long, which no valid key can produce because the
 * biased fields are each non-negative and the top field cannot reach the sign bit.
 */
public final class SectionCoord {

    private static final int FIELD_BITS = 21;

    private static final long FIELD_MASK = (1L << FIELD_BITS) - 1;

    private static final long BIAS = 1L << (FIELD_BITS - 1);

    public static final int MIN = -(int) BIAS;
    public static final int MAX = (int) BIAS - 1;

    /** Never produced by {@link #key}; used as the empty-slot marker. */
    public static final long EMPTY = Long.MIN_VALUE;

    private SectionCoord() {
    }

    public static long key(int x, int y, int z) {
        if (x < MIN || x > MAX || y < MIN || y > MAX || z < MIN || z > MAX) {
            throw new IllegalArgumentException(
                    "section (" + x + "," + y + "," + z + ") is outside " + MIN + ".." + MAX);
        }
        return (((long) x + BIAS) << (2 * FIELD_BITS))
                | (((long) y + BIAS) << FIELD_BITS)
                | ((long) z + BIAS);
    }

    public static int x(long key) {
        return (int) ((key >>> (2 * FIELD_BITS)) & FIELD_MASK) - (int) BIAS;
    }

    public static int y(long key) {
        return (int) ((key >>> FIELD_BITS) & FIELD_MASK) - (int) BIAS;
    }

    public static int z(long key) {
        return (int) (key & FIELD_MASK) - (int) BIAS;
    }
}
