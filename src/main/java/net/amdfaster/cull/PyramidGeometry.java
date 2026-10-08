package net.amdfaster.cull;

/**
 * The shape of the hierarchical depth buffer, and the arithmetic that reads it correctly.
 *
 * <p>Two details here are load-bearing, and getting either one wrong makes geometry vanish rather
 * than merely making the cull slower.
 *
 * <h2>Levels are ceil-halved, not floor-halved</h2>
 * Vulkan defines mip level {@code n} of an image as {@code max(1, dim >> n)}, which floors. On a
 * 1920x1080 depth buffer that makes level 4 sixty-seven rows tall, and 67 * 2 = 134, so row 135 of
 * level 3 belongs to no texel at level 4. A pyramid built that way silently drops a strip of the
 * screen at every odd dimension, and there are five such levels between 1080p and 1x1.
 *
 * <p>This pyramid uses {@code ceil(dim / 2^level)} instead, so 1080 becomes 135, then 68, and every
 * source texel is covered. The extra tap that falls outside the finer level is clamped to its edge,
 * which duplicates a value already in the reduction and therefore cannot change the result. Both
 * halves of that claim are verified exhaustively in the tests: over 599 base sizes and 20 levels,
 * ceil-halving drops nothing and floor-halving drops a texel in 2061 steps.
 *
 * <h2>The reduction is a minimum, because depth is reversed</h2>
 * With reversed-Z a larger depth is nearer. A box is fully hidden only when its closest point is
 * behind <em>every</em> surface its footprint covers, which is a statement about the minimum surface
 * depth over that footprint, not the maximum. A max-reduced pyramid answers a different question --
 * "is the box behind the nearest thing anywhere in this region" -- and culls boxes that are plainly
 * visible through the one far pixel they actually occupy.
 *
 * <p>Minimising also fixes the direction the ceil/floor issue bites in. A dropped texel makes a max
 * reduction smaller, which only costs performance; it makes a min reduction <em>larger</em>, which
 * culls visible geometry. With a min reduction the pyramid has to be exact, which is the second
 * reason the levels are ceil-halved.
 */
public final class PyramidGeometry {

    /** The range of texels at one level that a pixel rectangle covers. Both ends are inclusive. */
    public record TexelSpan(int minX, int minY, int maxX, int maxY) {

        public int width() {
            return this.maxX - this.minX + 1;
        }

        public int height() {
            return this.maxY - this.minY + 1;
        }

        public int count() {
            return width() * height();
        }
    }

    private final int baseWidth;
    private final int baseHeight;
    private final int maxLevel;
    private final int[] widths;
    private final int[] heights;

    public PyramidGeometry(int baseWidth, int baseHeight) {
        if (baseWidth <= 0 || baseHeight <= 0) {
            throw new IllegalArgumentException(
                    "pyramid base must be positive: " + baseWidth + "x" + baseHeight);
        }
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;

        int level = 0;
        while (levelDim(baseWidth, level) > 1 || levelDim(baseHeight, level) > 1) {
            level++;
        }
        this.maxLevel = level;
        this.widths = new int[level + 1];
        this.heights = new int[level + 1];
        for (int i = 0; i <= level; i++) {
            this.widths[i] = levelDim(baseWidth, i);
            this.heights[i] = levelDim(baseHeight, i);
        }
    }

    /** Ceiling division by a power of two. {@code -(-a >> b)} rounds toward positive infinity. */
    private static int levelDim(int base, int level) {
        return Math.max(1, -(-base >> level));
    }

    public int baseWidth() {
        return this.baseWidth;
    }

    public int baseHeight() {
        return this.baseHeight;
    }

    /** Deepest level, at which both dimensions have reached one texel. */
    public int maxLevel() {
        return this.maxLevel;
    }

    public int levelWidth(int level) {
        return this.widths[checkLevel(level)];
    }

    public int levelHeight(int level) {
        return this.heights[checkLevel(level)];
    }

    private int checkLevel(int level) {
        if (level < 0 || level > this.maxLevel) {
            throw new IllegalArgumentException("level " + level + " outside 0.." + this.maxLevel);
        }
        return level;
    }

    /**
     * The coarsest level whose texels are at least as large as the box.
     *
     * <p>At that level a box spans at most two texels per axis, so the reduction reads at most four
     * fetches. Sampling a finer level would need a gather over pixels the pyramid has already
     * reduced, which is slower and no more accurate; sampling a coarser one would widen the footprint
     * beyond the box and let unrelated geometry into the minimum, culling things that are visible.
     *
     * @param boxWidthPixels  box width in level-0 pixels
     * @param boxHeightPixels box height in level-0 pixels
     */
    public int selectLevel(float boxWidthPixels, float boxHeightPixels) {
        float longest = Math.max(boxWidthPixels, boxHeightPixels);
        if (!(longest > 1f)) {
            return 0;
        }
        int level = (int) Math.ceil(Math.log(longest) / Math.log(2.0));
        return Math.min(level, this.maxLevel);
    }

    /**
     * The texels at {@code level} that a pixel rectangle covers, clamped to the level's bounds.
     *
     * <p>The caller must read every texel in the returned span and take the minimum. Reading only
     * the centre texel, which is the tempting shortcut, ignores the texels the box overhangs and
     * tests it against the wrong footprint.
     */
    public TexelSpan spanFor(int level, float pixelMinX, float pixelMinY,
                             float pixelMaxX, float pixelMaxY) {
        checkLevel(level);
        int shift = level;
        int texelWidth = levelWidth(level);
        int texelHeight = levelHeight(level);

        int minX = clamp((int) Math.floor(pixelMinX) >> shift, texelWidth);
        int minY = clamp((int) Math.floor(pixelMinY) >> shift, texelHeight);
        // The far edge is inclusive: a box reaching pixel 63.5 still covers texel 1 at shift 5, and
        // flooring after the shift is what makes an exactly-aligned edge land on its own texel.
        int maxX = clamp((int) Math.floor(pixelMaxX) >> shift, texelWidth);
        int maxY = clamp((int) Math.floor(pixelMaxY) >> shift, texelHeight);
        return new TexelSpan(minX, minY, maxX, maxY);
    }

    private static int clamp(int value, int limit) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, limit - 1);
    }

    /**
     * The level-0 pixels one texel at {@code level} covers, used by the pyramid builder to know which
     * source texels to fold together.
     */
    public int sourceTexelCount(int level) {
        checkLevel(level);
        if (level == 0) {
            return 1;
        }
        // The last texel of a ceil-halved level has only one real source; clamping gives it two
        // reads of the same texel, so the count stays four and the result is unchanged.
        return 4;
    }
}
