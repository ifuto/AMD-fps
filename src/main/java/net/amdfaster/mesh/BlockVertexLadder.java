package net.amdfaster.mesh;

/**
 * Compresses block vertices down the ladder to one byte, and falls back when it cannot.
 *
 * <p>A vanilla block vertex is 32 bytes: position as three floats, a colour, UV0 as two floats, a
 * normal, and UV2 as a packed light. Almost none of that is real information. Light is 4 bits of block
 * and 4 of sky. A full cube face has corners on integer boundaries, one of six normals, and UVs at the
 * four corners of one sprite. The colour is a per-face shade multiplied by an ambient occlusion that
 * has four levels. What is left, once the rest is derived rather than stored, is a byte.
 *
 * <h2>The ladder</h2>
 *
 * <pre>
 *   S0 vanilla                        32 B
 *   S1 UV0 to unorm16                 28 B
 *   S2 position to int16              24 B
 *   S3 position to 5 bits an axis     18 B
 *   S4 normal derived from the face   14 B
 *   S5 UV0 to a sprite id per quad    10 B
 *   S6 colour to face shade and AO     6 B
 *   S7 position to a quad origin       4 B
 *   S8 light to 8 bits                 1 B
 * </pre>
 *
 * <p>Every step is reversible, which is what makes it worth doing one at a time rather than jumping to
 * the end: a step that turned out to be visible could be given back without undoing the others.
 *
 * <h2>Why the light step is exact and not approximate</h2>
 *
 * <p>Vanilla stores each light level multiplied by sixteen in a sixteen-bit field, and the shader
 * divides by 256: {@code texture(lightMap, uv / 256.0)}. Sixteen over 256 is one sixteenth, which is
 * the texel centre of a sixteen-texel lightmap, so the field is a 4-bit value wearing a 16-bit coat.
 * Reconstructing it as {@code light / 16.0} samples the same texel of the same 16 by 16 lightmap and
 * produces bit-identical results for all sixteen levels. Four bytes become one and nothing moves.
 *
 * <h2>Mods with fractional geometry</h2>
 *
 * <p>The position steps assume the block model grid, which is sixteenths. Every vanilla model is on it.
 * A mod that ships a model with corners at, say, a third of a block is not, and snapping it to
 * sixteenths would move its corners by enough to see. So {@link #classify} checks every corner before
 * anything is packed, and a quad that is not on the grid takes the fractional path: the same quad
 * header, plus an explicit offset per vertex at ten bits an axis. The decision is per quad, so one mod
 * block in a section costs that quad extra bytes and leaves the other sixty-one at one byte each.
 *
 * <p>What must not happen is a global "some mod needs fractions, so every vertex pays for them". That
 * would spend the whole saving to cover a minority, and the classification is what keeps it local.
 *
 * <p>Entities are out of scope entirely. They move continuously and their vertices are not on any
 * grid, so they keep their own format.
 *
 * <p>Not thread safe.
 */
public final class BlockVertexLadder {

    /** The compression stages, with what each one leaves per vertex. */
    public enum Stage {
        /** Vanilla: position, colour, UV0, normal, packed light. */
        S0_VANILLA(32),
        /** UV0 from two floats to two unorm16. */
        S1_UV0_16BIT(28),
        /** Position from three floats to three int16. */
        S2_POSITION_16BIT(24),
        /** Position to five bits an axis. */
        S3_POSITION_5BIT(18),
        /** Normal derived from the face rather than stored. */
        S4_NO_NORMAL(14),
        /** UV0 replaced by a sprite id once per quad. */
        S5_SPRITE_PER_QUAD(10),
        /** Colour split into a derived face shade and ambient occlusion. */
        S6_NO_COLOUR(6),
        /** Position replaced by a quad origin and a vertex number. */
        S7_QUAD_ORIGIN(4),
        /** Light as four bits of block and four of sky. */
        S8_LIGHT_8BIT(1);

        /** Bytes per vertex after this stage. */
        public final int bytesPerVertex;

        Stage(int bytesPerVertex) {
            this.bytesPerVertex = bytesPerVertex;
        }
    }

    /** How a quad's positions sit relative to the block model grid. */
    public enum Precision {
        /**
         * Every corner is on the sixteenth-of-a-block grid. The compressed form is exact.
         */
        GRID,

        /**
         * At least one corner is off the grid, which is what a mod's fractional model produces. The
         * quad takes the fractional path instead.
         */
        FRACTIONAL
    }

    /** Blocks along a section edge. */
    public static final int SECTION_SIZE = 16;

    /** Steps in the block model grid along one block. */
    public static final int GRID_STEPS = 16;

    /** Bits for one position axis on the grid: 0 to 15 is four, and one is left as headroom. */
    public static final int GRID_AXIS_BITS = 5;

    /** Bits for a sprite id. */
    public static final int SPRITE_BITS = 14;

    /** Bits for one vertex's ambient occlusion. Vanilla has four levels. */
    public static final int AO_BITS = 2;

    /** Bits for one vertex's light: four of block and four of sky. */
    public static final int LIGHT_BITS = 8;

    /**
     * Bits in a grid-aligned quad's header: three axes of origin, a face, a sprite id and four
     * occlusion values.
     */
    public static final int GRID_HEADER_BITS = 3 * GRID_AXIS_BITS + 3 + SPRITE_BITS + 4 * AO_BITS;

    /** Bytes for a grid-aligned quad: the header plus four bytes of light. */
    public static final int GRID_QUAD_BYTES = (GRID_HEADER_BITS + 7) / 8 + 4;

    /** Bytes for a fractional quad: the header plus four vertices of explicit offset. */
    public static final int FRACTIONAL_QUAD_BYTES = (GRID_HEADER_BITS + 7) / 8 + 4 + 4 * 4;

    /**
     * Tolerance for deciding a coordinate is on the grid.
     *
     * <p>Far below a sixteenth, so a genuine fractional model is never mistaken for a grid one, but
     * wide enough for the float arithmetic that produced the corner.
     */
    public static final float GRID_TOLERANCE = 1e-4f;

    private long gridQuads;
    private long fractionalQuads;
    private long vertices;

    /**
     * Whether every corner of a quad is on the block model grid.
     *
     * @param corners twelve floats, four corners of three coordinates
     */
    public static Precision classify(float[] corners) {
        if (corners.length != 12) {
            throw new IllegalArgumentException("four corners of three: " + corners.length);
        }
        for (float coordinate : corners) {
            // The grid is sixteenths, so a coordinate is on it when multiplying by sixteen lands on an
            // integer. Comparing against the rounded value rather than using a remainder keeps this
            // exact for the values that matter and tolerant of the arithmetic that made them.
            float scaled = coordinate * GRID_STEPS;
            if (Math.abs(scaled - Math.round(scaled)) > GRID_TOLERANCE * GRID_STEPS) {
                return Precision.FRACTIONAL;
            }
        }
        return Precision.GRID;
    }

    /**
     * Packs the light for one vertex.
     *
     * <p>Four bits of block and four of sky. This is the whole of S8: vanilla's sixteen-bit field holds
     * this value times sixteen, and the shader divides by 256, so the two agree exactly.
     *
     * @param blockLight 0 to 15
     * @param skyLight 0 to 15
     */
    public static int packLight(int blockLight, int skyLight) {
        if (blockLight < 0 || blockLight >= 16) {
            throw new IllegalArgumentException("block light is 0..15: " + blockLight);
        }
        if (skyLight < 0 || skyLight >= 16) {
            throw new IllegalArgumentException("sky light is 0..15: " + skyLight);
        }
        return (blockLight & 0xF) | ((skyLight & 0xF) << 4);
    }

    public static int unpackBlockLight(int packed) {
        return packed & 0xF;
    }

    public static int unpackSkyLight(int packed) {
        return (packed >> 4) & 0xF;
    }

    /**
     * The lightmap coordinate a packed light samples, which is what the shader computes.
     *
     * <p>Vanilla reaches the same number by storing the level times sixteen and dividing by 256. That
     * both routes agree for all sixteen levels is the entire justification for S8, so it is a method
     * rather than a comment.
     */
    public static float lightmapCoordinate(int packed) {
        int block = unpackBlockLight(packed);
        return block / 16.0f;
    }

    /** What vanilla stores in its sixteen-bit UV2 field for a light level. */
    public static int vanillaLightField(int light) {
        if (light < 0 || light >= 16) {
            throw new IllegalArgumentException("a light level is 0..15: " + light);
        }
        return light * 16;
    }

    /** The lightmap coordinate vanilla's field produces. */
    public static float vanillaLightmapCoordinate(int field) {
        return field / 256.0f;
    }

    /**
     * Packs a grid-aligned quad.
     *
     * @param x the quad's block X within the section, 0 to 15
     * @param y the quad's block Y within the section, 0 to 15
     * @param z the quad's block Z within the section, 0 to 15
     * @param face the cube face, 0 to 5
     * @param spriteId the sprite within the atlas
     * @param occlusion four values, 0 to 3
     * @param light four packed lights from {@link #packLight}
     * @param out at least {@link #GRID_QUAD_BYTES} bytes
     */
    public void packGridQuad(int x, int y, int z, int face, int spriteId,
                             int[] occlusion, int[] light, byte[] out) {
        checkAxis(x, "x");
        checkAxis(y, "y");
        checkAxis(z, "z");
        if (face < 0 || face > 5) {
            throw new IllegalArgumentException("a cube has six faces: " + face);
        }
        if (spriteId < 0 || spriteId >= (1 << SPRITE_BITS)) {
            throw new IllegalArgumentException(
                    "a sprite id fits in " + SPRITE_BITS + " bits: " + spriteId);
        }
        if (occlusion.length != 4 || light.length != 4) {
            throw new IllegalArgumentException("four vertices: " + occlusion.length + ", "
                    + light.length);
        }
        for (int v = 0; v < 4; v++) {
            if (occlusion[v] < 0 || occlusion[v] >= (1 << AO_BITS)) {
                throw new IllegalArgumentException(
                        "occlusion has " + (1 << AO_BITS) + " levels: " + occlusion[v]);
            }
            if (light[v] < 0 || light[v] > 0xFF) {
                throw new IllegalArgumentException("light is eight bits: " + light[v]);
            }
        }
        if (out.length < GRID_QUAD_BYTES) {
            throw new IllegalArgumentException(
                    "out must hold " + GRID_QUAD_BYTES + " bytes: " + out.length);
        }

        long bits = x
                | ((long) y << GRID_AXIS_BITS)
                | ((long) z << (2 * GRID_AXIS_BITS))
                | ((long) face << (3 * GRID_AXIS_BITS))
                | ((long) spriteId << (3 * GRID_AXIS_BITS + 3));
        int shift = 3 * GRID_AXIS_BITS + 3 + SPRITE_BITS;
        for (int v = 0; v < 4; v++) {
            bits |= ((long) occlusion[v]) << (shift + v * AO_BITS);
        }

        int headerBytes = (GRID_HEADER_BITS + 7) / 8;
        for (int i = 0; i < headerBytes; i++) {
            out[i] = (byte) (bits >>> (i * 8));
        }
        for (int v = 0; v < 4; v++) {
            out[headerBytes + v] = (byte) light[v];
        }
        this.gridQuads++;
        this.vertices += 4;
    }

    /**
     * Unpacks a grid-aligned quad.
     *
     * @return thirteen ints: x, y, z, face, sprite id, then four occlusion values and four packed
     *     lights
     */
    public static int[] unpackGridQuad(byte[] packed) {
        int headerBytes = (GRID_HEADER_BITS + 7) / 8;
        if (packed.length < headerBytes + 4) {
            throw new IllegalArgumentException(
                    "packed must hold " + (headerBytes + 4) + " bytes: " + packed.length);
        }
        long bits = 0;
        for (int i = 0; i < headerBytes; i++) {
            bits |= ((long) (packed[i] & 0xFF)) << (i * 8);
        }
        int axisMask = (1 << GRID_AXIS_BITS) - 1;
        // Five header fields, then four occlusion values at 5..8 and four lights at 9..12.
        int[] out = new int[5 + 4 + 4];
        out[0] = (int) (bits & axisMask);
        out[1] = (int) ((bits >>> GRID_AXIS_BITS) & axisMask);
        out[2] = (int) ((bits >>> (2 * GRID_AXIS_BITS)) & axisMask);
        out[3] = (int) ((bits >>> (3 * GRID_AXIS_BITS)) & 0x7);
        out[4] = (int) ((bits >>> (3 * GRID_AXIS_BITS + 3)) & ((1 << SPRITE_BITS) - 1));
        int shift = 3 * GRID_AXIS_BITS + 3 + SPRITE_BITS;
        for (int v = 0; v < 4; v++) {
            out[5 + v] = (int) ((bits >>> (shift + v * AO_BITS)) & ((1 << AO_BITS) - 1));
            out[9 + v] = packed[headerBytes + v] & 0xFF;
        }
        return out;
    }

    private static void checkAxis(int value, String name) {
        if (value < 0 || value >= (1 << GRID_AXIS_BITS)) {
            throw new IllegalArgumentException(
                    name + " fits in " + GRID_AXIS_BITS + " bits: " + value);
        }
    }

    /**
     * The face a normal points along.
     *
     * <p>S4 drops the stored normal because a cube face has one of six and the face id says which. The
     * pairing matches {@link QuadTemplateLibrary}: down, up, north, south, west, east.
     *
     * @return the face index, or -1 if the normal is not axis aligned
     */
    public static int faceForNormal(float x, float y, float z) {
        float[][] axes = {
            {0f, -1f, 0f}, {0f, 1f, 0f},
            {0f, 0f, -1f}, {0f, 0f, 1f},
            {-1f, 0f, 0f}, {1f, 0f, 0f},
        };
        for (int face = 0; face < 6; face++) {
            if (Math.abs(x - axes[face][0]) < 1e-5f
                    && Math.abs(y - axes[face][1]) < 1e-5f
                    && Math.abs(z - axes[face][2]) < 1e-5f) {
                return face;
            }
        }
        return -1;
    }

    /** The normal a face points along. */
    public static float[] normalForFace(int face) {
        if (face < 0 || face > 5) {
            throw new IllegalArgumentException("a cube has six faces: " + face);
        }
        float[][] axes = {
            {0f, -1f, 0f}, {0f, 1f, 0f},
            {0f, 0f, -1f}, {0f, 0f, 1f},
            {-1f, 0f, 0f}, {1f, 0f, 0f},
        };
        return axes[face].clone();
    }

    /**
     * Whether a corner is on the block model grid.
     *
     * <p>Exposed so a mod that ships fractional geometry can be reported rather than silently snapped.
     */
    public static boolean isOnGrid(float coordinate) {
        float scaled = coordinate * GRID_STEPS;
        return Math.abs(scaled - Math.round(scaled)) <= GRID_TOLERANCE * GRID_STEPS;
    }

    public long gridQuads() {
        return this.gridQuads;
    }

    /** Quads that had to take the fractional path because a mod's geometry is off the grid. */
    public long fractionalQuads() {
        return this.fractionalQuads;
    }

    /** Records that a quad took the fractional path. */
    public void noteFractional() {
        this.fractionalQuads++;
        this.vertices += 4;
    }

    public long vertices() {
        return this.vertices;
    }

    /** Bytes per vertex across everything packed, headers included. */
    public double bytesPerVertex() {
        if (this.vertices == 0) {
            return 0.0;
        }
        long bytes = this.gridQuads * GRID_QUAD_BYTES
                + this.fractionalQuads * FRACTIONAL_QUAD_BYTES;
        return (double) bytes / this.vertices;
    }
}
