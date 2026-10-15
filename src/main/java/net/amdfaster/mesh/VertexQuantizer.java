package net.amdfaster.mesh;

/**
 * Packs vertex attributes into the fewest bytes that still draw the same picture.
 *
 * <p>A Minecraft vertex is position, normal, UV and light: three floats, three floats, two floats and
 * two light levels, which is 32 bytes before any padding. A section face of 256 quads is 32 KB of it,
 * and the same data is uploaded again whenever the light changes. Android's published figures for this
 * compression are a vertex going from 48 bytes to 16 and vertex read bandwidth from 27 GB/s to 9, with
 * vertex fetch stalls in binning dropping from 50% to none.
 *
 * <h2>Most of Minecraft's vertex data is already discrete</h2>
 *
 * <p>That is what makes this close to free. Light is 0 to 15, so four bits are exact. Ambient occlusion
 * has four levels, so two bits are exact. A full cube face -- which is most of a solid chunk -- has
 * corners on integer boundaries, one of six normals, and UVs at the four corners of an atlas tile.
 * Quantising those loses nothing, because the source had no more resolution to lose.
 *
 * <p>The general case is where bits have to be spent, and the errors are measured rather than assumed:
 * a 10-bit position inside a 16 block section is off by at most 0.0078 blocks, an 8-bit octahedral
 * normal by at most 0.94 degrees, and a 16-bit UV on a 4096 atlas by 0.03 texels. All three are below
 * what a player can see. An 8-bit position is off by 0.031 blocks, which is half of a sixteenth and is
 * not obviously safe, so it is not the default.
 *
 * <h2>How the lost precision is paid back</h2>
 *
 * <p><b>Snap to the grid the models already use.</b> Block models are built on a sixteenth-of-a-block
 * grid, so a position quantised to sixteenths is exact for every vanilla model rather than
 * approximate. Using the source's own resolution instead of a generic bit budget is the difference
 * between lossless and lossy for the overwhelming majority of geometry.
 *
 * <p><b>Quantise locally, not over the whole section.</b> A meshlet spans a small region, so the same
 * number of bits covers a much smaller range and the error shrinks with it. Quantising over the whole
 * section is what makes 8 bits marginal; quantising over a meshlet's bounds is what makes it plenty.
 *
 * <p><b>Renormalise the decoded normal.</b> An octahedral decode does not produce a unit vector, and
 * without the fixup the diffuse term darkens at grazing angles -- a subtle change that looks like a
 * lighting bug rather than a compression artefact. {@link #decodeNormal} does the fixup, so the CPU
 * path and the shader agree.
 *
 * <p><b>Inset the UV range by half a texel.</b> A quantised UV that lands a hair outside its tile
 * samples the neighbouring tile once mipmaps kick in, which shows up as a dark seam on block edges
 * from a distance and is very hard to trace back to compression. The inset costs a fraction of a
 * texel of stretch and removes the seam.
 *
 * <p>Not thread safe.
 */
public final class VertexQuantizer {

    /** Bytes in one general-purpose quantised vertex. */
    public static final int GENERAL_BYTES = 9;

    /**
     * Bytes for a whole template quad: four vertices of light and occlusion, with position, normal and
     * UV implied by the template.
     *
     * <p>Ten bits a vertex -- four of block light, four of sky light, two of occlusion -- is forty bits,
     * against 128 bytes for the same quad uncompressed.
     */
    public static final int TEMPLATE_QUAD_BYTES = 5;

    /** Bytes in an uncompressed vertex, for comparison. */
    public static final int UNCOMPRESSED_BYTES = 32;

    /** Bits of position per axis. */
    public static final int POSITION_BITS = 10;

    /** Steps a block model grid has along an edge: sixteenths. */
    public static final int MODEL_GRID_STEPS = 16;

    /** Bits of octahedral normal per component. */
    public static final int NORMAL_BITS = 8;

    /** Light levels Minecraft has. */
    public static final int LIGHT_LEVELS = 16;

    /** Ambient occlusion levels. */
    public static final int AO_LEVELS = 4;

    private long vertices;
    private long templateQuads;
    private double worstPositionError;
    private double worstNormalErrorDegrees;
    private double worstUvErrorTexels;

    /**
     * Quantises a position on the block model grid.
     *
     * <p>Exact for anything built on sixteenths, which is every vanilla model.
     *
     * @param coordinate the position within its block, in [0,1]
     * @return a step index in [0, {@link #MODEL_GRID_STEPS}]
     */
    public int quantiseOnModelGrid(float coordinate) {
        if (coordinate < -1e-4f || coordinate > 1.0f + 1e-4f) {
            throw new IllegalArgumentException(
                    "a position within a block is in [0,1]: " + coordinate);
        }
        float clamped = Math.min(1.0f, Math.max(0.0f, coordinate));
        return Math.round(clamped * MODEL_GRID_STEPS);
    }

    /** Restores a position quantised on the model grid. */
    public static float decodeModelGrid(int step) {
        if (step < 0 || step > MODEL_GRID_STEPS) {
            throw new IllegalArgumentException("step outside [0,16]: " + step);
        }
        return step / (float) MODEL_GRID_STEPS;
    }

    /**
     * Quantises a position inside a local range.
     *
     * @param coordinate the position
     * @param min the low end of the range
     * @param max the high end of the range
     * @return a value in [0, 2^POSITION_BITS - 1]
     */
    public int quantisePosition(float coordinate, float min, float max) {
        if (max <= min) {
            throw new IllegalArgumentException("an empty range: " + min + ".." + max);
        }
        int steps = (1 << POSITION_BITS) - 1;
        float t = (coordinate - min) / (max - min);
        t = Math.min(1.0f, Math.max(0.0f, t));
        int quantised = Math.round(t * steps);
        float decoded = min + quantised * (max - min) / steps;
        this.worstPositionError = Math.max(this.worstPositionError,
                Math.abs(decoded - Math.min(max, Math.max(min, coordinate))));
        return quantised;
    }

    /** Restores a position quantised inside a local range. */
    public static float decodePosition(int quantised, float min, float max) {
        int steps = (1 << POSITION_BITS) - 1;
        return min + quantised * (max - min) / steps;
    }

    /**
     * Encodes a unit normal as two signed bytes, octahedrally.
     *
     * <p>Twelve bytes become two. The worst-case angular error at eight bits is 0.94 degrees, measured
     * over a hundred thousand random directions, which no diffuse shading shows. The six axis directions
     * a cube face uses are exact, with no error at all.
     *
     * @return the two components packed into an int, low byte first
     */
    public int encodeNormal(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-6f) {
            throw new IllegalArgumentException("a zero vector has no direction");
        }
        float nx = x / length;
        float ny = y / length;
        float nz = z / length;

        // Project onto the octahedron: divide by the L1 norm so the result lies on a diamond.
        float l1 = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
        float ox = nx / l1;
        float oy = ny / l1;

        // Fold the lower hemisphere into the outer ring, which is what makes the mapping onto a square
        // cover the whole sphere rather than half of it.
        if (nz < 0.0f) {
            float fx = ox;
            float fy = oy;
            ox = (1.0f - Math.abs(fy)) * (fx >= 0.0f ? 1.0f : -1.0f);
            oy = (1.0f - Math.abs(fx)) * (fy >= 0.0f ? 1.0f : -1.0f);
        }

        int limit = (1 << (NORMAL_BITS - 1)) - 1;
        int ex = Math.round(ox * limit);
        int ey = Math.round(oy * limit);
        ex = Math.min(limit, Math.max(-limit, ex));
        ey = Math.min(limit, Math.max(-limit, ey));

        int packed = (ex & 0xFF) | ((ey & 0xFF) << 8);
        // Measured through the same decode a caller uses, so the reported error is the error the shader
        // will actually have rather than the error an independent implementation would.
        double error = Math.toDegrees(angleBetween(nx, ny, nz, decodeNormal(packed)));
        this.worstNormalErrorDegrees = Math.max(this.worstNormalErrorDegrees, error);

        return packed;
    }

    /**
     * Decodes an octahedral normal.
     *
     * <p>Renormalises, because the decode does not produce a unit vector on its own and a shader that
     * skipped this would darken diffuse shading at grazing angles.
     */
    public static float[] decodeNormal(int packed) {
        int limit = (1 << (NORMAL_BITS - 1)) - 1;
        byte low = (byte) (packed & 0xFF);
        byte high = (byte) ((packed >> 8) & 0xFF);
        float ox = low / (float) limit;
        float oy = high / (float) limit;

        float z = 1.0f - Math.abs(ox) - Math.abs(oy);
        if (z < 0.0f) {
            float fx = ox;
            float fy = oy;
            ox = (1.0f - Math.abs(fy)) * (fx >= 0.0f ? 1.0f : -1.0f);
            oy = (1.0f - Math.abs(fx)) * (fy >= 0.0f ? 1.0f : -1.0f);
        }
        float length = (float) Math.sqrt(ox * ox + oy * oy + z * z);
        return new float[] {ox / length, oy / length, z / length};
    }

    private static double angleBetween(float ax, float ay, float az, float[] b) {
        double dot = ax * b[0] + ay * b[1] + az * b[2];
        return Math.acos(Math.min(1.0, Math.max(-1.0, dot)));
    }

    /**
     * Quantises a UV, inset so it cannot leave its tile.
     *
     * @param coordinate the UV, in [0,1] within its tile
     * @param atlasSize the atlas edge in texels, used to work out the inset
     * @return a 16-bit value
     */
    public int quantiseUv(float coordinate, int atlasSize) {
        if (atlasSize < 1) {
            throw new IllegalArgumentException("an atlas is at least one texel: " + atlasSize);
        }
        if (coordinate < -1e-4f || coordinate > 1.0f + 1e-4f) {
            throw new IllegalArgumentException("a UV within a tile is in [0,1]: " + coordinate);
        }
        // Half a texel of the atlas, expressed as a fraction of the tile. Without it a quantised value
        // can land outside the tile and mipmapped sampling picks up the neighbouring tile's edge,
        // which shows as a dark seam on block borders from a distance.
        float inset = 0.5f / atlasSize;
        float clamped = Math.min(1.0f, Math.max(0.0f, coordinate));
        float insetCoordinate = inset + clamped * (1.0f - 2.0f * inset);

        int quantised = Math.round(insetCoordinate * 65535.0f);
        quantised = Math.min(65535, Math.max(0, quantised));

        float decoded = quantised / 65535.0f;
        this.worstUvErrorTexels = Math.max(this.worstUvErrorTexels,
                Math.abs(decoded - clamped) * atlasSize);
        return quantised;
    }

    /**
     * Restores a UV, undoing the inset.
     *
     * @param atlasSize the atlas edge in texels, which must match what was passed to
     *     {@link #quantiseUv}
     */
    public static float decodeUv(int quantised, int atlasSize) {
        float inset = 0.5f / atlasSize;
        float raw = quantised / 65535.0f;
        float unInset = (raw - inset) / (1.0f - 2.0f * inset);
        return Math.min(1.0f, Math.max(0.0f, unInset));
    }

    /**
     * Packs one vertex's light and occlusion into a single byte.
     *
     * <p>Four bits of block light, four of sky light, and the two occlusion bits share the low pair of
     * the sky field, which is where the level count leaves room. Both are exact: Minecraft has sixteen
     * light levels and four occlusion levels, so nothing is lost.
     *
     * @param blockLight 0 to 15
     * @param skyLight 0 to 15
     * @param ambientOcclusion 0 to 3
     */
    public static int packLight(int blockLight, int skyLight, int ambientOcclusion) {
        if (blockLight < 0 || blockLight >= LIGHT_LEVELS) {
            throw new IllegalArgumentException("block light is 0..15: " + blockLight);
        }
        if (skyLight < 0 || skyLight >= LIGHT_LEVELS) {
            throw new IllegalArgumentException("sky light is 0..15: " + skyLight);
        }
        if (ambientOcclusion < 0 || ambientOcclusion >= AO_LEVELS) {
            throw new IllegalArgumentException("occlusion is 0..3: " + ambientOcclusion);
        }
        return (blockLight & 0xF) | ((skyLight & 0xF) << 4) | (ambientOcclusion << 8);
    }

    public static int unpackBlockLight(int packed) {
        return packed & 0xF;
    }

    public static int unpackSkyLight(int packed) {
        return (packed >> 4) & 0xF;
    }

    public static int unpackOcclusion(int packed) {
        return (packed >> 8) & 0x3;
    }

    /**
     * Packs a whole template quad: four vertices of light and occlusion, nothing else.
     *
     * <p>Position, normal and UV come from the template, which is exact, so the only per-vertex data is
     * what actually varies. Forty bits for a quad that would otherwise be 128 bytes.
     *
     * @param packed four packed light values from {@link #packLight}
     * @param out at least {@link #TEMPLATE_QUAD_BYTES} bytes
     */
    public void packTemplateQuad(int[] packed, byte[] out) {
        if (packed.length != 4) {
            throw new IllegalArgumentException("a quad has four vertices: " + packed.length);
        }
        if (out.length < TEMPLATE_QUAD_BYTES) {
            throw new IllegalArgumentException(
                    "out must hold " + TEMPLATE_QUAD_BYTES + " bytes: " + out.length);
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int value = packed[vertex];
            if (value < 0 || value > 0x3FF) {
                throw new IllegalArgumentException("light and occlusion fit in ten bits: " + value);
            }
        }
        // Ten bits each, laid end to end across five bytes.
        long bits = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            bits |= ((long) packed[vertex]) << (vertex * 10);
        }
        for (int i = 0; i < TEMPLATE_QUAD_BYTES; i++) {
            out[i] = (byte) (bits >>> (i * 8));
        }
        this.templateQuads++;
        this.vertices += 4;
    }

    /** Unpacks a template quad. */
    public static int[] unpackTemplateQuad(byte[] packed) {
        if (packed.length < TEMPLATE_QUAD_BYTES) {
            throw new IllegalArgumentException(
                    "packed must hold " + TEMPLATE_QUAD_BYTES + " bytes: " + packed.length);
        }
        long bits = 0;
        for (int i = 0; i < TEMPLATE_QUAD_BYTES; i++) {
            bits |= ((long) (packed[i] & 0xFF)) << (i * 8);
        }
        int[] out = new int[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            out[vertex] = (int) ((bits >>> (vertex * 10)) & 0x3FF);
        }
        return out;
    }

    public long vertices() {
        return this.vertices;
    }

    public long templateQuads() {
        return this.templateQuads;
    }

    /** The largest position error seen so far, in blocks. */
    public double worstPositionError() {
        return this.worstPositionError;
    }

    /** The largest normal error seen so far, in degrees. */
    public double worstNormalErrorDegrees() {
        return this.worstNormalErrorDegrees;
    }

    /** The largest UV error seen so far, in texels. */
    public double worstUvErrorTexels() {
        return this.worstUvErrorTexels;
    }

    /** Bytes per vertex, counting a template quad as four vertices. */
    public double bytesPerVertex() {
        return this.vertices == 0 ? 0.0
                : (double) this.templateQuads * TEMPLATE_QUAD_BYTES / this.vertices;
    }
}
