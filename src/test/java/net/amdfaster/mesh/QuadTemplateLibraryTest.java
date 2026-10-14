package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuadTemplateLibraryTest {

    private static float[] face(int template) {
        float[] out = new float[12];
        new QuadTemplateLibrary().templateVertices(template, out);
        return out;
    }

    private static float[] offset(float[] corners, float dx, float dy, float dz) {
        float[] out = corners.clone();
        for (int v = 0; v < 4; v++) {
            out[v * 3] += dx;
            out[v * 3 + 1] += dy;
            out[v * 3 + 2] += dz;
        }
        return out;
    }

    @Test
    void everyTemplateMatchesItselfAndNothingElse() {
        // If two faces matched the same quad, whichever came first in the loop would win and the
        // mesher would emit the wrong face. Six distinct shapes, six distinct answers.
        QuadTemplateLibrary library = new QuadTemplateLibrary();

        for (int template = 0; template < QuadTemplateLibrary.TEMPLATE_COUNT; template++) {
            assertEquals(template, library.templateFor(face(template)),
                    "template " + template + " recognised");
        }
        assertEquals(6L, library.matches());
        for (int template = 0; template < QuadTemplateLibrary.TEMPLATE_COUNT; template++) {
            assertEquals(1L, library.matchesFor(template), "template " + template + " hit once");
        }
        assertEquals(100.0, library.matchRatePercent());
    }

    @Test
    void eachFaceHasTheAxisItClaims() {
        // The winding tables are easy to get wrong in a way that produces a face on the wrong side of
        // the cube, so the fixed coordinate is checked against the name.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        int[] fixedAxis = {1, 1, 2, 2, 0, 0};
        float[] fixedValue = {0f, 1f, 0f, 1f, 0f, 1f};

        for (int t = 0; t < QuadTemplateLibrary.TEMPLATE_COUNT; t++) {
            float[] corners = face(t);
            for (int v = 0; v < QuadTemplateLibrary.VERTICES; v++) {
                assertEquals(fixedValue[t], corners[v * 3 + fixedAxis[t]],
                        "template " + t + " vertex " + v + " fixed coordinate");
            }
        }
        assertEquals(6, Orientation.COUNT, "one per orientation");
    }

    @Test
    void aReversedWindingMatchesNothing() {
        // The same four corners traversed the other way round is the inside of the cube, and
        // back-face culling would drop it. Treating it as the same draw would make faces disappear.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[] up = face(1);
        float[] reversed = new float[12];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(up, (3 - v) * 3, reversed, v * 3, 3);
        }

        assertEquals(QuadTemplateLibrary.NO_MATCH, library.templateFor(reversed));
        assertFalse(library.sameWinding(up, reversed));
        assertTrue(library.sameWinding(up, up.clone()));
    }

    @Test
    void aBlockModelInsetByASixteenthDoesNotMatch() {
        // Connected textures and inset models are not full faces. Claiming one is produces a face at
        // the wrong size, which is visible rather than merely slow.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[] inset = face(1);
        for (int v = 0; v < 4; v++) {
            inset[v * 3] = inset[v * 3] * (14f / 16f) + 1f / 16f;
            inset[v * 3 + 2] = inset[v * 3 + 2] * (14f / 16f) + 1f / 16f;
        }

        assertEquals(QuadTemplateLibrary.NO_MATCH, library.templateFor(inset));
        assertTrue(1f / 16f > QuadTemplateLibrary.TOLERANCE * 100,
                "and the inset is far outside the tolerance, not marginally outside it");
    }

    @Test
    void aWholeBlockOffsetIsAllowedFor() {
        // The mesher works in section-local coordinates, so the corners are the canonical ones plus a
        // block offset. Comparing against the offset avoids translating the corners back and rounding.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[] moved = offset(face(3), 7f, 12f, 4f);

        assertEquals(QuadTemplateLibrary.NO_MATCH, library.templateFor(moved),
                "without the offset it does not match");
        assertEquals(3, library.templateFor(moved, 7f, 12f, 4f));
    }

    @Test
    void standardUvsAreRecognisedInAnyRotation() {
        // A rotated texture is the same geometry, and a renderer can rotate the UV in the shader, so a
        // rotation should not disqualify the quad from being instanced.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[][] canonical = QuadTemplateLibrary.standardUvs();
        float[] flat = new float[8];
        for (int v = 0; v < 4; v++) {
            flat[v * 2] = canonical[v][0];
            flat[v * 2 + 1] = canonical[v][1];
        }
        assertTrue(library.hasStandardUvs(flat));

        // Rotated by one corner.
        float[] rotated = new float[8];
        for (int v = 0; v < 4; v++) {
            rotated[v * 2] = canonical[(v + 1) % 4][0];
            rotated[v * 2 + 1] = canonical[(v + 1) % 4][1];
        }
        assertTrue(library.hasStandardUvs(rotated), "a rotation is still standard");

        // A half-tile texture is not.
        float[] partial = flat.clone();
        partial[2] = 0.5f;
        assertFalse(library.hasStandardUvs(partial));
    }

    @Test
    void aRepeatedCornerIsNotAStandardUvSet() {
        // Compared as a multiset, so a quad using one corner twice has to fail rather than pass on the
        // first two matches.
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[] doubled = {0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f};

        assertFalse(library.hasStandardUvs(doubled));
    }

    @Test
    void templateVerticesFillsTheArrayAndCanBeReadBack() {
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        float[] out = new float[12];

        library.templateVertices(5, out);

        assertEquals(1f, out[0], "east face starts at x = 1");
        assertEquals(1f, out[3]);
        assertEquals(1f, out[6]);
        assertEquals(1f, out[9]);
        assertEquals(library.templateFor(out), 5);
    }

    @Test
    void matchRateReportsTheProportionOfStandardQuads() {
        // The figure that decides whether this is worth having: in a stone chunk almost every exposed
        // face is a full cube face.
        QuadTemplateLibrary library = new QuadTemplateLibrary();

        for (int i = 0; i < 600; i++) {
            library.templateFor(face(i % 6));
        }
        for (int i = 0; i < 30; i++) {
            // Inset quads, as a model that is not a full cube would produce.
            float[] inset = face(0);
            inset[0] += 0.25f;
            library.templateFor(inset);
        }

        assertEquals(630L, library.quads());
        assertEquals(600L, library.matches());
        assertEquals(95.2, library.matchRatePercent(), 0.1);
        assertEquals(100L, library.matchesFor(0), "and the templates are spread evenly");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        QuadTemplateLibrary library = new QuadTemplateLibrary();

        assertThrows(IllegalArgumentException.class, () -> library.templateFor(new float[11]));
        assertThrows(IllegalArgumentException.class, () -> library.templateFor(new float[13], 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> library.hasStandardUvs(new float[7]));
        assertThrows(IllegalArgumentException.class, () -> library.sameWinding(new float[12], new float[6]));
        assertThrows(IndexOutOfBoundsException.class, () -> library.templateVertices(-1, new float[12]));
        assertThrows(IndexOutOfBoundsException.class, () -> library.templateVertices(6, new float[12]));
        assertThrows(IndexOutOfBoundsException.class, () -> library.matchesFor(6));
        assertThrows(IllegalArgumentException.class,
                () -> library.templateVertices(0, new float[11]));
    }

    @Test
    void aFreshLibraryReportsZeroNotNaN() {
        QuadTemplateLibrary library = new QuadTemplateLibrary();
        assertEquals(0.0, library.matchRatePercent());
        assertEquals(0L, library.quads());
        assertEquals(6, QuadTemplateLibrary.TEMPLATE_COUNT);
        assertEquals(4, QuadTemplateLibrary.VERTICES);
    }
}
