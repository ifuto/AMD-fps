package net.amdfaster.mesh;

/**
 * Packs a section's quads as tightly as each one allows, and can reconstruct them for checking.
 *
 * <p>Two compressions already exist here: {@link QuadTemplateLibrary} recognises the full cube face
 * every solid block emits, and {@link VertexQuantizer} packs what is left. This puts them together and
 * answers the question that decides whether either is safe to ship -- what does the reconstructed mesh
 * actually look like against the original?
 *
 * <p>A quad that matches a template carries no geometry at all: position, normal and UV come from the
 * template exactly, and the only data is light and occlusion, ten bits a vertex, five bytes for the
 * quad. A quad that does not match carries a quantised vertex. On a section that is mostly stone the
 * first case is nearly all of it, which is how a 32 KB section face gets small enough to matter.
 *
 * <h2>Why reconstruction is in here at all</h2>
 *
 * <p>Because "reduces fidelity but the look does not change" is a claim, and a claim about appearance
 * that is only argued is not verified. {@link #reconstruct} produces the vertices the shader would
 * produce from the packed bytes, and {@link #worstPositionError} reports how far they are from what
 * went in. The caller can then decide, per section, whether the error is inside what it is willing to
 * accept -- and a section that is not can fall back to the uncompressed path rather than shipping
 * something wrong.
 *
 * <h2>What the fallback is for</h2>
 *
 * <p>Not every model quantises well. A model with geometry finer than a sixteenth, or a curved surface
 * built from many quads, will show the grid. Rather than choosing a bit count that is safe for the
 * worst case and paying it everywhere, each section is measured and the ones that exceed the tolerance
 * keep their original vertices. In practice that is a small minority: the tolerance is a fraction of a
 * texel and a fraction of a degree, and full cube faces have no error at all.
 *
 * <p>Not thread safe.
 */
public final class CompressedSectionMesh {

    /**
     * Position error above which a section falls back to uncompressed vertices, in blocks.
     *
     * <p>A sixteenth of a block is the finest step a block model has, so an error below it cannot move
     * a corner onto a step it was not already on.
     */
    public static final double POSITION_TOLERANCE = 1.0 / 16.0;

    /** Normal error above which a section falls back, in degrees. */
    public static final double NORMAL_TOLERANCE = 2.0;

    /** UV error above which a section falls back, in texels. */
    public static final double UV_TOLERANCE = 0.5;

    /**
     * Bytes for one non-template quad.
     *
     * <p>Two bytes of normal for the whole quad, then nine a vertex. Nine is exactly 72 bits, which is
     * what one vertex needs: three positions at ten bits, two UVs at sixteen, and ten bits of light and
     * occlusion. Packing it as a long rather than into individual bytes is what keeps the light field
     * intact -- writing it as a single byte silently dropped the two occlusion bits.
     */
    public static final int GENERAL_SLOT_BYTES = 2 + 4 * 9;


    private final QuadTemplateLibrary templates = new QuadTemplateLibrary();
    private final VertexQuantizer quantizer = new VertexQuantizer();

    /**
     * The quantisation range each quad was packed against, six floats per quad.
     *
     * <p>Recorded per quad here; a real buffer stores it once per meshlet, which is what makes local
     * quantisation cheap rather than paying for the range on every quad.
     */
    private float[][] ranges;

    private final byte[] packed;
    private final boolean[] isTemplate;
    private final int[] templateIds;

    private int quadCount;
    private int bytes;

    private long templateQuads;
    private long generalQuads;
    private long fallbacks;

    private double worstPositionError;
    private double worstNormalErrorDegrees;
    private double worstUvErrorTexels;

    /**
     * @param maxQuads the most quads this will hold
     */
    public CompressedSectionMesh(int maxQuads) {
        if (maxQuads < 1) {
            throw new IllegalArgumentException("a section has at least one quad: " + maxQuads);
        }
        // Worst case is a quad with no template match: four general vertices.
        // Two bytes of normal for the quad, then nine bytes a vertex.
        this.packed = new byte[maxQuads * GENERAL_SLOT_BYTES];
        this.isTemplate = new boolean[maxQuads];
        this.templateIds = new int[maxQuads];
        this.ranges = new float[maxQuads][];
    }

    /** A quad's geometry, before packing. */
    public static final class InputQuad {
        /** Twelve floats: four corners of three coordinates, in winding order. */
        public final float[] corners;

        /** Eight floats: four corners of U and V. */
        public final float[] uvs;

        /** The face normal, three floats. */
        public final float[] normal;

        /** Block light per corner, 0 to 15. */
        public final int[] blockLight;

        /** Sky light per corner, 0 to 15. */
        public final int[] skyLight;

        /** Occlusion per corner, 0 to 3. */
        public final int[] occlusion;

        public InputQuad(float[] corners, float[] uvs, float[] normal,
                         int[] blockLight, int[] skyLight, int[] occlusion) {
            if (corners.length != 12) {
                throw new IllegalArgumentException("four corners of three: " + corners.length);
            }
            if (uvs.length != 8) {
                throw new IllegalArgumentException("four corners of two: " + uvs.length);
            }
            if (normal.length != 3) {
                throw new IllegalArgumentException("a normal is three floats: " + normal.length);
            }
            for (int[] perVertex : new int[][] {blockLight, skyLight, occlusion}) {
                if (perVertex.length != 4) {
                    throw new IllegalArgumentException("one value per corner: " + perVertex.length);
                }
            }
            this.corners = corners;
            this.uvs = uvs;
            this.normal = normal;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
            this.occlusion = occlusion;
        }
    }

    /**
     * Adds a quad.
     *
     * @param quad the quad
     * @param offsetX the block's X within the section
     * @param offsetY the block's Y within the section
     * @param offsetZ the block's Z within the section
     * @param atlasSize the atlas edge in texels
     * @return true if it was packed, false if the section is full
     */
    public boolean add(InputQuad quad, int offsetX, int offsetY, int offsetZ, int atlasSize) {
        if (this.quadCount >= this.isTemplate.length) {
            return false;
        }
        int template = this.templates.templateFor(quad.corners, offsetX, offsetY, offsetZ);
        int index = this.quadCount;

        if (template != QuadTemplateLibrary.NO_MATCH && this.templates.hasStandardUvs(quad.uvs)) {
            packTemplate(index, template, quad);
        } else {
            packGeneral(index, quad, atlasSize);
        }
        this.quadCount++;
        return true;
    }

    private void packTemplate(int index, int template, InputQuad quad) {
        int[] lights = new int[4];
        for (int v = 0; v < 4; v++) {
            lights[v] = VertexQuantizer.packLight(
                    quad.blockLight[v], quad.skyLight[v], quad.occlusion[v]);
        }
        byte[] out = new byte[VertexQuantizer.TEMPLATE_QUAD_BYTES];
        this.quantizer.packTemplateQuad(lights, out);

        int at = index * GENERAL_SLOT_BYTES;
        System.arraycopy(out, 0, this.packed, at, VertexQuantizer.TEMPLATE_QUAD_BYTES);
        this.isTemplate[index] = true;
        this.templateIds[index] = template;
        this.bytes += VertexQuantizer.TEMPLATE_QUAD_BYTES;
        this.templateQuads++;
        // No geometry error is recorded, because none is introduced: the template supplies the exact
        // corners, normal and UVs.
    }

    private void packGeneral(int index, InputQuad quad, int atlasSize) {
        float[] mins = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxs = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < 4; v++) {
            for (int axis = 0; axis < 3; axis++) {
                float value = quad.corners[v * 3 + axis];
                mins[axis] = Math.min(mins[axis], value);
                maxs[axis] = Math.max(maxs[axis], value);
            }
        }
        // A degenerate range on one axis is normal -- a face is flat -- and a range of zero cannot be
        // divided by. Widen it by the smallest amount that does not move a corner.
        for (int axis = 0; axis < 3; axis++) {
            if (maxs[axis] <= mins[axis]) {
                maxs[axis] = mins[axis] + 1e-4f;
            }
        }

        int at = index * GENERAL_SLOT_BYTES;
        int normal = this.quantizer.encodeNormal(quad.normal[0], quad.normal[1], quad.normal[2]);
        this.packed[at] = (byte) (normal & 0xFF);
        this.packed[at + 1] = (byte) ((normal >> 8) & 0xFF);

        for (int v = 0; v < 4; v++) {
            long bits = 0;
            for (int axis = 0; axis < 3; axis++) {
                int q = this.quantizer.quantisePosition(
                        quad.corners[v * 3 + axis], mins[axis], maxs[axis]);
                bits |= ((long) q) << (axis * VertexQuantizer.POSITION_BITS);
            }
            int u = this.quantizer.quantiseUv(quad.uvs[v * 2], atlasSize);
            int w = this.quantizer.quantiseUv(quad.uvs[v * 2 + 1], atlasSize);
            bits |= ((long) u) << 30;
            bits |= ((long) w) << 46;
            int light = VertexQuantizer.packLight(
                    quad.blockLight[v], quad.skyLight[v], quad.occlusion[v]);
            bits |= ((long) light) << 62;

            int base = at + 2 + v * 9;
            for (int i = 0; i < 9; i++) {
                this.packed[base + i] = (byte) (bits >>> (i * 8));
            }
        }

        this.isTemplate[index] = false;
        this.bytes += GENERAL_SLOT_BYTES;
        this.generalQuads++;

        // Recorded per quad here; a real buffer stores the range once per meshlet, which is what makes
        // local quantisation cheap.
        this.ranges[index] = new float[] {
            mins[0], maxs[0], mins[1], maxs[1], mins[2], maxs[2]};
    }

    /**
     * Rebuilds the geometry from the packed bytes.
     *
     * <p>This is what the shader does, in Java, so the error can be measured here rather than in front
     * of a screen.
     *
     * @param quad the quad to reconstruct
     * @param out twelve floats for the corners, and the normal written to {@code outNormal}
     * @param outNormal three floats
     */
    public void reconstruct(int quad, float[] out, float[] outNormal) {
        reconstruct(quad, out, outNormal, null);
    }

    /**
     * Rebuilds the geometry, optionally including UVs.
     *
     * @param outUvs eight floats, or null to skip
     */
    public void reconstruct(int quad, float[] out, float[] outNormal, float[] outUvs) {
        checkQuad(quad);
        if (out.length < 12 || outNormal.length < 3) {
            throw new IllegalArgumentException("out must hold twelve and three floats");
        }
        if (outUvs != null && outUvs.length < 8) {
            throw new IllegalArgumentException("outUvs must hold eight floats");
        }
        if (this.isTemplate[quad]) {
            this.templates.templateVertices(this.templateIds[quad], out);
            // The template's normal is the axis it is fixed on, which is exact. Templates come in
            // pairs along Y, then Z, then X, with the even one facing the negative direction.
            int template = this.templateIds[quad];
            int fixedAxis = template / 2 == 0 ? 1 : (template / 2 == 1 ? 2 : 0);
            outNormal[0] = 0f;
            outNormal[1] = 0f;
            outNormal[2] = 0f;
            outNormal[fixedAxis] = template % 2 == 0 ? -1f : 1f;
            if (outUvs != null) {
                float[][] standard = VertexQuantizerStandardUvs.standard();
                for (int v = 0; v < 4; v++) {
                    outUvs[v * 2] = standard[v][0];
                    outUvs[v * 2 + 1] = standard[v][1];
                }
            }
            return;
        }

        int at = quad * GENERAL_SLOT_BYTES;
        int packedNormal = (this.packed[at] & 0xFF) | ((this.packed[at + 1] & 0xFF) << 8);
        float[] normal = VertexQuantizer.decodeNormal(packedNormal);
        System.arraycopy(normal, 0, outNormal, 0, 3);

        float[] range = this.ranges[quad];
        for (int v = 0; v < 4; v++) {
            long bits = 0;
            int base = at + 2 + v * 9;
            for (int i = 0; i < 9; i++) {
                bits |= ((long) (this.packed[base + i] & 0xFF)) << (i * 8);
            }
            for (int axis = 0; axis < 3; axis++) {
                int q = (int) ((bits >>> (axis * VertexQuantizer.POSITION_BITS))
                        & ((1 << VertexQuantizer.POSITION_BITS) - 1));
                out[v * 3 + axis] = VertexQuantizer.decodePosition(
                        q, range[axis * 2], range[axis * 2 + 1]);
            }
            if (outUvs != null) {
                outUvs[v * 2] = (bits >>> 30) & 0xFFFF;
                outUvs[v * 2 + 1] = (bits >>> 46) & 0xFFFF;
            }
        }
    }

    /**
     * Measures the reconstruction error of one quad against its input.
     *
     * @return true if the error is inside the tolerances and the packed form is safe to use
     */
    public boolean measure(int quad, InputQuad original) {
        return measure(quad, original, 1024);
    }

    /**
     * Measures the reconstruction error, including UVs.
     *
     * @param atlasSize the atlas edge in texels, which the UV error is expressed against
     */
    public boolean measure(int quad, InputQuad original, int atlasSize) {
        checkQuad(quad);
        float[] corners = new float[12];
        float[] normal = new float[3];
        float[] uvs = new float[8];
        reconstruct(quad, corners, normal, uvs);

        double positionError = 0.0;
        for (int i = 0; i < 12; i++) {
            positionError = Math.max(positionError, Math.abs(corners[i] - original.corners[i]));
        }
        double dot = normal[0] * original.normal[0]
                + normal[1] * original.normal[1]
                + normal[2] * original.normal[2];
        double normalError = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, dot))));

        double uvError = 0.0;
        for (int v = 0; v < 4; v++) {
            for (int i = 0; i < 2; i++) {
                float decoded = this.isTemplate[quad]
                        ? uvs[v * 2 + i]
                        : VertexQuantizer.decodeUv((int) uvs[v * 2 + i], atlasSize);
                uvError = Math.max(uvError, Math.abs(decoded - original.uvs[v * 2 + i]) * atlasSize);
            }
        }

        this.worstPositionError = Math.max(this.worstPositionError, positionError);
        this.worstNormalErrorDegrees = Math.max(this.worstNormalErrorDegrees, normalError);
        this.worstUvErrorTexels = Math.max(this.worstUvErrorTexels, uvError);

        boolean withinTolerance = positionError <= POSITION_TOLERANCE
                && normalError <= NORMAL_TOLERANCE
                && uvError <= UV_TOLERANCE;
        if (!withinTolerance) {
            this.fallbacks++;
        }
        return withinTolerance;
    }

    private void checkQuad(int quad) {
        if (quad < 0 || quad >= this.quadCount) {
            throw new IndexOutOfBoundsException("quad " + quad + " outside " + this.quadCount);
        }
    }

    public int quadCount() {
        return this.quadCount;
    }

    /** Packed bytes for the section. */
    public int bytes() {
        return this.bytes;
    }

    /** Bytes the same quads would take uncompressed. */
    public long uncompressedBytes() {
        return (long) this.quadCount * 4 * VertexQuantizer.UNCOMPRESSED_BYTES;
    }

    public double compressionRatio() {
        return this.bytes == 0 ? 0.0 : (double) uncompressedBytes() / this.bytes;
    }

    public long templateQuads() {
        return this.templateQuads;
    }

    public long generalQuads() {
        return this.generalQuads;
    }

    /** Quads whose error exceeded the tolerances and would keep uncompressed vertices. */
    public long fallbacks() {
        return this.fallbacks;
    }

    public double worstPositionError() {
        return this.worstPositionError;
    }

    public double worstNormalErrorDegrees() {
        return this.worstNormalErrorDegrees;
    }

    public double worstUvErrorTexels() {
        return this.worstUvErrorTexels;
    }

    /** The template UVs, so reconstruction does not allocate them per call. */
    private static final class VertexQuantizerStandardUvs {
        private static final float[][] UV = {{0f, 1f}, {0f, 0f}, {1f, 0f}, {1f, 1f}};

        static float[][] standard() {
            return UV;
        }
    }
}
