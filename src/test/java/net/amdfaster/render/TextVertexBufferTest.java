package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextVertexBufferTest {

    private static final String TEXT = "Chest of diamonds";

    private static byte[] verticesFor(int glyphs) {
        return new byte[glyphs * TextVertexBuffer.BYTES_PER_GLYPH];
    }

    @Test
    void theSameTextIsNotLaidOutAgain() {
        // A sign's text does not change between the moment the player writes it and the moment they edit
        // it, which for most signs is never -- yet every glyph is a quad laid out and uploaded again each
        // frame.
        TextVertexBuffer buffer = new TextVertexBuffer();

        assertTrue(buffer.needsRebuild(TEXT), "the first frame builds it");
        buffer.store(verticesFor(TEXT.length()), TEXT.length());

        for (int i = 0; i < 99; i++) {
            assertFalse(buffer.needsRebuild(TEXT), "frame " + i);
        }

        assertEquals(1L, buffer.rebuilt());
        assertEquals(99L, buffer.reused());
        assertEquals(99.0, buffer.reusedPercent(), 0.01);
    }

    @Test
    void editedTextRebuilds() {
        // A sign that keeps showing the old text is not a rendering artefact. Signs are how players label
        // chests and mark trails, and a label that does not change when edited looks like the game is not
        // saving what they typed.
        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);
        buffer.store(verticesFor(TEXT.length()), TEXT.length());

        assertTrue(buffer.needsRebuild("Chest of iron"));
        assertEquals(2L, buffer.rebuilt());
        assertNull(buffer.vertices(), "the old vertices are dropped, not served");
    }

    @Test
    void theVerticesSurviveWhileTheCameraMoves() {
        // A sign does not move but the camera does, so the text is drawn somewhere different every frame.
        // That is a transform applied to the same vertices, not a reason to recompute them.
        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);
        byte[] stored = verticesFor(TEXT.length());
        buffer.store(stored, TEXT.length());

        for (int frame = 0; frame < 100; frame++) {
            assertFalse(buffer.needsRebuild(TEXT));
            assertSame(stored, buffer.vertices(), "the same buffer, frame " + frame);
        }
        assertEquals(1L, buffer.rebuilt());
    }

    @Test
    void textIsComparedNotJustHashed() {
        // A hash collision would serve one sign's vertices for another, which is a label showing the
        // wrong words -- reported as world corruption rather than a renderer mod.
        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);
        buffer.store(verticesFor(TEXT.length()), TEXT.length());

        assertTrue(buffer.needsRebuild("Chest of diamond!"),
                "the same length, so a length check would not catch it");
        assertEquals("Chest of diamond!", buffer.text(),
                "what was last asked for, not what was built before");
    }

    @Test
    void emptyTextIsAllowed() {
        // A wiped sign is a real state, not an error.
        TextVertexBuffer buffer = new TextVertexBuffer();

        assertTrue(buffer.needsRebuild(""));
        buffer.store(new byte[0], 0);

        assertFalse(buffer.needsRebuild(""));
        assertEquals(0, buffer.glyphs());
    }

    @Test
    void aShortBufferIsRejected() {
        // A buffer that does not match the text would draw garbage past the end of what was laid out.
        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);

        assertThrows(IllegalArgumentException.class,
                () -> buffer.store(verticesFor(TEXT.length() - 1), TEXT.length()));
    }

    @Test
    void storingWithoutAskingIsRejected() {
        TextVertexBuffer buffer = new TextVertexBuffer();

        assertThrows(IllegalStateException.class, () -> buffer.store(verticesFor(4), 4));
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        // For a font or resource pack change, where the cached glyph shapes are from the old font.
        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);
        buffer.store(verticesFor(TEXT.length()), TEXT.length());

        buffer.invalidate();

        assertNull(buffer.text());
        assertTrue(buffer.needsRebuild(TEXT));
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        assertEquals(0.8, TextVertexBuffer.msPerFrame(2000), 1e-9);

        TextVertexBuffer buffer = new TextVertexBuffer();
        buffer.needsRebuild(TEXT);
        buffer.store(verticesFor(TEXT.length()), TEXT.length());
        for (int i = 0; i < 99; i++) {
            buffer.needsRebuild(TEXT);
        }

        assertEquals(99.0, buffer.reusedPercent(), 0.01);
        assertEquals(0.99 * TEXT.length() * TextVertexBuffer.MS_PER_GLYPH,
                buffer.msSavedPerFrame(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        TextVertexBuffer buffer = new TextVertexBuffer();

        assertThrows(NullPointerException.class, () -> buffer.needsRebuild(null));
        buffer.needsRebuild(TEXT);
        assertThrows(IllegalArgumentException.class, () -> buffer.store(verticesFor(4), -1));
    }

    @Test
    void aFreshBufferReportsZeroNotNaN() {
        TextVertexBuffer buffer = new TextVertexBuffer();

        assertEquals(0.0, buffer.reusedPercent());
        assertEquals(0.0, buffer.msSavedPerFrame());
        assertEquals(0L, buffer.frames());
        assertEquals(0, buffer.glyphs());
        assertNull(buffer.text());
        assertNull(buffer.vertices());
        assertEquals(16, TextVertexBuffer.BYTES_PER_GLYPH);
    }
}
