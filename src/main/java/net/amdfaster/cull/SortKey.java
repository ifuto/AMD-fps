package net.amdfaster.cull;

/**
 * The 64-bit key that transparent and opaque geometry is sorted by, on the GPU.
 *
 * <p>Why a key rather than a comparison function: the sort is a radix sort, and a radix sort has no
 * comparisons. It needs an integer that increases monotonically with draw order, and it needs the
 * whole ordering decided before the sort starts. Research on GPU sorting is consistent that
 * bucket-and-merge beats bitonic by roughly 2.5x here, because bitonic is O(n log^2 n), needs a
 * power of two, and scatters through global memory instead of staying in LDS. See
 * {@code docs/notes/09}.
 *
 * <p>The layout is {@code bucket (8) | depth (32) | id (24)}, compared unsigned. Bucket is the top
 * field so one sort handles every material class in the right relative order, which matters because
 * opaque geometry has to be drawn before translucent geometry regardless of how far away either is.
 *
 * <p>Source: {@code docs/notes/14} §7.
 */
public final class SortKey {

    /**
     * Opaque geometry, drawn front to back. Early depth rejects the fragments behind the first
     * surface, so drawing the nearest thing first is a win on bandwidth rather than on correctness.
     */
    public static final int BUCKET_OPAQUE = 0;

    /**
     * Cutout geometry. Sorted with the opaque bucket's direction, but after it, because the Z
     * pre-pass that cutout relies on has to have finished writing depth first. See the fragment
     * shader's comment on why cutout does not discard.
     */
    public static final int BUCKET_CUTOUT = 1;

    /** Translucent geometry, drawn back to front, because it blends. */
    public static final int BUCKET_TRANSLUCENT = 2;

    public static final int BUCKET_COUNT = 3;

    public static final int BUCKET_BITS = 8;
    public static final int DEPTH_BITS = 32;
    public static final int ID_BITS = 24;

    /** 2^24 - 1. Sorting more than this many pieces of geometry in one frame is not supported. */
    public static final int MAX_ID = (1 << ID_BITS) - 1;

    private static final long UNSIGNED_32 = 0xFFFFFFFFL;
    private static final int DEPTH_SHIFT = ID_BITS;
    private static final int BUCKET_SHIFT = ID_BITS + DEPTH_BITS;

    private SortKey() {
    }

    /**
     * Builds a key.
     *
     * <p>The depth is inverted for the opaque and cutout buckets and not for the translucent one,
     * so the caller cannot get the direction wrong. That is the whole point of folding it in here
     * rather than taking a pre-transformed depth: front-to-back and back-to-front are one
     * character apart in a variable name and completely opposite in the result.
     *
     * @param depth the fragment depth in [0,1] under reversed Z, so 1.0 is at the near plane.
     *              Values outside the range are clamped; see {@link #depthField}.
     * @param id a per-draw identifier that breaks ties, so that two coplanar translucent quads keep
     *           the same relative order every frame instead of flickering
     */
    public static long key(int bucket, float depth, int id) {
        if (bucket < 0 || bucket >= BUCKET_COUNT) {
            throw new IllegalArgumentException("bucket " + bucket + " is not in 0.." + (BUCKET_COUNT - 1));
        }
        if (id < 0 || id > MAX_ID) {
            throw new IllegalArgumentException("id " + id + " is not in 0.." + MAX_ID);
        }
        boolean frontToBack = bucket != BUCKET_TRANSLUCENT;
        long depthField = depthField(depth, frontToBack);
        return ((long) bucket << BUCKET_SHIFT) | (depthField << DEPTH_SHIFT) | (id & 0xFFFFFFL);
    }

    /**
     * The monotonic 32-bit transform of a depth.
     *
     * <p>For non-negative floats the IEEE-754 bit pattern already increases with the value, which
     * is why this is a reinterpret and not a comparison. That property is exactly what makes a
     * comparison-free sort possible.
     *
     * <p>It holds only for non-negative floats. Negative ones sort backwards in their bit pattern,
     * which would put geometry behind the camera in front of everything else. Depth under reversed
     * Z is always in [0,1] once clipped, but clipping happens later in the pipeline than this key
     * is built, so the value is clamped rather than trusted.
     */
    static long depthField(float depth, boolean frontToBack) {
        float clamped = Float.isNaN(depth) ? 0f : Math.min(1f, Math.max(0f, depth));
        long bits = Float.floatToRawIntBits(clamped) & UNSIGNED_32;
        // Reversed Z puts the near plane at 1.0, so "front first" means descending, which is what
        // the inversion produces.
        return frontToBack ? (UNSIGNED_32 ^ bits) : bits;
    }

    public static int bucket(long key) {
        return (int) (key >>> BUCKET_SHIFT);
    }

    public static int id(long key) {
        return (int) (key & 0xFFFFFFL);
    }

    /**
     * Compares two keys. Unsigned, because the bucket sits in the top 8 bits and a signed compare
     * would put bucket 128 and above before bucket 0.
     */
    public static int compare(long a, long b) {
        return Long.compareUnsigned(a, b);
    }
}
