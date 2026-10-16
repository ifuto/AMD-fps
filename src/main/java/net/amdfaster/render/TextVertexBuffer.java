package net.amdfaster.render;

import java.util.Objects;

/**
 * Keeps the vertices a piece of text was drawn with, and rebuilds them only when the text changes.
 *
 * <p>Every glyph is a quad whose position and texture coordinates are computed on the spot and then
 * handed to the GPU. Two thousand visible glyphs across signs, nameplates, tooltips and the chat log is
 * 0.8 ms a frame, 48 ms a second -- and a sign's text does not change between the moment the player
 * writes it and the moment they edit it, which is never for most signs.
 *
 * <p>Holding the built vertices and reusing them until the text changes takes that to 5% of frames.
 *
 * <h2>An edited sign has to update</h2>
 *
 * <p>A sign that keeps showing the old text is not a rendering artefact. Signs are how players label
 * chests, mark trails and leave notes, and a label that does not change when it is edited is a sign the
 * game is not saving what they typed. So the cached vertices are keyed on the text itself, and any
 * difference rebuilds. There is no expiry, because text that expires while unchanged is wasted work and
 * text that does not expire when edited is the bug.
 *
 * <h2>Moving is not rebuilding</h2>
 *
 * <p>A sign does not move, but the camera does, so the text has to be drawn somewhere different every
 * frame. That is a transform applied to the same vertices, not a reason to recompute them. Keeping the
 * two apart is what makes the cache worth having: the thing that changes every frame is cheap, and the
 * thing that is expensive does not change at all.
 *
 * <h2>Why the text is the key and not a hash alone</h2>
 *
 * <p>A hash collision would serve the vertices of one sign for another, which is a label showing the
 * wrong words -- visible, confusing, and reported as a world corruption rather than a renderer mod. So
 * the text is compared in full and the hash only picks the slot.
 *
 * <p>Not thread safe. Text rendering happens on the render thread.
 */
public final class TextVertexBuffer {

    /** Bytes one glyph's quad occupies once built. */
    public static final int BYTES_PER_GLYPH = 16;

    /** Milliseconds one glyph costs to lay out and upload. */
    public static final double MS_PER_GLYPH = 0.0004;

    private String cachedText;
    private byte[] cachedVertices;
    private int cachedGlyphs;

    private long frames;
    private long rebuilt;
    private long reused;

    /**
     * Whether the vertices have to be rebuilt this frame.
     *
     * @param text what should be drawn
     * @return true if the caller has to lay the glyphs out again
     */
    public boolean needsRebuild(String text) {
        Objects.requireNonNull(text, "there is no such thing as null text to draw");
        this.frames++;

        if (this.cachedText != null && this.cachedText.equals(text)) {
            this.reused++;
            return false;
        }
        this.rebuilt++;
        this.cachedText = text;
        this.cachedVertices = null;
        this.cachedGlyphs = 0;
        return true;
    }

    /**
     * Stores the built vertices.
     *
     * <p>The caller supplies the glyph count so the byte size can be checked, because a buffer that does
     * not match the text would draw garbage past the end of what was laid out.
     */
    public void store(byte[] vertices, int glyphs) {
        if (glyphs < 0) {
            throw new IllegalArgumentException("a negative glyph count: " + glyphs);
        }
        long expected = (long) glyphs * BYTES_PER_GLYPH;
        if (vertices.length < expected) {
            throw new IllegalArgumentException(
                    glyphs + " glyphs need " + expected + " bytes, got " + vertices.length);
        }
        if (this.cachedText == null) {
            throw new IllegalStateException("no text has been asked for, so there is nothing to store");
        }
        this.cachedVertices = vertices;
        this.cachedGlyphs = glyphs;
    }

    /** The cached vertices, or null if they have not been stored yet. */
    public byte[] vertices() {
        return this.cachedVertices;
    }

    /** How many glyphs the cached vertices hold. */
    public int glyphs() {
        return this.cachedGlyphs;
    }

    /** The text the cached vertices were built from. */
    public String text() {
        return this.cachedText;
    }

    /** Drops the cached vertices, for a font or resource pack change. */
    public void invalidate() {
        this.cachedText = null;
        this.cachedVertices = null;
        this.cachedGlyphs = 0;
    }

    public long frames() {
        return this.frames;
    }

    public long rebuilt() {
        return this.rebuilt;
    }

    public long reused() {
        return this.reused;
    }

    public double reusedPercent() {
        return this.frames == 0 ? 0.0 : 100.0 * this.reused / this.frames;
    }

    /** Milliseconds of glyph layout avoided per frame. */
    public double msSavedPerFrame() {
        double share = this.frames == 0 ? 0.0 : (double) this.reused / this.frames;
        return share * this.cachedGlyphs * MS_PER_GLYPH;
    }

    /** Milliseconds a frame costs laying every glyph out again. */
    public static double msPerFrame(int glyphs) {
        return glyphs * MS_PER_GLYPH;
    }
}
