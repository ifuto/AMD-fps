package net.amdfaster.cull;

/**
 * The occlusion side of the two-pass cull: project a meshlet's bounds to screen space and read the
 * hierarchical depth buffer at the coarsest level that still covers it.
 *
 * <p>Reading the mip whose texels are about the size of the box is the whole trick. Sampling the
 * finest level means a four-tap gather on a box that covers a thousand pixels, which is both slow
 * and wrong: the max-reduced levels already hold the answer, and a single fetch at the right level
 * is one instruction.
 *
 * <p>The depth buffer is reversed-Z, so "occluded" means the box's <em>nearest</em> depth is
 * farther than the pyramid's <em>maximum</em> at that level -- comparisons run the opposite way to
 * a conventional depth buffer.
 */
public final class HiZ {

    /** A screen-space rectangle in pixels, plus whether the projection was trustworthy. */
    public record ScreenRect(float minX, float minY, float maxX, float maxY, boolean reliable) {

        public float width() {
            return this.maxX - this.minX;
        }

        public float height() {
            return this.maxY - this.minY;
        }

        /**
         * True only when the rectangle is provably outside the viewport. An unreliable rectangle
         * is never off-screen: a box with a corner behind the camera projects to nonsense, and
         * culling on nonsense is how geometry disappears while you look at it.
         */
        public boolean isOffScreen(int screenWidth, int screenHeight) {
            return this.reliable
                    && (this.maxX <= 0f || this.minX >= screenWidth
                    || this.maxY <= 0f || this.minY >= screenHeight);
        }

        /** A rectangle that covers everything, for the cases where projecting is not safe. */
        public static ScreenRect everything() {
            return new ScreenRect(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE, false);
        }
    }

    private HiZ() {
    }

    /** Deepest level of a pyramid built over {@code width x height} by repeated halving. */
    public static int maxLevel(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("pyramid dimensions must be positive");
        }
        int smallest = Math.min(width, height);
        int level = 0;
        while (smallest > 1) {
            smallest >>= 1;
            level++;
        }
        return level;
    }

    /**
     * The coarsest level whose texels are at least as large as the box, clamped to the pyramid.
     *
     * @param aabbWidthPixels  box width in level-0 pixels
     * @param aabbHeightPixels box height in level-0 pixels
     */
    public static int selectLevel(int hiZWidth, int hiZHeight,
                                  float aabbWidthPixels, float aabbHeightPixels) {
        float longest = Math.max(aabbWidthPixels, aabbHeightPixels);
        if (longest <= 1f) {
            return 0;
        }
        int level = (int) Math.ceil(Math.log(longest) / Math.log(2.0));
        return Math.min(level, maxLevel(hiZWidth, hiZHeight));
    }

    /**
     * Projects a world-space box to a pixel rectangle by transforming its eight corners.
     *
     * <p>Not the tightest possible bound -- the silhouette of a rotated box is smaller than the
     * hull of its corners -- but it is cheap, it is what the shader does, and it is conservative in
     * the direction that matters.
     *
     * @return a rectangle in level-0 pixels, {@code reliable} false if any corner landed behind the
     *         camera, in which case the caller must treat the box as visible
     */
    public static ScreenRect projectToPixels(float minX, float minY, float minZ,
                                             float maxX, float maxY, float maxZ,
                                             float[] viewProjection,
                                             int screenWidth, int screenHeight) {
        float loX = Float.POSITIVE_INFINITY;
        float loY = Float.POSITIVE_INFINITY;
        float hiX = Float.NEGATIVE_INFINITY;
        float hiY = Float.NEGATIVE_INFINITY;

        for (int corner = 0; corner < 8; corner++) {
            float x = (corner & 1) != 0 ? maxX : minX;
            float y = (corner & 2) != 0 ? maxY : minY;
            float z = (corner & 4) != 0 ? maxZ : minZ;

            float clipX = viewProjection[0] * x + viewProjection[4] * y
                    + viewProjection[8] * z + viewProjection[12];
            float clipY = viewProjection[1] * x + viewProjection[5] * y
                    + viewProjection[9] * z + viewProjection[13];
            float clipW = viewProjection[3] * x + viewProjection[7] * y
                    + viewProjection[11] * z + viewProjection[15];
            if (clipW <= 0f) {
                return ScreenRect.everything();
            }

            float ndcX = clipX / clipW;
            float ndcY = clipY / clipW;
            // Vulkan's framebuffer has Y increasing downwards; NDC has it upwards.
            float pixelX = (ndcX * 0.5f + 0.5f) * screenWidth;
            float pixelY = (0.5f - ndcY * 0.5f) * screenHeight;

            loX = Math.min(loX, pixelX);
            loY = Math.min(loY, pixelY);
            hiX = Math.max(hiX, pixelX);
            hiY = Math.max(hiY, pixelY);
        }
        return new ScreenRect(loX, loY, hiX, hiY, true);
    }
}
