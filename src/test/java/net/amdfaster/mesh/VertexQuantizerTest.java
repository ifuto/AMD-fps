package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexQuantizerTest {

    @Test
    void theSixAxisNormalsACubeUsesAreExact() {
        // The important case. Every full cube face has one of these six normals, and a quantised normal
        // that was slightly off would tilt the shading on every block in the world.
        VertexQuantizer quantizer = new VertexQuantizer();
        float[][] axes = {
            {0f, 1f, 0f}, {0f, -1f, 0f},
            {1f, 0f, 0f}, {-1f, 0f, 0f},
            {0f, 0f, 1f}, {0f, 0f, -1f},
        };

        for (float[] axis : axes) {
            int packed = quantizer.encodeNormal(axis[0], axis[1], axis[2]);
            float[] decoded = VertexQuantizer.decodeNormal(packed);
            for (int i = 0; i < 3; i++) {
                assertEquals(axis[i], decoded[i], 1e-5f, "axis " + i);
            }
        }
        assertEquals(0.0, quantizer.worstNormalErrorDegrees(), 1e-5);
    }

    @Test
    void aRandomNormalIsWithinADegreeAfterTheRenormalise() {
        // Measured, not estimated: 0.94 degrees worst case over a hundred thousand directions. The
        // earlier figure in the class comment was 0.45, which was the step between quantised components
        // rather than the angle the result is off by.
        VertexQuantizer quantizer = new VertexQuantizer();
        Random random = new Random(20261015L);

        for (int i = 0; i < 100_000; i++) {
            float x = random.nextFloat() * 2f - 1f;
            float y = random.nextFloat() * 2f - 1f;
            float z = random.nextFloat() * 2f - 1f;
            if (Math.abs(x) + Math.abs(y) + Math.abs(z) < 1e-4f) {
                continue;
            }
            int packed = quantizer.encodeNormal(x, y, z);
            float[] decoded = VertexQuantizer.decodeNormal(packed);

            float length = (float) Math.sqrt(
                    decoded[0] * decoded[0] + decoded[1] * decoded[1] + decoded[2] * decoded[2]);
            assertEquals(1.0f, length, 1e-4f, "the decoded vector is unit length");
        }
        assertTrue(quantizer.worstNormalErrorDegrees() < 1.0,
                "worst case was " + quantizer.worstNormalErrorDegrees() + " degrees");
    }

    @Test
    void decodingWithoutTheRenormaliseWouldNotBeUnitLength() {
        // The reason the fixup is in the decode rather than left to the shader. A vector that is not
        // unit length darkens diffuse shading at grazing angles, which reads as a lighting bug rather
        // than as a compression artefact.
        int packed = new VertexQuantizer().encodeNormal(1f, 1f, 1f);

        byte low = (byte) (packed & 0xFF);
        byte high = (byte) ((packed >> 8) & 0xFF);
        float ox = low / 127.0f;
        float oy = high / 127.0f;
        float z = 1.0f - Math.abs(ox) - Math.abs(oy);
        float raw = (float) Math.sqrt(ox * ox + oy * oy + z * z);

        assertTrue(Math.abs(raw - 1.0f) > 1e-3f, "the raw decode is not unit length: " + raw);
        float[] fixed = VertexQuantizer.decodeNormal(packed);
        float length = (float) Math.sqrt(
                fixed[0] * fixed[0] + fixed[1] * fixed[1] + fixed[2] * fixed[2]);
        assertEquals(1.0f, length, 1e-5f, "and the decode fixes it");
    }

    @Test
    void positionsOnTheModelGridAreExact() {
        // The compensation that makes this lossless for vanilla geometry. Block models are built on a
        // sixteenth-of-a-block grid, so quantising to sixteenths is not an approximation of them.
        VertexQuantizer quantizer = new VertexQuantizer();

        for (int step = 0; step <= 16; step++) {
            float original = step / 16.0f;
            int quantised = quantizer.quantiseOnModelGrid(original);
            assertEquals(step, quantised, "step " + step);
            assertEquals(original, VertexQuantizer.decodeModelGrid(quantised), 1e-6f);
        }
    }

    @Test
    void aLocalRangeShrinksThePositionError() {
        // Quantising over a whole 16 block section makes 10 bits marginal; quantising over a meshlet's
        // bounds covers a much smaller range with the same bits.
        VertexQuantizer coarse = new VertexQuantizer();
        coarse.quantisePosition(3.7f, 0f, 16f);
        assertTrue(coarse.worstPositionError() < 0.008,
                "a 16 block range at 10 bits: " + coarse.worstPositionError());

        VertexQuantizer fine = new VertexQuantizer();
        fine.quantisePosition(3.7f, 3.0f, 4.0f);
        assertTrue(fine.worstPositionError() < 0.0005,
                "the same bits over one block: " + fine.worstPositionError());
        assertTrue(fine.worstPositionError() < coarse.worstPositionError() / 10,
                "and it is more than ten times better");
    }

    @Test
    void aQuantisedUvNeverLeavesItsTile() {
        // Without the inset a quantised value can land a hair outside the tile, and once mipmaps kick
        // in the sample picks up the neighbouring tile's edge -- a dark seam on block borders seen from
        // a distance, which is very hard to trace back to compression.
        VertexQuantizer quantizer = new VertexQuantizer();
        int atlas = 4096;

        for (int i = 0; i <= 1000; i++) {
            float uv = i / 1000.0f;
            int quantised = quantizer.quantiseUv(uv, atlas);
            assertTrue(quantised >= 0 && quantised <= 65535);

            float raw = quantised / 65535.0f;
            assertTrue(raw >= 0.5f / atlas && raw <= 1.0f - 0.5f / atlas,
                    "stays half a texel inside the tile at uv = " + uv);
        }
        assertTrue(quantizer.worstUvErrorTexels() < 0.05,
                "worst error was " + quantizer.worstUvErrorTexels() + " texels");
    }

    @Test
    void aUvRoundTrips() {
        VertexQuantizer quantizer = new VertexQuantizer();

        for (int i = 0; i <= 100; i++) {
            float uv = i / 100.0f;
            int quantised = quantizer.quantiseUv(uv, 1024);
            assertEquals(uv, VertexQuantizer.decodeUv(quantised, 1024), 1e-4f, "uv " + uv);
        }
    }

    @Test
    void lightAndOcclusionPackExactly() {
        // Both are already discrete: sixteen light levels and four occlusion levels. Nothing is lost.
        for (int block = 0; block < 16; block++) {
            for (int sky = 0; sky < 16; sky++) {
                for (int ao = 0; ao < 4; ao++) {
                    int packed = VertexQuantizer.packLight(block, sky, ao);
                    assertEquals(block, VertexQuantizer.unpackBlockLight(packed));
                    assertEquals(sky, VertexQuantizer.unpackSkyLight(packed));
                    assertEquals(ao, VertexQuantizer.unpackOcclusion(packed));
                }
            }
        }
    }

    @Test
    void aTemplateQuadIsFiveBytesAndRoundTrips() {
        // Position, normal and UV come from the template exactly, so the only per-vertex data is light
        // and occlusion: ten bits each, forty bits for a quad that would otherwise be 128 bytes.
        VertexQuantizer quantizer = new VertexQuantizer();
        Random random = new Random(7L);

        for (int trial = 0; trial < 10_000; trial++) {
            int[] packed = new int[4];
            for (int v = 0; v < 4; v++) {
                packed[v] = VertexQuantizer.packLight(
                        random.nextInt(16), random.nextInt(16), random.nextInt(4));
            }
            byte[] out = new byte[VertexQuantizer.TEMPLATE_QUAD_BYTES];
            quantizer.packTemplateQuad(packed, out);

            assertArrayEquals(packed, VertexQuantizer.unpackTemplateQuad(out), "trial " + trial);
        }
        assertEquals(10_000L, quantizer.templateQuads());
        assertEquals(40_000L, quantizer.vertices());
        assertEquals(1.25, quantizer.bytesPerVertex(), 1e-9, "five bytes for four vertices");
    }

    @Test
    void theSavingAgainstAnUncompressedQuad() {
        assertEquals(32, VertexQuantizer.UNCOMPRESSED_BYTES);
        assertEquals(128, 4 * VertexQuantizer.UNCOMPRESSED_BYTES, "an uncompressed quad");
        assertEquals(5, VertexQuantizer.TEMPLATE_QUAD_BYTES);
        assertEquals(25.6, 128.0 / VertexQuantizer.TEMPLATE_QUAD_BYTES, 0.01, "times smaller");
        assertEquals(9, VertexQuantizer.GENERAL_BYTES);
        assertEquals(10, VertexQuantizer.POSITION_BITS);
        assertEquals(8, VertexQuantizer.NORMAL_BITS);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        VertexQuantizer quantizer = new VertexQuantizer();

        assertThrows(IllegalArgumentException.class, () -> quantizer.quantiseOnModelGrid(1.5f));
        assertThrows(IllegalArgumentException.class, () -> quantizer.quantiseOnModelGrid(-0.5f));
        assertThrows(IllegalArgumentException.class, () -> VertexQuantizer.decodeModelGrid(17));
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.quantisePosition(1f, 5f, 5f), "an empty range");
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.encodeNormal(0f, 0f, 0f), "a zero vector");
        assertThrows(IllegalArgumentException.class, () -> quantizer.quantiseUv(1.5f, 1024));
        assertThrows(IllegalArgumentException.class, () -> quantizer.quantiseUv(0.5f, 0));
        assertThrows(IllegalArgumentException.class, () -> VertexQuantizer.decodeUv(100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> VertexQuantizer.packLight(16, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> VertexQuantizer.packLight(0, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.packTemplateQuad(new int[3], new byte[5]));
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.packTemplateQuad(new int[] {0, 0, 0, 0x400}, new byte[5]),
                "eleven bits does not fit");
        assertThrows(IllegalArgumentException.class,
                () -> VertexQuantizer.unpackTemplateQuad(new byte[4]));
    }

    @Test
    void aFreshQuantizerReportsZeroNotNaN() {
        VertexQuantizer quantizer = new VertexQuantizer();
        assertEquals(0.0, quantizer.bytesPerVertex());
        assertEquals(0.0, quantizer.worstPositionError());
        assertEquals(0.0, quantizer.worstNormalErrorDegrees());
        assertEquals(0.0, quantizer.worstUvErrorTexels());
        assertEquals(16, VertexQuantizer.MODEL_GRID_STEPS);
        assertEquals(16, VertexQuantizer.LIGHT_LEVELS);
        assertEquals(4, VertexQuantizer.AO_LEVELS);
    }
}
