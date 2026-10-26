package net.amdfaster.bench;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/**
 * The same frame, submitted to a real OpenGL driver instead of rasterised here.
 *
 * <p>{@link SoftRenderer} cannot price a draw call. It has no driver, so six hundred HUD elements and one
 * batched submission cost it almost the same, and the 906 to 123 draw reduction the benchmark measures
 * shows up as a count and not as time. This class removes that blind spot: the geometry goes through
 * Mesa, the state changes go through Mesa, and the frame rate is what the driver actually delivers.
 *
 * <p>What runs where is the point. The CPU still does everything a client's CPU does -- culling, sorting,
 * batching, the model-view composition, building vertex buffers -- and the pixels are shaded by llvmpipe's
 * compiled rasteriser on the other cores. That split is the one a real client has, and it is the split a
 * single-threaded software rasteriser cannot reproduce: on one core, fill is the one term the two
 * pipelines cannot differ on, and it swallows every other difference.
 *
 * <p>What it is not: a GPU. llvmpipe is Mesa's software rasteriser, so per-pixel cost and per-draw cost
 * are both wrong in absolute terms. The comparison is still between two pipelines on the same driver, and
 * llvmpipe's draw overhead is far below AMD's OpenGL driver, which is the hardware this mod targets. That
 * makes the number a floor rather than a prediction, and it is reported as one.
 *
 * <h2>What has to stay identical to the software path</h2>
 *
 * <p>The camera, the projection, the counters and {@link #projectBox} are inherited rather than
 * reimplemented, so the occlusion culler is handed the same screen rectangles and depths it would be on
 * the CPU path. A second copy of that maths would be a second opinion about where a section lands on
 * screen, and the two would drift.
 */
class GlRaster extends SoftRenderer {

    /** x, y, z, u, v, shade. */
    private static final int FLOATS_PER_VERTEX = 6;

    /** State index for the orthographic GUI pass, which is not one of the three world modes. */
    private static final int STATE_SCREEN = 3;

    private static final String VERTEX_SHADER = String.join("\n",
            "#version 150 core",
            "uniform mat4 uMvp;",
            "in vec3 aPos;",
            "in vec2 aUv;",
            "in float aShade;",
            "out vec2 vUv;",
            "out float vShade;",
            "void main() {",
            "    gl_Position = uMvp * vec4(aPos, 1.0);",
            "    vUv = aUv;",
            "    vShade = aShade;",
            "}");

    private static final String FRAGMENT_SHADER = String.join("\n",
            "#version 150 core",
            "uniform sampler2D uAtlas;",
            "uniform float uCut;",
            "in vec2 vUv;",
            "in float vShade;",
            "out vec4 fragColor;",
            "void main() {",
            "    vec4 texel = texture(uAtlas, vUv);",
            "    if (texel.a < uCut) discard;",
            "    fragColor = vec4(texel.rgb * vShade, texel.a);",
            "}");

    private static final String SCREEN_VERTEX_SHADER = String.join("\n",
            "#version 150 core",
            "uniform vec2 uToClip;",
            "in vec2 aPos;",
            "in vec2 aUv;",
            "in float aShade;",
            "out vec2 vUv;",
            "out float vShade;",
            "void main() {",
            "    vec2 clip = vec2(aPos.x * uToClip.x - 1.0, 1.0 - aPos.y * uToClip.y);",
            "    gl_Position = vec4(clip, 0.0, 1.0);",
            "    vUv = aUv;",
            "    vShade = aShade;",
            "}");

    // ---- the atlas ------------------------------------------------------------------------------
    private final Texture[] textureTable;
    private final int atlasTexture;
    private final float[] tileU;
    private final float[] tileV;
    private final float[] tileSpanU;
    private final float[] tileSpanV;

    // ---- programs -------------------------------------------------------------------------------
    private final int program;
    private final int uniformMvp;
    private final int uniformCut;
    private final int screenProgram;
    private final int uniformToClip;
    private final int uniformScreenCut;

    // ---- objects --------------------------------------------------------------------------------
    private final int screenVao;
    private final int screenVbo;

    /** One buffer for geometry that is rebuilt every frame. See {@link #drawDynamicMesh}. */
    private final int dynamicVbo;

    private final int dynamicVao;

    /**
     * A full-screen quad at the far plane, and the program that puts it there.
     *
     * <p>This backend's depth buffer will not take a clear: neither glClear with the depth bit nor
     * glClearBufferfv writes it, and no call that builds the attachment reports an error, so the buffer
     * holds whatever the previous frame left and every fragment in the world fails a depth test against
     * it. It is written by drawing to it instead -- one quad, comparison forced to always pass, colour
     * writes masked off.
     */
    private final int depthClearVao;

    private final int depthClearProgram;
    private final int[] terrainVao = new int[3];
    private final int[] terrainVbo = new int[3];
    private final int[] terrainVertexCount = new int[3];
    /** Unique vertices in each indexed layer, so the multi-draw cannot read past the end. */
    private final int[] indexedVertexCount = new int[3];

    private final LinkedHashMap<Mesh, int[]> meshCache = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Mesh, int[]> eldest) {
            if (size() <= 1024) {
                return false;
            }
            // Evicting without deleting would leak a buffer per rebuilt rain mesh, and the rain mesh is
            // rebuilt every frame by the baseline on purpose: that rebuild is the cost being measured.
            GL30.glDeleteVertexArrays(eldest.getValue()[1]);
            GL15.glDeleteBuffers(eldest.getValue()[0]);
            GlRaster.this.evictions++;
            return true;
        }
    };

    /** Baked mesh to its range in a terrain layer buffer: layer, first vertex, vertex count. */
    private final IdentityHashMap<Mesh, int[]> terrainRange = new IdentityHashMap<>();
    /** The six face meshes of a section to the one merged range they occupy. */
    private final IdentityHashMap<Mesh[], int[]> faceRange = new IdentityHashMap<>();

    /**
     * Baked mesh to its range in the indexed copy of a terrain layer: layer, first index, index count,
     * base vertex.
     *
     * <p>The same terrain is baked twice, once as a quad array and once indexed, and the two submission
     * routes each draw the copy that matches what they are. This is not a handicap applied to one side
     * of the comparison: Sodium's chunk mesh is a non-indexed quad array drawn with
     * {@code glMultiDrawArrays}, with no index buffer anywhere in it, while a meshlet renderer submits
     * indexed geometry. Which of the two a renderer has decides how many vertices the driver has to
     * shade, and that is the difference being measured -- {@code drawFaceMeshes} draws the quad array,
     * {@code drawMesh} draws the indexed copy.
     */
    private final IdentityHashMap<Mesh, int[]> terrainIndexedRange = new IdentityHashMap<>();
    private final int[] terrainIbo = new int[3];
    private final IntBuffer indexedCountBuffer = MemoryUtil.memAllocInt(8192);
    /** Byte offsets into the element buffer, which is what the multi-draw wants, not index numbers. */
    private final PointerBuffer indexedOffsetBuffer = MemoryUtil.memAllocPointer(8192);
    private int indexedBatchSize;

    private final IntBuffer firstBuffer = MemoryUtil.memAllocInt(8192);
    private final IntBuffer countBuffer = MemoryUtil.memAllocInt(8192);
    private final FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
    /** One float, for the explicit depth clear. */
    private final FloatBuffer depthClear = MemoryUtil.memAllocFloat(1);
    /** The error the explicit depth clear produced, because an invalid enum there fails silently. */
    int depthClearError;

    private float[] screenData = new float[5 * 6 * 512];
    private int screenFloats;

    private final float[] viewProjection = new float[16];
    private final float[] projection = new float[16];
    private final float[] mvp = new float[16];

    private int batchLayer = -1;
    private int batchState;
    private int batchSize;

    private int currentState = -1;

    /**
     * Draws every face regardless of which way it points, and reports what that costs in coverage.
     *
     * <p>This exists because the first two runs of this backend painted 5% of the scene and the cause was
     * a guess away from being found. Either the winding is wrong and culling is eating the world, or
     * something else never reaches the framebuffer at all. One frame with culling off says which, and a
     * diagnosis beats a third guess.
     */
    boolean debugNoCull;

    /**
     * Submits every terrain range as its own {@code glDrawArrays} instead of one multi-draw.
     *
     * <p>The ranges demonstrably reach the driver -- the report counts eleven thousand of them -- and the
     * picture is still empty, so either the ranges describe nothing or the call that consumes them does
     * nothing. Drawing them one at a time separates those, and it is also the fallback this backend uses
     * on a driver with no multi-draw.
     */
    boolean debugNoMultiDraw;

    /** Skips the depth test, which is the last thing between a submitted triangle and a fragment. */
    boolean debugNoDepth;

    /**
     * Overrides the depth comparison, or 0 to leave it alone.
     *
     * <p>Everything else has been isolated and cleared, and what is left is a depth test that rejects
     * every fragment in the world while the driver reports the clear value and the comparison it was
     * handed. GL_ALWAYS with the test still enabled separates the two halves of that sentence: if it
     * paints the scene, the comparison against the buffer is what fails; if it does not, merely enabling
     * the test is enough to stop the world drawing, and the buffer is not the question at all.
     */
    int debugDepthFunc;

    /** Draws terrain meshes one at a time through the per-mesh path instead of the shared layer buffers. */
    boolean debugNoSharedBuffer;

    /** First range of the opaque batch, kept so it can be drawn again through a fresh vertex array. */
    int opaqueProbeFirst;
    int opaqueProbeCount;

    /**
     * Draws one terrain range again, through a vertex array built at this instant over the buffer that is
     * already resident.
     *
     * <p>This is the last fork in the investigation. The per-mesh path draws the same triangles and they
     * appear; this path, set up by the same calls, draws a quarter of them. Re-declaring the attribute
     * state at draw time changed nothing. So either the buffer built at startup does not hold what was
     * put into it, or the ranges do not describe where it was put. Building the vertex array here, over
     * the buffer that is already there, tells those apart: if this draws and the frame did not, the
     * resident vertex array is the fault; if this also draws nothing, the buffer or the ranges are.
     */
    /**
     * Clears to sky and draws the opaque terrain range once, with back-face culling on and the front face
     * set to whichever of the two the caller names.
     *
     * <p>The winding has now been guessed twice and been wrong twice, and both guesses were made while a
     * separate bug -- ranges counted in floats and read as vertices -- was hiding the answer. With the
     * ranges right, this settles it by measurement: one of the two settings draws the section and the
     * other draws nothing, and the coverage check says which.
     */
    void debugProbeOpaque(int frontFace) {
        if (this.opaqueProbeCount <= 0) {
            return;
        }
        GL11.glClearColor(0x78 / 255.0f, 0xA7 / 255.0f, 0xFF / 255.0f, 1.0f);
        GL11.glClearDepth(0.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(frontFace);
        GL30.glBindVertexArray(this.terrainVao[OPAQUE]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.terrainVbo[OPAQUE]);
        bindAttributes(this.program);
        compose(null);
        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.uniformMvp, false, this.matrixBuffer);
        GL20.glUniform1f(this.uniformCut, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, this.opaqueProbeFirst, this.opaqueProbeCount);
        GL11.glFinish();
        // The cached state no longer describes the driver, so the next real draw has to say it again.
        this.currentState = -1;
    }

    void debugRedrawOpaqueRange() {
        if (this.opaqueProbeCount <= 0) {
            return;
        }
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.terrainVbo[OPAQUE]);
        bindAttributes(this.program);
        applyState(OPAQUE);
        compose(null);
        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.uniformMvp, false, this.matrixBuffer);
        GL20.glUniform1f(this.uniformCut, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, this.opaqueProbeFirst, this.opaqueProbeCount);
        GL30.glDeleteVertexArrays(vao);
    }

    /** First vertex of the opaque terrain layer, kept so a report can push it through the matrix. */
    private final float[] probeVertex = new float[3];

    /** Set once a frame, when the first opaque range has been drawn and its first vertex read back. */
    private boolean probeCaptured;

    /**
     * Clips rasterisation to a single pixel, leaving every draw, every vertex and every state change in
     * place.
     *
     * <p>This is how the frame is split in two without modelling either half. The same pipeline, the same
     * geometry and the same driver calls run once with it off and once with it on, and the difference is
     * the cost of touching pixels. Nothing is estimated: a real client on a real GPU has almost no cost on
     * the scissored side of that subtraction, so the scissored ratio is the one that transfers, and the
     * full ratio is the one a software rasteriser shows.
     */
    boolean debugScissorTo1x1;

    /** Vertex count of a terrain layer buffer, so a report can show whether anything was baked into it. */
    int terrainVertices(int layer) {
        return this.terrainVertexCount[layer];
    }

    /** Ranges in the batch most recently submitted, and the first of them. */
    int lastBatchSize;
    int lastRangeFirst;
    int lastRangeCount;

    // ---- reported -------------------------------------------------------------------------------
    long glDraws;
    long glMultiDraws;
    long glRanges;
    /**
     * Nanoseconds the batched terrain path spends on per-frame state versus on the draw itself.
     *
     * <p>The submit stage came back at 53 nanoseconds a triangle against 39 for a baseline drawing
     * twice the geometry through the same shader, and nothing in the range counts explains it -- the
     * batched path already issues under two draws a frame. So the stage is split in two here rather
     * than guessed at: the program, matrix, vertex array and attribute redeclaration on one side,
     * the multi-draw the driver actually has to shade on the other.
     */
    long nsTerrainSetup;
    long nsTerrainDraw;
    long glUploads;
    long glTriangles;
    long evictions;

    GlRaster(int width, int height, Assets assets) {
        super(width, height);
        Texture[] textures = assets.textures;
        this.textureTable = textures;
        int side = atlasSide(textures);
        int[] origins = new int[textures.length * 2];
        packAtlas(textures, side, origins);
        this.atlasTexture = uploadAtlas(textures, side, origins);
        this.tileU = new float[textures.length];
        this.tileV = new float[textures.length];
        this.tileSpanU = new float[textures.length];
        this.tileSpanV = new float[textures.length];
        for (int i = 0; i < textures.length; i++) {
            this.tileU[i] = origins[i * 2] / (float) side;
            this.tileV[i] = origins[i * 2 + 1] / (float) side;
            this.tileSpanU[i] = textures[i].width / (float) side;
            this.tileSpanV[i] = textures[i].height / (float) side;
        }

        this.program = link(VERTEX_SHADER, FRAGMENT_SHADER);
        this.uniformMvp = GL20.glGetUniformLocation(this.program, "uMvp");
        this.uniformCut = GL20.glGetUniformLocation(this.program, "uCut");
        this.screenProgram = link(SCREEN_VERTEX_SHADER, FRAGMENT_SHADER);
        this.uniformToClip = GL20.glGetUniformLocation(this.screenProgram, "uToClip");
        this.uniformScreenCut = GL20.glGetUniformLocation(this.screenProgram, "uCut");

        // Bound once for the whole run: the atlas is the only texture anything samples, which is what
        // makes a texture switch cost nothing here and a draw call cost what a draw call costs.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.atlasTexture);
        GL20.glUseProgram(this.program);
        GL20.glUniform1i(GL20.glGetUniformLocation(this.program, "uAtlas"), 0);
        GL20.glUseProgram(this.screenProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(this.screenProgram, "uAtlas"), 0);

        this.screenVao = GL30.glGenVertexArrays();
        this.screenVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.screenVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.screenVbo);
        // Sized rather than left unallocated: LWJGL's glBufferData takes a size or a buffer, and passing a
        // null buffer dereferences it. endFrame re-specifies the store with the real quads every frame.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 20L * 6 * 512, GL15.GL_STREAM_DRAW);
        bindScreenAttributes(this.screenProgram);

        // Reversed Z: the near plane is depth 1 and the far plane is 0, so "greater" is nearer and the
        // clear value is zero. The projection is the one ViewProjection builds, and clip control puts the
        // depth range on [0, 1] to match it rather than letting GL rescale it into [-1, 1].
        // Deliberately NOT calling glClipControl.
        //
        // It is the one piece of non-default state in the depth path, and the depth path is the one thing
        // left that does not work: culling, the multi-draw, the matrices, the geometry, the ranges, the
        // vertex arrays and the buffers have each been isolated by measurement and each cleared, the
        // driver reports the clear value and the comparison it was handed, the depth buffer is attached
        // with no error from either call that made it, and switching between reversed Z and standard Z
        // changed the painted count by exactly zero. Every combination of those ought to pass the test,
        // so the remaining suspect is the one that changes how the driver maps depth at all. Leaving it
        // out costs a rescale of [-1, 1] into [0, 1] and nothing else; the ordering it preserves is the
        // ordering the test needs.
        // Clockwise, which looks like a typo and is not. Screen x grows with right.p and screen y with
        // up.p, and the camera looks along forward, so the frame the screen is drawn in is
        // (right, up, -forward) -- left handed, because (right, up, forward) is right handed. A triangle
        // wound counter-clockwise as seen from outside therefore reaches the framebuffer clockwise, and
        // GL_CCW here would reject every front face in the world. The first run of this backend did
        // exactly that: both pipelines painted only their clouds, their rain and their GUI, and the
        // coverage check against the software rasteriser is what said so.
        this.dynamicVbo = GL15.glGenBuffers();
        this.dynamicVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.dynamicVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.dynamicVbo);
        // Sized rather than left unallocated: glBufferData with a null buffer dereferences it. The first
        // real submission re-specifies the store at whatever size the frame needs.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) FLOATS_PER_VERTEX * 4 * 6 * 4096,
                GL15.GL_STREAM_DRAW);
        bindAttributes(this.program);

        int depthClearVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, depthClearVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {
                -1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f,
                -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f,
        }, GL15.GL_STATIC_DRAW);
        this.depthClearVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.depthClearVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, depthClearVbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
        this.depthClearProgram = link(
                "#version 150 core\n"
                        + "in vec2 aPos;\n"
                        + "void main() { gl_Position = vec4(aPos, 1.0, 1.0); }\n",
                "#version 150 core\n"
                        + "out vec4 fragColor;\n"
                        + "void main() { fragColor = vec4(0.0); }\n");
        GL11.glFrontFace(GL11.GL_CW);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glClearDepth(0.0);
        GL30.glBindVertexArray(0);
    }

    // ---- atlas ----------------------------------------------------------------------------------

    /**
     * How many times each texture is repeated inside its atlas cell.
     *
     * <p>The mesher merges runs of coplanar faces into one quad and repeats the sprite across it, which
     * needs the sprite to wrap. The software rasteriser wraps by masking the texel index. Here the atlas
     * holds many sprites side by side, so wrapping the whole atlas would sample the neighbour; instead
     * each sprite gets a cell sixteen of itself wide and tall, and a merged quad of up to sixteen tiles
     * -- the width of a section, so the largest there is -- stays inside its own cell.
     */
    private static final int ATLAS_TILES = 16;

    /** Smallest power of two a shelf packer fits every texture into. */
    private static int atlasSide(Texture[] textures) {
        for (int side = 256; side <= 8192; side *= 2) {
            if (fits(textures, side)) {
                return side;
            }
        }
        throw new IllegalStateException("no atlas size fits " + textures.length + " textures");
    }

    private static boolean fits(Texture[] textures, int side) {
        int cursorX = 0;
        int cursorY = 0;
        int rowHeight = 0;
        for (int index : byHeight(textures)) {
            Texture texture = textures[index];
            int cellW = texture.width * ATLAS_TILES;
            int cellH = texture.height * ATLAS_TILES;
            if (cursorX + cellW > side) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            if (cursorY + cellH > side) {
                return false;
            }
            cursorX += cellW;
            rowHeight = Math.max(rowHeight, cellH);
        }
        return true;
    }

    private static void packAtlas(Texture[] textures, int side, int[] origins) {
        int cursorX = 0;
        int cursorY = 0;
        int rowHeight = 0;
        for (int index : byHeight(textures)) {
            Texture texture = textures[index];
            if (cursorX + texture.width > side) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            origins[index * 2] = cursorX;
            origins[index * 2 + 1] = cursorY;
            cursorX += texture.width * ATLAS_TILES;
            rowHeight = Math.max(rowHeight, texture.height * ATLAS_TILES);
        }
    }

    /** Tallest first, which is what makes a shelf packer leave little behind. */
    private static int[] byHeight(Texture[] textures) {
        Integer[] order = new Integer[textures.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(textures[b].height, textures[a].height));
        int[] out = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            out[i] = order[i];
        }
        return out;
    }

    private static int uploadAtlas(Texture[] textures, int side, int[] origins) {
        int[] pixels = new int[side * side];
        for (int index = 0; index < textures.length; index++) {
            Texture texture = textures[index];
            int originX = origins[index * 2];
            int originY = origins[index * 2 + 1];
            // Tiled, not centred: the cell has to answer correctly for a merged quad that runs up to
            // sixteen tiles in either direction.
            for (int tileY = 0; tileY < ATLAS_TILES; tileY++) {
                for (int y = 0; y < texture.height; y++) {
                    for (int tileX = 0; tileX < ATLAS_TILES; tileX++) {
                        System.arraycopy(texture.argb, y * texture.width, pixels,
                                (originY + tileY * texture.height + y) * side
                                        + originX + tileX * texture.width,
                                texture.width);
                    }
                }
            }
        }
        ByteBuffer bytes = MemoryUtil.memAlloc(side * side * 4);
        for (int pixel : pixels) {
            bytes.put((byte) ((pixel >> 16) & 0xFF));
            bytes.put((byte) ((pixel >> 8) & 0xFF));
            bytes.put((byte) (pixel & 0xFF));
            bytes.put((byte) ((pixel >>> 24) & 0xFF));
        }
        bytes.flip();
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, side, side, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
        // Nearest and clamped: these are 16x16 block textures magnified across the screen, and a linear
        // filter would blur every block edge the software path draws sharp.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // GL_CLAMP_TO_EDGE is a GL 1.2 constant; GL11 does not carry it.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        MemoryUtil.memFree(bytes);
        return texture;
    }

    // ---- shaders --------------------------------------------------------------------------------

    private static int link(String vertexSource, String fragmentSource) {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int handle = GL20.glCreateProgram();
        GL20.glAttachShader(handle, vertex);
        GL20.glAttachShader(handle, fragment);
        // Bound before linking, so the attribute locations are the same in both programs and neither
        // vertex array object has to ask the driver where its inputs ended up.
        GL20.glBindAttribLocation(handle, 0, "aPos");
        GL20.glBindAttribLocation(handle, 1, "aUv");
        GL20.glBindAttribLocation(handle, 2, "aShade");
        GL20.glLinkProgram(handle);
        if (GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            throw new IllegalStateException("link failed: " + GL20.glGetProgramInfoLog(handle));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        return handle;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            throw new IllegalStateException("compile failed: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static void bindAttributes(int program) {
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 20L);
    }

    private static void bindScreenAttributes(int program) {
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 20, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 8L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, 20, 16L);
    }

    // ---- camera ---------------------------------------------------------------------------------

    /** Hands the driver the same projection the software path rasterises with. */
    void setViewProjection(float[] matrix) {
        System.arraycopy(matrix, 0, this.viewProjection, 0, 16);
        compose(null);
    }

    /**
     * Builds the projection on its own, without the view.
     *
     * <p>{@code drawMeshWith} is handed a model-VIEW matrix, exactly as the software rasteriser takes it:
     * the caller has already folded the camera in. Multiplying that by view-projection applies the view
     * twice, which does not fail loudly -- it moves every entity, particle and rain drop to somewhere
     * else that is usually still on screen, so the frame looks busy and the numbers look plausible.
     * Terrain passes no model at all and needs view-projection; this is what the other path needs.
     */
    void setProjection(float near, float nearHalfWidth, float nearHalfHeight, float far) {
        float sx = near / nearHalfWidth;
        float sy = near / nearHalfHeight;
        float a = -near / (far - near);
        float b = near * far / (far - near);
        java.util.Arrays.fill(this.projection, 0.0f);
        this.projection[0] = sx;
        this.projection[5] = sy;
        this.projection[10] = a;
        this.projection[14] = b;
        this.projection[11] = 1.0f;
    }

    /**
     * Folds a twelve float row-major model-view into the projection, or copies the projection when there
     * is no model. Folding rather than transforming twice is what a renderer does: an entity's yaw, its
     * position and the camera would otherwise be three passes over the same vertices instead of one.
     */
    private void compose(float[] modelView) {
        float[] out = this.mvp;
        // No model means the vertices are already in world space and the view has to come from here.
        // A model means it is a model-view matrix and already contains the view, so only the projection
        // is left to apply. Getting this the wrong way round draws the same picture in the wrong place.
        float[] vp = modelView == null ? this.viewProjection : this.projection;
        for (int column = 0; column < 4; column++) {
            float m0;
            float m1;
            float m2;
            float m3;
            if (modelView == null) {
                m0 = column == 0 ? 1.0f : 0.0f;
                m1 = column == 1 ? 1.0f : 0.0f;
                m2 = column == 2 ? 1.0f : 0.0f;
                m3 = column == 3 ? 1.0f : 0.0f;
            } else if (column < 3) {
                m0 = modelView[column];
                m1 = modelView[4 + column];
                m2 = modelView[8 + column];
                m3 = 0.0f;
            } else {
                // The fourth column of a row-major 3x4 is the translation, and dropping it would draw
                // every entity at the world origin.
                m0 = modelView[3];
                m1 = modelView[7];
                m2 = modelView[11];
                m3 = 1.0f;
            }
            for (int row = 0; row < 4; row++) {
                // Column major, which is what glUniformMatrix4fv wants and what ViewProjection writes.
                out[column * 4 + row] = vp[row] * m0 + vp[4 + row] * m1
                        + vp[8 + row] * m2 + vp[12 + row] * m3;
            }
        }
        // Turn the depth row round: z' = w - z, so ndc z' is 1 - ndc z, near is 0 and far is 1.
        //
        // ViewProjection is reversed-Z on [0, 1] because the occlusion culler wants nearer to be larger
        // and a Vulkan D32_SFLOAT buffer makes that exact. This driver path is not that. It is a 24 bit
        // integer depth buffer behind a GL default clear value of 1.0 and a default comparison of
        // GL_LESS, and with near 0.1 against far 190 the reversed values land between 0.0005 and 0.01 --
        // which is where the last four runs went to die, with the driver reporting the clear value and
        // the comparison it had been given and every fragment in the world failing anyway. Standard Z
        // costs nothing here: the two pipelines are compared with each other on the same driver, and
        // neither of them is served by the depth precision.
        // Twice, not once. ViewProjection puts near at ndc z 1 and far at 0, and the window transform
        // maps ndc [-1, 1] to window [0, 1], so z' = w - z would land the whole world in the top half of
        // the depth buffer and throw away half of its 24 bits. z' = w - 2z maps near to -1 and far to +1,
        // which is the range the driver was built for.
        float z0 = out[2];
        float z1 = out[6];
        float z2 = out[10];
        float z3 = out[14];
        out[2] = out[3] - 2.0f * z0;
        out[6] = out[7] - 2.0f * z1;
        out[10] = out[11] - 2.0f * z2;
        out[14] = out[15] - 2.0f * z3;
        this.matrixBuffer.clear();
        this.matrixBuffer.put(out);
        this.matrixBuffer.flip();
    }

    // ---- terrain --------------------------------------------------------------------------------

    /**
     * Bakes every section's meshes into three resident buffers, one per layer.
     *
     * <p>Resident because that is what a chunk renderer keeps: the terrain is uploaded once and drawn many
     * times, and charging either pipeline for a per-frame upload would measure the wrong thing. The rain
     * mesh is the opposite case and is deliberately not here -- the baseline rebuilds it every frame and
     * pays for the upload, which is the cost {@code WeatherMeshCache} exists to remove.
     *
     * <p>The opaque layer is also where the winding is settled. {@code GL_CULL_FACE} decides front from
     * the order the corners reach the framebuffer, and the mesher's order was never checked against that.
     * So each triangle is compared with the outward normal of the face it belongs to and its second and
     * third corners are swapped when they disagree. Back faces are then rejected by the driver, which is
     * free, instead of by the caller, which is what a merged section mesh has no way to do.
     */
    /**
     * Bakes one terrain layer a second time as indexed geometry, and uploads its element buffer.
     *
     * <p>A greedy run is one quad submitted as two triangles, so its four corners arrive as six
     * vertices and the two they share are transformed again every frame to produce exactly the value
     * they had the first time. On llvmpipe that is essentially the entire submit stage -- 435
     * microseconds a frame, of which 9 was per-frame state -- because the driver shades what it is
     * handed, and it is handed three vertices a triangle.
     *
     * <p>Two corners are the same vertex only when all six baked floats agree: position, the atlas
     * coordinates the tile lookup produced, and the shade. Position alone is not enough, because two
     * faces meeting at a cube corner share a position and nothing else.
     */
    private void buildIndexedLayer(int layer, SectionMeshes[] sections, int triangles) {
        if (triangles == 0) {
            this.terrainIbo[layer] = 0;
            return;
        }
        float[] positions = new float[triangles * 3 * FLOATS_PER_VERTEX];
        int[] indices = new int[triangles * 3];
        int capacity = 1;
        while (capacity < triangles * 6) {
            capacity <<= 1;
        }
        // Open addressed, one slot per vertex, holding the vertex index plus one so zero means empty.
        int[] slots = new int[capacity];
        long[] hashes = new long[capacity];
        int vertexCount = 0;
        int indexAt = 0;
        int faces = layer == 0 ? 6 : 1;
        for (SectionMeshes section : sections) {
            for (int f = 0; f < faces; f++) {
                Mesh mesh;
                float[] normal;
                if (layer == 0) {
                    mesh = section.opaque[f];
                    normal = FACE_NORMALS[f];
                } else {
                    mesh = layer == 1 ? section.cutout : section.blend;
                    normal = null;
                }
                int first = indexAt;
                float[] verts = mesh.verts;
                int[] triTex = mesh.triTex;
                float[] triShade = mesh.triShade;
                for (int triangle = 0; triangle < mesh.triangleCount; triangle++) {
                    int base = triangle * 15;
                    int texture = triTex[triangle];
                    float shade = triShade[triangle];
                    float tileU = this.tileU[texture];
                    float tileV = this.tileV[texture];
                    float spanU = this.tileSpanU[texture];
                    float spanV = this.tileSpanV[texture];
                    // The same winding repair the quad array gets, so the two copies agree corner for
                    // corner and the coverage check compares like with like.
                    int order = 0;
                    if (normal != null) {
                        float ax = verts[base + 5] - verts[base];
                        float ay = verts[base + 6] - verts[base + 1];
                        float az = verts[base + 7] - verts[base + 2];
                        float bx = verts[base + 10] - verts[base];
                        float by = verts[base + 11] - verts[base + 1];
                        float bz = verts[base + 12] - verts[base + 2];
                        float crossX = ay * bz - az * by;
                        float crossY = az * bx - ax * bz;
                        float crossZ = ax * by - ay * bx;
                        if (crossX * normal[0] + crossY * normal[1] + crossZ * normal[2] < 0.0f) {
                            order = 1;
                        }
                    }
                    for (int corner = 0; corner < 3; corner++) {
                        int source = corner == 1 && order == 1 ? 2
                                : (corner == 2 && order == 1 ? 1 : corner);
                        int v = base + source * 5;
                        float x = verts[v];
                        float y = verts[v + 1];
                        float z = verts[v + 2];
                        float u = tileU + verts[v + 3] * spanU;
                        float w = tileV + verts[v + 4] * spanV;
                        long hash = hashVertex(x, y, z, u, w, shade);
                        int at = ((int) (hash >>> 32)) & (capacity - 1);
                        int found = -1;
                        while (slots[at] != 0) {
                            if (hashes[at] == hash) {
                                int o = (slots[at] - 1) * FLOATS_PER_VERTEX;
                                if (positions[o] == x && positions[o + 1] == y && positions[o + 2] == z
                                        && positions[o + 3] == u && positions[o + 4] == w
                                        && positions[o + 5] == shade) {
                                    found = slots[at] - 1;
                                    break;
                                }
                            }
                            at = (at + 1) & (capacity - 1);
                        }
                        if (found < 0) {
                            found = vertexCount++;
                            int o = found * FLOATS_PER_VERTEX;
                            positions[o] = x;
                            positions[o + 1] = y;
                            positions[o + 2] = z;
                            positions[o + 3] = u;
                            positions[o + 4] = w;
                            positions[o + 5] = shade;
                            slots[at] = found + 1;
                            hashes[at] = hash;
                        }
                        indices[indexAt++] = found;
                    }
                }
                this.terrainIndexedRange.put(mesh, new int[] {layer, first, indexAt - first});
            }
        }
        this.indexedVertexCount[layer] = vertexCount;
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indexAt);
        indexBuffer.put(indices, 0, indexAt);
        indexBuffer.flip();
        // Bound while the layer's vertex array is bound, so the element buffer is part of that array's
        // state and the multi-draw below does not have to rebind it per frame.
        GL30.glBindVertexArray(this.terrainVao[layer]);
        this.terrainIbo[layer] = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.terrainIbo[layer]);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indexBuffer);
        GL30.glBindVertexArray(0);
    }

    /**
     * Vertices the indexed copy of the opaque layer holds, against the quad array's.
     *
     * <p>Printed so the deduplication can be seen to have happened rather than assumed. If the two
     * numbers came back equal the bake would have found no shared corners at all, the element buffer
     * would be pure overhead, and the ratio would say so for the wrong reason.
     */
    int indexedVertexCount() {
        return this.indexedVertexCount[0];
    }

    /** Every baked float has to match for two corners to be one vertex, so all six go into the hash. */
    private static long hashVertex(float x, float y, float z, float u, float v, float shade) {
        long h = Float.floatToIntBits(x) & 0xFFFFFFFFL;
        h = h * 0x9E3779B97F4A7C15L + (Float.floatToIntBits(y) & 0xFFFFFFFFL);
        h = h * 0x9E3779B97F4A7C15L + (Float.floatToIntBits(z) & 0xFFFFFFFFL);
        h = h * 0x9E3779B97F4A7C15L + (Float.floatToIntBits(u) & 0xFFFFFFFFL);
        h = h * 0x9E3779B97F4A7C15L + (Float.floatToIntBits(v) & 0xFFFFFFFFL);
        h = h * 0x9E3779B97F4A7C15L + (Float.floatToIntBits(shade) & 0xFFFFFFFFL);
        return h ^ (h >>> 29);
    }

    void buildTerrain(SectionMeshes[] sections) {
        for (int layer = 0; layer < 3; layer++) {
            int triangles = 0;
            for (SectionMeshes section : sections) {
                if (layer == 0) {
                    for (Mesh face : section.opaque) {
                        triangles += face.triangleCount;
                    }
                } else if (layer == 1) {
                    triangles += section.cutout.triangleCount;
                } else {
                    triangles += section.blend.triangleCount;
                }
            }
            float[] data = new float[triangles * 3 * FLOATS_PER_VERTEX];
            int[] faceFirst = new int[6];
            int[] faceCount = new int[6];
            // Vertices, not floats. glDrawArrays and glMultiDrawArrays both count vertices, and the
            // buffer holds six floats per vertex, so a range recorded in floats points six times too far
            // into the buffer and reads six times too much -- the wrong sections, drawn from the wrong
            // place, which still puts triangles on screen and so still looks like it is working.
            int at = 0;
            for (SectionMeshes section : sections) {
                if (layer == 0) {
                    int sectionStart = at;
                    for (int face = 0; face < 6; face++) {
                        faceFirst[face] = at;
                        at = append(data, at, section.opaque[face], FACE_NORMALS[face]);
                        faceCount[face] = at - faceFirst[face];
                        this.terrainRange.put(section.opaque[face],
                                new int[] {0, faceFirst[face], faceCount[face]});
                    }
                    this.faceRange.put(section.opaque, new int[] {0, sectionStart, at - sectionStart});
                } else {
                    Mesh mesh = layer == 1 ? section.cutout : section.blend;
                    int start = at;
                    at = append(data, at, mesh, null);
                    this.terrainRange.put(mesh, new int[] {layer, start, at - start});
                }
            }
            this.terrainVertexCount[layer] = at;
            if (layer == 0 && at > 0) {
                this.probeVertex[0] = data[0];
                this.probeVertex[1] = data[1];
                this.probeVertex[2] = data[2];
            }
            FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
            buffer.put(data);
            buffer.flip();
            this.terrainVbo[layer] = GL15.glGenBuffers();
            this.terrainVao[layer] = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(this.terrainVao[layer]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.terrainVbo[layer]);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
            bindAttributes(this.program);
            MemoryUtil.memFree(buffer);
            this.glUploads++;
            // After the vertex array exists, because the element buffer is bound into it and becomes
            // part of its state.
            buildIndexedLayer(layer, sections, triangles);
        }
        GL30.glBindVertexArray(0);
    }

    /**
     * Writes one mesh into a layer buffer, optionally repairing its winding against a known normal.
     *
     * <p>Takes and returns a vertex index. The buffer is floats and the draw calls are vertices, and
     * letting the caller convert is how the two got confused once already.
     */
    private int append(float[] data, int vertexAt, Mesh mesh, float[] normal) {
        float[] verts = mesh.verts;
        int[] triTex = mesh.triTex;
        float[] triShade = mesh.triShade;
        int at = vertexAt * FLOATS_PER_VERTEX;
        for (int triangle = 0; triangle < mesh.triangleCount; triangle++) {
            int base = triangle * 15;
            int texture = triTex[triangle];
            float shade = triShade[triangle];
            float u0 = this.tileU[texture];
            float v0 = this.tileV[texture];
            float su = this.tileSpanU[texture];
            float sv = this.tileSpanV[texture];
            int order = 0;
            if (normal != null) {
                float ax = verts[base + 5] - verts[base];
                float ay = verts[base + 6] - verts[base + 1];
                float az = verts[base + 7] - verts[base + 2];
                float bx = verts[base + 10] - verts[base];
                float by = verts[base + 11] - verts[base + 1];
                float bz = verts[base + 12] - verts[base + 2];
                float crossX = ay * bz - az * by;
                float crossY = az * bx - ax * bz;
                float crossZ = ax * by - ay * bx;
                if (crossX * normal[0] + crossY * normal[1] + crossZ * normal[2] < 0.0f) {
                    order = 1;
                }
            }
            for (int corner = 0; corner < 3; corner++) {
                int source = corner == 1 && order == 1 ? 2 : (corner == 2 && order == 1 ? 1 : corner);
                int vertex = base + source * 5;
                data[at] = verts[vertex];
                data[at + 1] = verts[vertex + 1];
                data[at + 2] = verts[vertex + 2];
                data[at + 3] = u0 + verts[vertex + 3] * su;
                data[at + 4] = v0 + verts[vertex + 4] * sv;
                data[at + 5] = shade;
                at += FLOATS_PER_VERTEX;
            }
        }
        return at / FLOATS_PER_VERTEX;
    }

    @Override
    void beginTerrain(int mode) {
        this.batchLayer = mode;
        this.batchState = mode;
        this.batchSize = 0;
        this.indexedBatchSize = 0;
        this.firstBuffer.clear();
        this.countBuffer.clear();
        this.indexedCountBuffer.clear();
        this.indexedOffsetBuffer.clear();
    }

    @Override
    void endTerrain() {
        int layer = this.batchLayer;
        this.batchLayer = -1;
        if (layer < 0 || (this.batchSize == 0 && this.indexedBatchSize == 0)) {
            return;
        }
        long setupAt = System.nanoTime();
        applyState(this.batchState);
        // Recomposed rather than reused: terrain vertices are in world space, so the model is the
        // identity, and whatever the last entity draw left in this buffer is not that.
        compose(null);
        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.uniformMvp, false, this.matrixBuffer);
        GL20.glUniform1f(this.uniformCut, this.batchState == CUTOUT ? 0.5f : 0.0f);
        GL30.glBindVertexArray(this.terrainVao[layer]);
        // Re-declared rather than trusted. The per-mesh path draws the very same triangles from the very
        // same meshes and they land on screen; this one, set up by textually identical calls, does not.
        // So the vertex array state built once at startup is not the state in force at draw time, and
        // rather than guess a third time this says so: if coverage comes back with these three lines, the
        // cause is the cached state and not the geometry, the ranges, the matrix or the draw call.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.terrainVbo[layer]);
        bindAttributes(this.program);
        this.lastBatchSize = this.batchSize;
        this.lastRangeFirst = this.firstBuffer.get(0);
        this.lastRangeCount = this.countBuffer.get(0);
        if (layer == OPAQUE) {
            this.opaqueProbeFirst = this.lastRangeFirst;
            this.opaqueProbeCount = this.lastRangeCount;
            if (!this.probeCaptured && this.lastRangeCount > 0) {
                // Read back the vertex the first range actually starts with, so the clip arithmetic is
                // checked against a corner that is on screen rather than one that happens to be baked
                // first. The buffer is already bound by the lines above.
                FloatBuffer one = MemoryUtil.memAllocFloat(FLOATS_PER_VERTEX);
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,
                        (long) this.lastRangeFirst * FLOATS_PER_VERTEX * 4L, one);
                this.probeVertex[0] = one.get(0);
                this.probeVertex[1] = one.get(1);
                this.probeVertex[2] = one.get(2);
                MemoryUtil.memFree(one);
                this.probeCaptured = true;
            }
        }
        this.nsTerrainSetup += System.nanoTime() - setupAt;
        long drawAt = System.nanoTime();
        if (this.debugNoMultiDraw && this.indexedBatchSize == 0) {
            for (int i = 0; i < this.batchSize; i++) {
                GL11.glDrawArrays(GL11.GL_TRIANGLES, this.firstBuffer.get(i), this.countBuffer.get(i));
            }
            this.glDraws += this.batchSize;
            this.nsTerrainDraw += System.nanoTime() - drawAt;
            return;
        }
        if (this.debugNoMultiDraw) {
            for (int i = 0; i < this.indexedBatchSize; i++) {
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, this.indexedCountBuffer.get(i),
                        GL11.GL_UNSIGNED_INT, this.indexedOffsetBuffer.get(i), 0);
            }
            this.glDraws += this.indexedBatchSize;
            this.nsTerrainDraw += System.nanoTime() - drawAt;
            return;
        }
        if (this.indexedBatchSize > 0) {
            // The element buffer is part of this vertex array's state -- it was bound while the array
            // was, in buildIndexedLayer -- so it comes with the bind above and is not touched per frame.
            this.indexedCountBuffer.limit(this.indexedBatchSize);
            this.indexedOffsetBuffer.limit(this.indexedBatchSize);
            GL14.glMultiDrawElements(GL11.GL_TRIANGLES, this.indexedCountBuffer,
                    GL11.GL_UNSIGNED_INT, this.indexedOffsetBuffer);
            this.glMultiDraws++;
            this.glDraws++;
        }
        if (this.batchSize > 0) {
            this.firstBuffer.limit(this.batchSize);
            this.countBuffer.limit(this.batchSize);
            GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, this.firstBuffer, this.countBuffer);
            this.glMultiDraws++;
            this.glDraws++;
        }
        this.nsTerrainDraw += System.nanoTime() - drawAt;
    }

    @Override
    void drawMeshWith(Mesh mesh, int mode, Texture[] textures, float[] modelView) {
        if (mesh.triangleCount == 0) {
            return;
        }
        if (this.batchLayer >= 0 && !this.debugNoSharedBuffer) {
            int[] range = this.terrainIndexedRange.get(mesh);
            if (range != null && range[0] == this.batchLayer) {
                if (this.indexedBatchSize == this.indexedCountBuffer.capacity()) {
                    throw new IllegalStateException("indexed terrain batch overflow");
                }
                this.indexedCountBuffer.put(this.indexedBatchSize, range[2]);
                this.indexedOffsetBuffer.put(this.indexedBatchSize, (long) range[1] * 4L);
                this.indexedBatchSize++;
                this.glRanges++;
                this.glTriangles += range[2] / 3;
                return;
            }
            // A mesh the indexed bake never saw still draws, from the quad array. Both copies are built
            // from the same pass over the same sections so this should not happen, but a mesh arriving
            // here would otherwise fall all the way through to a per-mesh buffer upload every frame.
            int[] plain = this.terrainRange.get(mesh);
            if (plain != null && plain[0] == this.batchLayer) {
                if (this.batchSize == this.firstBuffer.capacity()) {
                    throw new IllegalStateException("terrain batch overflow");
                }
                this.firstBuffer.put(this.batchSize, plain[1]);
                this.countBuffer.put(this.batchSize, plain[2]);
                this.batchSize++;
                this.glRanges++;
                this.glTriangles += plain[2] / 3;
                return;
            }
        }
        int[] resident = resident(mesh);
        applyState(mode);
        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.uniformMvp, false, composeToBuffer(modelView));
        GL20.glUniform1f(this.uniformCut, mode == CUTOUT ? 0.5f : 0.0f);
        GL30.glBindVertexArray(resident[1]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, resident[2]);
        this.glDraws++;
        this.glTriangles += resident[2] / 3;
    }

    @Override
    void drawFaceMeshes(Mesh[] faces, int mode, Texture[] textures) {
        int[] range = this.faceRange.get(faces);
        if (range == null || this.batchLayer < 0 || this.debugNoSharedBuffer) {
            super.drawFaceMeshes(faces, mode, textures);
            return;
        }
        if (range[2] == 0) {
            return;
        }
        this.firstBuffer.put(this.batchSize, range[1]);
        this.countBuffer.put(this.batchSize, range[2]);
        this.batchSize++;
        this.glRanges++;
        this.glTriangles += range[2] / 3;
    }

    /** The buffer and vertex array a mesh lives in, uploaded the first time it is drawn. */
    /**
     * Draws per-frame geometry through one buffer that is re-specified rather than reallocated.
     *
     * <p>Batching the particles into one mesh removed twenty-three draw calls and added a vertex buffer
     * object a frame, because the resident cache is keyed on the mesh and a batch is a new mesh every
     * time. This is the path the screen already uses: one buffer, GL_STREAM_DRAW, and the driver is told
     * the contents are about to be replaced.
     */
    @Override
    void drawDynamicMesh(Mesh mesh, int mode, Texture[] textures, float[] modelView) {
        if (mesh.triangleCount == 0) {
            return;
        }
        int vertices = mesh.vertexCount();
        float[] data = new float[vertices * FLOATS_PER_VERTEX];
        append(data, 0, mesh, null);
        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
        buffer.put(data);
        buffer.flip();
        applyState(mode);
        GL20.glUseProgram(this.program);
        GL20.glUniformMatrix4fv(this.uniformMvp, false, composeToBuffer(modelView));
        GL20.glUniform1f(this.uniformCut, mode == CUTOUT ? 0.5f : 0.0f);
        GL30.glBindVertexArray(this.dynamicVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.dynamicVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        bindAttributes(this.program);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertices);
        MemoryUtil.memFree(buffer);
        GL30.glBindVertexArray(0);
        this.glDraws++;
        this.glUploads++;
        this.glTriangles += vertices / 3;
    }

    private int[] resident(Mesh mesh) {
        int[] cached = this.meshCache.get(mesh);
        if (cached != null) {
            return cached;
        }
        int vertices = mesh.vertexCount();
        float[] data = new float[vertices * FLOATS_PER_VERTEX];
        append(data, 0, mesh, null);
        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
        buffer.put(data);
        buffer.flip();
        int vbo = GL15.glGenBuffers();
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        bindAttributes(this.program);
        MemoryUtil.memFree(buffer);
        GL30.glBindVertexArray(0);
        this.glUploads++;
        int[] entry = new int[] {vbo, vao, vertices};
        this.meshCache.put(mesh, entry);
        return entry;
    }

    // ---- the GUI --------------------------------------------------------------------------------

    @Override
    void drawScreenQuad(Texture texture, float u0, float v0, float u1, float v1,
            int x0, int y0, int x1, int y1, float brightness) {
        int index = indexOf(texture);
        if (index < 0) {
            return;
        }
        quad(x0, y0, x1, y1, index, u0, v0, u1, v1, brightness);
    }

    @Override
    void drawScreenBatch(float[] quads, int quadCount, Texture texture, float brightness) {
        int index = indexOf(texture);
        if (index < 0) {
            return;
        }
        for (int q = 0; q < quadCount; q++) {
            int b = q * 8;
            quad((int) quads[b], (int) quads[b + 1], (int) quads[b + 2], (int) quads[b + 3],
                    index, quads[b + 4], quads[b + 5], quads[b + 6], quads[b + 7], brightness);
        }
    }

    /**
     * Texture identity to its table index. The atlas holds every texture, so this is a lookup and not a
     * bind -- which is the whole reason the atlas exists: one bind for the frame instead of one per draw.
     */
    private int indexOf(Texture texture) {
        Texture[] table = this.textureTable;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == texture) {
                return i;
            }
        }
        return -1;
    }

    /** Appends one screen quad as two triangles: x, y, u, v, shade per vertex. */
    private void quad(int x0, int y0, int x1, int y1, int index,
            float u0, float v0, float u1, float v1, float brightness) {
        int needed = this.screenFloats + 30;
        if (needed > this.screenData.length) {
            this.screenData = java.util.Arrays.copyOf(this.screenData, Math.max(needed,
                    this.screenData.length * 2));
        }
        float au = this.tileU[index] + u0 * this.tileSpanU[index];
        float av = this.tileV[index] + v0 * this.tileSpanV[index];
        float bu = this.tileU[index] + u1 * this.tileSpanU[index];
        float bv = this.tileV[index] + v1 * this.tileSpanV[index];
        float[] out = this.screenData;
        int at = this.screenFloats;
        at = screenVertex(out, at, x0, y0, au, av, brightness);
        at = screenVertex(out, at, x1, y0, bu, av, brightness);
        at = screenVertex(out, at, x1, y1, bu, bv, brightness);
        at = screenVertex(out, at, x0, y0, au, av, brightness);
        at = screenVertex(out, at, x1, y1, bu, bv, brightness);
        at = screenVertex(out, at, x0, y1, au, bv, brightness);
        this.screenFloats = at;
    }

    private static int screenVertex(float[] out, int at, int x, int y, float u, float v, float shade) {
        out[at] = x;
        out[at + 1] = y;
        out[at + 2] = u;
        out[at + 3] = v;
        out[at + 4] = shade;
        return at + 5;
    }

    // ---- frame ----------------------------------------------------------------------------------

    @Override
    void clear(int sky) {
        int r = (sky >> 16) & 0xFF;
        int g = (sky >> 8) & 0xFF;
        int b = sky & 0xFF;
        GL11.glClearColor(r / 255.0f, g / 255.0f, b / 255.0f, 1.0f);
        // Re-asserted every frame rather than trusted from the constructor. Reversed Z clears to zero
        // and compares with GL_GREATER, and the pair only works together: the default clear value is 1.0
        // and the default comparison is GL_LESS, so either half reverting on its own makes every fragment
        // in the world fail the test while the GUI -- which has the depth test off -- keeps drawing.
        // That is exactly the picture this backend produced for a dozen runs.
        GL11.glClearDepth(1.0);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glViewport(0, 0, this.width, this.height);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        clearDepthByQuad();
        this.probeCaptured = false;
        // Enabled after the clears, which honour the scissor themselves and would otherwise leave the
        // depth buffer holding the previous frame.
        if (this.debugScissorTo1x1) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 1, 1);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        // And again, explicitly. glClear with the depth bit was not clearing anything: the comparison
        // itself works -- GL_ALWAYS paints the whole scene and GL_NEVER paints only the GUI -- but
        // GL_LESS against a buffer cleared to 1.0 rejected every fragment in the world, and so did
        // GL_GREATER against the same buffer cleared to 0.0. Only one buffer content is consistent with
        // both, and it is the 0.6164 that a read of it kept returning and that I twice wrote off as
        // uninitialised memory because clearing to 0.25 did not change it. It was the truth: the buffer
        // holds what the last frame left in it, and every frame is depth tested against the one before.
        this.depthClear.put(0, 1.0f);
        // GL_DEPTH here is 0x1801, the old glDrawBuffer enum that glClearBufferfv reuses -- not 0x8200,
        // which is a framebuffer attachment enum and produces GL_INVALID_ENUM and nothing else. Getting
        // that wrong is silent, which is the whole reason the error is read straight back.
        GL30.glClearBufferfv(GL11.GL_DEPTH, 0, this.depthClear);
        this.depthClearError = GL11.glGetError();
    }

    /**
     * What the driver holds, and what is in the depth buffer after a clear.
     *
     * <p>The last four runs were spent on a depth test that rejected everything while the driver reported
     * the exact clear value and comparison it had been handed. Reporting the buffer itself is the part
     * that was missing: a clear value that is right and a buffer that is not cleared to it are different
     * faults with the same symptoms.
     */
    String debugDepthState() {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glReadPixels(this.width / 2, this.height / 2, 1, 1,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
        float atOne = pixel.getFloat(0);
        // Cleared again to a different value. If the read follows the clear value, the read is real and
        // the buffer really is being cleared, and the depth test is failing for some other reason. If it
        // does not follow, the read is reporting uninitialised memory and says nothing at all -- which is
        // what two different scenes reporting the same 0.6164 already suggested.
        GL11.glClearDepth(0.25);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glReadPixels(this.width / 2, this.height / 2, 1, 1,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
        float atQuarter = pixel.getFloat(0);
        int readError = GL11.glGetError();
        MemoryUtil.memFree(pixel);
        GL11.glClearDepth(1.0);
        // GL_FLOAT against a 24 bit depth buffer is not a legal glReadPixels pair, so the two reads above
        // may be reporting an invalid operation and nothing else. The error is read after them, and the
        // centre depth after the far plane quad says whether a depth write is visible at all.
        return String.format(java.util.Locale.ROOT,
                "depthFunc=0x%x readAt1=%.4f readAt0.25=%.4f readErr=0x%x quadDepth=%.4f clearBufErr=0x%x",
                GL11.glGetInteger(GL11.GL_DEPTH_FUNC), atOne, atQuarter, readError, centreDepth(),
                this.depthClearError);
    }

    /** Submits the GUI quads collected during the frame as one draw. */
    @Override
    void endFrame() {
        if (this.screenFloats == 0) {
            finishFrame();
            return;
        }
        FloatBuffer buffer = MemoryUtil.memAllocFloat(this.screenFloats);
        buffer.put(this.screenData, 0, this.screenFloats);
        buffer.flip();
        applyState(STATE_SCREEN);
        GL20.glUseProgram(this.screenProgram);
        GL20.glUniform2f(this.uniformToClip, 2.0f / this.width, 2.0f / this.height);
        GL20.glUniform1f(this.uniformScreenCut, 0.0f);
        GL30.glBindVertexArray(this.screenVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.screenVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.screenFloats / 5);
        MemoryUtil.memFree(buffer);
        this.screenFloats = 0;
        this.glDraws++;
        finishFrame();
    }

    /**
     * Waits for the frame to actually finish. GL is asynchronous, and a stopwatch around a queue of
     * commands nobody has executed yet measures how fast the queue fills rather than how fast it drains.
     */
    private static void finishFrame() {
        GL11.glFinish();
    }

    private FloatBuffer composeToBuffer(float[] modelView) {
        compose(modelView);
        return this.matrixBuffer;
    }

    /**
     * Sets the pipeline state, and only touches GL when it actually changes.
     *
     * <p>Redundant state changes are not free in a driver, and a renderer that issued them on every draw
     * would be measuring its own sloppiness rather than the pipelines.
     */
    /** Forgets the cached pipeline state, so a change to {@link #debugNoCull} actually reaches GL. */
    void invalidateState() {
        this.currentState = -1;
    }

    /** The location the model-view-projection matrix was found at, or -1 if the driver did not expose it. */
    int mvpLocation() {
        return this.uniformMvp;
    }

    private void applyState(int state) {
        if (state == this.currentState) {
            return;
        }
        this.currentState = state;
        switch (state) {
            case OPAQUE:
            case CUTOUT:
                if (this.debugNoDepth) {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                } else {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    if (this.debugDepthFunc != 0) {
                        GL11.glDepthFunc(this.debugDepthFunc);
                    }
                }
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_BLEND);
                if (this.debugNoCull) {
                    GL11.glDisable(GL11.GL_CULL_FACE);
                } else {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                }
                break;
            case BLEND:
                if (this.debugNoDepth) {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                } else {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    if (this.debugDepthFunc != 0) {
                        GL11.glDepthFunc(this.debugDepthFunc);
                    }
                }
                GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                break;
            default:
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                break;
        }
    }

    /**
     * Pushes a world-space point through the matrix the driver was last handed, and says what comes out.
     *
     * <p>The software rasteriser and this one are supposed to be running the same projection, and this is
     * how that is checked rather than assumed: a point that leaves the clip cube is a triangle no driver
     * will ever rasterise, and it looks exactly like a triangle that was never submitted.
     */
    String debugClip(float x, float y, float z) {
        float[] m = this.mvp;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (cw == 0.0f) {
            return "w=0";
        }
        return String.format(java.util.Locale.ROOT, "clip=%.1f,%.1f,%.1f,%.1f ndc=%.2f,%.2f,%.2f",
                cx, cy, cz, cw, cx / cw, cy / cw, cz / cw);
    }

    /**
     * The first vertex of the first range the opaque layer drew this frame, in world space.
     *
     * <p>It used to be the first vertex baked into the buffer, which belongs to whichever section was
     * baked first and has no reason to be in front of the camera. It was behind it: the clip w it
     * produced was -87.0, so the perspective divide mirrored it to an ndc that looked perfectly
     * reasonable, and four runs were spent reading a matrix that was never wrong.
     */
    float[] probeVertex() {
        return this.probeVertex;
    }

    /** The model-view-projection matrix the driver was last handed, for the same check. */
    String debugMatrix() {
        float[] m = this.mvp;
        return String.format(java.util.Locale.ROOT, "m0=%.3f m5=%.3f m10=%.3f m12=%.1f m13=%.1f m14=%.1f",
                m[0], m[5], m[10], m[12], m[13], m[14]);
    }

    /** Painted pixels of the current framebuffer, against the sky the frame was cleared to. */
    int paintedNow() {
        ByteBuffer image = MemoryUtil.memAlloc(this.width * this.height * 4);
        readBack(image);
        int count = painted(image, this.width, this.height, 0x78A7FF);
        MemoryUtil.memFree(image);
        return count;
    }

    /**
     * Writes the far plane into every depth sample by drawing it, because this backend's depth buffer
     * will not take a clear. The comparison is forced to always pass so what is already there cannot
     * reject the write, colour writes are masked off so only depth changes, and the quad covers the
     * whole viewport at clip z equal to clip w, which is the far plane.
     */
    private void clearDepthByQuad() {
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL20.glUseProgram(this.depthClearProgram);
        GL30.glBindVertexArray(this.depthClearVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL30.glBindVertexArray(0);
    }

    /** The depth at the centre, so the parent test can say whether the far plane write landed. */
    float centreDepth() {
        // Allocated with MemoryUtil like every other buffer here rather than with a MemoryStack, which
        // this class does not import.
        FloatBuffer depth = MemoryUtil.memAllocFloat(1);
        GL11.glReadPixels(this.width / 2, this.height / 2, 1, 1, GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT, depth);
        float value = depth.get(0);
        MemoryUtil.memFree(depth);
        return value;
    }

    /** Reads the framebuffer back, which is how the report proves a picture was drawn and not skipped. */
    void readBack(ByteBuffer target) {
        GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, target);
    }

    /** Pixels that are not the clear colour, so a blank frame cannot be reported as a fast one. */
    static int painted(ByteBuffer rgba, int width, int height, int clearRgb) {
        int count = 0;
        for (int i = 0; i < width * height; i++) {
            int r = rgba.get(i * 4) & 0xFF;
            int g = rgba.get(i * 4 + 1) & 0xFF;
            int b = rgba.get(i * 4 + 2) & 0xFF;
            int rgb = (r << 16) | (g << 8) | b;
            if (rgb != clearRgb) {
                count++;
            }
        }
        return count;
    }

    /** How many pixels differ between two readbacks, which is the correctness guard for this backend. */
    static double differencePercent(ByteBuffer a, ByteBuffer b, int width, int height) {
        int different = 0;
        for (int i = 0; i < width * height; i++) {
            int offset = i * 4;
            if (a.get(offset) != b.get(offset)
                    || a.get(offset + 1) != b.get(offset + 1)
                    || a.get(offset + 2) != b.get(offset + 2)) {
                different++;
            }
        }
        return 100.0 * different / (width * height);
    }

    /** Zeroes the submission counters, so the two pipelines are not reported cumulatively. */
    void resetGlCounters() {
        this.glDraws = 0;
        this.glMultiDraws = 0;
        this.glRanges = 0;
        this.glUploads = 0;
        this.glTriangles = 0;
        this.nsTerrainSetup = 0;
        this.nsTerrainDraw = 0;
    }

    void close() {
        MemoryUtil.memFree(this.firstBuffer);
        MemoryUtil.memFree(this.countBuffer);
        MemoryUtil.memFree(this.matrixBuffer);
    }
}
