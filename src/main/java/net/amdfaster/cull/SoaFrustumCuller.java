package net.amdfaster.cull;

/**
 * Frustum culling over structure-of-arrays box data, and the reference the native backend is checked
 * against.
 *
 * <p>This is the same algorithm the backend implements in AVX-512 in
 * {@code src/main/resources/native/afback_cull.c}, written out here in scalar Java. It is not a
 * placeholder: it is the path that runs when the backend is not available, and it is the reference
 * against which the vector path is checked at startup. A culler that is fast and occasionally wrong
 * deletes geometry once every few thousand frames in a way nobody can reproduce, so the two
 * implementations have to be independently written and then shown to agree.
 *
 * <h2>Why structure of arrays</h2>
 *
 * <p>Six parallel arrays rather than an array of boxes. On the vector path this is what lets a plane
 * coefficient broadcast across sixteen boxes while each box's coordinate is already contiguous. On the
 * scalar path it is what lets the JIT recognise the loop and vectorise it itself. And on Zen, where
 * gather and scatter are much slower than a contiguous load, it means no kernel here ever gathers.
 *
 * <h2>Why this test is safe</h2>
 *
 * <p>For each plane the box corner farthest along the plane normal -- the positive vertex -- is the last
 * part of the box to leave the half-space. If even that corner is outside, the whole box is. The test
 * can therefore never reject a box that is visible. That asymmetry is the whole point: a false negative
 * costs frames, a false positive puts a hole in the world, and only one of those is recoverable.
 *
 * <p>Stateless.
 */
public final class SoaFrustumCuller {

    /** Minecraft's frustum, which is a box in a pyramid. */
    public static final int PLANES = 6;

    /** Boxes per AVX-512 block in the backend. The scalar path uses the same stride. */
    public static final int BLOCK = 16;

    /** The plane coefficients, so that one plane's coefficient is contiguous across the six planes. */
    public static final class Planes {
        public final float[] a = new float[PLANES];
        public final float[] b = new float[PLANES];
        public final float[] c = new float[PLANES];
        public final float[] d = new float[PLANES];

        /**
         * Sets one plane. The visible side is {@code a*x + b*y + c*z + d >= 0}.
         *
         * @param index plane 0 to 5
         */
        public void set(int index, float a, float b, float c, float d) {
            if (index < 0 || index >= PLANES) {
                throw new IndexOutOfBoundsException("plane " + index);
            }
            this.a[index] = a;
            this.b[index] = b;
            this.c[index] = c;
            this.d[index] = d;
        }
    }

    /** The boxes, one array per axis per bound. */
    public static final class Boxes {
        public final float[] minX;
        public final float[] minY;
        public final float[] minZ;
        public final float[] maxX;
        public final float[] maxY;
        public final float[] maxZ;

        public Boxes(int capacity) {
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
        }

        /**
         * Stores one box.
         *
         * <p>The bounds are ordered rather than assumed ordered. A box whose min and max arrived
         * transposed would invert the positive-vertex test and cull things that are in front of the
         * camera, which looks exactly like chunks disappearing when you turn around.
         */
        public void put(int index, float x0, float y0, float z0, float x1, float y1, float z1) {
            this.minX[index] = Math.min(x0, x1);
            this.maxX[index] = Math.max(x0, x1);
            this.minY[index] = Math.min(y0, y1);
            this.maxY[index] = Math.max(y0, y1);
            this.minZ[index] = Math.min(z0, z1);
            this.maxZ[index] = Math.max(z0, z1);
        }
    }

    private SoaFrustumCuller() {
    }

    /**
     * Culls boxes 0 to {@code count}, writing one boolean per box.
     *
     * @return how many boxes were left visible
     */
    public static int cull(Planes planes, Boxes boxes, int count, boolean[] visible) {
        if (visible.length < count) {
            throw new IllegalArgumentException(
                    "output holds " + visible.length + " but " + count + " boxes were submitted");
        }
        int kept = 0;
        for (int i = 0; i < count; i++) {
            boolean inside = true;
            for (int k = 0; k < PLANES; k++) {
                float a = planes.a[k];
                float b = planes.b[k];
                float c = planes.c[k];
                float px = a > 0.0f ? boxes.maxX[i] : boxes.minX[i];
                float py = b > 0.0f ? boxes.maxY[i] : boxes.minY[i];
                float pz = c > 0.0f ? boxes.maxZ[i] : boxes.minZ[i];
                if (a * px + b * py + c * pz + planes.d[k] < 0.0f) {
                    inside = false;
                    break;
                }
            }
            visible[i] = inside;
            if (inside) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * Culls into a bitmask rather than a boolean array.
     *
     * <p>A thousand culled boxes as booleans is a thousand bytes the caller then has to scan; as a mask
     * it is sixteen longs, and the caller that wants a count gets it from {@link Long#bitCount} instead
     * of a loop. This is the form the backend returns over the socket, so the two sides agree on the
     * wire format.
     *
     * @return how many boxes were left visible
     */
    public static int cullToMask(Planes planes, Boxes boxes, int count, long[] mask) {
        if (mask.length * Long.SIZE < count) {
            throw new IllegalArgumentException(
                    "mask holds " + (mask.length * Long.SIZE) + " bits but " + count + " boxes were submitted");
        }
        for (int i = 0; i < mask.length; i++) {
            mask[i] = 0L;
        }
        int kept = 0;
        for (int i = 0; i < count; i++) {
            boolean inside = true;
            for (int k = 0; k < PLANES; k++) {
                float a = planes.a[k];
                float b = planes.b[k];
                float c = planes.c[k];
                float px = a > 0.0f ? boxes.maxX[i] : boxes.minX[i];
                float py = b > 0.0f ? boxes.maxY[i] : boxes.minY[i];
                float pz = c > 0.0f ? boxes.maxZ[i] : boxes.minZ[i];
                if (a * px + b * py + c * pz + planes.d[k] < 0.0f) {
                    inside = false;
                    break;
                }
            }
            if (inside) {
                mask[i >>> 6] |= 1L << (i & 63);
                kept++;
            }
        }
        return kept;
    }

    /** Reads one result back out of a mask produced by {@link #cullToMask}. */
    public static boolean isVisible(long[] mask, int index) {
        return (mask[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /** Counts the set bits in a mask, which is the number of visible boxes. */
    public static int countVisible(long[] mask, int count) {
        int total = 0;
        int words = (count + Long.SIZE - 1) / Long.SIZE;
        for (int i = 0; i < words; i++) {
            long word = mask[i];
            if (i == words - 1 && (count & 63) != 0) {
                // The last word is only partly used. Counting its padding bits would report boxes that
                // were never submitted as visible.
                word &= (1L << (count & 63)) - 1L;
            }
            total += Long.bitCount(word);
        }
        return total;
    }
}
