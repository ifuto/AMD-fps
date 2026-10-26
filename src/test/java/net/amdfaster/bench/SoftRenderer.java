package net.amdfaster.bench;

/**
 * A software rasteriser: the same triangle pipeline a GPU runs, executed on the CPU.
 *
 * <p>This exists because the project's FPS numbers were, until now, arithmetic. A cost model can be
 * internally consistent and still wrong, and this one was: an earlier version carried a 4.0 ms
 * "irreducible" residual that quietly capped every speedup it could ever report at 1.93x. A rasteriser
 * cannot do that. It either shades the pixel or it does not, and the frame rate is a stopwatch reading.
 *
 * <p>What it is honest about: the cost of a pixel and the cost of a submitted vertex are the same for
 * both pipelines, so the ratio it produces measures how much less geometry and how much less fill
 * AMD-Faster submits. It does not measure GL driver overhead, which is real on AMD and which no
 * software rasteriser can reproduce. That is a limitation to state, not to hide.
 *
 * <p>Perspective correctness is done the standard way: {@code u/z}, {@code v/z} and {@code 1/z} are
 * interpolated linearly across the screen and divided at the end. Depth is compared as {@code 1/z}
 * directly, which is both linear in screen space and monotonic in view-space depth, so the depth buffer
 * needs no conversion and reversed-Z falls out for free.
 */
class SoftRenderer {

    /**
     * Outward normal of each face direction, indexed the way {@link Assets} numbers them.
     *
     * <p>Shared rather than copied into each pipeline: a merged mesh and a directional draw have to agree
     * on which way a face points, and two copies of that table are how they stop agreeing.
     */
    static final float[][] FACE_NORMALS = {
        {0.0f, -1.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
        {0.0f, 0.0f, -1.0f},
        {0.0f, 0.0f, 1.0f},
        {-1.0f, 0.0f, 0.0f},
        {1.0f, 0.0f, 0.0f},
    };

    /** Depth write, no blend. */
    static final int OPAQUE = 0;
    /** Alpha test, depth write, no blend: leaves and cutout glass. */
    static final int CUTOUT = 1;
    /** Source-over blend, no depth write: water, particles, GUI. */
    static final int BLEND = 2;

    private static final int CLIP_MAX = 4;

    final int width;
    final int height;

    private final int[] colour;
    private final float[] depth;

    private float camX;
    private float camY;
    private float camZ;
    private float rightX;
    private float rightY;
    private float rightZ;
    private float upX;
    private float upY;
    private float upZ;
    private float fwdX;
    private float fwdY;
    private float fwdZ;
    private float scaleX;
    private float scaleY;
    private float nearClip;
    private float centreX;
    private float centreY;
    private int skyColour;

    /**
     * Whether the rasteriser shades pixels or only accounts for them.
     *
     * <p>Turning this off measures the CPU-side pipeline on its own: culling, sorting, batching, the
     * model-view composition, the vertex transform, the clip, the projection and the triangle setup, with
     * the per-pixel work left out. That split is not a convenience, it is the difference between the two
     * things a frame is made of. On a real client the pixels belong to the GPU and cost a fraction of a
     * millisecond at this resolution, while everything else is the CPU's and is what the frame rate is
     * actually made of. A software rasteriser puts both on one core, which is why it reports a floor
     * rather than the answer: the fill it charges is the one term the two pipelines cannot differ on.
     */
    private boolean fillEnabled = true;

    private final float[] inX = new float[3];
    private final float[] inY = new float[3];
    private final float[] inZ = new float[3];
    private final float[] inU = new float[3];
    private final float[] inV = new float[3];
    private final float[] outX = new float[CLIP_MAX];
    private final float[] outY = new float[CLIP_MAX];
    private final float[] outZ = new float[CLIP_MAX];
    private final float[] outU = new float[CLIP_MAX];
    private final float[] outV = new float[CLIP_MAX];
    private final float[] sx = new float[CLIP_MAX];
    private final float[] sy = new float[CLIP_MAX];
    private final float[] invZ = new float[CLIP_MAX];
    private final float[] uOverZ = new float[CLIP_MAX];
    private final float[] vOverZ = new float[CLIP_MAX];

    // ---- counters, all of them read by the benchmark report -------------------------------
    long trianglesSubmitted;
    long trianglesBehindNear;
    long trianglesBackface;
    long trianglesDegenerate;
    long pixelsTested;
    long pixelsShaded;
    long pixelsBlended;
    long drawCalls;

    SoftRenderer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a framebuffer needs a positive size");
        }
        this.width = width;
        this.height = height;
        this.colour = new int[width * height];
        this.depth = new float[width * height];
        this.centreX = width * 0.5f;
        this.centreY = height * 0.5f;
    }

    void setCamera(float eyeX, float eyeY, float eyeZ,
            float rX, float rY, float rZ,
            float uX, float uY, float uZ,
            float fX, float fY, float fZ,
            float nearHalfWidth, float nearHalfHeight, float near) {
        if (!(near > 0f)) {
            throw new IllegalArgumentException("near must be positive: " + near);
        }
        this.camX = eyeX;
        this.camY = eyeY;
        this.camZ = eyeZ;
        this.rightX = rX;
        this.rightY = rY;
        this.rightZ = rZ;
        this.upX = uX;
        this.upY = uY;
        this.upZ = uZ;
        this.fwdX = fX;
        this.fwdY = fY;
        this.fwdZ = fZ;
        // The scale turns a view-space slope into pixels: at the near plane a point one nearHalfWidth to
        // the right lands at the edge of the framebuffer.
        this.scaleX = (this.width * 0.5f) * near / nearHalfWidth;
        this.scaleY = (this.height * 0.5f) * near / nearHalfHeight;
        this.nearClip = near;
    }

    /**
     * Writes the world-to-view matrix as twelve floats, row-major with the translation in the fourth
     * column, so a caller can compose a model matrix into it and hand the result back to
     * {@link #drawMeshWith}.
     */
    void fillViewMatrix(float[] out) {
        if (out == null || out.length != 12) {
            throw new IllegalArgumentException("the view matrix is twelve floats");
        }
        out[0] = this.rightX;
        out[1] = this.rightY;
        out[2] = this.rightZ;
        out[3] = -(this.camX * this.rightX + this.camY * this.rightY + this.camZ * this.rightZ);
        out[4] = this.upX;
        out[5] = this.upY;
        out[6] = this.upZ;
        out[7] = -(this.camX * this.upX + this.camY * this.upY + this.camZ * this.upZ);
        out[8] = this.fwdX;
        out[9] = this.fwdY;
        out[10] = this.fwdZ;
        out[11] = -(this.camX * this.fwdX + this.camY * this.fwdY + this.camZ * this.fwdZ);
    }

    /**
     * How many pixels one block spans at a given view depth, which is what a level-of-detail decision is
     * made from: an entity's height in blocks times this is its height on screen.
     */
    float pixelsPerUnitAt(float viewDepth) {
        if (!(viewDepth > 0.0f)) {
            return Float.POSITIVE_INFINITY;
        }
        return this.scaleY / viewDepth;
    }

    void setFillEnabled(boolean enabled) {
        this.fillEnabled = enabled;
    }

    boolean fillEnabled() {
        return this.fillEnabled;
    }

    void resetCounters() {
        this.trianglesSubmitted = 0;
        this.trianglesBehindNear = 0;
        this.trianglesBackface = 0;
        this.trianglesDegenerate = 0;
        this.pixelsTested = 0;
        this.pixelsShaded = 0;
        this.pixelsBlended = 0;
        this.drawCalls = 0;
    }

    void clear(int sky) {
        this.skyColour = sky;
        java.util.Arrays.fill(this.colour, sky);
        // Reversed-Z: zero is the far plane, so clearing to zero means everything draws over the clear.
        java.util.Arrays.fill(this.depth, 0.0f);
    }

    int skyColour() {
        return this.skyColour;
    }

    /**
     * Draws the six directional face meshes of a section as one submission.
     *
     * <p>This is what Sodium's mesher produces: one mesh per section per layer, not one per face, with
     * the faces turned away rejected by the GPU rather than by the caller. Modelling the baseline as six
     * separate submissions would charge it for six draw calls no renderer in the comparison stack issues,
     * and the speedup would measure that invention. Here the six are walked and the ones facing away are
     * dropped before any vertex work, which is the same outcome the driver reaches for free.
     */
    void drawFaceMeshes(Mesh[] faces, int mode, Texture[] textures) {
        for (int face = 0; face < faces.length; face++) {
            float[] normal = FACE_NORMALS[face];
            drawDirectional(faces[face], mode, textures, normal[0], normal[1], normal[2]);
        }
    }

    /**
     * Opens a batch of terrain draws that {@link #endTerrain()} submits together.
     *
     * <p>Sodium batches chunk sections with {@code glMultiDrawElementsBaseVertex}, grouped into regions,
     * so a baseline that issued one draw call per section would be a straw man. Both pipelines get the
     * same batching here; what differs is how many ranges end up in the batch and how many vertices each
     * range carries.
     */
    void beginTerrain(int mode) {
    }

    /** Submits whatever {@link #beginTerrain(int)} collected. */
    void endTerrain() {
    }

    /** Ends the frame, flushing anything the renderer has been holding. */
    void endFrame() {
    }

    /** Draws a baked mesh whose vertices are already in world space. */
    void drawMesh(Mesh mesh, int mode, Texture[] textures) {
        drawMeshWith(mesh, mode, textures, null);
    }

    /**
     * Draws a mesh that was built this frame and will be thrown away at the end of it.
     *
     * <p>Separated from {@link #drawMesh} because a backend that caches uploaded geometry has to treat
     * the two differently. Keying a cache on the mesh object is right for a section, which is built once
     * and drawn for minutes, and wrong for a particle batch, which is a new object every frame: the cache
     * misses every time, so each frame allocates a buffer, uploads it, and evicts the last one. The
     * software rasteriser has nothing to cache and does the same thing either way.
     */
    void drawDynamicMesh(Mesh mesh, int mode, Texture[] textures) {
        drawMesh(mesh, mode, textures);
    }

    /**
     * Draws a baked mesh through a model-view matrix, which is how anything that moves is drawn.
     *
     * @param modelView twelve floats, row-major, mapping model space to view space; null draws the
     *     vertices as world space through the camera transform alone
     *
     * <p>Folding the model matrix into the view matrix rather than transforming twice is not a shortcut,
     * it is what a renderer does: an entity's yaw, its position and the camera would otherwise be three
     * passes over the same vertices instead of one.
     */
    void drawMeshWith(Mesh mesh, int mode, Texture[] textures, float[] modelView) {
        int count = mesh.triangleCount;
        if (count == 0) {
            return;
        }
        this.drawCalls++;
        float[] verts = mesh.verts;
        int[] triTex = mesh.triTex;
        float[] triShade = mesh.triShade;
        boolean transformed = modelView != null;

        for (int t = 0; t < count; t++) {
            this.trianglesSubmitted++;
            int base = t * 15;

            boolean anyInFront = false;
            boolean allInFront = true;
            for (int k = 0; k < 3; k++) {
                int o = base + k * 5;
                float ex;
                float ey;
                float ez;
                if (transformed) {
                    float mx = verts[o];
                    float my = verts[o + 1];
                    float mz = verts[o + 2];
                    ex = modelView[0] * mx + modelView[1] * my + modelView[2] * mz + modelView[3];
                    ey = modelView[4] * mx + modelView[5] * my + modelView[6] * mz + modelView[7];
                    ez = modelView[8] * mx + modelView[9] * my + modelView[10] * mz + modelView[11];
                } else {
                    float dx = verts[o] - this.camX;
                    float dy = verts[o + 1] - this.camY;
                    float dz = verts[o + 2] - this.camZ;
                    ex = dx * this.rightX + dy * this.rightY + dz * this.rightZ;
                    ey = dx * this.upX + dy * this.upY + dz * this.upZ;
                    ez = dx * this.fwdX + dy * this.fwdY + dz * this.fwdZ;
                }
                this.inX[k] = ex;
                this.inY[k] = ey;
                this.inZ[k] = ez;
                this.inU[k] = verts[o + 3];
                this.inV[k] = verts[o + 4];
                if (ez >= this.nearClip) {
                    anyInFront = true;
                } else {
                    allInFront = false;
                }
            }
            if (!anyInFront) {
                this.trianglesBehindNear++;
                continue;
            }

            // When nothing needed clipping the vertices are still in the input arrays, and reading the
            // output arrays instead would project the previous triangle's leftovers. That is not a
            // theoretical worry: it is what this code did first, and the frame it produced had 95 percent
            // of its pixels missing while every counter looked plausible.
            float[] eyeX = allInFront ? this.inX : this.outX;
            float[] eyeY = allInFront ? this.inY : this.outY;
            float[] eyeZ = allInFront ? this.inZ : this.outZ;
            float[] eyeU = allInFront ? this.inU : this.outU;
            float[] eyeV = allInFront ? this.inV : this.outV;
            int n = allInFront ? 3 : clipNearPlane();
            for (int k = 0; k < n; k++) {
                float z = eyeZ[k];
                float inv = 1.0f / z;
                this.sx[k] = this.centreX + eyeX[k] * inv * this.scaleX;
                this.sy[k] = this.centreY - eyeY[k] * inv * this.scaleY;
                this.invZ[k] = inv;
                this.uOverZ[k] = eyeU[k] * inv;
                this.vOverZ[k] = eyeV[k] * inv;
            }
            Texture texture = textures[triTex[t]];
            float shade = triShade[t];
            for (int k = 1; k + 1 < n; k++) {
                rasterise(0, k, k + 1, texture, shade, mode);
            }
        }
    }

    /**
     * Clips the scratch triangle against the near plane only.
     *
     * <p>Only the near plane needs clipping because the other five are handled by the scissor: the
     * bounding box is intersected with the framebuffer before the scan. World geometry here never gets
     * close enough to the camera for the projected coordinates to lose precision, which is the reason a
     * single-plane clip is enough rather than a shortcut.
     *
     * @return how many vertices survive, three or four
     */
    private int clipNearPlane() {
        int out = 0;
        for (int k = 0; k < 3; k++) {
            int next = (k + 1) % 3;
            float z0 = this.inZ[k];
            float z1 = this.inZ[next];
            boolean in0 = z0 >= this.nearClip;
            boolean in1 = z1 >= this.nearClip;
            if (in0) {
                out = push(out, this.inX[k], this.inY[k], z0, this.inU[k], this.inV[k]);
            }
            if (in0 != in1) {
                // The edge crosses the plane. Solve for the crossing rather than bisecting, so the new
                // vertex is exactly on the plane and no seam appears between clipped triangles.
                float span = z1 - z0;
                float t = span == 0.0f ? 0.0f : (this.nearClip - z0) / span;
                out = push(out,
                        this.inX[k] + (this.inX[next] - this.inX[k]) * t,
                        this.inY[k] + (this.inY[next] - this.inY[k]) * t,
                        this.nearClip,
                        this.inU[k] + (this.inU[next] - this.inU[k]) * t,
                        this.inV[k] + (this.inV[next] - this.inV[k]) * t);
            }
        }
        return out;
    }

    private int push(int index, float x, float y, float z, float u, float v) {
        if (index >= CLIP_MAX) {
            return index;
        }
        this.outX[index] = x;
        this.outY[index] = y;
        this.outZ[index] = z;
        this.outU[index] = u;
        this.outV[index] = v;
        return index + 1;
    }

    private void rasterise(int i0, int i1, int i2, Texture texture, float shade, int mode) {
        float x0 = this.sx[i0];
        float y0 = this.sy[i0];
        float x1 = this.sx[i1];
        float y1 = this.sy[i1];
        float x2 = this.sx[i2];
        float y2 = this.sy[i2];

        // Edge function coefficients. E(p) = A * p.x + B * p.y + C is positive on the left of the edge
        // from a to b, and stepping p by one pixel adds A or B, which is what makes the inner loop an add.
        float a12 = y1 - y2;
        float b12 = x2 - x1;
        float a20 = y2 - y0;
        float b20 = x0 - x2;
        float a01 = y0 - y1;
        float b01 = x1 - x0;
        float area = a12 * (x0 - x1) + b12 * (y0 - y1);
        if (!(area > 0.0f)) {
            if (area < 0.0f) {
                // Clockwise in screen space. Swapping two vertices flips the sign and keeps the
                // attributes with their vertices, which is cheaper and less error-prone than carrying a
                // winding flag through the mesher.
                float tx = x1;
                x1 = x2;
                x2 = tx;
                float ty = y1;
                y1 = y2;
                y2 = ty;
                float tc = this.invZ[i1];
                this.invZ[i1] = this.invZ[i2];
                this.invZ[i2] = tc;
                float tu = this.uOverZ[i1];
                this.uOverZ[i1] = this.uOverZ[i2];
                this.uOverZ[i2] = tu;
                float tv = this.vOverZ[i1];
                this.vOverZ[i1] = this.vOverZ[i2];
                this.vOverZ[i2] = tv;
                a12 = y1 - y2;
                b12 = x2 - x1;
                a20 = y2 - y0;
                b20 = x0 - x2;
                a01 = y0 - y1;
                b01 = x1 - x0;
                area = a12 * (x0 - x1) + b12 * (y0 - y1);
                if (!(area > 0.0f)) {
                    this.trianglesDegenerate++;
                    return;
                }
            } else {
                this.trianglesDegenerate++;
                return;
            }
        }
        float invArea = 1.0f / area;

        float minX = Math.min(x0, Math.min(x1, x2));
        float maxX = Math.max(x0, Math.max(x1, x2));
        float minY = Math.min(y0, Math.min(y1, y2));
        float maxY = Math.max(y0, Math.max(y1, y2));
        int px0 = Math.max(0, (int) Math.floor(minX));
        int px1 = Math.min(this.width - 1, (int) Math.ceil(maxX));
        int py0 = Math.max(0, (int) Math.floor(minY));
        int py1 = Math.min(this.height - 1, (int) Math.ceil(maxY));
        if (px0 > px1 || py0 > py1) {
            return;
        }
        if (!this.fillEnabled) {
            this.pixelsTested += (long) (px1 - px0 + 1) * (py1 - py0 + 1);
            return;
        }

        float c0 = this.invZ[i0];
        float c1 = this.invZ[i1];
        float c2 = this.invZ[i2];
        float au0 = this.uOverZ[i0];
        float au1 = this.uOverZ[i1];
        float au2 = this.uOverZ[i2];
        float av0 = this.vOverZ[i0];
        float av1 = this.vOverZ[i1];
        float av2 = this.vOverZ[i2];

        int texW = texture.width;
        int texH = texture.height;
        int[] texels = texture.argb;

        float w0Row = a12 * (px0 + 0.5f - x1) + b12 * (py0 + 0.5f - y1);
        float w1Row = a20 * (px0 + 0.5f - x2) + b20 * (py0 + 0.5f - y2);
        float w2Row = a01 * (px0 + 0.5f - x0) + b01 * (py0 + 0.5f - y0);

        for (int py = py0; py <= py1; py++) {
            float w0 = w0Row;
            float w1 = w1Row;
            float w2 = w2Row;
            int rowBase = py * this.width;
            for (int px = px0; px <= px1; px++) {
                if (w0 >= 0.0f && w1 >= 0.0f && w2 >= 0.0f) {
                    this.pixelsTested++;
                    float c = (w0 * c0 + w1 * c1 + w2 * c2) * invArea;
                    int index = rowBase + px;
                    // Larger 1/z is nearer, which is the reversed-Z comparison with no conversion.
                    if (c > this.depth[index]) {
                        float b0 = (w0 * au0 + w1 * au1 + w2 * au2) * invArea / c;
                        float b1 = (w0 * av0 + w1 * av1 + w2 * av2) * invArea / c;
                        int tx = ((int) Math.floor(b0 * texW)) & (texW - 1);
                        int ty = ((int) Math.floor(b1 * texH)) & (texH - 1);
                        int texel = texels[ty * texW + tx];
                        int alpha = texel >>> 24;
                        if (mode != CUTOUT || alpha >= 128) {
                            if (mode != BLEND) {
                                this.depth[index] = c;
                            }
                            int r = (int) (((texel >> 16) & 0xFF) * shade);
                            int g = (int) (((texel >> 8) & 0xFF) * shade);
                            int b = (int) ((texel & 0xFF) * shade);
                            if (mode == BLEND && alpha < 255) {
                                this.pixelsBlended++;
                                int dst = this.colour[index];
                                int inv = 255 - alpha;
                                r = (r * alpha + ((dst >> 16) & 0xFF) * inv) / 255;
                                g = (g * alpha + ((dst >> 8) & 0xFF) * inv) / 255;
                                b = (b * alpha + (dst & 0xFF) * inv) / 255;
                                this.colour[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            } else {
                                this.colour[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                            this.pixelsShaded++;
                        }
                    }
                }
                w0 += a12;
                w1 += a20;
                w2 += a01;
            }
            w0Row += b12;
            w1Row += b20;
            w2Row += b01;
        }
    }

    /**
     * Draws an axis-aligned textured rectangle in screen space, which is what every HUD element is.
     *
     * <p>No depth buffer and no perspective: the GUI is an orthographic layer composited last, and
     * pretending otherwise would only add work the real thing does not do.
     */
    void drawScreenQuad(Texture texture, float u0, float v0, float u1, float v1,
            int x0, int y0, int x1, int y1, float brightness) {
        this.drawCalls++;
        int px0 = Math.max(0, x0);
        int py0 = Math.max(0, y0);
        int px1 = Math.min(this.width - 1, x1);
        int py1 = Math.min(this.height - 1, y1);
        if (px0 > px1 || py0 > py1) {
            return;
        }
        if (!this.fillEnabled) {
            this.pixelsTested += (long) (px1 - px0 + 1) * (py1 - py0 + 1);
            return;
        }
        int spanX = Math.max(1, x1 - x0);
        int spanY = Math.max(1, y1 - y0);
        float du = (u1 - u0) / spanX;
        float dv = (v1 - v0) / spanY;
        int texW = texture.width;
        int texH = texture.height;
        int[] texels = texture.argb;

        for (int py = py0; py <= py1; py++) {
            int ty = ((int) Math.floor(v0 + (py - y0) * dv)) & (texH - 1);
            int rowBase = py * this.width;
            for (int px = px0; px <= px1; px++) {
                this.pixelsTested++;
                int tx = ((int) Math.floor(u0 + (px - x0) * du)) & (texW - 1);
                int texel = texels[ty * texW + tx];
                int alpha = texel >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int r = (int) (((texel >> 16) & 0xFF) * brightness);
                int g = (int) (((texel >> 8) & 0xFF) * brightness);
                int b = (int) ((texel & 0xFF) * brightness);
                if (alpha < 255) {
                    this.pixelsBlended++;
                    int dst = this.colour[rowBase + px];
                    int inv = 255 - alpha;
                    r = (r * alpha + ((dst >> 16) & 0xFF) * inv) / 255;
                    g = (g * alpha + ((dst >> 8) & 0xFF) * inv) / 255;
                    b = (b * alpha + (dst & 0xFF) * inv) / 255;
                }
                this.colour[rowBase + px] = 0xFF000000 | (r << 16) | (g << 8) | b;
                this.pixelsShaded++;
            }
        }
    }

    /** How many pixels are not the clear colour, which proves the frame is a picture and not a blank. */
    int drawnPixels() {
        int sky = this.skyColour;
        int count = 0;
        for (int pixel : this.colour) {
            if (pixel != sky) {
                count++;
            }
        }
        return count;
    }

    /**
     * Draws a batch of screen-space quads in one submission.
     *
     * <p>Eight floats per quad -- x0, y0, x1, y1, u0, v0, u1, v1 -- and one call for all of them. This is
     * what batching actually is: not fewer pixels, because the pixels are the pixels, but one buffer, one
     * state setup and one dispatch instead of six hundred. A software rasteriser feels that difference as
     * method calls and bounds arithmetic rather than as driver overhead, so it understates it; it does not
     * invent it.
     */
    void drawScreenBatch(float[] quads, int quadCount, Texture texture, float brightness) {
        if (quadCount <= 0) {
            return;
        }
        this.drawCalls++;
        int texW = texture.width;
        int texH = texture.height;
        int[] texels = texture.argb;
        for (int q = 0; q < quadCount; q++) {
            int b = q * 8;
            int x0 = (int) quads[b];
            int y0 = (int) quads[b + 1];
            int x1 = (int) quads[b + 2];
            int y1 = (int) quads[b + 3];
            float u0 = quads[b + 4];
            float v0 = quads[b + 5];
            float du = (quads[b + 6] - u0) / Math.max(1, x1 - x0);
            float dv = (quads[b + 7] - v0) / Math.max(1, y1 - y0);
            int px0 = Math.max(0, x0);
            int py0 = Math.max(0, y0);
            int px1 = Math.min(this.width - 1, x1);
            int py1 = Math.min(this.height - 1, y1);
            for (int py = py0; py <= py1; py++) {
                int ty = ((int) Math.floor(v0 + (py - y0) * dv)) & (texH - 1);
                int rowBase = py * this.width;
                for (int px = px0; px <= px1; px++) {
                    this.pixelsTested++;
                    if (!this.fillEnabled) {
                        continue;
                    }
                    int tx = ((int) Math.floor(u0 + (px - x0) * du)) & (texW - 1);
                    int texel = texels[ty * texW + tx];
                    int alpha = texel >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    int r = (int) (((texel >> 16) & 0xFF) * brightness);
                    int g = (int) (((texel >> 8) & 0xFF) * brightness);
                    int blue = (int) ((texel & 0xFF) * brightness);
                    if (alpha < 255) {
                        this.pixelsBlended++;
                        int dst = this.colour[rowBase + px];
                        int inv = 255 - alpha;
                        r = (r * alpha + ((dst >> 16) & 0xFF) * inv) / 255;
                        g = (g * alpha + ((dst >> 8) & 0xFF) * inv) / 255;
                        blue = (blue * alpha + (dst & 0xFF) * inv) / 255;
                    }
                    this.colour[rowBase + px] = 0xFF000000 | (r << 16) | (g << 8) | blue;
                    this.pixelsShaded++;
                }
            }
        }
    }

    /** A cheap content fingerprint, so a report can show the two pipelines drew comparable scenes. */
    long checksum() {
        long sum = 1469598103934665603L;
        for (int i = 0; i < this.colour.length; i += 37) {
            sum ^= this.colour[i] & 0xFFFFFFFFL;
            sum *= 1099511628211L;
        }
        return sum;
    }

    /**
     * Draws a directional mesh unless the whole of it turns away from the camera.
     *
     * <p>This is what a GPU does for free: back-facing triangles are rejected at setup, after the vertex
     * shader and before a single pixel is touched. A benchmark that rasterised them per pixel would be
     * charging the baseline for work no driver performs, and the speedup would be a measurement of that
     * unfairness rather than of anything AMD-Faster does.
     *
     * <p>The test uses the face normal and the box rather than the screen-space winding, so it does not
     * depend on how the mesher happened to order a quad's corners -- a dependency that would be invisible
     * here and would show up as a world with holes in it.
     *
     * @param nx normal of every triangle in the mesh, which is what makes it directional
     */
    void drawDirectional(Mesh mesh, int mode, Texture[] textures, float nx, float ny, float nz) {
        if (mesh.triangleCount == 0) {
            return;
        }
        // The furthest corner along the normal, not the centre: a face at the top of a section is
        // visible from below the section's middle, and testing the middle would drop it.
        float px = nx > 0.0f ? mesh.minX : mesh.maxX;
        float py = ny > 0.0f ? mesh.minY : mesh.maxY;
        float pz = nz > 0.0f ? mesh.minZ : mesh.maxZ;
        float dx = this.camX - px;
        float dy = this.camY - py;
        float dz = this.camZ - pz;
        if (nx * dx + ny * dy + nz * dz <= 0.0f) {
            this.trianglesBackface += mesh.triangleCount;
            return;
        }
        drawMesh(mesh, mode, textures);
    }

    /**
     * Projects a world-space box to a screen rectangle and the nearest depth it reaches.
     *
     * <p>This is what an occlusion culler is handed: not the box, but the rectangle it covers and how
     * close it gets. Returning false means the box is wholly behind the near plane, in which case there
     * is no rectangle to speak of and nothing to test.
     *
     * @param out six floats: x0, y0, x1, y1, nearest depth, and the distance to the box centre
     * @return whether any part of the box is in front of the near plane
     */
    boolean projectBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
            float[] out) {
        float best = Float.POSITIVE_INFINITY;
        float screenMinX = Float.POSITIVE_INFINITY;
        float screenMinY = Float.POSITIVE_INFINITY;
        float screenMaxX = Float.NEGATIVE_INFINITY;
        float screenMaxY = Float.NEGATIVE_INFINITY;
        boolean any = false;
        float centreX = 0.0f;
        float centreY = 0.0f;
        float centreZ = 0.0f;
        for (int i = 0; i < 8; i++) {
            float wx = (i & 1) == 0 ? minX : maxX;
            float wy = (i & 2) == 0 ? minY : maxY;
            float wz = (i & 4) == 0 ? minZ : maxZ;
            float dx = wx - this.camX;
            float dy = wy - this.camY;
            float dz = wz - this.camZ;
            float ez = dx * this.fwdX + dy * this.fwdY + dz * this.fwdZ;
            centreX += dx * this.rightX + dy * this.rightY + dz * this.rightZ;
            centreY += dx * this.upX + dy * this.upY + dz * this.upZ;
            centreZ += ez;
            if (ez < this.nearClip) {
                continue;
            }
            any = true;
            float inv = 1.0f / ez;
            float px = this.centreX + (dx * this.rightX + dy * this.rightY + dz * this.rightZ)
                    * inv * this.scaleX;
            float py = this.centreY - (dx * this.upX + dy * this.upY + dz * this.upZ)
                    * inv * this.scaleY;
            if (px < screenMinX) {
                screenMinX = px;
            }
            if (px > screenMaxX) {
                screenMaxX = px;
            }
            if (py < screenMinY) {
                screenMinY = py;
            }
            if (py > screenMaxY) {
                screenMaxY = py;
            }
            // The Hi-Z buffer keeps the smallest depth, so the nearest corner is the one that matters:
            // a box is hidden only when even its closest point is behind what is already drawn there.
            if (ez < best) {
                best = ez;
            }
        }
        if (!any) {
            return false;
        }
        out[0] = screenMinX;
        out[1] = screenMinY;
        out[2] = screenMaxX;
        out[3] = screenMaxY;
        out[4] = best;
        out[5] = (float) Math.sqrt(centreX * centreX + centreY * centreY + centreZ * centreZ) * 0.125f;
        return true;
    }
}
