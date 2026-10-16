package net.amdfaster.mesh;

import net.amdfaster.mesh.BlockVertexLadder.Precision;
import net.amdfaster.mesh.BlockVertexLadder.Stage;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockVertexLadderTest {

    @Test
    void theLadderEndsAtOneBytePerVertex() {
        assertEquals(32, Stage.S0_VANILLA.bytesPerVertex);
        assertEquals(28, Stage.S1_UV0_16BIT.bytesPerVertex);
        assertEquals(24, Stage.S2_POSITION_16BIT.bytesPerVertex);
        assertEquals(18, Stage.S3_POSITION_5BIT.bytesPerVertex);
        assertEquals(14, Stage.S4_NO_NORMAL.bytesPerVertex);
        assertEquals(10, Stage.S5_SPRITE_PER_QUAD.bytesPerVertex);
        assertEquals(6, Stage.S6_NO_COLOUR.bytesPerVertex);
        assertEquals(4, Stage.S7_QUAD_ORIGIN.bytesPerVertex);
        assertEquals(1, Stage.S8_LIGHT_8BIT.bytesPerVertex);
        assertEquals(9, Stage.values().length);
    }

    @Test
    void s8IsExactBecauseSixteenOverTwoFiftySixIsOneSixteenth() {
        // The whole justification for the last step. Vanilla stores the level times sixteen in a
        // sixteen-bit field and the shader divides by 256, so the field is a 4-bit value wearing a
        // 16-bit coat. Both routes land on the same texel of the same 16 by 16 lightmap.
        for (int light = 0; light < 16; light++) {
            int packed = BlockVertexLadder.packLight(light, light);
            assertEquals(light, BlockVertexLadder.unpackBlockLight(packed));

            int field = BlockVertexLadder.vanillaLightField(light);
            assertEquals(light * 16, field);
            assertEquals(BlockVertexLadder.vanillaLightmapCoordinate(field),
                    BlockVertexLadder.lightmapCoordinate(packed), 0.0f,
                    "light " + light + " samples the same texel");
        }
        assertEquals(0.9375f, BlockVertexLadder.lightmapCoordinate(
                BlockVertexLadder.packLight(15, 0)), 0.0f);
    }

    @Test
    void aGridAlignedQuadIsNineBytes() {
        BlockVertexLadder ladder = new BlockVertexLadder();
        int[] occlusion = {3, 2, 1, 0};
        int[] light = {
            BlockVertexLadder.packLight(15, 15),
            BlockVertexLadder.packLight(0, 15),
            BlockVertexLadder.packLight(15, 0),
            BlockVertexLadder.packLight(7, 3),
        };

        byte[] out = new byte[BlockVertexLadder.GRID_QUAD_BYTES];
        ladder.packGridQuad(3, 70 & 0x1F, 5, 1, 4211, occlusion, light, out);

        assertEquals(9, BlockVertexLadder.GRID_QUAD_BYTES);
        assertEquals(9, out.length);
        assertEquals(40, BlockVertexLadder.GRID_HEADER_BITS);

        int[] unpacked = BlockVertexLadder.unpackGridQuad(out);
        assertEquals(3, unpacked[0], "x");
        assertEquals(70 & 0x1F, unpacked[1], "y");
        assertEquals(5, unpacked[2], "z");
        assertEquals(1, unpacked[3], "face");
        assertEquals(4211, unpacked[4], "sprite id");
        for (int v = 0; v < 4; v++) {
            assertEquals(occlusion[v], unpacked[5 + v], "occlusion " + v);
            assertEquals(light[v], unpacked[9 + v], "light " + v);
        }
    }

    @Test
    void everyFieldOfTheHeaderSurvivesTenThousandRandomQuads() {
        BlockVertexLadder ladder = new BlockVertexLadder();
        Random random = new Random(20261015L);
        byte[] out = new byte[BlockVertexLadder.GRID_QUAD_BYTES];

        for (int trial = 0; trial < 10_000; trial++) {
            int x = random.nextInt(32);
            int y = random.nextInt(32);
            int z = random.nextInt(32);
            int face = random.nextInt(6);
            int sprite = random.nextInt(1 << BlockVertexLadder.SPRITE_BITS);
            int[] occlusion = new int[4];
            int[] light = new int[4];
            for (int v = 0; v < 4; v++) {
                occlusion[v] = random.nextInt(4);
                light[v] = random.nextInt(256);
            }

            ladder.packGridQuad(x, y, z, face, sprite, occlusion, light, out);
            int[] got = BlockVertexLadder.unpackGridQuad(out);

            assertEquals(x, got[0], "trial " + trial + " x");
            assertEquals(y, got[1], "trial " + trial + " y");
            assertEquals(z, got[2], "trial " + trial + " z");
            assertEquals(face, got[3], "trial " + trial + " face");
            assertEquals(sprite, got[4], "trial " + trial + " sprite");
            for (int v = 0; v < 4; v++) {
                assertEquals(occlusion[v], got[5 + v], "trial " + trial + " ao " + v);
                assertEquals(light[v], got[9 + v], "trial " + trial + " light " + v);
            }
        }
    }

    @Test
    void gridCoordinatesAreRecognisedAndFractionsAreNot() {
        // A mod that ships a model with corners off the sixteenths must be detected, or its corners get
        // snapped and visibly move. The tolerance is far below a sixteenth, so a genuine fraction is
        // never mistaken for a grid value.
        assertTrue(BlockVertexLadder.isOnGrid(0.0f));
        assertTrue(BlockVertexLadder.isOnGrid(1f / 16f));
        assertTrue(BlockVertexLadder.isOnGrid(7f / 16f));
        assertTrue(BlockVertexLadder.isOnGrid(0.5f));
        assertTrue(BlockVertexLadder.isOnGrid(1.0f));
        assertTrue(BlockVertexLadder.isOnGrid(15f / 16f));

        assertFalse(BlockVertexLadder.isOnGrid(1f / 3f), "a third is not on the grid");
        assertFalse(BlockVertexLadder.isOnGrid(0.1f));
        assertFalse(BlockVertexLadder.isOnGrid(0.03f));
    }

    @Test
    void aQuadWithOneFractionalCornerTakesTheFractionalPath() {
        // Per quad, not per section. One mod block in a section costs that quad extra bytes and leaves
        // the rest at one byte each. The alternative -- some mod needs fractions, so every vertex pays
        // for them -- would spend the whole saving to cover a minority.
        float[] grid = {
            0f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f,
        };
        assertEquals(Precision.GRID, BlockVertexLadder.classify(grid));

        float[] fractional = grid.clone();
        fractional[3] = 1f / 3f;
        assertEquals(Precision.FRACTIONAL, BlockVertexLadder.classify(fractional),
                "one corner off the grid is enough");
    }

    @Test
    void fractionalQuadsCostMoreAndTheClassSaysHowMuch() {
        assertEquals(9, BlockVertexLadder.GRID_QUAD_BYTES);
        assertEquals(25, BlockVertexLadder.FRACTIONAL_QUAD_BYTES,
                "the header, four bytes of light, and four vertices of explicit offset");

        BlockVertexLadder ladder = new BlockVertexLadder();
        byte[] out = new byte[BlockVertexLadder.GRID_QUAD_BYTES];
        int[] zero = {0, 0, 0, 0};
        for (int i = 0; i < 62; i++) {
            ladder.packGridQuad(i % 16, i / 16, 0, i % 6, i, zero, zero, out);
        }
        ladder.noteFractional();

        assertEquals(62L, ladder.gridQuads());
        assertEquals(1L, ladder.fractionalQuads());
        assertEquals(252L, ladder.vertices());
        assertTrue(ladder.bytesPerVertex() < 2.4,
                "one fractional quad barely moves the average: " + ladder.bytesPerVertex());
    }

    @Test
    void aNormalIsRecoverableFromTheFaceAlone() {
        // S4 drops the stored normal because a cube face has one of six and the face id says which.
        float[][] axes = {
            {0f, -1f, 0f}, {0f, 1f, 0f},
            {0f, 0f, -1f}, {0f, 0f, 1f},
            {-1f, 0f, 0f}, {1f, 0f, 0f},
        };
        for (int face = 0; face < 6; face++) {
            assertEquals(face, BlockVertexLadder.faceForNormal(
                    axes[face][0], axes[face][1], axes[face][2]), "face " + face);
            assertArrayEquals(axes[face], BlockVertexLadder.normalForFace(face), 0.0f);
        }
        assertEquals(-1, BlockVertexLadder.faceForNormal(0.577f, 0.577f, 0.577f),
                "a diagonal normal has no face, and says so rather than guessing");
    }

    @Test
    void aSectionOfStoneCostsNineBytesAQuad() {
        BlockVertexLadder ladder = new BlockVertexLadder();
        byte[] out = new byte[BlockVertexLadder.GRID_QUAD_BYTES];
        int[] zero = {0, 0, 0, 0};

        for (int i = 0; i < 256; i++) {
            ladder.packGridQuad(i % 16, i / 16, 0, i % 6, i, zero, zero, out);
        }

        assertEquals(256L, ladder.gridQuads());
        assertEquals(1024L, ladder.vertices());
        assertEquals(2.25, ladder.bytesPerVertex(), 1e-9, "nine bytes for four vertices");
        assertEquals(14.2, 32.0 / 2.25, 0.1, "against the 32 byte vanilla vertex");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BlockVertexLadder ladder = new BlockVertexLadder();
        byte[] out = new byte[BlockVertexLadder.GRID_QUAD_BYTES];
        int[] zero = {0, 0, 0, 0};

        assertThrows(IllegalArgumentException.class, () -> BlockVertexLadder.classify(new float[11]));
        assertThrows(IllegalArgumentException.class, () -> BlockVertexLadder.packLight(16, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockVertexLadder.packLight(0, -1));
        assertThrows(IllegalArgumentException.class, () -> BlockVertexLadder.vanillaLightField(16));
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(32, 0, 0, 0, 0, zero, zero, out), "x off the axis range");
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(0, 0, 0, 6, 0, zero, zero, out), "no seventh face");
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(0, 0, 0, 0, 1 << BlockVertexLadder.SPRITE_BITS,
                        zero, zero, out), "sprite id too large");
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(0, 0, 0, 0, 0, new int[] {0, 0, 0}, zero, out));
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(0, 0, 0, 0, 0, new int[] {4, 0, 0, 0}, zero, out),
                "occlusion has four levels");
        assertThrows(IllegalArgumentException.class,
                () -> ladder.packGridQuad(0, 0, 0, 0, 0, zero, zero, new byte[8]));
        assertThrows(IllegalArgumentException.class,
                () -> BlockVertexLadder.unpackGridQuad(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> BlockVertexLadder.normalForFace(6));
    }

    @Test
    void aFreshLadderReportsZeroNotNaN() {
        BlockVertexLadder ladder = new BlockVertexLadder();
        assertEquals(0.0, ladder.bytesPerVertex());
        assertEquals(0L, ladder.vertices());
        assertEquals(16, BlockVertexLadder.SECTION_SIZE);
        assertEquals(16, BlockVertexLadder.GRID_STEPS);
        assertEquals(5, BlockVertexLadder.GRID_AXIS_BITS);
        assertEquals(14, BlockVertexLadder.SPRITE_BITS);
        assertEquals(2, BlockVertexLadder.AO_BITS);
        assertEquals(8, BlockVertexLadder.LIGHT_BITS);
    }
}
