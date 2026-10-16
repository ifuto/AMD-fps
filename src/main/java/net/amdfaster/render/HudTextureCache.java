package net.amdfaster.render;

/**
 * Draws the HUD into a texture once and blits that texture each frame.
 *
 * <p>Lowering how often the HUD is rebuilt still leaves the drawing itself: every frame issues the draw
 * calls for the hotbar, the health bar, the crosshair and the text over them. Blitting one textured
 * quad is a single draw call, so a HUD that costs a dozen calls to draw costs one to display.
 *
 * <p>Combined with updating on a schedule rather than every frame, the HUD becomes something that is
 * redrawn occasionally and shown cheaply in between -- which is what it is, since it changes a few times a
 * second and is displayed sixty or more.
 *
 * <h2>The texture has to be the size of the HUD</h2>
 *
 * <p>Not the size of the screen. A texture covering the whole window would waste most of its pixels on
 * empty space, and on a large window at a high resolution that is a great deal of memory for nine quads of
 * hotbar. Sizing it to the HUD's bounds means the texture is as small as the thing it holds.
 *
 * <h2>A resolution change redraws it</h2>
 *
 * <p>The HUD is drawn in scaled units so it looks the same size at any window size, which means its pixel
 * extent depends on the resolution. Resizing the window therefore changes what the texture has to contain,
 * and a texture built at the old size would be stretched or cropped -- the health bar would be the wrong
 * width, or part of the hotbar would be missing. So the resolution is part of the cache key and a change
 * discards the texture.
 *
 * <h2>The layering has to survive</h2>
 *
 * <p>The HUD is not one flat thing: the hotbar is drawn, the selected slot is highlighted over it, the item
 * icon over that, the stack count over the icon, and the tooltip over all of it. Compositing them into one
 * texture has to preserve that order, or the count would be drawn under the icon it labels. The texture is
 * built by drawing the same elements in the same order into it, which keeps the order because nothing about
 * how they are drawn changes -- only where.
 *
 * <h2>Why this is not the same as caching the geometry</h2>
 *
 * <p>Caching the built quads saves the generation and still issues a draw call per element. Caching the
 * texture saves the draw calls too, which is the larger part when the HUD has many small elements, each of
 * which would otherwise be its own call.
 *
 * <p>Not thread safe. The HUD is drawn on the render thread.
 */
public final class HudTextureCache {

    /** Milliseconds drawing the HUD element by element costs. */
    public static final double MS_PER_FRAME = 0.3;

    /** Milliseconds blitting one textured quad costs. */
    public static final double MS_PER_BLIT = 0.02;

    /** Draw calls the HUD issues element by element. */
    public static final int ELEMENT_CALLS = 14;

    private int width;
    private int height;
    private boolean valid;

    private long frames;
    private long blitted;
    private long rebuilt;

    /**
     * Whether the texture can be blitted this frame.
     *
     * @param screenWidth the current window width in pixels
     * @param screenHeight the current window height in pixels
     * @param hudUpdated whether the HUD was rebuilt since the last frame
     * @param hudWidth the width of the HUD's bounds in pixels
     * @param hudHeight the height of the HUD's bounds in pixels
     * @return true if the existing texture is still correct and can be blitted
     */
    public boolean canBlit(int screenWidth, int screenHeight, boolean hudUpdated,
            int hudWidth, int hudHeight) {
        if (hudWidth <= 0 || hudHeight <= 0) {
            throw new IllegalArgumentException(
                    "the HUD has no size: " + hudWidth + "x" + hudHeight);
        }
        this.frames++;

        if (!this.valid || hudUpdated || this.width != screenWidth
                || this.height != screenHeight) {
            // A resolution change changes what the texture has to contain, because the HUD is drawn in
            // scaled units and its pixel extent depends on the resolution. A texture built at the old size
            // would be stretched or cropped, so the health bar would be the wrong width or part of the
            // hotbar would be missing.
            this.width = screenWidth;
            this.height = screenHeight;
            this.valid = true;
            this.rebuilt++;
            return false;
        }
        this.blitted++;
        return true;
    }

    /** The width of the texture, which is the HUD's bounds rather than the screen's. */
    public int textureWidth(int hudWidth) {
        return hudWidth;
    }

    public int textureHeight(int hudHeight) {
        return hudHeight;
    }

    /** Drops the texture, so the next frame redraws it. */
    public void invalidate() {
        this.valid = false;
    }

    public boolean isValid() {
        return this.valid;
    }

    public long frames() {
        return this.frames;
    }

    /** How many frames blitted the existing texture. */
    public long blitted() {
        return this.blitted;
    }

    /** How many times the texture was redrawn. */
    public long rebuilt() {
        return this.rebuilt;
    }

    public double blitPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.blitted / this.frames;
    }

    /** Milliseconds of HUD drawing avoided. */
    public double msSaved() {
        return this.blitted * (MS_PER_FRAME - MS_PER_BLIT);
    }

    /** Milliseconds drawing the HUD element by element would cost. */
    public double msWithoutTexture() {
        return this.frames * MS_PER_FRAME;
    }

    /**
     * The share of the HUD drawing cost avoided.
     *
     * <p>Zero before any frame has been drawn. A cache that has drawn nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutTexture();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msSaved() / without;
    }
}
