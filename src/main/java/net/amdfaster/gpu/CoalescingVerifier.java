package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.platform.AmdArchitecture;

/**
 * Checks whether the mesh buffers' strides let a wavefront's loads coalesce, and by how much they do
 * not when they fail.
 *
 * <p>A wavefront issues one load instruction whose addresses come from every lane at once, and the memory
 * unit merges them into the fewest whole cache lines that cover them. Thirty-two lanes reading four
 * consecutive bytes each want 128 contiguous bytes and cost one line. The same thirty-two lanes reading
 * four bytes each at an eight byte stride want the same 128 bytes, touch the same four lines, and get
 * half of every line thrown away -- the fetch happened either way, so the bandwidth is gone and nothing
 * reports it, because the geometry still comes out correct.
 *
 * <p>So this is a bandwidth check, not a correctness check, and it is worth having because the fixed
 * meshlet stride that makes the cull shader's draw derivation possible is also what decides it. That
 * stride is not chosen for coalescing, it is chosen so meshlet {@code i} can be addressed as
 * {@code i * stride} with no lookup table, and the two goals do not automatically agree.
 *
 * <p>The lines are the measured ones for the architectures this targets. RDNA's L0 vector cache and the
 * Infinity Cache use 64 byte lines and the shared L2 uses 128, so 64 is the pessimistic figure and the
 * one checked by default: a layout that coalesces at 64 coalesces at 128 too, but not the reverse, since
 * a 64 byte read aligned to 64 is half of a 128 byte line.
 *
 * @see <a href="https://www.rastergrid.com/blog/gpu-tech/2021/01/understanding-gpu-caches/">Understanding
 *      GPU caches -- a 32-wide wave reading 4-byte elements coalesces into one 128-byte line</a>
 * @see <a href="https://zolotukhin.ai/zinc/docs/amd-gpu-reference/">RDNA4 memory hierarchy -- L0 64 B
 *      lines, L2 128 B lines, Infinity Cache 64 B lines</a>
 */
public final class CoalescingVerifier {

    /** RDNA L0 vector cache and Infinity Cache line size, in bytes. */
    public static final int LINE_L0_BYTES = 64;

    /** The shared L2 line size, in bytes. */
    public static final int LINE_L2_BYTES = 128;

    /** Lanes in an RDNA wavefront. */
    public static final int WAVE32 = 32;

    /** Lanes in a GCN or CDNA wavefront, and in one {@code local_size_x} workgroup here. */
    public static final int WAVE64 = 64;

    private CoalescingVerifier() {
    }

    /**
     * One stream's coalescing verdict.
     *
     * @param linesIdeal    lines the same bytes would cost if the read started on a line boundary
     * @param linesWorst    lines it costs at the worst starting offset the slot stride can produce
     * @param distinctStartOffsets how many different line offsets the slot stride cycles through
     * @param efficiency    useful bytes over fetched bytes, reading from an aligned start
     * @param efficiencyWorst the same at the worst starting offset
     */
    public record Verdict(String stream, int wave, int slotStride, int accessBytes, int lineBytes,
                          int linesIdeal, int linesWorst, int distinctStartOffsets,
                          double efficiency, double efficiencyWorst) {

        /** Whether even the best case wastes part of every line it fetches. */
        public boolean coalesced() {
            return this.linesWorst == this.linesIdeal;
        }

        /** The extra lines the slot stride costs per wave, over the aligned ideal. */
        public int extraLines() {
            return this.linesWorst - this.linesIdeal;
        }
    }

    /**
     * Counts the cache lines a wavefront's load touches.
     *
     * <p>Each lane {@code i} reads {@code accessBytes} starting at {@code offset + i * stride}. The lines
     * are counted exactly rather than estimated, because the interesting cases are the ones an estimate
     * gets wrong: a stride equal to the access size is perfectly dense and an estimate that divides by
     * the line size would still be right, but a stride of twice the access size touches exactly as many
     * lines while using half of each, and only the union of the ranges shows that.
     *
     * @param offset where lane 0 starts, in bytes from a line boundary
     */
    public static int linesTouched(int wave, int stride, int accessBytes, int lineBytes, int offset) {
        require(wave > 0, "wave must have lanes");
        require(stride > 0, "stride must be positive");
        require(accessBytes > 0, "access must read something");
        require(lineBytes > 0, "line size must be positive");
        require(offset >= 0, "offset must not be negative");
        int first = offset / lineBytes;
        int last = first;
        for (int i = 0; i < wave; i++) {
            int start = offset + i * stride;
            int end = start + accessBytes - 1;
            int startLine = start / lineBytes;
            int endLine = end / lineBytes;
            if (startLine < first) {
                first = startLine;
            }
            if (endLine > last) {
                last = endLine;
            }
        }
        return last - first + 1;
    }

    /**
     * How many different line offsets one meshlet's slot can start at.
     *
     * <p>Meshlet {@code n} starts at {@code n * slotStride}, so the offsets repeat with period
     * {@code lineBytes / gcd(slotStride, lineBytes)}. A slot stride that is a whole number of lines gives
     * one offset and every meshlet is aligned; one that is not cycles through several, and whichever of
     * them straddles a boundary is the expensive one.
     */
    public static int distinctStartOffsets(int slotStride, int lineBytes) {
        require(slotStride > 0, "slot stride must be positive");
        require(lineBytes > 0, "line size must be positive");
        return lineBytes / gcd(slotStride, lineBytes);
    }

    /** Whether every meshlet's slot begins on a line boundary. */
    public static boolean slotAligned(int slotStride, int lineBytes) {
        return distinctStartOffsets(slotStride, lineBytes) == 1;
    }

    /** The line offset meshlet {@code meshlet}'s slot begins at. */
    public static int slotOffset(int slotStride, int meshlet, int lineBytes) {
        require(meshlet >= 0, "meshlet index must not be negative");
        return (int) (((long) slotStride * meshlet) % lineBytes);
    }

    /**
     * Verifies one stream over the offsets its slot stride can produce.
     *
     * @param wave        lanes in the wavefront doing the read
     * @param slotStride  bytes from one meshlet's data to the next in this buffer
     * @param accessBytes bytes each lane reads
     * @param lineBytes   cache line size to check against
     */
    public static Verdict verify(String stream, int wave, int slotStride, int accessBytes, int lineBytes) {
        require(stream != null, "stream needs a name");
        int requested = wave * accessBytes;
        // Within one meshlet the lanes read consecutive elements, so the lane stride is the element size
        // and not the slot stride. The slot stride only decides where that meshlet's block begins, which
        // is what the offset walk below varies.
        int linesAligned = linesTouched(wave, accessBytes, accessBytes, lineBytes, 0);
        int offsets = distinctStartOffsets(slotStride, lineBytes);
        int linesWorst = linesAligned;
        for (int n = 0; n < offsets; n++) {
            int lines = linesTouched(wave, accessBytes, accessBytes, lineBytes,
                    slotOffset(slotStride, n, lineBytes));
            if (lines > linesWorst) {
                linesWorst = lines;
            }
        }
        return new Verdict(stream, wave, slotStride, accessBytes, lineBytes, linesAligned, linesWorst,
                offsets, (double) requested / (linesAligned * lineBytes),
                (double) requested / (linesWorst * lineBytes));
    }

    /**
     * Verifies every buffer of a section's mesh data at the pessimistic line size.
     *
     * <p>The position, attribute and light buffers are all read element by element with each lane taking
     * the next element, so they are dense by construction and the only question is where the meshlet's
     * block starts. The index buffer is the one that genuinely cannot be dense: a draw reads the indices
     * its quads named, and those name vertices in whatever order the mesher emitted them.
     */
    public static Verdict[] verifySection(MeshletGpuLayout layout) {
        int vertices = Meshlet.MAX_QUADS * 4;
        return new Verdict[] {
                verify("position", WAVE32, vertices * Meshlet.POSITION_STRIDE, Meshlet.POSITION_STRIDE,
                        LINE_L0_BYTES),
                verify("attribute", WAVE32, vertices * Meshlet.ATTRIBUTE_STRIDE, Meshlet.ATTRIBUTE_STRIDE,
                        LINE_L0_BYTES),
                verify("light", WAVE32, vertices * Meshlet.LIGHT_STRIDE, Meshlet.LIGHT_STRIDE,
                        LINE_L0_BYTES),
                verify("cull record", WAVE32, CullBindings.MESHLET_RECORD_BYTES,
                        CullBindings.MESHLET_RECORD_BYTES, LINE_L0_BYTES),
                verify("orientation", WAVE32, CullBindings.ORIENTATION_BYTES_PER_MESHLET,
                        CullBindings.ORIENTATION_BYTES_PER_MESHLET, LINE_L0_BYTES),
                verify("index", WAVE32, MeshletGpuLayout.INDICES_PER_MESHLET, 2, LINE_L0_BYTES),
        };
    }

    /** The workgroup size the shaders compile with, which is two wave32s on RDNA. */
    public static int workgroupLanes() {
        return AmdArchitecture.WORKGROUP_SIZE;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
