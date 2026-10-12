package net.amdfaster.platform;

/**
 * What a vendor's hardware actually does, in the terms that change the code we generate.
 *
 * <p>This is not a capability list. It is the small set of numbers where AMD and Intel differ enough
 * that writing one version for both is measurably slower than writing two.
 *
 * <h2>The one that matters most: extended math</h2>
 *
 * <p>Both vendors run transcendentals on a separate, much narrower unit than the main ALUs, but the
 * ratio differs and both are worse than intuition suggests.
 *
 * <p>On RDNA the transcendental unit runs at a quarter of the FP32 rate. On Xe-HPG an XVE issues
 * <b>2 extended-math operations per clock against 16 FP32 MADs</b>, which is an eighth. Intel's
 * {@code sqrt} is on the same narrow path at 2 per clock. So a shader that trades an multiply-add for a
 * {@code exp} or a {@code sqrt} loses more on Intel than on AMD, and any "fast approximation" written
 * for one vendor is a different trade on the other.
 *
 * <h2>Shared memory</h2>
 *
 * <p>An Xe-core carries 192 KB of combined L1 and SLM, with 128 KB usable as shared local memory.
 * An RDNA work-group processor has 64 KB of LDS. A tile size chosen for one does not fit the other,
 * and picking the smaller of the two for both throws away half of Intel's.
 *
 * <h2>Vector width</h2>
 *
 * <p>RDNA runs wave32 or wave64. Xe-HPG's XVE is 256 bits and SIMD8, but <b>Xe2 moved to a native
 * SIMD16 engine</b> and halved the XVE count per Xe-core to compensate. Code tuned to eight-lane
 * granularity leaves half of a Xe2 engine idle, so the granularity has to come from here rather than
 * from a constant.
 *
 * <p>Immutable.
 */
public final class VendorProfile {

    public enum Vendor {
        AMD,
        INTEL,
        UNKNOWN
    }

    /** A vendor's numbers, in the units the rest of the mod uses. */
    public record Shape(
            Vendor vendor,
            int fmaOpsPerClock,
            int extendedMathOpsPerClock,
            int sharedMemoryBytes,
            int nativeSimdWidth,
            boolean hasMeshShaders,
            boolean l3IsPartitioned,
            boolean canAssumeLruEviction) {

        /**
         * How much more expensive a transcendental is than a multiply-add on this hardware.
         *
         * <p>Anything above 1 means an approximation using ordinary arithmetic is worth writing; the
         * larger this is, the more terms the approximation can afford and still win.
         */
        public int extendedMathPenalty() {
            return fmaOpsPerClock / Math.max(1, extendedMathOpsPerClock);
        }

        /**
         * The largest square tile of {@code floatsPerTexel} floats that fits in shared memory with
         * {@code bufferCount} buffers live at once.
         *
         * <p>Square is the shape that minimises the halo: a tile of area A has a perimeter proportional
         * to sqrt(A), so the redundant border work falls as the tile grows, and the limit is shared
         * memory. Intel's larger SLM therefore buys genuinely larger tiles rather than just headroom.
         *
         * @return the tile edge in texels, at least 1
         */
        public int maxSquareTile(int floatsPerTexel, int bufferCount) {
            long usable = (long) sharedMemoryBytes / Math.max(1, bufferCount);
            long perRow = usable / Math.max(1, floatsPerTexel * 4L);
            int edge = (int) Math.floor(Math.sqrt(perRow));
            return Math.max(1, edge);
        }
    }

    /**
     * RDNA. Transcendentals at a quarter rate, 64 KB of LDS, wave32 on RDNA2 and later.
     *
     * <p>Mesh shaders exist on RDNA2 and later but the mod targets them separately through the Vulkan
     * device check rather than trusting this flag, because a driver can expose the extension without
     * the hardware being good at it.
     */
    public static final Shape AMD_RDNA = new Shape(
            Vendor.AMD,
            128, 32,
            64 * 1024,
            32,
            true,
            false,
            true);

    /**
     * Xe-HPG, which is Arc A-series and the Xe-LP iGPUs' successor.
     *
     * <p>The numbers are per XVE: 16 FP32 MAD against 2 extended-math operations, both per clock. The
     * SLM figure is the 128 KB shared local memory of one Xe-core.
     */
    public static final Shape INTEL_XE_HPG = new Shape(
            Vendor.INTEL,
            16, 2,
            128 * 1024,
            8,
            true,
            false,
            true);

    /**
     * Xe2, which is Battlemage and Lunar Lake onward.
     *
     * <p>Native SIMD16. The XVE count per Xe-core is halved against Xe-HPG, which the width makes up
     * for, but any code that assumed eight lanes now leaves half the engine idle.
     */
    public static final Shape INTEL_XE2 = new Shape(
            Vendor.INTEL,
            16, 2,
            128 * 1024,
            16,
            true,
            false,
            true);

    /**
     * Used when nothing is recognised.
     *
     * <p>Deliberately the most conservative of the three on every axis: the smallest shared memory, the
     * narrowest vector, the worst transcendental penalty. Code sized against this runs correctly
     * everywhere and leaves performance on the table, which is the right way to fail.
     */
    public static final Shape CONSERVATIVE = new Shape(
            Vendor.UNKNOWN,
            16, 2,
            32 * 1024,
            8,
            false,
            false,
            true);

    private VendorProfile() {
    }

    /**
     * Picks a profile from a device name or vendor string.
     *
     * <p>Matching on a name is crude, but the alternative is a device-id table that has to be maintained
     * for every SKU, and the cost of guessing wrong here is a slightly suboptimal tile size rather than
     * incorrect output. Anything unrecognised gets {@link #CONSERVATIVE}.
     */
    public static Shape fromDeviceName(String name) {
        if (name == null) {
            return CONSERVATIVE;
        }
        String lower = name.toLowerCase();
        if (lower.contains("intel") || lower.contains("arc") || lower.contains("iris")) {
            // Xe2 first: Battlemage and Lunar Lake are the newer parts, and "Arc B" is how they appear.
            if (lower.contains("arc b") || lower.contains("battlemage")
                    || lower.contains("lunar") || lower.contains("xe2")) {
                return INTEL_XE2;
            }
            return INTEL_XE_HPG;
        }
        if (lower.contains("radeon") || lower.contains("amd") || lower.contains("ati")) {
            return AMD_RDNA;
        }
        return CONSERVATIVE;
    }

    /**
     * Whether the shared memory of two profiles differs enough to need separate tile sizes.
     *
     * <p>Reporting this lets a caller that builds one tile size for both vendors see that it is leaving
     * performance on the table, rather than having to compare the constants itself.
     */
    public static boolean tileSizesDiverge(Shape a, Shape b) {
        return a.sharedMemoryBytes() != b.sharedMemoryBytes();
    }
}
