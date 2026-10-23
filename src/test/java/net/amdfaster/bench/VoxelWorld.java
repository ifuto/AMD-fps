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

        for (int i = 0; i < 6; i++) {
            section.opaque[i] = opaque[i].build();
        }
        section.cutout = cutout.build();
        section.blend = blend.build();
        section.solidBox = solidBox;
        return section;
    }

    private static final int[] OFFSET_X = {0, 0, 0, 0, -1, 1};
    private static final int[] OFFSET_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] OFFSET_Z = {0, 0, -1, 1, 0, 0};

    private static void emit(MeshBuilder target, int face, Assets.FaceDef def,
            float x0, float y0, float z0, float x1, float y1, float z1) {
        float shade = Assets.FACE_SHADE[face];
        // Bottom-left, bottom-right, top-left, top-right, with v running down the texture the way the
        // PNG rows do.
        float uLo = def.u0;
        float uHi = def.u1;
        float vLo = def.v0;
        float vHi = def.v1;
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
