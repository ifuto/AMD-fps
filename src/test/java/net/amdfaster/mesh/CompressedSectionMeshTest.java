package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressedSectionMeshTest {

    private static final int ATLAS = 1024;

    /** The six template faces, in the order QuadTemplateLibrary defines them. */
    private static float[][] face(int template) {
        float[] out = new float[12];
        new QuadTemplateLibrary().templateVertices(template, out);
        return out;
    }

    private static float[] standardUvs() {
        float[][] canonical = QuadTemplateLibrary.standardUvs();
        float[] flat = new float[8];
        for (int v = 0; v < 4; v++) {
            flat[v * 2] = canonical[v][0];
            flat[v * 2 + 1] = canonical[v][1];
        }
        return flat;
    }

    private static float[] axisNormal(int template) {
        int fixedAxis = template / 2 == 0 ? 1 : (template / 2 == 1 ? 2 : 0);
        float[] n = new float[3];
        n[fixedAxis] = template % 2 == 0 ? -1f : 1f;
        return n;
    }

    private static CompressedSectionMesh.InputQuad templateQuad(int template, int light) {
        int[] block = {light, light, light, light};
        int[] sky = {15, 15, 15, 15};
        int[] ao = {3, 3, 3, 3};
        return new CompressedSectionMesh.InputQuad(
                face(template), standardUvs(), axisNormal(template), block, sky, ao);
    }

    @Test
    void aFullCubeFaceIsFiveBytesAndReconstructsExactly() {
        // The case that matters most: most of a solid chunk is full cube faces, and for those the
        // template supplies the exact corners, normal and UVs, so nothing is lost at all.
        CompressedSectionMesh mesh = new CompressedSectionMesh(64);

        assertTrue(mesh.add(templateQuad(1, 12), 3, 70, 5, ATLAS));

        assertEquals(1, mesh.quadCount());
        assertEquals(5, mesh.bytes());
        assertEquals(1L, mesh.templateQuads());
        assertEquals(0L, mesh.generalQuads());

        float[] corners = new float[12];
        float[] normal = new float[3];
        mesh.reconstruct(0, corners, normal);

        float[] want = face(1);
        for (int i = 0; i < 12; i++) {
            assertEquals(want[i], corners[i], 0.0f, "corner " + i);
        }
        assertEquals(0f, normal[0], 0.0f);
        assertEquals(1f, normal[1], 0.0f, "the up face points up");
        assertEquals(0f, normal[2], 0.0f);

        assertTrue(mesh.measure(0, templateQuad(1, 12), ATLAS));
        assertEquals(0.0, mesh.worstPositionError(), 0.0);
        assertEquals(0.0, mesh.worstNormalErrorDegrees(), 1e-6);
    }

    @Test
    void allSixFacesGetTheRightNormal() {
        // Pinned separately, because the pairing -- templates in pairs along Y, then Z, then X, with
        // the even one facing the negative direction -- is easy to get wrong in a way that flips a face
        // and makes back-face culling remove it.
        CompressedSectionMesh mesh = new CompressedSectionMesh(64);
        float[][] wanted = {
            {0f, -1f, 0f}, {0f, 1f, 0f},
            {0f, 0f, -1f}, {0f, 0f, 1f},
            {-1f, 0f, 0f}, {1f, 0f, 0f},
        };

        for (int template = 0; template < 6; template++) {
            mesh.add(templateQuad(template, 10), 0, 0, 0, ATLAS);
        }
        assertEquals(6, mesh.quadCount());
        assertEquals(30, mesh.bytes(), "six quads at five bytes");

        for (int template = 0; template < 6; template++) {
            float[] normal = new float[3];
            mesh.reconstruct(template, new float[12], normal);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(wanted[template][axis], normal[axis], 0.0f,
                        "template " + template + " axis " + axis);
            }
        }
    }

    @Test
    void aGeneralQuadIsThirtyEightBytesAndLandsInsideTheTolerance() {
        // A face inset by a sixteenth, which is not a template. Positions are quantised over the quad's
        // own bounds, so the error is a fraction of the quad rather than of the section.
        float[] corners = {
            0.0625f, 1f, 0.0625f,
            0.0625f, 1f, 0.9375f,
            0.9375f, 1f, 0.9375f,
            0.9375f, 1f, 0.0625f,
        };
        CompressedSectionMesh.InputQuad quad = new CompressedSectionMesh.InputQuad(
                corners, standardUvs(), new float[] {0f, 1f, 0f},
                new int[] {15, 15, 15, 15}, new int[] {15, 15, 15, 15}, new int[] {3, 3, 3, 3});

        CompressedSectionMesh mesh = new CompressedSectionMesh(16);
        assertTrue(mesh.add(quad, 0, 0, 0, ATLAS));

        assertEquals(38, mesh.bytes());
        assertEquals(38, CompressedSectionMesh.GENERAL_SLOT_BYTES);
        assertEquals(1L, mesh.generalQuads());
        assertEquals(0L, mesh.templateQuads());

        assertTrue(mesh.measure(0, quad, ATLAS), "inside the tolerances");
        assertTrue(mesh.worstPositionError() < 1e-4,
                "positions snap to the model grid, error was " + mesh.worstPositionError());
        assertTrue(mesh.worstNormalErrorDegrees() < 1.0,
                "normal error was " + mesh.worstNormalErrorDegrees());
        assertTrue(mesh.worstUvErrorTexels() < 0.5,
                "uv error was " + mesh.worstUvErrorTexels() + " texels");
        assertEquals(0L, mesh.fallbacks());
    }

    @Test
    void aSectionOfStoneCompressesByMoreThanTwentyTimes() {
        CompressedSectionMesh mesh = new CompressedSectionMesh(512);

        for (int template = 0; template < 256; template++) {
            mesh.add(templateQuad(template % 6, 10 + (template % 6), template % 16),
                    template % 16, template / 16, 0, ATLAS);
        }

        assertEquals(256, mesh.quadCount());
        assertEquals(256 * 5, mesh.bytes());
        assertEquals(256L * 4 * VertexQuantizer.UNCOMPRESSED_BYTES, mesh.uncompressedBytes());
        assertEquals(25.6, mesh.compressionRatio(), 0.01);
    }

    @Test
    void aQuadThatIsTooFarOffFallsBack() {
        // A normal the octahedral encoding cannot represent closely enough. The section keeps its
        // original vertices rather than shipping something visibly wrong.
        CompressedSectionMesh mesh = new CompressedSectionMesh(16);
        float[] corners = {
            0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, 0f,
        };
        // Not a unit vector, and deliberately not one of the six axes.
        float[] normal = {0.1f, 0.1f, 0.1f};
        CompressedSectionMesh.InputQuad quad = new CompressedSectionMesh.InputQuad(
                corners, standardUvs(), normal,
                new int[] {0, 0, 0, 0}, new int[] {0, 0, 0, 0}, new int[] {0, 0, 0, 0});

        mesh.add(quad, 0, 0, 0, ATLAS);

        assertFalse(mesh.measure(0, quad, ATLAS),
                "a normal of (0.1, 0.1, 0.1) is not unit length, so the comparison is against a vector"
                        + " the encoding was never given");
        assertEquals(1L, mesh.fallbacks());
    }

    @Test
    void lightAndOcclusionSurviveBothPaths() {
        CompressedSectionMesh mesh = new CompressedSectionMesh(16);
        int[] block = {0, 5, 10, 15};
        int[] sky = {15, 10, 5, 0};
        int[] ao = {0, 1, 2, 3};
        CompressedSectionMesh.InputQuad quad = new CompressedSectionMesh.InputQuad(
                face(3), standardUvs(), axisNormal(3), block, sky, ao);

        mesh.add(quad, 0, 0, 0, ATLAS);
        assertEquals(5, mesh.bytes(), "still a template quad");
        assertTrue(mesh.measure(0, quad, ATLAS));
        assertEquals(0.0, mesh.worstPositionError(), 0.0);
    }

    @Test
    void aFullMeshRefusesMoreQuads() {
        CompressedSectionMesh mesh = new CompressedSectionMesh(2);

        assertTrue(mesh.add(templateQuad(0, 1), 0, 0, 0, ATLAS));
        assertTrue(mesh.add(templateQuad(1, 1), 0, 1, 0, ATLAS));
        assertFalse(mesh.add(templateQuad(2, 1), 0, 2, 0, ATLAS), "full");
        assertEquals(2, mesh.quadCount());
    }

    @Test
    void tolerancesAreTheOnesDocumented() {
        assertEquals(1.0 / 16.0, CompressedSectionMesh.POSITION_TOLERANCE, 1e-12,
                "a sixteenth, the finest step a block model has");
        assertEquals(2.0, CompressedSectionMesh.NORMAL_TOLERANCE);
        assertEquals(0.5, CompressedSectionMesh.UV_TOLERANCE);
        assertEquals(38, CompressedSectionMesh.GENERAL_SLOT_BYTES);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CompressedSectionMesh(0));

        CompressedSectionMesh mesh = new CompressedSectionMesh(8);
        mesh.add(templateQuad(1, 5), 0, 0, 0, ATLAS);

        assertThrows(IllegalArgumentException.class,
                () -> new CompressedSectionMesh.InputQuad(new float[11], standardUvs(),
                        new float[3], new int[4], new int[4], new int[4]));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressedSectionMesh.InputQuad(face(0), new float[7],
                        new float[3], new int[4], new int[4], new int[4]));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressedSectionMesh.InputQuad(face(0), standardUvs(),
                        new float[2], new int[4], new int[4], new int[4]));
        assertThrows(IllegalArgumentException.class,
                () -> new CompressedSectionMesh.InputQuad(face(0), standardUvs(),
                        new float[3], new int[3], new int[4], new int[4]));
        assertThrows(IllegalArgumentException.class,
                () -> mesh.reconstruct(0, new float[11], new float[3]));
        assertThrows(IndexOutOfBoundsException.class, () -> mesh.reconstruct(1, new float[12],
                new float[3]));
    }

    @Test
    void aFreshMeshReportsZeroNotNaN() {
        CompressedSectionMesh mesh = new CompressedSectionMesh(8);
        assertEquals(0.0, mesh.compressionRatio());
        assertEquals(0, mesh.bytes());
        assertEquals(0, mesh.quadCount());
        assertEquals(0.0, mesh.worstPositionError());
        assertEquals(0.0, mesh.worstNormalErrorDegrees());
        assertEquals(0.0, mesh.worstUvErrorTexels());
    }
}
