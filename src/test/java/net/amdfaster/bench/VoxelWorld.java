package net.amdfaster.bench;

/**
 * A voxel world, stored as one flat byte array.
 *
 * <p>Sections are 16 blocks on an edge, which is Minecraft's own granularity and the unit every culler
 * in this project decides about. Coordinates are world coordinates; the origin is a corner, so a
 * section index is a coordinate shifted right by four.
 */
final class VoxelWorld {

    static final int SECTION = 16;

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    private final byte[] blocks;

    VoxelWorld(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("a world needs a positive size");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new byte[sizeX * sizeY * sizeZ];
    }

    int sectionsX() {
        return this.sizeX / SECTION;
    }

    int sectionsY() {
        return this.sizeY / SECTION;
    }

    int sectionsZ() {
        return this.sizeZ / SECTION;
    }

    boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
    }

    int get(int x, int y, int z) {
        if (!inside(x, y, z)) {
            // Outside the world is air rather than a wall: a benchmark that ended the world in bedrock
            // would cull every section past the edge and report a speedup that came from having no world.
            return Assets.AIR;
        }
        return this.blocks[(y * this.sizeZ + z) * this.sizeX + x] & 0xFF;
    }

    void set(int x, int y, int z, int id) {
        if (inside(x, y, z)) {
            this.blocks[(y * this.sizeZ + z) * this.sizeX + x] = (byte) id;
        }
    }

    /** Whether this block's presence hides the face of a neighbour touching it. */
    boolean hidesFace(int neighbour, int self, Assets assets) {
        if (neighbour == Assets.AIR) {
            return false;
        }
        if (!assets.blocks[neighbour].solid) {
            // Two leaves or two panes of glass touching still show a face between them in the transparent
            // passes, but the same block against itself is interior and is not drawn.
            return neighbour == self;
        }
        return true;
    }

    int solidBlocks() {
        int count = 0;
        for (byte block : this.blocks) {
            if (block != Assets.AIR) {
                count++;
            }
        }
        return count;
    }
}

/**
 * The baked geometry of one section: six directional opaque meshes, plus the cutout and translucent
 * passes that cannot be culled by section alone.
 *
 * <p>Six directional meshes rather than one is the point. A camera looking north cannot see a single
 * north-facing triangle anywhere in the world, and keeping those triangles in the same buffer as the
 * ones it can see means the decision has to be made per triangle after the vertices are transformed.
 * Splitting them makes the decision once, per section, before anything is touched.
 */
final class SectionMeshes {

    final int sectionX;
    final int sectionY;
    final int sectionZ;

    /** Indexed by {@link Assets#DOWN} and friends. */
    final Mesh[] opaque = new Mesh[6];
    Mesh cutout = new Mesh();
    Mesh blend = new Mesh();

    float minX;
    float minY;
    float minZ;
    float maxX;
    float maxY;
    float maxZ;

    /**
     * Whether every block in the section is opaque, which makes the section's bounding box an exact
     * description of its geometry.
     *
     * <p>That matters for occlusion culling. A box standing in for a section is only a safe occluder when
     * the section really is a box; using the bounding box of a section with a doorway in it would hide
     * whatever is visible through the doorway, which is a hole in the world that follows the player
     * around. A full section has no such gap, its box is its shape, and it is also exactly the section
     * that hides a cave.
     */
    boolean solidBox;

    SectionMeshes(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        for (int i = 0; i < 6; i++) {
            this.opaque[i] = new Mesh();
        }
        this.minX = sectionX * VoxelWorld.SECTION;
        this.minY = sectionY * VoxelWorld.SECTION;
        this.minZ = sectionZ * VoxelWorld.SECTION;
        this.maxX = this.minX + VoxelWorld.SECTION;
        this.maxY = this.minY + VoxelWorld.SECTION;
        this.maxZ = this.minZ + VoxelWorld.SECTION;
    }

    int triangles() {
        int total = this.cutout.triangleCount + this.blend.triangleCount;
        for (Mesh mesh : this.opaque) {
            total += mesh.triangleCount;
        }
        return total;
    }

    boolean isEmpty() {
        return triangles() == 0;
    }
}

/**
 * Turns voxels into triangles: the mesh build, which is where Sodium earns most of its keep.
 *
 * <p>A face is emitted only when the neighbour on that side does not hide it. That single rule is the
 * difference between 36 triangles per block and about two, and it is why a solid cube of stone costs
 * almost nothing to draw -- which in turn is why the wins this project is after have to come from
 * somewhere other than "mesh the chunks better".
 */
final class SectionMesher {

    private SectionMesher() {
    }

    static SectionMeshes build(VoxelWorld world, int sx, int sy, int sz, Assets assets) {
        SectionMeshes section = new SectionMeshes(sx, sy, sz);
        MeshBuilder[] opaque = new MeshBuilder[6];
        for (int i = 0; i < 6; i++) {
            opaque[i] = new MeshBuilder();
        }
        MeshBuilder cutout = new MeshBuilder();
        MeshBuilder blend = new MeshBuilder();

        int x0 = sx * VoxelWorld.SECTION;
        int y0 = sy * VoxelWorld.SECTION;
        int z0 = sz * VoxelWorld.SECTION;
        boolean solidBox = true;

        for (int y = y0; y < y0 + VoxelWorld.SECTION; y++) {
            for (int z = z0; z < z0 + VoxelWorld.SECTION; z++) {
                for (int x = x0; x < x0 + VoxelWorld.SECTION; x++) {
                    int id = world.get(x, y, z);
                    if (id == Assets.AIR) {
                        solidBox = false;
                        continue;
                    }
                    if (!assets.blocks[id].solid) {
                        solidBox = false;
                    }
                    Assets.BlockGeometry geometry = assets.blocks[id];
                    float bx0 = x + geometry.fromX / 16.0f;
                    float by0 = y + geometry.fromY / 16.0f;
                    float bz0 = z + geometry.fromZ / 16.0f;
                    float bx1 = x + geometry.toX / 16.0f;
                    float by1 = y + geometry.toY / 16.0f;
                    float bz1 = z + geometry.toZ / 16.0f;

                    for (int face = 0; face < 6; face++) {
                        int neighbour = world.get(x + OFFSET_X[face], y + OFFSET_Y[face], z + OFFSET_Z[face]);
                        if (world.hidesFace(neighbour, id, assets)) {
                            continue;
                        }
                        MeshBuilder target;
                        if (geometry.mode == SoftRenderer.OPAQUE) {
                            if (mergeable(geometry, geometry.faces[face])) {
                                // Left to the greedy pass below, which draws the whole run as one quad.
                                continue;
                            }
                            target = opaque[face];
                        } else if (geometry.mode == SoftRenderer.CUTOUT) {
                            target = cutout;
                        } else {
                            target = blend;
                        }
                        emit(target, face, geometry.faces[face], bx0, by0, bz0, bx1, by1, bz1);
                    }
                }
            }
        }

        greedyFaces(world, assets, opaque, sx, sy, sz);

        for (int i = 0; i < 6; i++) {
            section.opaque[i] = opaque[i].build();
        }
        section.cutout = cutout.build();
        section.blend = blend.build();
        section.solidBox = solidBox;
        return section;
    }

    /**
     * Merges every run of coplanar, identically textured opaque faces into one quad, which is what
     * Sodium's mesher does and what makes a flat plain cost two triangles instead of five hundred and
     * twelve.
     *
     * <p>Per face direction and per slice along the normal, the exposed faces of the section become a
     * 16x16 mask keyed by block, and rectangles are taken off it greedily. Only full cubes whose sprite
     * covers the whole texture are eligible: anything else keeps its own quad, because a merged quad
     * repeats its sprite and a partial one would repeat the wrong pixels. Both rasterisers already wrap
     * at the sprite edge -- the software one masks the texel index, the GL one samples a cell that holds
     * the texture tiled sixteen times -- so a merged quad is drawn identically to the faces it replaces.
     */
    private static void greedyFaces(VoxelWorld world, Assets assets, MeshBuilder[] opaque,
            int sx, int sy, int sz) {
        int x0 = sx * VoxelWorld.SECTION;
        int y0 = sy * VoxelWorld.SECTION;
        int z0 = sz * VoxelWorld.SECTION;
        int side = VoxelWorld.SECTION;
        int[] mask = new int[side * side];
        for (int face = 0; face < 6; face++) {
            for (int a = 0; a < side; a++) {
                java.util.Arrays.fill(mask, 0);
                for (int v = 0; v < side; v++) {
                    for (int u = 0; u < side; u++) {
                        int x = face < 2 ? x0 + u : face < 4 ? x0 + u : x0 + a;
                        int y = face < 2 ? y0 + a : y0 + v;
                        int z = face < 2 ? z0 + v : face < 4 ? z0 + a : z0 + u;
                        int id = world.get(x, y, z);
                        if (id == Assets.AIR) {
                            continue;
                        }
                        Assets.BlockGeometry geometry = assets.blocks[id];
                        if (!mergeable(geometry, geometry.faces[face])) {
                            continue;
                        }
                        int neighbour = world.get(x + OFFSET_X[face], y + OFFSET_Y[face], z + OFFSET_Z[face]);
                        if (world.hidesFace(neighbour, id, assets)) {
                            continue;
                        }
                        // One past the block id, so that zero keeps meaning empty.
                        mask[v * side + u] = id + 1;
                    }
                }
                for (int v = 0; v < side; v++) {
                    for (int u = 0; u < side; u++) {
                        int key = mask[v * side + u];
                        if (key == 0) {
                            continue;
                        }
                        int w = 1;
                        while (u + w < side && mask[v * side + u + w] == key) {
                            w++;
                        }
                        int h = 1;
                        while (v + h < side) {
                            boolean whole = true;
                            for (int k = 0; k < w; k++) {
                                if (mask[(v + h) * side + u + k] != key) {
                                    whole = false;
                                    break;
                                }
                            }
                            if (!whole) {
                                break;
                            }
                            h++;
                        }
                        for (int dv = 0; dv < h; dv++) {
                            for (int du = 0; du < w; du++) {
                                mask[(v + dv) * side + u + du] = 0;
                            }
                        }
                        emitMerged(opaque[face], face, assets.blocks[key - 1].faces[face],
                                x0, y0, z0, a, u, v, w, h);
                    }
                }
            }
        }
    }

    /** A full cube whose sprite is the whole texture, so the merged quad can repeat it safely. */
    private static boolean mergeable(Assets.BlockGeometry geometry, Assets.FaceDef def) {
        return geometry.mode == SoftRenderer.OPAQUE
                && geometry.fromX == 0.0f && geometry.fromY == 0.0f && geometry.fromZ == 0.0f
                && geometry.toX == 16.0f && geometry.toY == 16.0f && geometry.toZ == 16.0f
                && def.u0 == 0.0f && def.v0 == 0.0f && def.u1 == 1.0f && def.v1 == 1.0f;
    }

    /** One quad over a rectangle of the slice, with the sprite repeated across it. */
    private static void emitMerged(MeshBuilder target, int face, Assets.FaceDef def,
            int x0, int y0, int z0, int a, int u, int v, int w, int h) {
        if (face < 2) {
            emit(target, face, def, x0 + u, y0 + a, z0 + v, x0 + u + w, y0 + a + 1, z0 + v + h, w, h);
        } else if (face < 4) {
            emit(target, face, def, x0 + u, y0 + v, z0 + a, x0 + u + w, y0 + v + h, z0 + a + 1, w, h);
        } else {
            emit(target, face, def, x0 + a, y0 + v, z0 + u, x0 + a + 1, y0 + v + h, z0 + u + w, w, h);
        }
    }

    private static final int[] OFFSET_X = {0, 0, 0, 0, -1, 1};
    private static final int[] OFFSET_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] OFFSET_Z = {0, 0, -1, 1, 0, 0};

    private static void emit(MeshBuilder target, int face, Assets.FaceDef def,
            float x0, float y0, float z0, float x1, float y1, float z1) {
        emit(target, face, def, x0, y0, z0, x1, y1, z1, 1.0f, 1.0f);
    }

    private static void emit(MeshBuilder target, int face, Assets.FaceDef def,
            float x0, float y0, float z0, float x1, float y1, float z1, float repeatU, float repeatV) {
        float shade = Assets.FACE_SHADE[face];
        // Bottom-left, bottom-right, top-left, top-right, with v running down the texture the way the
        // PNG rows do. A merged quad runs its coordinates past the sprite and both rasterisers wrap, so
        // repeating is just a wider span.
        float uLo = def.u0;
        float uHi = def.u0 + repeatU * (def.u1 - def.u0);
        float vLo = def.v0;
        float vHi = def.v0 + repeatV * (def.v1 - def.v0);
        switch (face) {
            case Assets.DOWN:
                target.quad(x0, y0, z1, uLo, vHi, x1, y0, z1, uHi, vHi,
                        x0, y0, z0, uLo, vLo, x1, y0, z0, uHi, vLo, def.texture, shade);
                break;
            case Assets.UP:
                target.quad(x0, y1, z0, uLo, vHi, x1, y1, z0, uHi, vHi,
                        x0, y1, z1, uLo, vLo, x1, y1, z1, uHi, vLo, def.texture, shade);
                break;
            case Assets.NORTH:
                target.quad(x1, y0, z0, uLo, vHi, x0, y0, z0, uHi, vHi,
                        x1, y1, z0, uLo, vLo, x0, y1, z0, uHi, vLo, def.texture, shade);
                break;
            case Assets.SOUTH:
                target.quad(x0, y0, z1, uLo, vHi, x1, y0, z1, uHi, vHi,
                        x0, y1, z1, uLo, vLo, x1, y1, z1, uHi, vLo, def.texture, shade);
                break;
            case Assets.WEST:
                target.quad(x0, y0, z0, uLo, vHi, x0, y0, z1, uHi, vHi,
                        x0, y1, z0, uLo, vLo, x0, y1, z1, uHi, vLo, def.texture, shade);
                break;
            default:
                target.quad(x1, y0, z1, uLo, vHi, x1, y0, z0, uHi, vHi,
                        x1, y1, z1, uLo, vLo, x1, y1, z0, uHi, vLo, def.texture, shade);
                break;
        }
    }
}
