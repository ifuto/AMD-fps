package net.amdfaster.bench;

/**
 * A triangle soup: the baked output of the mesher, and the unit the cullers decide about.
 *
 * <p>Vertices carry position in world space plus texture coordinates, five floats each, and triangles
 * carry a texture id and a shade. Position is world space rather than section-local because a software
 * rasteriser has no model matrix to hide behind, and because it makes the AABB the cullers test be the
 * AABB the geometry actually occupies.
 *
 * <p>Triangle soup rather than an index buffer: the mesher emits quads as two triangles and nothing
 * downstream shares a vertex, so an index buffer would be a second array to keep in step for no saving
 * in what this benchmark measures.
 */
final class Mesh {

    /** Five floats per vertex: x, y, z, u, v. */
    static final int FLOATS_PER_VERTEX = 5;

    /** The face direction constants, matching {@code MeshletFaceMaskCuller}. */
    static final int DOWN = 1;
    static final int UP = 2;
    static final int NORTH = 4;
    static final int SOUTH = 8;
    static final int WEST = 16;
    static final int EAST = 32;

    float[] verts = new float[0];
    int[] triTex = new int[0];
    float[] triShade = new float[0];
    int triangleCount;

    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float minZ = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float maxZ = Float.NEGATIVE_INFINITY;

    boolean isEmpty() {
        return this.triangleCount == 0;
    }

    int vertexCount() {
        return this.triangleCount * 3;
    }

    boolean intersectsAabb(float oMinX, float oMinY, float oMinZ,
            float oMaxX, float oMaxY, float oMaxZ) {
        return this.minX <= oMaxX && this.maxX >= oMinX
                && this.minY <= oMaxY && this.maxY >= oMinY
                && this.minZ <= oMaxZ && this.maxZ >= oMinZ;
    }
}

/**
 * Accumulates triangles into a {@link Mesh}, growing as it goes and trimming at the end.
 *
 * <p>One builder per mesh. Not thread safe, which is fine because meshing a section is one thread's
 * job and the benchmark meshes on the calling thread the way a synchronous rebuild would.
 */
final class MeshBuilder {

    private static final int INITIAL_TRIANGLES = 64;

    private float[] verts = new float[INITIAL_TRIANGLES * 3 * Mesh.FLOATS_PER_VERTEX];
    private int[] triTex = new int[INITIAL_TRIANGLES];
    private float[] triShade = new float[INITIAL_TRIANGLES];
    private int triangles;

    private float minX = Float.POSITIVE_INFINITY;
    private float minY = Float.POSITIVE_INFINITY;
    private float minZ = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY;
    private float maxY = Float.NEGATIVE_INFINITY;
    private float maxZ = Float.NEGATIVE_INFINITY;

    int triangles() {
        return this.triangles;
    }

    /**
     * Empties the builder without giving its arrays back.
     *
     * <p>Particle, rain and shadow batches are rebuilt from nothing every frame -- the particles move
     * and the rain falls -- so the frame was allocating three builders, growing each past its initial
     * sixty-four triangles, and throwing all of it away at the end. The geometry is genuinely new each
     * time; the storage is not.
     */
    void reset() {
        this.triangles = 0;
        this.minX = Float.POSITIVE_INFINITY;
        this.minY = Float.POSITIVE_INFINITY;
        this.minZ = Float.POSITIVE_INFINITY;
        this.maxX = Float.NEGATIVE_INFINITY;
        this.maxY = Float.NEGATIVE_INFINITY;
        this.maxZ = Float.NEGATIVE_INFINITY;
    }

    /**
     * Appends a quad as two triangles.
     *
     * @param texture the index into the texture table
     * @param shade   the face brightness Minecraft bakes into a quad's vertices
     */
    void quad(float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            int texture, float shade) {
        triangle(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x3, y3, z3, u3, v3, texture, shade);
        triangle(x0, y0, z0, u0, v0, x3, y3, z3, u3, v3, x2, y2, z2, u2, v2, texture, shade);
    }

    void triangle(float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            int texture, float shade) {
        if (this.triangles == this.triTex.length) {
            grow();
        }
        int base = this.triangles * 3 * Mesh.FLOATS_PER_VERTEX;
        float[] v = this.verts;
        v[base] = x0;
        v[base + 1] = y0;
        v[base + 2] = z0;
        v[base + 3] = u0;
        v[base + 4] = v0;
        v[base + 5] = x1;
        v[base + 6] = y1;
        v[base + 7] = z1;
        v[base + 8] = u1;
        v[base + 9] = v1;
        v[base + 10] = x2;
        v[base + 11] = y2;
        v[base + 12] = z2;
        v[base + 13] = u2;
        v[base + 14] = v2;
        this.triTex[this.triangles] = texture;
        this.triShade[this.triangles] = shade;
        this.triangles++;

        if (x0 < this.minX) {
            this.minX = x0;
        }
        if (x1 < this.minX) {
            this.minX = x1;
        }
        if (x2 < this.minX) {
            this.minX = x2;
        }
        if (x0 > this.maxX) {
            this.maxX = x0;
        }
        if (x1 > this.maxX) {
            this.maxX = x1;
        }
        if (x2 > this.maxX) {
            this.maxX = x2;
        }
        if (y0 < this.minY) {
            this.minY = y0;
        }
        if (y1 < this.minY) {
            this.minY = y1;
        }
        if (y2 < this.minY) {
            this.minY = y2;
        }
        if (y0 > this.maxY) {
            this.maxY = y0;
        }
        if (y1 > this.maxY) {
            this.maxY = y1;
        }
        if (y2 > this.maxY) {
            this.maxY = y2;
        }
        if (z0 < this.minZ) {
            this.minZ = z0;
        }
        if (z1 < this.minZ) {
            this.minZ = z1;
        }
        if (z2 < this.minZ) {
            this.minZ = z2;
        }
        if (z0 > this.maxZ) {
            this.maxZ = z0;
        }
        if (z1 > this.maxZ) {
            this.maxZ = z1;
        }
        if (z2 > this.maxZ) {
            this.maxZ = z2;
        }
    }

    private void grow() {
        int next = this.triTex.length * 2;
        float[] newVerts = new float[next * 3 * Mesh.FLOATS_PER_VERTEX];
        System.arraycopy(this.verts, 0, newVerts, 0, this.triangles * 3 * Mesh.FLOATS_PER_VERTEX);
        this.verts = newVerts;
        int[] newTex = new int[next];
        System.arraycopy(this.triTex, 0, newTex, 0, this.triangles);
        this.triTex = newTex;
        float[] newShade = new float[next];
        System.arraycopy(this.triShade, 0, newShade, 0, this.triangles);
        this.triShade = newShade;
    }

    Mesh build() {
        Mesh mesh = new Mesh();
        if (this.triangles == 0) {
            return mesh;
        }
        int used = this.triangles * 3 * Mesh.FLOATS_PER_VERTEX;
        mesh.verts = new float[used];
        System.arraycopy(this.verts, 0, mesh.verts, 0, used);
        mesh.triTex = new int[this.triangles];
        System.arraycopy(this.triTex, 0, mesh.triTex, 0, this.triangles);
        mesh.triShade = new float[this.triangles];
        System.arraycopy(this.triShade, 0, mesh.triShade, 0, this.triangles);
        mesh.triangleCount = this.triangles;
        mesh.minX = this.minX;
        mesh.minY = this.minY;
        mesh.minZ = this.minZ;
        mesh.maxX = this.maxX;
        mesh.maxY = this.maxY;
        mesh.maxZ = this.maxZ;
        return mesh;
    }
}
