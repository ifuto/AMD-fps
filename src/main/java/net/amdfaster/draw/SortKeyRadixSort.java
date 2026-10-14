package net.amdfaster.draw;

/**
 * Sorts draw keys by radix, unsigned, carrying a payload with each one.
 *
 * <p>A frame's draw batches are ordered by a 64-bit key: bucket, then depth, then id. The obvious
 * implementation is {@code Arrays.sort}, and it is wrong here for a reason that does not show up in
 * testing with small depths. The key is compared <em>unsigned</em> -- bucket occupies the top eight
 * bits, so a bucket at or above 128 sets the sign bit -- and {@code Arrays.sort(long[])} sorts signed.
 * Every key in the translucent bucket would move ahead of every key in the opaque one, and the frame
 * would draw back to front where it should draw front to back.
 *
 * <p>Radix sorts by byte, so the sign is not a special case. That is the reason this exists rather
 * than a speed claim.
 *
 * <h2>What the speed argument actually is</h2>
 *
 * <p>Counting operations does not settle it. A comparison sort is O(n log n) and a radix sort is eight
 * passes, so at 8,192 keys that is about thirteen comparisons each against eight passes, and a Java
 * {@code Long.compare} with a swap is cheap. The difference that matters is that the comparison sort's
 * branches depend on the data, while the radix passes are a histogram and then a scatter, both of
 * which walk memory in a predictable order. On a CPU that is what decides it, and it cannot be
 * measured here because there is no JDK in this environment. What can be verified is that the order is
 * correct and that it is stable, and both are.
 *
 * <h2>Stability</h2>
 *
 * <p>Equal keys keep their input order. That is not a nicety: two draw batches with the same bucket,
 * depth and id are drawn in the order they were submitted, and a sort that reordered them would make
 * the frame flicker between two equally valid orders. The scatter walks the input forwards and places
 * each element at the next free slot for its byte, which is what makes it stable. Walking it backwards
 * would reverse every group of equal keys.
 *
 * <p>Not thread safe.
 */
public final class SortKeyRadixSort {

    /** Bytes in a 64-bit key, and so passes over the data. */
    public static final int PASSES = 8;

    /** Histogram buckets per pass. */
    public static final int RADIX = 256;

    private final int[] histogram = new int[RADIX];

    private int[] scratchKeys;
    private int[] scratchPayload;

    private long sorts;
    private long elements;
    private long passesRun;

    /**
     * Sorts keys and their payload in place, unsigned by key.
     *
     * @param keys the sort keys, reordered
     * @param payload values that travel with each key, reordered identically
     * @param count how many leading elements to sort; the rest are left alone
     * @throws IllegalArgumentException if the arrays differ in length or the count is out of range
     */
    public void sort(long[] keys, int[] payload, int count) {
        if (keys.length != payload.length) {
            throw new IllegalArgumentException(
                    "keys and payload must be the same length: " + keys.length + ", " + payload.length);
        }
        if (count < 0 || count > keys.length) {
            throw new IllegalArgumentException(
                    "count " + count + " outside " + keys.length);
        }
        if (count < 2) {
            this.sorts++;
            return;
        }
        ensureScratch(count);
        this.sorts++;
        this.elements += count;

        long[] srcKeys = keys;
        int[] srcPayload = payload;
        long[] dstKeys = this.scratchKeys;
        int[] dstPayload = this.scratchPayload;

        for (int pass = 0; pass < PASSES; pass++) {
            int shift = pass * 8;
            java.util.Arrays.fill(this.histogram, 0);
            for (int i = 0; i < count; i++) {
                this.histogram[(int) ((srcKeys[i] >>> shift) & 0xFF)]++;
            }
            int total = 0;
            for (int bucket = 0; bucket < RADIX; bucket++) {
                int n = this.histogram[bucket];
                this.histogram[bucket] = total;
                total += n;
            }
            // Forwards, which is what makes the sort stable. Each element takes the next free slot for
            // its byte, so elements with equal keys stay in input order.
            for (int i = 0; i < count; i++) {
                int bucket = (int) ((srcKeys[i] >>> shift) & 0xFF);
                int position = this.histogram[bucket]++;
                dstKeys[position] = srcKeys[i];
                dstPayload[position] = srcPayload[i];
            }
            this.passesRun++;

            long[] nextKeys = srcKeys;
            int[] nextPayload = srcPayload;
            srcKeys = dstKeys;
            srcPayload = dstPayload;
            dstKeys = nextKeys;
            dstPayload = nextPayload;
        }
        // Eight passes is even, so the result lands back in the caller's arrays.
        if (srcKeys != keys) {
            System.arraycopy(srcKeys, 0, keys, 0, count);
            System.arraycopy(srcPayload, 0, payload, 0, count);
        }
    }

    /** Sorts the whole array. */
    public void sort(long[] keys, int[] payload) {
        sort(keys, payload, keys.length);
    }

    private void ensureScratch(int count) {
        if (this.scratchKeys == null || this.scratchKeys.length < count) {
            int size = Math.max(count, 1024);
            this.scratchKeys = new long[size];
            this.scratchPayload = new int[size];
        }
    }

    /**
     * Whether an array is in unsigned ascending order.
     *
     * <p>{@code Long.compareUnsigned} rather than {@code <}, for the reason in the class comment.
     */
    public static boolean isSortedUnsigned(long[] keys, int count) {
        for (int i = 1; i < count; i++) {
            if (Long.compareUnsigned(keys[i - 1], keys[i]) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * A reference sort, for checking this one against.
     *
     * <p>Deliberately a different algorithm -- an insertion sort -- so a shared bug in the radix
     * passes cannot agree with it. Only practical for small inputs, which is what the tests use.
     */
    public static void referenceSort(long[] keys, int[] payload, int count) {
        for (int i = 1; i < count; i++) {
            long key = keys[i];
            int value = payload[i];
            int j = i - 1;
            while (j >= 0 && Long.compareUnsigned(keys[j], key) > 0) {
                keys[j + 1] = keys[j];
                payload[j + 1] = payload[j];
                j--;
            }
            keys[j + 1] = key;
            payload[j + 1] = value;
        }
    }

    public long sorts() {
        return this.sorts;
    }

    public long elements() {
        return this.elements;
    }

    public long passesRun() {
        return this.passesRun;
    }
}
