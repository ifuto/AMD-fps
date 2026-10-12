package net.amdfaster.cull;

import java.util.Arrays;

/**
 * Builds a {@link TileDepthBuffer} from a set of occluders and answers visibility queries against it.
 *
 * <p>The buffer is the data structure; this is the procedure that makes it useful. Three things it does
 * are not obvious from the buffer alone, and all three come from how Intel's masked occlusion culling is
 * driven.
 *
 * <h2>Front to back</h2>
 *
 * <p>Occluders are rasterised nearest first. The depth store already keeps the nearer surface, so order
 * does not change the result -- it changes the cost. Rasterising front to back means the sub-tiles a
 * later, farther occluder would touch are already written and already covered, so the common case is a
 * compare that fails rather than a write, and the writes that do happen land on cache lines that are
 * already resident.
 *
 * <h2>Queries interleave with rasterising</h2>
 *
 * <p>There is no penalty for interleaving the two, which is what makes it possible to rasterise a chunk
 * and immediately test the chunks behind it rather than finishing all occluders first. Finishing first
 * means holding every occluder's screen rectangle live at once; interleaving means holding one.
 *
 * <h2>The working layer</h2>
 *
 * <p>Occluders are kept in a bounded working set rather than an unbounded list. Once it is full, the
 * rasteriser flushes it into the buffer and starts again, which caps the memory a single frame's
 * occlusion pass can consume no matter how many occluders the scene offers. A scene with a thousand
 * candidate occluders must not be able to allocate a thousand rectangles.
 *
 * <p>Not thread safe. One rasteriser per culling thread.
 */
public final class SoftwareRasterizer {

    /**
     * Occluders held before a flush.
     *
     * <p>Each occluder is five floats and 64 of them is 1280 bytes, which fits in L1 alongside the
     * buffer slice being written. Past that the working set stops being a cache-resident list and starts
     * being a second thing to page through.
     */
    public static final int WORKING_LAYER = 64;

    private static final int FIELDS = 5;

    private final TileDepthBuffer buffer;
    private final float[] layer;
    private int layerSize;

    private int occludersSubmitted;
    private int flushes;
    private int candidatesTested;
    private int candidatesCulled;

    public SoftwareRasterizer(TileDepthBuffer buffer) {
        this.buffer = buffer;
        this.layer = new float[WORKING_LAYER * FIELDS];
    }

    /** The buffer this rasteriser writes into. */
    public TileDepthBuffer buffer() {
        return this.buffer;
    }

    /**
     * Offers an occluder.
     *
     * <p>Occluders are buffered rather than written immediately so that a whole layer can be sorted
     * front to back before any of it touches the depth store.
     *
     * @param x0 left edge in pixels, inclusive
     * @param y0 top edge in pixels, inclusive
     * @param x1 right edge in pixels, exclusive
     * @param y1 bottom edge in pixels, exclusive
     * @param depth linear view depth, nearer is smaller
     */
    public void addOccluder(int x0, int y0, int x1, int y1, float depth) {
        if (x1 <= x0 || y1 <= y0) {
            // An empty rectangle occludes nothing. Accepting it would waste a layer slot on something
            // that can only ever cost time.
            return;
        }
        if (this.layerSize == WORKING_LAYER) {
            flushLayer();
        }
        int base = this.layerSize * FIELDS;
        this.layer[base] = x0;
        this.layer[base + 1] = y0;
        this.layer[base + 2] = x1;
        this.layer[base + 3] = y1;
        this.layer[base + 4] = depth;
        this.layerSize++;
        this.occludersSubmitted++;
    }

    /**
     * Sorts the working layer front to back and rasterises it.
     *
     * <p>Safe to call at any point, including with an empty layer.
     */
    public void flushLayer() {
        if (this.layerSize == 0) {
            return;
        }
        sortByDepthAscending(this.layer, this.layerSize);
        for (int i = 0; i < this.layerSize; i++) {
            int base = i * FIELDS;
            this.buffer.rasterize(
                    (int) this.layer[base],
                    (int) this.layer[base + 1],
                    (int) this.layer[base + 2],
                    (int) this.layer[base + 3],
                    this.layer[base + 4]);
        }
        this.flushes++;
        this.layerSize = 0;
    }

    /**
     * Flushes any pending occluders, clearing the way for a query that must see all of them.
     *
     * <p>A query run against a buffer with occluders still in the layer would miss them and draw
     * something hidden. Callers that interleave adding and querying do not need this -- the interleaving
     * is the point -- but a caller that adds everything and then queries does.
     */
    public void complete() {
        flushLayer();
    }

    /**
     * Tests one candidate box.
     *
     * <p>Does not flush the layer, so the caller decides whether pending occluders should count. That
     * decision is the interleaving, and making it implicitly would be wrong more often than not.
     *
     * @return true if the box is definitely hidden
     */
    public boolean test(int x0, int y0, int x1, int y1, float nearDepth) {
        boolean occluded = this.buffer.isOccluded(x0, y0, x1, y1, nearDepth);
        this.candidatesTested++;
        if (occluded) {
            this.candidatesCulled++;
        }
        return occluded;
    }

    /**
     * Tests a batch of candidates held as screen rectangles with their near depths.
     *
     * <p>The layout is five floats per candidate in the order {@code x0, y0, x1, y1, nearDepth}, which is
     * the same record the occluder layer uses, so a scene can keep one array and use it for both.
     *
     * @param rects candidate records
     * @param count how many records are valid
     * @param visible one boolean per candidate, written by this call
     * @return how many candidates survived
     */
    public int testBatch(float[] rects, int count, boolean[] visible) {
        if (visible.length < count) {
            throw new IllegalArgumentException(
                    "output holds " + visible.length + " but " + count + " candidates were submitted");
        }
        if (rects.length < count * FIELDS) {
            throw new IllegalArgumentException(
                    "input holds " + (rects.length / FIELDS) + " records but " + count + " were requested");
        }
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int base = i * FIELDS;
            boolean occluded = this.buffer.isOccluded(
                    (int) rects[base],
                    (int) rects[base + 1],
                    (int) rects[base + 2],
                    (int) rects[base + 3],
                    rects[base + 4]);
            visible[i] = !occluded;
            this.candidatesTested++;
            if (occluded) {
                this.candidatesCulled++;
            } else {
                kept++;
            }
        }
        return kept;
    }

    /** Starts a new frame: clears the buffer and every counter. */
    public void beginFrame() {
        this.buffer.clear();
        this.layerSize = 0;
        this.occludersSubmitted = 0;
        this.flushes = 0;
        this.candidatesTested = 0;
        this.candidatesCulled = 0;
    }

    /** Occluders offered since the frame began, including ones still in the layer. */
    public int occludersSubmitted() {
        return this.occludersSubmitted;
    }

    /** How many times the working layer filled and was rasterised. */
    public int flushes() {
        return this.flushes;
    }

    /** Candidates still held in the working layer. */
    public int pendingOccluders() {
        return this.layerSize;
    }

    /** Candidates tested since the frame began. */
    public int candidatesTested() {
        return this.candidatesTested;
    }

    /** Candidates rejected since the frame began. */
    public int candidatesCulled() {
        return this.candidatesCulled;
    }

    /**
     * The cull rate as a percentage, or zero when nothing has been tested.
     *
     * <p>Dividing by zero here would produce NaN, which prints as NaN in the HUD and reads like a bug
     * rather than like an empty frame.
     */
    public double cullRatePercent() {
        if (this.candidatesTested == 0) {
            return 0.0;
        }
        return 100.0 * this.candidatesCulled / this.candidatesTested;
    }

    /**
     * Sorts occluder records by ascending depth, in place.
     *
     * <p>An insertion sort, because the layer is at most 64 records and callers typically offer them
     * already roughly ordered by distance, which is the case insertion sort handles in close to linear
     * time. A general sort would spend more comparing than the whole rasterise costs.
     *
     * <p>Records move as whole five-float units. Moving one field at a time, or moving them by stride
     * rather than as a block, would separate a rectangle from its depth and every subsequent write would
     * go to the wrong place.
     */
    static void sortByDepthAscending(float[] records, int count) {
        for (int i = 1; i < count; i++) {
            int base = i * FIELDS;
            float key0 = records[base];
            float key1 = records[base + 1];
            float key2 = records[base + 2];
            float key3 = records[base + 3];
            float key4 = records[base + 4];

            int j = i - 1;
            while (j >= 0 && records[j * FIELDS + 4] > key4) {
                int src = j * FIELDS;
                int dst = src + FIELDS;
                records[dst] = records[src];
                records[dst + 1] = records[src + 1];
                records[dst + 2] = records[src + 2];
                records[dst + 3] = records[src + 3];
                records[dst + 4] = records[src + 4];
                j--;
            }
            int dst = (j + 1) * FIELDS;
            records[dst] = key0;
            records[dst + 1] = key1;
            records[dst + 2] = key2;
            records[dst + 3] = key3;
            records[dst + 4] = key4;
        }
    }

    /** A copy of the working layer, for tests and diagnostics. */
    float[] layerSnapshot() {
        return Arrays.copyOf(this.layer, this.layerSize * FIELDS);
    }
}
