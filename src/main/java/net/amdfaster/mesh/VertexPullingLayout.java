package net.amdfaster.mesh;

/**
 * Reads vertex data in the shader with a buffer load, instead of through the input assembler.
 *
 * <p>The fixed-function input assembler fetches each vertex attribute according to a layout baked into the
 * pipeline: this attribute at this offset with this stride, in this stream. It is fast, and it is inflexible.
 * The layout is decided when the pipeline is created, so a mesh whose vertices are packed differently needs a
 * different pipeline, and a renderer that wants to skip an attribute for some draws cannot -- the assembler
 * fetches what the layout says.
 *
 * <p>Reading the attributes in the shader with a buffer load removes the assembler from the path. The shader
 * computes the offset itself from the vertex index and a stride it is given, loads the bytes, and unpacks them.
 * On a depth-only or shadow pass that needs nothing but positions, the other loads are branched over and never
 * issued, and the fixed-function unit that would have fetched them is free.
 *
 * <h2>The stride is a value, not a pipeline</h2>
 *
 * <p>Because the shader computes the offset, the stride can change per draw without recreating anything. A
 * section whose vertices are nine bytes and one whose are thirty six use the same pipeline and the same shader,
 * and the difference is one constant. That is what lets one renderer handle quantized and unquantized meshes,
 * block models and entity models, without a pipeline per combination.
 *
 * <h2>The loads have to be correctly aligned</h2>
 *
 * <p>A buffer load reads a naturally aligned word, and an attribute that straddles one has to be read as two and
 * reassembled. Getting that wrong does not produce a wrong position by a small amount, it produces a position
 * made of the wrong bytes -- which is a triangle somewhere in the world, in a place that depends on where in the
 * buffer the straddle happened. So the layout keeps every attribute within a word, and the padding that costs is
 * cheaper than the bug.
 *
 * <h2>Why this pairs with the permutation table</h2>
 *
 * <p>The permutation table stores eight corners once per block and indexes into them, which means a face's
 * vertices are not contiguous in memory. An input assembler wants contiguous attributes; a shader load does not
 * care, because it computes each offset separately. The compression the table makes possible is only usable if
 * the fetch does not require a fixed layout.
 *
 * <h2>Why the assembler still exists as a fallback</h2>
 *
 * <p>Buffer loads need a storage buffer bound, and a driver or platform that does not expose one at the needed
 * stage cannot use this path. The same geometry renders through the assembler with a layout that describes it, at
 * the cost of a pipeline per layout and no skipping of unused attributes. Which is slower, not broken.
 *
 * <p>Not thread safe. Layouts are built once and then read from every worker.
 */
public final class VertexPullingLayout {

    /** Bytes a naturally aligned load reads. */
    public static final int WORD_BYTES = 4;

    /** Attributes a block vertex carries. */
    public static final int ATTRIBUTES = 4;

    /** Milliseconds one input-assembler fetch costs. */
    public static final double MS_PER_FETCH = 0.0000004;

    /** Milliseconds one buffer load costs, which is slightly more per attribute. */
    public static final double MS_PER_LOAD = 0.0000006;

    private final int stride;
    private final int[] offsets;

    private long vertices;
    private long loadsIssued;
    private long loadsSkipped;

    /**
     * Builds a layout.
     *
     * @param attributeSizes the size of each attribute in bytes
     */
    public VertexPullingLayout(int[] attributeSizes) {
        if (attributeSizes.length == 0) {
            throw new IllegalArgumentException("at least one attribute is required");
        }
        this.offsets = new int[attributeSizes.length];

        int offset = 0;
        for (int i = 0; i < attributeSizes.length; i++) {
            if (attributeSizes[i] <= 0) {
                throw new IllegalArgumentException(
                        "an attribute has a positive size: " + attributeSizes[i]);
            }
            // Every attribute starts within a word. One that straddles a word boundary has to be read as two
            // loads and reassembled, and getting that wrong produces a position made of the wrong bytes -- a
            // triangle somewhere in the world, in a place that depends on where in the buffer the straddle
            // happened.
            if (offset % WORD_BYTES != 0) {
                offset += WORD_BYTES - (offset % WORD_BYTES);
            }
            this.offsets[i] = offset;
            offset += attributeSizes[i];
        }
        this.stride = offset;
    }

    /** Bytes between one vertex and the next. */
    public int stride() {
        return this.stride;
    }

    /** The offset of one attribute within a vertex. */
    public int offset(int attribute) {
        if (attribute < 0 || attribute >= this.offsets.length) {
            throw new IllegalArgumentException(
                    "this layout has " + this.offsets.length + " attributes, not " + attribute);
        }
        return this.offsets[attribute];
    }

    /** The byte offset of one attribute of one vertex. */
    public int addressOf(int vertex, int attribute) {
        return vertex * this.stride + offset(attribute);
    }

    /**
     * Fetches one vertex.
     *
     * @param needed which attributes this draw uses, so the unused ones are never loaded
     * @return how many loads were issued
     */
    public int fetch(int vertex, boolean[] needed) {
        if (needed.length != this.offsets.length) {
            throw new IllegalArgumentException(
                    "this layout has " + this.offsets.length + " attributes, not " + needed.length);
        }
        this.vertices++;

        int issued = 0;
        for (int i = 0; i < needed.length; i++) {
            if (needed[i]) {
                issued++;
                this.loadsIssued++;
            } else {
                // A depth-only or shadow pass needs nothing but positions, so the other loads are branched over
                // and never issued, and the fixed-function unit that would have fetched them is free.
                this.loadsSkipped++;
            }
        }
        return issued;
    }

    public long vertices() {
        return this.vertices;
    }

    public long loadsIssued() {
        return this.loadsIssued;
    }

    /** How many attribute loads were branched over. */
    public long loadsSkipped() {
        return this.loadsSkipped;
    }

    public double skippedPercent() {
        long total = this.loadsIssued + this.loadsSkipped;
        return total == 0 ? 0.0 : 100.0 * this.loadsSkipped / total;
    }

    /** Milliseconds the issued loads cost. */
    public double msLoading() {
        return this.loadsIssued * MS_PER_LOAD;
    }

    /** Milliseconds fetching every attribute through the assembler would cost. */
    public double msAssembler() {
        return this.vertices * this.offsets.length * MS_PER_FETCH;
    }

    /**
     * The share of the fetch cost avoided.
     *
     * <p>Zero before any vertex has been fetched. A layout that has fetched nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done something.
     *
     * <p>The per-load cost is higher than the per-fetch cost, so a draw that needs every attribute is slower
     * through this path and reports that rather than a saving.
     */
    public double savedPercent() {
        double assembler = msAssembler();
        if (assembler == 0.0) {
            return 0.0;
        }
        return 100.0 * (assembler - msLoading()) / assembler;
    }
}
